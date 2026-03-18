package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.config.CorrelationKeys;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.agent.util.TextEncodingSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

public class AuditConversationViewProcessor implements Processor {

    private static final int DEFAULT_LIMIT = 500;

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;
    private final AgentPlanSelectionResolver planSelectionResolver;
    private final String plansConfig;
    private final String blueprintUri;

    public AuditConversationViewProcessor(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        this(persistenceFacade, objectMapper, null, null, null);
    }

    public AuditConversationViewProcessor(PersistenceFacade persistenceFacade,
                                          ObjectMapper objectMapper,
                                          AgentPlanSelectionResolver planSelectionResolver,
                                          String plansConfig,
                                          String blueprintUri) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
        this.planSelectionResolver = planSelectionResolver;
        this.plansConfig = plansConfig == null || plansConfig.isBlank() ? null : plansConfig;
        this.blueprintUri = blueprintUri == null || blueprintUri.isBlank() ? null : blueprintUri;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();
        String conversationId = readText(in, "conversationId");
        if (conversationId == null || conversationId.isBlank()) {
            writeBadRequest(exchange, "conversationId query parameter is required");
            return;
        }

        int limit = parseLimit(readText(in, "limit"));
        List<AgentEvent> events = persistenceFacade.loadConversation(conversationId, limit);

        String aguiSessionId = resolveCorrelation(conversationId, CorrelationKeys.AGUI_SESSION_ID);
        String aguiRunId = resolveCorrelation(conversationId, CorrelationKeys.AGUI_RUN_ID);
        String aguiThreadId = resolveCorrelation(conversationId, CorrelationKeys.AGUI_THREAD_ID);

        if (isBlank(aguiSessionId) || isBlank(aguiRunId) || isBlank(aguiThreadId)) {
            for (AgentEvent event : events) {
                JsonNode correlation = extractCorrelation(event == null ? null : event.payload());
                if (correlation == null) {
                    continue;
                }
                if (isBlank(aguiSessionId)) {
                    aguiSessionId = nonBlank(correlation.path("aguiSessionId").asText(""));
                }
                if (isBlank(aguiRunId)) {
                    aguiRunId = nonBlank(correlation.path("aguiRunId").asText(""));
                }
                if (isBlank(aguiThreadId)) {
                    aguiThreadId = nonBlank(correlation.path("aguiThreadId").asText(""));
                }
            }
        }

        List<Map<String, Object>> perspective = new ArrayList<>();
        String effectiveBlueprint = resolveBlueprint(conversationId);
        AuditMetadataSupport.AgentStepMetadata currentAgentState = AuditMetadataSupport.deriveAgentStepMetadata(events, effectiveBlueprint);
        AuditMetadataSupport.A2ACorrelationMetadata currentA2aState = AuditMetadataSupport.deriveA2ACorrelation(events);
        AuditMetadataSupport.AgentStepMetadata stepAgentState = AuditMetadataSupport.AgentStepMetadata.fromBlueprint(
            effectiveBlueprint,
            AuditMetadataSupport.loadBlueprintMetadata(effectiveBlueprint)
        );
        for (AgentEvent event : events) {
            if (event == null) {
                continue;
            }
            stepAgentState = AuditMetadataSupport.advanceAgentStepMetadata(stepAgentState, event);
            String type = safeLower(event.type());
            String role = inferRole(type);
            JsonNode normalizedPayload = TextEncodingSupport.repairUtf8Mojibake(event.payload(), objectMapper);
            String text = extractVisibleText(type, normalizedPayload);
            if (text.isBlank() && !isVisibleEvent(type)) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", role);
            row.put("type", event.type());
            row.put("text", text);
            row.put("timestamp", event.timestamp() == null ? "" : event.timestamp().toString());
            row.put("manual", "assistant.manual.message".equals(event.type()));
            row.put("payload", normalizedPayload);
            row.put("agent", stepAgentState.asMap());
            perspective.add(row);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("eventCount", events.size());
        response.put("copilotKitAvailable", !isBlank(aguiSessionId) || !isBlank(aguiRunId) || !isBlank(aguiThreadId));
        response.put("agui", Map.of(
            "sessionId", fallback(aguiSessionId),
            "runId", fallback(aguiRunId),
            "threadId", fallback(aguiThreadId)
        ));
        response.put("agentPerspective", Map.of(
            "messageCount", perspective.size(),
            "messages", perspective
        ));
        response.put("agent", currentAgentState.asMap());
        response.put("a2a", currentA2aState.asMap());

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private String resolveBlueprint(String conversationId) {
        if (planSelectionResolver == null) {
            return blueprintUri;
        }
        return planSelectionResolver.resolveBlueprintForConversation(conversationId, plansConfig, blueprintUri);
    }

    private String resolveCorrelation(String conversationId, String key) {
        return CorrelationRegistry.global().resolve(conversationId, key, "");
    }

    private String inferRole(String type) {
        if (isTranscriptEvent(type)) {
            return inferTranscriptRole(type);
        }
        if (type.contains("assistant") || type.startsWith("agent.")) {
            return "assistant";
        }
        if (type.contains("user")) {
            return "user";
        }
        return "system";
    }

    private JsonNode extractCorrelation(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return null;
        }
        JsonNode direct = payload.path("correlation");
        if (direct != null && direct.isObject()) {
            return direct;
        }
        JsonNode nested = payload.path("payload").path("correlation");
        if (nested != null && nested.isObject()) {
            return nested;
        }
        return null;
    }

    private String extractVisibleText(String type, JsonNode payload) {
        if (isTranscriptEvent(type)) {
            String transcript = extractTranscriptText(payload);
            if (!transcript.isBlank()) {
                return transcript;
            }
        }
        return extractText(payload);
    }

    private String extractTranscriptText(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return "";
        }

        List<String> transcriptCandidates = List.of(
            payload.path("transcript").asText(""),
            payload.path("finalTranscript").asText(""),
            payload.path("final_transcript").asText(""),
            payload.path("utterance").asText(""),
            payload.path("text").asText(""),
            payload.path("item").path("content").path(0).path("transcript").asText(""),
            payload.path("item").path("content").path(0).path("text").asText("")
        );
        for (String candidate : transcriptCandidates) {
            String normalized = nonBlank(candidate);
            if (!normalized.isBlank()) {
                return TextEncodingSupport.repairUtf8Mojibake(normalized);
            }
        }

        JsonNode nestedPayload = payload.path("payload");
        if (nestedPayload.isObject()) {
            String nestedTranscript = extractTranscriptText(nestedPayload);
            if (!nestedTranscript.isBlank()) {
                return nestedTranscript;
            }
        }

        return "";
    }

    private String extractText(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return "";
        }

        List<String> directCandidates = List.of(
            payload.path("text").asText(""),
            payload.path("message").asText(""),
            payload.path("assistantMessage").asText(""),
            payload.path("content").asText(""),
            payload.path("delta").asText(""),
            payload.path("summary").asText(""),
            payload.path("output").asText(""),
            payload.path("input").asText("")
        );
        for (String candidate : directCandidates) {
            String normalized = nonBlank(candidate);
            if (!normalized.isBlank()) {
                return TextEncodingSupport.repairUtf8Mojibake(normalized);
            }
        }

        JsonNode nestedPayload = payload.path("payload");
        if (nestedPayload.isObject()) {
            String nestedText = extractText(nestedPayload);
            if (!nestedText.isBlank()) {
                return nestedText;
            }
        }

        if (payload.isTextual()) {
            return TextEncodingSupport.repairUtf8Mojibake(nonBlank(payload.asText("")));
        }
        return "";
    }

    private static boolean isVisibleEvent(String type) {
        return type.contains("message") || isTranscriptEvent(type);
    }

    private static boolean isTranscriptEvent(String type) {
        return type.contains("transcript") || type.contains("input_audio_transcription") || type.contains("output_audio_transcript");
    }

    private static String inferTranscriptRole(String type) {
        if (type.startsWith("response.") || type.contains("assistant") || type.contains("output_audio_transcript") || type.contains("output_text")) {
            return "assistant";
        }
        return "user";
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static int parseLimit(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(1, Math.min(parsed, 2000));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LIMIT;
        }
    }

    private static String readText(Message in, String headerName) {
        Object value = in.getHeader(headerName);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String fallback(String value) {
        return value == null ? "" : value;
    }

    private void writeBadRequest(Exchange exchange, String message) throws Exception {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(Map.of(
            "error", "bad_request",
            "message", message
        )));
    }
}
