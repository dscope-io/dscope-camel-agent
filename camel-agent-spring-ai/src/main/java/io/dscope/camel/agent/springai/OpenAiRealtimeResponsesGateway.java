package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ToolSpec;
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

    public OpenAiRealtimeResponsesGateway(Properties properties) {
        this(properties, new OpenAiRealtimeRelayClient());
    }

    OpenAiRealtimeResponsesGateway(Properties properties, RealtimeRelayClient realtimeRelayClient) {
        this.properties = properties;
        this.realtimeRelayClient = realtimeRelayClient;
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

            String prompt = buildPrompt(systemPrompt, userContext);
            realtimeRelayClient.sendEvent(conversationId, conversationItemCreate(prompt));
            realtimeRelayClient.sendEvent(conversationId, responseCreate(tools, temperature, maxTokens));

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
            return new SpringAiChatGateway.SpringAiChatResult(message, parseState.toolCalls, terminal);
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
        try {
            JsonNode parsed = arguments == null || arguments.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(arguments);
            toolCalls.add(new AiToolCall(name, parsed));
            usedToolNames.add(name);
        } catch (java.io.IOException ignored) {
            toolCalls.add(new AiToolCall(name, MAPPER.createObjectNode()));
            usedToolNames.add(name);
        }
    }

    private String conversationItemCreate(String prompt) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "conversation.item.create");
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "message");
        item.put("role", "user");
        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode inputText = MAPPER.createObjectNode();
        inputText.put("type", "input_text");
        inputText.put("text", prompt);
        content.add(inputText);
        item.set("content", content);
        root.set("item", item);
        return MAPPER.writeValueAsString(root);
    }

    private String responseCreate(List<ToolSpec> tools, Double temperature, Integer maxTokens) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "response.create");
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode modalities = MAPPER.createArrayNode();
        modalities.add("text");
        response.set("modalities", modalities);
        if (temperature != null) {
            response.put("temperature", temperature);
        }
        if (maxTokens != null) {
            response.put("max_output_tokens", maxTokens);
        }
        response.put("tool_choice", "auto");

        ArrayNode toolDefs = MAPPER.createArrayNode();
        if (tools != null) {
            for (ToolSpec tool : tools) {
                if (tool == null || tool.name() == null || tool.name().isBlank()) {
                    continue;
                }
                ObjectNode toolDef = MAPPER.createObjectNode();
                toolDef.put("type", "function");
                toolDef.put("name", tool.name());
                toolDef.put("description", firstNonBlank(tool.description(), tool.name()));
                JsonNode schema = tool.inputSchema() == null || tool.inputSchema().isNull()
                    ? MAPPER.createObjectNode().put("type", "object")
                    : tool.inputSchema();
                toolDef.set("parameters", schema);
                toolDefs.add(toolDef);
            }
        }
        if (!toolDefs.isEmpty()) {
            response.set("tools", toolDefs);
        }

        root.set("response", response);
        return MAPPER.writeValueAsString(root);
    }

    private String buildPrompt(String systemPrompt, String userContext) {
        String instructions = "Use the conversation context to decide whether to call a tool. "
            + "Call tools when needed for support actions, especially for ticket creation. "
            + "If you call a tool, provide accurate JSON arguments matching the schema.";
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction:\n").append(systemPrompt.trim()).append("\n\n");
        }
        prompt.append(instructions).append("\n\nConversation context:\n")
            .append(userContext == null ? "" : userContext);
        return prompt.toString();
    }

    private String wsEndpoint(String baseUrl, String configuredEndpoint) {
        if (configuredEndpoint != null && !configuredEndpoint.isBlank()) {
            return configuredEndpoint.trim();
        }
        String normalized = firstNonBlank(baseUrl, "https://api.openai.com").trim();
        if (normalized.startsWith("wss://")) {
            return appendRealtimePath(normalized);
        }
        if (normalized.startsWith("ws://")) {
            return appendRealtimePath(normalized);
        }
        if (normalized.startsWith("https://")) {
            return appendRealtimePath("wss://" + normalized.substring("https://".length()));
        }
        if (normalized.startsWith("http://")) {
            return appendRealtimePath("ws://" + normalized.substring("http://".length()));
        }
        return appendRealtimePath("wss://" + normalized);
    }

    private String appendRealtimePath(String endpoint) {
        String trimmed = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        if (trimmed.endsWith("/v1/realtime")) {
            return trimmed;
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed + "/realtime";
        }
        return trimmed + "/v1/realtime";
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
    }
}
