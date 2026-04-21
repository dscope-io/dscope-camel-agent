package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceOptions;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ModelUsage;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.model.TokenUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

final class OpenAiSdkResponsesGateway implements OpenAiResponsesGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Properties properties;
    private final ModelCostEstimator modelCostEstimator;
    private final OpenAiResponseExecutor responseExecutor;

    OpenAiSdkResponsesGateway(Properties properties) {
        this(properties, OpenAiSdkResponsesGateway::executeResponse);
    }

    OpenAiSdkResponsesGateway(Properties properties, OpenAiResponseExecutor responseExecutor) {
        this.properties = properties == null ? new Properties() : properties;
        this.modelCostEstimator = new ModelCostEstimator(this.properties);
        this.responseExecutor = responseExecutor;
    }

    @Override
    public SpringAiChatGateway.SpringAiChatResult generate(String apiMode,
                                                           String systemPrompt,
                                                           String userContext,
                                                           List<ToolSpec> tools,
                                                           String model,
                                                           Double temperature,
                                                           Integer maxTokens,
                                                           String apiKey,
                                                           String baseUrl,
                                                           Consumer<String> streamingTokenCallback) {
        String selectedModel = firstNonBlank(model, property("agent.runtime.spring-ai.openai.model"), "gpt-5.4");
        String organization = firstNonBlank(
            System.getProperty(prop("agent.runtime.spring-ai.openai.organization-system-property", "openai.organization")),
            property("agent.runtime.spring-ai.openai.organization")
        );
        String project = firstNonBlank(
            System.getProperty(prop("agent.runtime.spring-ai.openai.project-system-property", "openai.project")),
            property("agent.runtime.spring-ai.openai.project")
        );

        try {
            ToolDefinitions definitions = buildTools(tools);
            ResponseCreateParams.Builder request = ResponseCreateParams.builder()
                .model(selectedModel)
                .input(userContext == null ? "" : userContext)
                .toolChoice(ToolChoiceOptions.AUTO);

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                request.instructions(systemPrompt.trim());
            }
            if (temperature != null) {
                request.temperature(temperature);
            }
            if (maxTokens != null) {
                request.maxOutputTokens(maxTokens.longValue());
            }
            for (FunctionTool functionTool : definitions.tools()) {
                request.addTool(functionTool);
            }

            OpenAiPromptCacheSupport.PromptCacheSettings promptCache = OpenAiPromptCacheSupport.resolve(properties, selectedModel, systemPrompt, tools);
            if (promptCache.enabled()) {
                request.putAdditionalBodyProperty("prompt_cache_key", JsonValue.from(promptCache.key()));
                if (promptCache.retention() != null && !promptCache.retention().isBlank()) {
                    request.putAdditionalBodyProperty("prompt_cache_retention", JsonValue.from(promptCache.retention()));
                }
            }

            Response response = responseExecutor.execute(request.build(), apiKey, normalizeSdkBaseUrl(baseUrl), organization, project);
            if (response.status().isPresent() && ResponseStatus.FAILED.equals(response.status().get())) {
                String message = response.error().map(error -> error.message()).orElse("OpenAI responses HTTP request failed");
                stream(message, streamingTokenCallback);
                return new SpringAiChatGateway.SpringAiChatResult(message, List.of(), true);
            }

            List<AiToolCall> toolCalls = extractToolCalls(response, definitions.aliases());
            String assistantMessage = extractAssistantMessage(response);
            stream(assistantMessage, streamingTokenCallback);

            TokenUsage tokenUsage = extractTokenUsage(response);
            ModelUsage modelUsage = modelCostEstimator.estimate("openai", selectedModel, apiMode, tokenUsage);
            return new SpringAiChatGateway.SpringAiChatResult(assistantMessage, toolCalls, toolCalls.isEmpty(), tokenUsage, modelUsage);
        } catch (Exception e) {
            String message = "OpenAI responses HTTP invocation error: " + exceptionSummary(e);
            stream(message, streamingTokenCallback);
            return new SpringAiChatGateway.SpringAiChatResult(message, List.of(), true);
        }
    }

    private ToolDefinitions buildTools(List<ToolSpec> tools) {
        List<FunctionTool> functionTools = new ArrayList<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        LinkedHashSet<String> usedNames = new LinkedHashSet<>();
        if (tools == null) {
            return new ToolDefinitions(functionTools, aliases);
        }
        for (ToolSpec tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            String normalizedName = normalizeOpenAiToolName(tool.name(), usedNames);
            aliases.put(normalizedName, tool.name());
            JsonNode schema = tool.inputSchema() == null || tool.inputSchema().isNull()
                ? MAPPER.createObjectNode().put("type", "object")
                : normalizeStrictToolSchema(tool.inputSchema());
            FunctionTool.Parameters.Builder parameters = FunctionTool.Parameters.builder();
            if (schema.isObject()) {
                schema.properties().forEach(entry ->
                    parameters.putAdditionalProperty(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
            } else {
                parameters.putAdditionalProperty("type", JsonValue.from("object"));
            }
            functionTools.add(FunctionTool.builder()
                .name(normalizedName)
                .description(firstNonBlank(tool.description(), tool.name()))
                .parameters(parameters.build())
                .strict(true)
                .build());
        }
        return new ToolDefinitions(functionTools, aliases);
    }

    private JsonNode normalizeStrictToolSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return schema;
        }
        return normalizeSchemaNode(schema.deepCopy());
    }

    private JsonNode normalizeSchemaNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode normalizedArray = MAPPER.createArrayNode();
            node.forEach(item -> normalizedArray.add(normalizeSchemaNode(item)));
            return normalizedArray;
        }
        if (!node.isObject()) {
            return node;
        }
        ObjectNode normalized = ((ObjectNode) node).deepCopy();
        normalized.properties().forEach(entry -> normalized.set(entry.getKey(), normalizeSchemaNode(entry.getValue())));

        JsonNode properties = normalized.path("properties");
        boolean objectSchema = "object".equals(normalized.path("type").asText(null)) || properties.isObject();
        if (objectSchema) {
            normalized.put("additionalProperties", false);
        }
        if (objectSchema && !properties.isObject()) {
            normalized.set("properties", MAPPER.createObjectNode());
            normalized.set("required", MAPPER.createArrayNode());
            properties = normalized.path("properties");
        }
        if (properties.isObject()) {
            ArrayNode required = MAPPER.createArrayNode();
            properties.properties().forEach(entry -> required.add(entry.getKey()));
            normalized.set("required", required);
        }
        return normalized;
    }

    private List<AiToolCall> extractToolCalls(Response response, Map<String, String> aliases) {
        List<AiToolCall> toolCalls = new ArrayList<>();
        if (response == null) {
            return toolCalls;
        }
        for (ResponseOutputItem outputItem : response.output()) {
            if (outputItem == null || !outputItem.isFunctionCall()) {
                continue;
            }
            ResponseFunctionToolCall toolCall = outputItem.asFunctionCall();
            String toolName = aliases.getOrDefault(toolCall.name(), toolCall.name());
            String arguments = toolCall.arguments();
            try {
                toolCalls.add(new AiToolCall(
                    toolName,
                    arguments == null || arguments.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(arguments)
                ));
            } catch (Exception ignored) {
                toolCalls.add(new AiToolCall(toolName, MAPPER.createObjectNode()));
            }
        }
        return toolCalls;
    }

    private String extractAssistantMessage(Response response) {
        if (response == null) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        for (ResponseOutputItem outputItem : response.output()) {
            if (outputItem == null || !outputItem.isMessage()) {
                continue;
            }
            ResponseOutputMessage outputMessage = outputItem.asMessage();
            for (ResponseOutputMessage.Content content : outputMessage.content()) {
                if (content != null && content.isOutputText()) {
                    String text = content.asOutputText().text();
                    if (text != null && !text.isBlank()) {
                        message.append(text);
                    }
                }
            }
        }
        return message.toString();
    }

    private TokenUsage extractTokenUsage(Response response) {
        if (response == null || response.usage().isEmpty()) {
            return null;
        }
        ResponseUsage usage = response.usage().get();
        TokenUsage tokenUsage = TokenUsage.of(
            safeInteger(usage.inputTokens()),
            safeInteger(usage.outputTokens()),
            safeInteger(usage.totalTokens())
        );
        return tokenUsage.isReported() ? tokenUsage : null;
    }

    private static Integer safeInteger(long value) {
        return value <= Integer.MAX_VALUE ? (int) value : Integer.MAX_VALUE;
    }

    private static void stream(String message, Consumer<String> callback) {
        if (callback != null && message != null && !message.isBlank()) {
            callback.accept(message);
        }
    }

    private static OpenAIClient buildClient(String apiKey, String baseUrl, String organization, String project) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl);
        if (organization != null && !organization.isBlank()) {
            builder.organization(organization);
        }
        if (project != null && !project.isBlank()) {
            builder.project(project);
        }
        return builder.build();
    }

    private static Response executeResponse(ResponseCreateParams request,
                                            String apiKey,
                                            String baseUrl,
                                            String organization,
                                            String project) {
        OpenAIClient client = buildClient(apiKey, baseUrl, organization, project);
        try {
            return client.responses().create(request);
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String normalizeSdkBaseUrl(String baseUrl) {
        String normalized = firstNonBlank(baseUrl, "https://api.openai.com");
        if (normalized.endsWith("/v1/responses")) {
            return normalized.substring(0, normalized.length() - "/responses".length());
        }
        if (normalized.endsWith("/v1")) {
            return normalized;
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized + "/v1";
    }

    private String prop(String key, String defaultValue) {
        return firstNonBlank(property(key), defaultValue);
    }

    private String property(String key) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static String firstNonBlank(String first, String second, String third) {
        return firstNonBlank(firstNonBlank(first, second), third);
    }

    private static String normalizeOpenAiToolName(String toolName, LinkedHashSet<String> usedNames) {
        String source = toolName == null ? "" : toolName.trim();
        String normalized = source.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (normalized.isBlank()) {
            normalized = "tool";
        }
        String candidate = normalized;
        int index = 2;
        while (usedNames.contains(candidate)) {
            candidate = normalized + "_" + index;
            index++;
        }
        usedNames.add(candidate);
        return candidate;
    }

    private static String exceptionSummary(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            String causeMessage = cause.getMessage();
            String causeType = cause.getClass().getSimpleName();
            if (causeMessage == null || causeMessage.isBlank()) {
                return message + " (cause: " + causeType + ")";
            }
            return message + " (cause: " + causeType + ": " + causeMessage + ")";
        }
        return message;
    }

    @FunctionalInterface
    interface OpenAiResponseExecutor {
        Response execute(ResponseCreateParams request, String apiKey, String baseUrl, String organization, String project);
    }

    private record ToolDefinitions(List<FunctionTool> tools, Map<String, String> aliases) {
    }
}