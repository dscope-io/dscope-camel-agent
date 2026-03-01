package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.config.CorrelationKeys;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.registry.CorrelationRegistry;
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

    public AuditConversationViewProcessor(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
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
        for (AgentEvent event : events) {
            if (event == null) {
                continue;
            }
            String type = safeLower(event.type());
            String role = inferRole(type);
            String text = extractText(event.payload());
            if (text.isBlank() && !type.contains("message")) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", role);
            row.put("type", event.type());
            row.put("text", text);
            row.put("timestamp", event.timestamp() == null ? "" : event.timestamp().toString());
            row.put("manual", "assistant.manual.message".equals(event.type()));
            row.put("payload", event.payload());
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

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private String resolveCorrelation(String conversationId, String key) {
        return CorrelationRegistry.global().resolve(conversationId, key, "");
    }

    private String inferRole(String type) {
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
                return normalized;
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
            return nonBlank(payload.asText(""));
        }
        return "";
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
