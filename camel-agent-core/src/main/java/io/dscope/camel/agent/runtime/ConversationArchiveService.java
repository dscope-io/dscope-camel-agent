package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ConversationArchiveService {

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;
    private volatile boolean enabled;

    public ConversationArchiveService(PersistenceFacade persistenceFacade, ObjectMapper objectMapper, boolean enabled) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void appendAgUiTurn(String conversationId,
                               String userText,
                               String assistantText,
                               String sessionId,
                               String runId) {
        if (!enabled || persistenceFacade == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        appendMessage(conversationId, "user", userText, "conversation.user.message", sessionId, runId, "agui");
        appendMessage(conversationId, "assistant", assistantText, "conversation.assistant.message", sessionId, runId, "agui");
    }

    public void appendRealtimeTranscriptObserved(String conversationId,
                                                 String direction,
                                                 String transcript,
                                                 String observedEventType) {
        if (!enabled || persistenceFacade == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (transcript == null || transcript.isBlank()) {
            return;
        }
        String normalizedDirection = normalizeDirection(direction, observedEventType);
        Instant now = Instant.now();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("conversationId", conversationId);
        payload.put("direction", normalizedDirection);
        payload.put("source", "realtime");
        payload.put("observedEventType", observedEventType == null ? "" : observedEventType);
        payload.put("text", transcript);
        payload.put("at", now.toString());
        persistenceFacade.appendEvent(
            new AgentEvent(conversationId, null, "conversation.realtime.observed", payload, now),
            UUID.randomUUID().toString()
        );
    }

    public void appendRealtimeTurn(String conversationId, String userText, String assistantText) {
        if (!enabled || persistenceFacade == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        appendMessage(conversationId, "user", userText, "conversation.user.message", null, null, "realtime");
        appendMessage(conversationId, "assistant", assistantText, "conversation.assistant.message", null, null, "realtime");
    }

    public List<AgentEvent> loadConversationEvents(String conversationId, String sessionId, int limit) {
        if (persistenceFacade == null || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        int boundedLimit = Math.max(1, Math.min(limit <= 0 ? 300 : limit, 2000));
        String normalizedSessionId = sessionId == null ? "" : sessionId.trim();

        return persistenceFacade.loadConversation(conversationId, boundedLimit).stream()
            .filter(event -> event.type() != null
                && event.type().toLowerCase(Locale.ROOT).startsWith("conversation."))
            .filter(event -> {
                if (normalizedSessionId.isBlank()) {
                    return true;
                }
                if (event.payload() == null) {
                    return false;
                }
                String payloadSessionId = event.payload().path("sessionId").asText("");
                return normalizedSessionId.equals(payloadSessionId);
            })
            .limit(boundedLimit)
            .toList();
    }

    private void appendMessage(String conversationId,
                               String role,
                               String text,
                               String eventType,
                               String sessionId,
                               String runId,
                               String source) {
        if (text == null || text.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("conversationId", conversationId);
        payload.put("role", role == null ? "unknown" : role);
        payload.put("source", source == null ? "" : source);
        payload.put("sessionId", sessionId == null ? "" : sessionId);
        payload.put("runId", runId == null ? "" : runId);
        payload.put("text", text);
        payload.put("at", now.toString());
        persistenceFacade.appendEvent(
            new AgentEvent(conversationId, null, eventType, payload, now),
            UUID.randomUUID().toString()
        );
    }

    private String normalizeDirection(String direction, String observedEventType) {
        String normalized = direction == null ? "" : direction.trim().toLowerCase();
        if ("input".equals(normalized) || "output".equals(normalized)) {
            return normalized;
        }
        String observed = observedEventType == null ? "" : observedEventType.toLowerCase();
        if (observed.contains("input_audio_transcription")) {
            return "input";
        }
        if (observed.contains("output_audio_transcript") || observed.contains("output_text") || observed.startsWith("response.")) {
            return "output";
        }
        return "unknown";
    }
}
