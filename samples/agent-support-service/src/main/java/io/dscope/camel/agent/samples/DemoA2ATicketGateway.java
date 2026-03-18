package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.springai.SpringAiChatGateway;
import java.util.List;
import java.util.function.Consumer;

public final class DemoA2ATicketGateway implements SpringAiChatGateway {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public SpringAiChatResult generate(String systemPrompt,
                                       String userContext,
                                       List<ToolSpec> tools,
                                       String model,
                                       Double temperature,
                                       Integer maxTokens,
                                       Consumer<String> streamingTokenCallback) {
        String query = extractLastUserMessage(userContext);
        ToolSpec selectedTool = selectTool(tools, query);
        if (selectedTool == null) {
            String message = "Demo gateway did not find a matching tool.";
            if (streamingTokenCallback != null) {
                streamingTokenCallback.accept(message);
            }
            return new SpringAiChatResult(message, List.of(), true);
        }

        AiToolCall call = new AiToolCall(selectedTool.name(), mapper.createObjectNode().put("query", query));
        if (streamingTokenCallback != null) {
            streamingTokenCallback.accept("");
        }
        return new SpringAiChatResult("", List.of(call), true);
    }

    private ToolSpec selectTool(List<ToolSpec> tools, String query) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        String normalized = query == null ? "" : query.toLowerCase();
        if (containsTicketIntent(normalized)) {
            ToolSpec routeTool = findTool(tools, "support.ticket.manage.route");
            if (routeTool != null) {
                return routeTool;
            }
            ToolSpec a2aTool = findTool(tools, "support.ticket.manage");
            if (a2aTool != null) {
                return a2aTool;
            }
        }
        return tools.get(0);
    }

    private ToolSpec findTool(List<ToolSpec> tools, String name) {
        for (ToolSpec tool : tools) {
            if (tool != null && name.equals(tool.name())) {
                return tool;
            }
        }
        return null;
    }

    private boolean containsTicketIntent(String normalized) {
        return normalized.contains("ticket")
            || normalized.contains("open")
            || normalized.contains("create")
            || normalized.contains("update")
            || normalized.contains("close")
            || normalized.contains("status")
            || normalized.contains("escalate");
    }

    private String extractLastUserMessage(String userContext) {
        if (userContext == null || userContext.isBlank()) {
            return "";
        }
        String[] lines = userContext.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (line.startsWith("User: ")) {
                return toPlainText(line.substring("User: ".length()));
            }
            if (line.startsWith("user.message: ")) {
                return toPlainText(line.substring("user.message: ".length()));
            }
        }
        return toPlainText(userContext);
    }

    private String toPlainText(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        try {
            JsonNode node = mapper.readTree(trimmed);
            return node.isTextual() ? node.asText() : trimmed;
        } catch (Exception ignored) {
            return trimmed;
        }
    }
}
