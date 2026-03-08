package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ModelResponse;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.function.Consumer;

public class SpringAiModelClient implements AiModelClient {

    private final SpringAiChatGateway chatGateway;
    public SpringAiModelClient(SpringAiChatGateway chatGateway, ObjectMapper objectMapper) {
        this.chatGateway = chatGateway;
    }

    private static final java.util.Set<String> EXCLUDED_EVENT_TYPES = java.util.Set.of(
        "mcp.tools.discovered", "assistant.delta", "snapshot.written"
    );

    @Override
    public ModelResponse generate(String systemInstruction,
                                  List<AgentEvent> history,
                                  List<ToolSpec> tools,
                                  ModelOptions options,
                                  Consumer<String> streamingTokenCallback) {
        String context = buildContext(history);

        SpringAiChatGateway.SpringAiChatResult result = chatGateway.generate(
            systemInstruction,
            context,
            tools,
            options == null ? null : options.model(),
            options == null ? null : options.temperature(),
            options == null ? null : options.maxTokens(),
            streamingTokenCallback
        );

        return new ModelResponse(result.message(), result.toolCalls(), result.terminal());
    }

    private static final int MAX_PAYLOAD_LENGTH = 400;

    private String buildContext(List<AgentEvent> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        List<AgentEvent> relevant = history.stream()
            .filter(event -> !EXCLUDED_EVENT_TYPES.contains(event.type()))
            .toList();
        if (relevant.isEmpty()) {
            return "";
        }
        // Chronological order so the model sees natural conversation flow
        StringBuilder sb = new StringBuilder();
        for (AgentEvent event : relevant) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            String type = event.type();
            String payload = payloadText(event);
            switch (type) {
                case "user.message" -> sb.append("User: ").append(payload);
                case "agent.message" -> sb.append("Agent: ").append(truncate(payload, MAX_PAYLOAD_LENGTH));
                case "tool.start" -> sb.append("[Tool call: ").append(extractToolName(event)).append("]");
                case "tool.result" -> sb.append("[Tool result: ").append(truncate(payload, MAX_PAYLOAD_LENGTH)).append("]");
                default -> sb.append(type).append(": ").append(truncate(payload, MAX_PAYLOAD_LENGTH));
            }
        }
        return sb.toString();
    }

    private static String payloadText(AgentEvent event) {
        if (event.payload() == null) return "";
        return event.payload().isTextual() ? event.payload().asText() : event.payload().toString();
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(truncated)";
    }

    private static String extractToolName(AgentEvent event) {
        if (event.payload() != null && event.payload().has("name")) {
            return event.payload().get("name").asText();
        }
        String payload = payloadText(event);
        return payload.length() > 100 ? payload.substring(0, 100) + "..." : payload;
    }
}
