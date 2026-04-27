package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.vertexai.VertexAI;
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
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.util.LinkedMultiValueMap;

public final class MultiProviderSpringAiChatGateway implements SpringAiChatGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Properties properties;
    private final OpenAiResponsesGateway openAiResponsesGateway;
    private final ModelCostEstimator modelCostEstimator;

    public MultiProviderSpringAiChatGateway(Properties properties) {
        this(properties, new OpenAiResponsesDispatcherGateway(properties));
    }

    MultiProviderSpringAiChatGateway(Properties properties, OpenAiResponsesGateway openAiResponsesGateway) {
        this.properties = properties;
        this.openAiResponsesGateway = openAiResponsesGateway;
        this.modelCostEstimator = new ModelCostEstimator(properties);
    }

    @Override
    public SpringAiChatResult generate(String systemPrompt,
                                       String userContext,
                                       List<ToolSpec> tools,
                                       String model,
                                       Double temperature,
                                       Integer maxTokens,
                                       Consumer<String> streamingTokenCallback) {
        String provider = prop(properties, "agent.runtime.spring-ai.provider", "openai").toLowerCase();
        try {
            return switch (provider) {
                case "gemini" -> generateGemini(systemPrompt, userContext, tools, model, temperature, maxTokens, streamingTokenCallback);
                case "claude", "anthropic" -> generateClaude(systemPrompt, userContext, tools, model, temperature, maxTokens, streamingTokenCallback);
                default -> generateOpenAi(systemPrompt, userContext, tools, model, temperature, maxTokens, streamingTokenCallback);
            };
        } catch (Exception e) {
            return terminal(provider.toUpperCase() + " invocation error: " + exceptionSummary(e), streamingTokenCallback);
        }
    }

    private SpringAiChatResult generateOpenAi(String systemPrompt,
                                              String userContext,
                                              List<ToolSpec> tools,
                                              String model,
                                              Double temperature,
                                              Integer maxTokens,
                                              Consumer<String> callback) throws Exception {
        String apiMode = prop(properties, "agent.runtime.spring-ai.openai.api-mode",
            prop(properties, "spring.ai.openai.api-mode", "chat")).toLowerCase();
        String apiKeyProperty = prop(properties, "agent.runtime.spring-ai.openai.api-key-system-property", "openai.api.key");
        String configuredApiKey = firstNonBlank(
            property(properties, "agent.runtime.spring-ai.openai.api-key"),
            property(properties, "spring.ai.openai.api-key")
        );
        String directConfiguredApiKey = configuredApiKey;
        if (directConfiguredApiKey != null
            && directConfiguredApiKey.startsWith("${")
            && directConfiguredApiKey.endsWith("}")) {
            directConfiguredApiKey = null;
        }
        String apiKey = firstNonBlank(
            firstNonBlank(System.getProperty(apiKeyProperty), System.getenv("OPENAI_API_KEY")),
            firstNonBlank(resolvePropertyReference(configuredApiKey), directConfiguredApiKey)
        );
        if (apiKey == null) {
            return terminal("OpenAI API key is missing. Set -D" + apiKeyProperty + "=<token>.", callback);
        }

        String configuredBaseUrl = firstNonBlank(
            property(properties, "agent.runtime.spring-ai.openai.base-url"),
            property(properties, "spring.ai.openai.base-url")
        );
        String baseUrl = configuredBaseUrl == null ? "https://api.openai.com" : configuredBaseUrl;
        String selectedModel = firstNonBlank(model, prop(properties, "agent.runtime.spring-ai.openai.model",
            prop(properties, "agent.runtime.spring-ai.model", "gpt-5.4")));
        double selectedTemperature = temperature != null ? temperature : doubleProp(properties, "agent.runtime.spring-ai.temperature", 0.2d);
        int selectedMaxTokens = maxTokens != null ? maxTokens : intProp(properties, "agent.runtime.spring-ai.max-tokens", 800);

        if ("responses-ws".equals(apiMode) || "responses_ws".equals(apiMode) || "responses.ws".equals(apiMode)
            || "responses".equals(apiMode) || "responses-http".equals(apiMode) || "responses_http".equals(apiMode)
            || "responses.http".equals(apiMode)) {
            return openAiResponsesGateway.generate(
                apiMode,
                systemPrompt,
                userContext,
                tools,
                selectedModel,
                selectedTemperature,
                selectedMaxTokens,
                apiKey,
                baseUrl,
                callback
            );
        }

        LinkedMultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        String organizationProperty = prop(properties, "agent.runtime.spring-ai.openai.organization-system-property", "openai.organization");
        String organization = System.getProperty(organizationProperty);
        if (organization != null && !organization.isBlank()) {
            headers.add("OpenAI-Organization", organization);
        }
        String projectProperty = prop(properties, "agent.runtime.spring-ai.openai.project-system-property", "openai.project");
        String project = System.getProperty(projectProperty);
        if (project != null && !project.isBlank()) {
            headers.add("OpenAI-Project", project);
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
            .baseUrl(toOpenAiApiBaseUrl(baseUrl))
            .completionsPath("/v1/chat/completions")
            .apiKey(new SimpleApiKey(apiKey))
            .headers(headers)
            .build();

        ToolCallbacksForOpenAi openAiTools = toolCallbacksForOpenAi(tools);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(selectedModel)
            .temperature(selectedTemperature)
            .maxTokens(selectedMaxTokens)
            .toolCallbacks(openAiTools.callbacks())
            .internalToolExecutionEnabled(false)
            .toolChoice("auto")
            .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(options)
            .build();
        SpringAiChatResult result = invokeSpringAiChat(chatModel, options, systemPrompt, userContext, "openai", selectedModel, apiMode, callback);
        return remapToolCallNames(result, openAiTools.aliases());
    }

    private SpringAiChatResult generateGemini(String systemPrompt,
                                              String userContext,
                                              List<ToolSpec> tools,
                                              String model,
                                              Double temperature,
                                              Integer maxTokens,
                                              Consumer<String> callback) {
        String projectId = firstNonBlank(
            property(properties, "agent.runtime.spring-ai.gemini.vertex.project-id"),
            firstNonBlank(System.getProperty("gemini.vertex.project-id"), System.getenv("GOOGLE_CLOUD_PROJECT"))
        );
        if (projectId == null) {
            return terminal("Gemini Vertex project id is missing. Set agent.runtime.spring-ai.gemini.vertex.project-id.", callback);
        }
        String location = firstNonBlank(
            property(properties, "agent.runtime.spring-ai.gemini.vertex.location"),
            firstNonBlank(System.getProperty("gemini.vertex.location"), "us-central1")
        );
        String selectedModel = firstNonBlank(model, prop(properties, "agent.runtime.spring-ai.gemini.model",
            prop(properties, "agent.runtime.spring-ai.model", "gemini-2.5-flash")));
        double selectedTemperature = temperature != null ? temperature : doubleProp(properties, "agent.runtime.spring-ai.temperature", 0.2d);
        int selectedMaxTokens = maxTokens != null ? maxTokens : intProp(properties, "agent.runtime.spring-ai.max-tokens", 800);

        try {
            VertexAI vertexAI = new VertexAI(projectId, location);
            VertexAiGeminiChatOptions options = VertexAiGeminiChatOptions.builder()
                .model(selectedModel)
                .temperature(selectedTemperature)
                .maxOutputTokens(selectedMaxTokens)
                .toolCallbacks(toolCallbacksFor(tools))
                .internalToolExecutionEnabled(false)
                .build();

            VertexAiGeminiChatModel chatModel = VertexAiGeminiChatModel.builder()
                .vertexAI(vertexAI)
                .defaultOptions(options)
                .build();
            return invokeSpringAiChat(chatModel, options, systemPrompt, userContext, "gemini", selectedModel, "chat", callback);
        } catch (Exception e) {
            return terminal("GEMINI invocation error: " + exceptionSummary(e), callback);
        }
    }

    private SpringAiChatResult generateClaude(String systemPrompt,
                                              String userContext,
                                              List<ToolSpec> tools,
                                              String model,
                                              Double temperature,
                                              Integer maxTokens,
                                              Consumer<String> callback) {
        String apiKeyProperty = prop(properties, "agent.runtime.spring-ai.claude.api-key-system-property", "anthropic.api.key");
        String apiKey = firstNonBlank(System.getProperty(apiKeyProperty), System.getenv("ANTHROPIC_API_KEY"));
        if (apiKey == null) {
            return terminal("Claude API key is missing. Set -D" + apiKeyProperty + "=<token>.", callback);
        }

        String baseUrl = prop(properties, "agent.runtime.spring-ai.claude.base-url", "https://api.anthropic.com/v1/messages");
        String selectedModel = firstNonBlank(model, prop(properties, "agent.runtime.spring-ai.claude.model",
            prop(properties, "agent.runtime.spring-ai.model", "claude-3-5-sonnet-20241022")));
        double selectedTemperature = temperature != null ? temperature : doubleProp(properties, "agent.runtime.spring-ai.temperature", 0.2d);
        int selectedMaxTokens = maxTokens != null ? maxTokens : intProp(properties, "agent.runtime.spring-ai.max-tokens", 800);
        String anthropicVersion = prop(properties, "agent.runtime.spring-ai.claude.anthropic-version", "2023-06-01");
        try {
            AnthropicApi anthropicApi = AnthropicApi.builder()
                .baseUrl(toAnthropicApiBaseUrl(baseUrl))
                .completionsPath("/v1/messages")
                .apiKey(new SimpleApiKey(apiKey))
                .anthropicVersion(anthropicVersion)
                .build();
            AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(selectedModel)
                .temperature(selectedTemperature)
                .maxTokens(selectedMaxTokens)
                .toolCallbacks(toolCallbacksFor(tools))
                .internalToolExecutionEnabled(false)
                .build();
            AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options)
                .build();
            return invokeSpringAiChat(chatModel, options, systemPrompt, userContext, "claude", selectedModel, "chat", callback);
        } catch (Exception e) {
            return terminal("CLAUDE invocation error: " + exceptionSummary(e), callback);
        }
    }

    private SpringAiChatResult invokeSpringAiChat(ChatModel chatModel,
                                                  org.springframework.ai.chat.prompt.ChatOptions chatOptions,
                                                  String systemPrompt,
                                                  String userContext,
                                                  String provider,
                                                  String model,
                                                  String apiMode,
                                                  Consumer<String> callback) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(buildUserPrompt(userContext)));

        Prompt prompt = new Prompt(messages, chatOptions);
        ChatResponse response = chatModel.call(prompt);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return result("", List.of(), callback);
        }
        AssistantMessage assistant = response.getResult().getOutput();
        List<AiToolCall> toolCalls = parseAssistantToolCalls(assistant);
        TokenUsage tokenUsage = extractTokenUsage(response);
        ModelUsage modelUsage = modelCostEstimator.estimate(provider, model, apiMode, tokenUsage);
        return result(assistant.getText(), toolCalls, tokenUsage, modelUsage, callback);
    }

    private List<ToolCallback> toolCallbacksFor(List<ToolSpec> tools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolSpec tool : tools) {
            String inputSchema = tool.inputSchema() == null || tool.inputSchema().isNull()
                ? "{\"type\":\"object\"}"
                : tool.inputSchema().toString();
            callbacks.add(
                FunctionToolCallback.<Map<String, Object>, Map<String, Object>>builder(
                        tool.name(),
                        arguments -> Map.of("status", "noop")
                    )
                    .description(tool.description() == null ? tool.name() : tool.description())
                    .inputType(Map.class)
                    .inputSchema(inputSchema)
                    .build()
            );
        }
        return callbacks;
    }

    private ToolCallbacksForOpenAi toolCallbacksForOpenAi(List<ToolSpec> tools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        LinkedHashSet<String> usedNames = new LinkedHashSet<>();
        for (ToolSpec tool : tools) {
            String inputSchema = tool.inputSchema() == null || tool.inputSchema().isNull()
                ? "{\"type\":\"object\"}"
                : tool.inputSchema().toString();
            String openAiToolName = normalizeOpenAiToolName(tool.name(), usedNames);
            aliases.put(openAiToolName, tool.name());
            callbacks.add(
                FunctionToolCallback.<Map<String, Object>, Map<String, Object>>builder(
                        openAiToolName,
                        arguments -> Map.of("status", "noop")
                    )
                    .description(tool.description() == null ? tool.name() : tool.description())
                    .inputType(Map.class)
                    .inputSchema(inputSchema)
                    .build()
            );
        }
        return new ToolCallbacksForOpenAi(callbacks, aliases);
    }

    private SpringAiChatResult remapToolCallNames(SpringAiChatResult result, Map<String, String> aliases) {
        if (result == null || result.toolCalls() == null || result.toolCalls().isEmpty() || aliases == null || aliases.isEmpty()) {
            return result;
        }
        List<AiToolCall> remapped = new ArrayList<>(result.toolCalls().size());
        for (AiToolCall toolCall : result.toolCalls()) {
            if (toolCall == null || toolCall.name() == null || toolCall.name().isBlank()) {
                continue;
            }
            String originalName = aliases.getOrDefault(toolCall.name(), toolCall.name());
            remapped.add(new AiToolCall(originalName, toolCall.arguments()));
        }
        return new SpringAiChatResult(result.message(), remapped, result.terminal(), result.tokenUsage(), result.modelUsage());
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

    private record ToolCallbacksForOpenAi(List<ToolCallback> callbacks, Map<String, String> aliases) {
    }

    private List<AiToolCall> parseAssistantToolCalls(AssistantMessage assistantMessage) {
        List<AiToolCall> toolCalls = new ArrayList<>();
        if (assistantMessage == null || assistantMessage.getToolCalls() == null) {
            return toolCalls;
        }
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            if (toolCall == null || toolCall.name() == null || toolCall.name().isBlank()) {
                continue;
            }
            String arguments = toolCall.arguments();
            try {
                toolCalls.add(new AiToolCall(
                    toolCall.name(),
                    arguments == null || arguments.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(arguments)
                ));
            } catch (JsonProcessingException ignored) {
                toolCalls.add(new AiToolCall(toolCall.name(), MAPPER.createObjectNode()));
            }
        }
        return toolCalls;
    }

    private SpringAiChatResult result(String assistantMessage,
                                      List<AiToolCall> toolCalls,
                                      TokenUsage tokenUsage,
                                      ModelUsage modelUsage,
                                      Consumer<String> callback) {
        String message = assistantMessage == null ? "" : assistantMessage;
        if (callback != null && !message.isBlank()) {
            callback.accept(message);
        }
        return new SpringAiChatResult(message, toolCalls, toolCalls.isEmpty(), tokenUsage, modelUsage);
    }

    private SpringAiChatResult result(String assistantMessage, List<AiToolCall> toolCalls, Consumer<String> callback) {
        return result(assistantMessage, toolCalls, null, null, callback);
    }

    private String buildUserPrompt(String userContext) {
        return """
            Use the conversation context to decide whether to call a tool.
            Call tools when needed for support actions, especially for ticket creation.
            If you call a tool, provide accurate JSON arguments matching the schema.

            Conversation context:
            """
            + (userContext == null ? "" : userContext);
    }

    private SpringAiChatResult terminal(String message, Consumer<String> callback) {
        if (callback != null && message != null && !message.isBlank()) {
            callback.accept(message);
        }
        return new SpringAiChatResult(message, List.of(), true);
    }

    private TokenUsage extractTokenUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return null;
        }
        TokenUsage tokenUsage = TokenUsage.of(
            usage.getPromptTokens(),
            usage.getCompletionTokens(),
            usage.getTotalTokens()
        );
        return tokenUsage.isReported() ? tokenUsage : null;
    }

    private static String prop(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String property(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double doubleProp(Properties properties, String key, double defaultValue) {
        try {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int intProp(Properties properties, String key, int defaultValue) {
        try {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
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

    private static String toOpenAiApiBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isEmpty()) {
            return "https://api.openai.com";
        }
        if (normalized.endsWith("/v1/chat/completions")) {
            return normalized.substring(0, normalized.length() - "/v1/chat/completions".length());
        }
        if (normalized.endsWith("/v1/responses")) {
            return normalized.substring(0, normalized.length() - "/v1/responses".length());
        }
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - "/v1".length());
        }
        if (normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String toAnthropicApiBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isEmpty()) {
            return "https://api.anthropic.com";
        }
        if (normalized.endsWith("/v1/messages")) {
            return normalized.substring(0, normalized.length() - "/v1/messages".length());
        }
        if (normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String resolvePropertyReference(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.startsWith("${") && value.endsWith("}") && value.length() > 3) {
            String reference = value.substring(2, value.length() - 1).trim();
            if (reference.isEmpty()) {
                return null;
            }
            String systemValue = System.getProperty(reference);
            if (systemValue != null && !systemValue.isBlank()) {
                return systemValue;
            }
            String envValue = System.getenv(reference);
            if (envValue != null && !envValue.isBlank()) {
                return envValue;
            }
            return null;
        }
        return value;
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

}
