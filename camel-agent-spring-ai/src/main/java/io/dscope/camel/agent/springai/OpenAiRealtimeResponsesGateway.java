package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ModelUsage;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.model.TokenUsage;
import io.dscope.camel.agent.realtime.OpenAiRealtimeRelayClient;
import io.dscope.camel.agent.realtime.RealtimeReconnectPolicy;
import io.dscope.camel.agent.realtime.RealtimeRelayClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

public final class OpenAiRealtimeResponsesGateway implements OpenAiResponsesGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Properties properties;
    private final RealtimeRelayClient realtimeRelayClient;
    private final ModelCostEstimator modelCostEstimator;

    public OpenAiRealtimeResponsesGateway(Properties properties) {
        this(properties, new OpenAiRealtimeRelayClient());
    }

    OpenAiRealtimeResponsesGateway(Properties properties, RealtimeRelayClient realtimeRelayClient) {
        this.properties = properties;
        this.realtimeRelayClient = realtimeRelayClient;
        this.modelCostEstimator = new ModelCostEstimator(properties);
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
        String conversationId = "responses-ws-" + UUID.randomUUID();
        String endpointUri = wsEndpoint(baseUrl, property("agent.runtime.spring-ai.openai.responses-ws.endpoint-uri"));
        String selectedModel = firstNonBlank(model, property("agent.runtime.spring-ai.openai.responses-ws.model"), "gpt-realtime");
        String selectedReasoningEffort = firstNonBlank(
            property("agent.runtime.spring-ai.openai.responses-ws.reasoning-effort"),
            property("agent.runtime.spring-ai.openai.reasoning-effort"),
            property("spring.ai.openai.chat.options.reasoning-effort"),
            property("spring.ai.openai.reasoning-effort")
        );
        long requestTimeoutMs = longProp("agent.runtime.spring-ai.openai.responses-ws.request-timeout-ms", 30_000L);
        long pollIntervalMs = longProp("agent.runtime.spring-ai.openai.responses-ws.poll-interval-ms", 50L);
        RealtimeReconnectPolicy reconnectPolicy = new RealtimeReconnectPolicy(
            intProp("agent.runtime.spring-ai.openai.responses-ws.max-send-retries", 3),
            intProp("agent.runtime.spring-ai.openai.responses-ws.max-reconnects", 8),
            longProp("agent.runtime.spring-ai.openai.responses-ws.initial-backoff-ms", 150L),
            longProp("agent.runtime.spring-ai.openai.responses-ws.max-backoff-ms", 2_000L)
        ).normalized();

        try {
            realtimeRelayClient.connect(conversationId, endpointUri, selectedModel, apiKey, reconnectPolicy);

            String event = responseCreate(
                selectedModel, systemPrompt, userContext, tools, temperature, maxTokens, selectedReasoningEffort);
            System.out.println("[RESPONSES-WS] Sending event: " + event);
            realtimeRelayClient.sendEvent(conversationId, event);

            ParseState parseState = new ParseState();
            long deadline = System.currentTimeMillis() + Math.max(1_000L, requestTimeoutMs);
            while (System.currentTimeMillis() <= deadline) {
                ArrayNode events = realtimeRelayClient.pollEvents(conversationId);
                consumeEvents(events, parseState, streamingTokenCallback);
                if (parseState.done || parseState.error != null) {
                    break;
                }
                LockSupport.parkNanos(Math.max(1L, pollIntervalMs) * 1_000_000L);
            }

            if (parseState.error != null) {
                String message = "OpenAI responses-ws invocation error: " + parseState.error;
                if (streamingTokenCallback != null) {
                    streamingTokenCallback.accept(message);
                }
                return new SpringAiChatGateway.SpringAiChatResult(message, List.of(), true);
            }

            if (!parseState.done) {
                String message = "OpenAI responses-ws timed out after " + requestTimeoutMs + " ms";
                if (streamingTokenCallback != null) {
                    streamingTokenCallback.accept(message);
                }
                return new SpringAiChatGateway.SpringAiChatResult(message, List.of(), true);
            }

            String message = parseState.message.toString();
            boolean terminal = parseState.toolCalls.isEmpty();
            TokenUsage tokenUsage = parseState.tokenUsage();
            ModelUsage modelUsage = modelCostEstimator.estimate("openai", selectedModel, apiMode, tokenUsage);
            return new SpringAiChatGateway.SpringAiChatResult(message, parseState.toolCalls, terminal, tokenUsage, modelUsage);
        } catch (Exception e) {
            String message = "OpenAI responses-ws invocation error: " + exceptionSummary(e);
            if (streamingTokenCallback != null) {
                streamingTokenCallback.accept(message);
            }
            return new SpringAiChatGateway.SpringAiChatResult(message, List.of(), true);
        } finally {
            try {
                realtimeRelayClient.close(conversationId);
            } catch (Exception ignored) {
            }
        }
    }

    private void consumeEvents(ArrayNode events, ParseState parseState, Consumer<String> callback) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (JsonNode event : events) {
            if (event == null || event.isNull()) {
                continue;
            }
            String type = text(event, "type");
            if ("relay.error".equals(type)) {
                parseState.error = firstNonBlank(text(event, "message"), "relay error");
                continue;
            }
            if ("error".equals(type)) {
                parseState.error = firstNonBlank(text(event.path("error"), "message"), text(event, "message"), "responses-ws error");
                continue;
            }
            maybeMergeUsage(event, parseState);
            if ("response.output_text.delta".equals(type) || "response.text.delta".equals(type)) {
                String delta = firstNonBlank(text(event, "delta"), text(event, "text"));
                appendDelta(parseState, delta, callback);
                continue;
            }
            if ("response.function_call_arguments.done".equals(type) || "response.output_item.done".equals(type)) {
                maybeAddToolCall(event, parseState.toolCalls, parseState.usedToolNames);
                continue;
            }
            if ("response.done".equals(type) || "response.completed".equals(type)) {
                maybeMergeResponseDone(event, parseState, callback);
                parseState.done = true;
            }
        }
    }

    private void appendDelta(ParseState state, String delta, Consumer<String> callback) {
        if (delta == null || delta.isBlank()) {
            return;
        }
        state.message.append(delta);
        if (callback != null) {
            callback.accept(delta);
        }
    }

    private void maybeMergeResponseDone(JsonNode event, ParseState state, Consumer<String> callback) {
        JsonNode response = event.path("response");
        if (!response.isObject()) {
            return;
        }
        mergeUsage(response.path("usage"), state);
        JsonNode output = response.path("output");
        if (!output.isArray()) {
            return;
        }
        for (JsonNode item : output) {
            if (!item.isObject()) {
                continue;
            }
            String type = text(item, "type");
            if ("function_call".equals(type)) {
                maybeAddToolCallFromNode(item, state.toolCalls, state.usedToolNames);
                continue;
            }
            if ("message".equals(type)) {
                JsonNode content = item.path("content");
                if (!content.isArray()) {
                    continue;
                }
                for (JsonNode chunk : content) {
                    String text = firstNonBlank(
                        text(chunk, "text"),
                        text(chunk, "output_text")
                    );
                    if (text != null && !text.isBlank() && state.message.indexOf(text) < 0) {
                        appendDelta(state, text, callback);
                    }
                }
            }
        }
    }

    private void maybeMergeUsage(JsonNode event, ParseState state) {
        if (event == null || event.isNull()) {
            return;
        }
        mergeUsage(event.path("usage"), state);
        JsonNode response = event.path("response");
        if (response.isObject()) {
            mergeUsage(response.path("usage"), state);
        }
    }

    private void mergeUsage(JsonNode usageNode, ParseState state) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull() || !usageNode.isObject()) {
            return;
        }
        TokenUsage tokenUsage = TokenUsage.of(
            firstInt(usageNode, "input_tokens", "prompt_tokens"),
            firstInt(usageNode, "output_tokens", "completion_tokens"),
            firstInt(usageNode, "total_tokens")
        );
        if (tokenUsage.isReported()) {
            state.tokenUsage = tokenUsage;
        }
    }

    private void maybeAddToolCall(JsonNode event, List<AiToolCall> toolCalls, LinkedHashSet<String> usedToolNames) {
        JsonNode item = event.path("item");
        if (item.isObject()) {
            maybeAddToolCallFromNode(item, toolCalls, usedToolNames);
            return;
        }
        if ("response.function_call_arguments.done".equals(text(event, "type"))) {
            String name = text(event, "name");
            String arguments = firstNonBlank(text(event, "arguments"), text(event, "delta"), "{}");
            addToolCall(name, arguments, toolCalls, usedToolNames);
        }
    }

    private void maybeAddToolCallFromNode(JsonNode item, List<AiToolCall> toolCalls, LinkedHashSet<String> usedToolNames) {
        String itemType = text(item, "type");
        if (!"function_call".equals(itemType)) {
            return;
        }
        String name = text(item, "name");
        String arguments = firstNonBlank(text(item, "arguments"), "{}");
        addToolCall(name, arguments, toolCalls, usedToolNames);
    }

    private void addToolCall(String name, String arguments, List<AiToolCall> toolCalls, LinkedHashSet<String> usedToolNames) {
        if (name == null || name.isBlank() || usedToolNames.contains(name)) {
            return;
        }
        String restored = restoreToolName(name);
        try {
            JsonNode parsed = arguments == null || arguments.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(arguments);
            toolCalls.add(new AiToolCall(restored, parsed));
            usedToolNames.add(name);
        } catch (java.io.IOException ignored) {
            toolCalls.add(new AiToolCall(restored, MAPPER.createObjectNode()));
            usedToolNames.add(name);
        }
    }

    /**
     * Sanitize tool name for OpenAI Responses API which only allows [a-zA-Z0-9_-].
     * Dots are replaced with double underscores to allow round-tripping.
     */
    private static String sanitizeToolName(String name) {
        return name == null ? name : name.replace(".", "__");
    }

    private static String restoreToolName(String name) {
        return name == null ? name : name.replace("__", ".");
    }

    private String responseCreate(String model, String systemPrompt, String userContext,
                                    List<ToolSpec> tools, Double temperature, Integer maxTokens,
                                    String reasoningEffort) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "response.create");
        if (model != null && !model.isBlank()) {
            root.put("model", model);
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.put("instructions", systemPrompt.trim());
        }

        // Embed user input in the response.create event
        ArrayNode input = MAPPER.createArrayNode();
        ObjectNode userMessage = MAPPER.createObjectNode();
        userMessage.put("type", "message");
        userMessage.put("role", "user");
        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode inputText = MAPPER.createObjectNode();
        inputText.put("type", "input_text");
        inputText.put("text", userContext == null ? "" : userContext);
        content.add(inputText);
        userMessage.set("content", content);
        input.add(userMessage);
        root.set("input", input);

        if (temperature != null) {
            root.put("temperature", temperature);
        }
        if (maxTokens != null) {
            root.put("max_output_tokens", maxTokens);
        }
        if (reasoningEffort != null && !reasoningEffort.isBlank()
            && !reasoningEffort.contains("${")) {
            root.set("reasoning", MAPPER.createObjectNode().put("effort", reasoningEffort));
        }
        OpenAiPromptCacheSupport.PromptCacheSettings promptCache = OpenAiPromptCacheSupport.resolve(properties, model, systemPrompt, tools);
        if (promptCache.enabled()) {
            root.put("prompt_cache_key", promptCache.key());
            if (promptCache.retention() != null && !promptCache.retention().isBlank()) {
                root.put("prompt_cache_retention", promptCache.retention());
            }
        }
        root.put("tool_choice", "auto");

        ArrayNode toolDefs = MAPPER.createArrayNode();
        if (tools != null) {
            for (ToolSpec tool : tools) {
                if (tool == null || tool.name() == null || tool.name().isBlank()) {
                    continue;
                }
                ObjectNode toolDef = MAPPER.createObjectNode();
                toolDef.put("type", "function");
                toolDef.put("name", sanitizeToolName(tool.name()));
                toolDef.put("description", firstNonBlank(tool.description(), tool.name()));
                JsonNode schema = tool.inputSchema() == null || tool.inputSchema().isNull()
                    ? MAPPER.createObjectNode().put("type", "object")
                    : tool.inputSchema();
                toolDef.set("parameters", schema);
                toolDefs.add(toolDef);
            }
        }
        if (!toolDefs.isEmpty()) {
            root.set("tools", toolDefs);
        }

        return MAPPER.writeValueAsString(root);
    }

    private String wsEndpoint(String baseUrl, String configuredEndpoint) {
        if (configuredEndpoint != null && !configuredEndpoint.isBlank()) {
            return configuredEndpoint.trim();
        }
        String normalized = firstNonBlank(baseUrl, "https://api.openai.com").trim();
        if (normalized.startsWith("wss://")) {
            return appendResponsesPath(normalized);
        }
        if (normalized.startsWith("ws://")) {
            return appendResponsesPath(normalized);
        }
        if (normalized.startsWith("https://")) {
            return appendResponsesPath("wss://" + normalized.substring("https://".length()));
        }
        if (normalized.startsWith("http://")) {
            return appendResponsesPath("ws://" + normalized.substring("http://".length()));
        }
        return appendResponsesPath("wss://" + normalized);
    }

    private String appendResponsesPath(String endpoint) {
        String trimmed = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        if (trimmed.endsWith("/v1/responses")) {
            return trimmed;
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed + "/responses";
        }
        return trimmed + "/v1/responses";
    }

    private String property(String key) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int intProp(String key, int defaultValue) {
        String value = property(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private long longProp(String key, long defaultValue) {
        String value = property(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private Integer firstInt(JsonNode node, String... fields) {
        if (node == null || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isInt() || value.isLong()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String exceptionSummary(Throwable error) {
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

    private static final class ParseState {
        private final StringBuilder message = new StringBuilder();
        private final List<AiToolCall> toolCalls = new ArrayList<>();
        private final LinkedHashSet<String> usedToolNames = new LinkedHashSet<>();
        private boolean done;
        private String error;
        private TokenUsage tokenUsage;

        private TokenUsage tokenUsage() {
            return tokenUsage != null && tokenUsage.isReported() ? tokenUsage : null;
        }
    }
}
