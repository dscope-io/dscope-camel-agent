package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

public class RuntimePurgePreviewProcessor implements Processor {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 2000;
    private static final int DEFAULT_EVENT_SCAN_LIMIT = 500;
    private static final int MAX_EVENT_SCAN_LIMIT = 5000;
    private static final int DEFAULT_CONVERSATION_SCAN_FACTOR = 5;
    private static final int MAX_CONVERSATION_SCAN_LIMIT = 10000;

    private final ObjectMapper objectMapper;
    private final PersistenceFacade persistenceFacade;

    public RuntimePurgePreviewProcessor(ObjectMapper objectMapper, PersistenceFacade persistenceFacade) {
        this.objectMapper = objectMapper;
        this.persistenceFacade = persistenceFacade;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();

        boolean requireClosed = parseBoolean(readText(in, "requireClosed"), true);
        String beforeText = firstNonBlank(readText(in, "before"), readText(in, "from"), readText(in, "purgeBefore"));
        Instant before = parseInstantOrNull(beforeText);
        if (beforeText != null && !beforeText.isBlank() && before == null) {
            writeBadRequest(exchange, "Invalid before timestamp. Use ISO-8601 format, e.g. 2026-02-25T23:59:59Z");
            return;
        }

        String agentName = normalizeName(firstNonBlank(readText(in, "agentName"), readText(in, "agentType"), readText(in, "purgeAgentName")));
        int limit = parseInt(readText(in, "limit"), DEFAULT_LIMIT, 1, MAX_LIMIT);
        int eventScanLimit = parseInt(readText(in, "eventScanLimit"), DEFAULT_EVENT_SCAN_LIMIT, 1, MAX_EVENT_SCAN_LIMIT);
        int conversationScanLimit = parseInt(
            readText(in, "conversationScanLimit"),
            Math.min(MAX_CONVERSATION_SCAN_LIMIT, limit * DEFAULT_CONVERSATION_SCAN_FACTOR),
            1,
            MAX_CONVERSATION_SCAN_LIMIT
        );
        boolean includeConversationIds = parseBoolean(readText(in, "includeConversationIds"), false);

        List<String> scannedConversationIds = persistenceFacade.listConversationIds(conversationScanLimit);
        List<String> matchedConversationIds = new ArrayList<>();
        int matchedClosed = 0;
        int matchedAgentName = 0;

        for (String conversationId : scannedConversationIds) {
            if (matchedConversationIds.size() >= limit) {
                break;
            }
            List<AgentEvent> events = persistenceFacade.loadConversation(conversationId, eventScanLimit);
            if (events.isEmpty()) {
                continue;
            }

            Instant closedAt = latestClosedAt(events);
            if (requireClosed) {
                if (closedAt == null) {
                    continue;
                }
                if (before != null && closedAt.isAfter(before)) {
                    continue;
                }
            } else if (before != null && !hasEventAtOrBefore(events, before)) {
                continue;
            }

            String detectedAgentName = detectAgentName(events);
            if (agentName != null && !agentName.isBlank()) {
                if (detectedAgentName == null || !detectedAgentName.equalsIgnoreCase(agentName)) {
                    continue;
                }
                matchedAgentName++;
            }
            if (closedAt != null) {
                matchedClosed++;
            }

            matchedConversationIds.add(conversationId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("preview", true);
        response.put("filters", Map.of(
            "requireClosed", requireClosed,
            "before", beforeText == null ? "" : beforeText,
            "agentName", agentName == null ? "" : agentName,
            "limit", limit,
            "conversationScanLimit", conversationScanLimit,
            "eventScanLimit", eventScanLimit
        ));
        response.put("scannedConversations", scannedConversationIds.size());
        response.put("matchedConversations", matchedConversationIds.size());
        response.put("matchedClosedConversations", matchedClosed);
        response.put("matchedAgentNameConversations", matchedAgentName);
        response.put("conversationIdsTruncated", matchedConversationIds.size() >= limit);
        if (includeConversationIds) {
            response.put("conversationIds", matchedConversationIds);
        }
        response.put("notes", List.of(
            "This endpoint is non-destructive and does not delete data.",
            "Results are a runtime preview based on loaded conversation events.",
            "Final purge scope is defined by DB purge SQL scripts."
        ));

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    private static String readText(Message in, String name) {
        Object value = in.getHeader(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static int parseInt(String text, int defaultValue, int min, int max) {
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(text.trim());
            return Math.max(min, Math.min(parsed, max));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean parseBoolean(String text, boolean defaultValue) {
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("true") || normalized.equals("1") || normalized.equals("yes")) {
            return true;
        }
        if (normalized.equals("false") || normalized.equals("0") || normalized.equals("no")) {
            return false;
        }
        return defaultValue;
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Instant latestClosedAt(List<AgentEvent> events) {
        Instant latest = null;
        for (AgentEvent event : events) {
            if (event == null || event.type() == null || !event.type().equals("agent.instance.closed")) {
                continue;
            }
            Instant ts = event.timestamp();
            if (ts != null && (latest == null || ts.isAfter(latest))) {
                latest = ts;
            }
        }
        return latest;
    }

    private static boolean hasEventAtOrBefore(List<AgentEvent> events, Instant before) {
        for (AgentEvent event : events) {
            if (event == null || event.timestamp() == null) {
                continue;
            }
            if (!event.timestamp().isAfter(before)) {
                return true;
            }
        }
        return false;
    }

    private String detectAgentName(List<AgentEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent event = events.get(i);
            if (event == null) {
                continue;
            }
            String fromPayload = extractAgentName(event.payload());
            if (fromPayload != null && !fromPayload.isBlank()) {
                return fromPayload;
            }
        }
        return null;
    }

    private String extractAgentName(Object payload) {
        if (payload == null) {
            return null;
        }
        JsonNode node = objectMapper.valueToTree(payload);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }

        String direct = text(node, "agentName");
        if (!direct.isBlank()) {
            return direct;
        }
        String directType = text(node, "agentType");
        if (!directType.isBlank()) {
            return directType;
        }

        JsonNode nestedPayload = node.path("payload");
        String nested = text(nestedPayload, "agentName");
        if (!nested.isBlank()) {
            return nested;
        }
        String nestedType = text(nestedPayload, "agentType");
        if (!nestedType.isBlank()) {
            return nestedType;
        }

        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private void writeBadRequest(Exchange exchange, String message) throws Exception {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", "bad_request");
        error.put("message", message == null ? "invalid request" : message);

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(error));
    }
}