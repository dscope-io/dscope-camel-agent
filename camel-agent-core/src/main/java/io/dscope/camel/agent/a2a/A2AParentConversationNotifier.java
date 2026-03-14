package io.dscope.camel.agent.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.time.Instant;
import java.util.UUID;

final class A2AParentConversationNotifier {

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;

    A2AParentConversationNotifier(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
    }

    void notifyParent(String parentConversationId,
                      String childConversationId,
                      String remoteTaskId,
                      String agentId,
                      String planName,
                      String planVersion,
                      String aguiSessionId,
                      String aguiRunId,
                      String aguiThreadId,
                      String replyText) {
        if (persistenceFacade == null || parentConversationId == null || parentConversationId.isBlank()) {
            return;
        }
        if (childConversationId != null && childConversationId.equals(parentConversationId)) {
            return;
        }

        Instant now = Instant.now();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("conversationId", parentConversationId);
        payload.put("role", "assistant");
        payload.put("source", "a2a.ticketing");
        payload.put("sessionId", fallback(aguiSessionId));
        payload.put("runId", fallback(aguiRunId));
        payload.put("threadId", fallback(aguiThreadId));
        payload.put("text", summarize(replyText));
        payload.put("at", now.toString());

        ObjectNode a2a = payload.putObject("a2a");
        a2a.put("agentId", fallback(agentId));
        a2a.put("planName", fallback(planName));
        a2a.put("planVersion", fallback(planVersion));
        a2a.put("taskId", fallback(remoteTaskId));
        a2a.put("linkedConversationId", fallback(childConversationId));

        JsonNode widget = ticketWidget(replyText);
        if (widget != null) {
            payload.set("widget", widget);
        }

        persistenceFacade.appendEvent(
            new AgentEvent(parentConversationId, remoteTaskId, "conversation.assistant.message", payload, now),
            UUID.randomUUID().toString()
        );
    }

    private JsonNode ticketWidget(String replyText) {
        if (replyText == null || replyText.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(replyText);
            if (!parsed.isObject()) {
                return null;
            }
            ObjectNode widget = objectMapper.createObjectNode();
            widget.put("template", "ticket-card");
            widget.set("data", parsed);
            return widget;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String summarize(String replyText) {
        if (replyText == null || replyText.isBlank()) {
            return "Ticket service posted an update.";
        }
        try {
            JsonNode parsed = objectMapper.readTree(replyText);
            if (parsed.isObject()) {
                String ticketId = parsed.path("ticketId").asText("");
                String status = parsed.path("status").asText("");
                String message = parsed.path("message").asText("");
                StringBuilder summary = new StringBuilder();
                if (!ticketId.isBlank()) {
                    summary.append(ticketId);
                }
                if (!status.isBlank()) {
                    if (summary.length() > 0) {
                        summary.append(" ");
                    }
                    summary.append("is ").append(status);
                }
                if (!message.isBlank()) {
                    if (summary.length() > 0) {
                        summary.append(". ");
                    }
                    summary.append(message);
                }
                return summary.length() == 0 ? replyText.trim() : summary.toString();
            }
        } catch (Exception ignored) {
        }
        return replyText.trim();
    }

    private String fallback(String value) {
        return value == null ? "" : value;
    }
}
