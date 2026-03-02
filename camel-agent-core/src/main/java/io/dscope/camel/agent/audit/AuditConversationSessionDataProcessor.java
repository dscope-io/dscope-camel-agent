package io.dscope.camel.agent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.runtime.ConversationArchiveService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

public class AuditConversationSessionDataProcessor implements Processor {

    private static final int DEFAULT_LIMIT = 300;
    private static final int MAX_LIMIT = 2000;

    private final ConversationArchiveService conversationArchiveService;
    private final ObjectMapper objectMapper;

    public AuditConversationSessionDataProcessor(ConversationArchiveService conversationArchiveService,
                                                 ObjectMapper objectMapper) {
        this.conversationArchiveService = conversationArchiveService;
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

        String sessionId = readText(in, "sessionId");
        String type = readText(in, "type");
        String query = readText(in, "q");
        String fromText = readText(in, "from");
        String toText = readText(in, "to");
        int limit = parseLimit(readText(in, "limit"));

        Instant from = parseInstantOrNull(fromText);
        if (fromText != null && !fromText.isBlank() && from == null) {
            writeBadRequest(exchange, "Invalid from timestamp. Use ISO-8601 format, e.g. 2026-02-25T23:00:00Z");
            return;
        }
        Instant to = parseInstantOrNull(toText);
        if (toText != null && !toText.isBlank() && to == null) {
            writeBadRequest(exchange, "Invalid to timestamp. Use ISO-8601 format, e.g. 2026-02-25T23:59:59Z");
            return;
        }

        List<AgentEvent> loaded = conversationArchiveService.loadConversationEvents(conversationId, sessionId, limit);
        Stream<AgentEvent> filteredStream = loaded.stream();

        if (type != null && !type.isBlank()) {
            String normalizedType = type.trim().toLowerCase(Locale.ROOT);
            filteredStream = filteredStream.filter(event -> event.type() != null
                && event.type().toLowerCase(Locale.ROOT).equals(normalizedType));
        }

        if (from != null) {
            filteredStream = filteredStream.filter(event -> event.timestamp() != null && !event.timestamp().isBefore(from));
        }
        if (to != null) {
            filteredStream = filteredStream.filter(event -> event.timestamp() != null && !event.timestamp().isAfter(to));
        }

        if (query != null && !query.isBlank()) {
            String normalizedQuery = query.toLowerCase(Locale.ROOT);
            filteredStream = filteredStream.filter(event -> containsIgnoreCase(event.type(), normalizedQuery)
                || containsIgnoreCase(String.valueOf(event.payload()), normalizedQuery));
        }

        List<Map<String, Object>> events = filteredStream
            .limit(limit)
            .map(this::toJson)
            .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationId", conversationId);
        response.put("sessionId", sessionId == null ? "" : sessionId);
        response.put("archivePersistenceEnabled", conversationArchiveService.enabled());
        response.put("filters", Map.of(
            "type", type == null ? "" : type,
            "q", query == null ? "" : query,
            "from", fromText == null ? "" : fromText,
            "to", toText == null ? "" : toText,
            "limit", limit
        ));
        response.put("count", events.size());
        response.put("events", events);

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private static String readText(Message in, String headerName) {
        Object value = in.getHeader(headerName);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static int parseLimit(String limitText) {
        if (limitText == null || limitText.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int parsed = Integer.parseInt(limitText.trim());
            return Math.max(1, Math.min(parsed, MAX_LIMIT));
        } catch (NumberFormatException ignored) {
            return DEFAULT_LIMIT;
        }
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private Map<String, Object> toJson(AgentEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        JsonNode payload = event.payload();
        data.put("conversationId", event.conversationId());
        data.put("taskId", event.taskId());
        data.put("type", event.type());
        data.put("role", text(payload, "role"));
        data.put("direction", text(payload, "direction"));
        data.put("source", text(payload, "source"));
        data.put("sessionId", text(payload, "sessionId"));
        data.put("runId", text(payload, "runId"));
        data.put("text", payloadText(payload));
        data.put("payload", payload);
        data.put("timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        return data;
    }

    private String text(JsonNode payload, String field) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return "";
        }
        JsonNode value = payload.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String payloadText(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return "";
        }
        if (payload.isTextual()) {
            return payload.asText("");
        }
        String text = text(payload, "text");
        if (!text.isBlank()) {
            return text;
        }
        text = text(payload, "transcript");
        if (!text.isBlank()) {
            return text;
        }
        text = text(payload, "message");
        if (!text.isBlank()) {
            return text;
        }
        return text(payload, "content");
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
