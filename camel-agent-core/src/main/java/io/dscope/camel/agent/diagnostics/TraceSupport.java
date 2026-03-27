package io.dscope.camel.agent.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.AgentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;

public final class TraceSupport {

    private static final int MAX_TEXT_CHARS = 1200;

    private TraceSupport() {
    }

    public static String bodyText(Exchange exchange) {
        Object body = exchange.getMessage().getBody();
        if (body == null) {
            return "";
        }
        if (body instanceof String text) {
            return text;
        }
        String text = exchange.getMessage().getBody(String.class);
        exchange.getMessage().setBody(text);
        return text == null ? "" : text;
    }

    public static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text
            .replace("\r", "\\r")
            .replace("\n", "\\n");
        if (normalized.length() <= MAX_TEXT_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_TEXT_CHARS) + "...";
    }

    public static String header(Exchange exchange, String name) {
        String value = exchange.getMessage().getHeader(name, String.class);
        return value == null ? "" : value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    public static List<String> summarizeStateMessages(Map<String, Object> params) {
        Map<String, Object> state = map(params.get("state"));
        Object messagesObj = state.get("messages");
        if (!(messagesObj instanceof List<?> messages)) {
            return List.of();
        }
        List<String> summarized = new ArrayList<>();
        for (Object messageObj : messages) {
            Map<String, Object> message = map(messageObj);
            String role = stringValue(message.get("role"));
            String text = extractText(message);
            summarized.add((role.isBlank() ? "unknown" : role) + ":" + excerpt(text));
        }
        return summarized;
    }

    public static String extractText(Map<String, Object> node) {
        String direct = stringValue(node.get("text"));
        if (!direct.isBlank()) {
            return direct;
        }
        Object content = node.get("content");
        if (content instanceof String text) {
            return text;
        }
        Object partsObj = node.get("parts");
        if (partsObj instanceof List<?> parts) {
            List<String> values = new ArrayList<>();
            for (Object partObj : parts) {
                Map<String, Object> part = map(partObj);
                String text = stringValue(part.get("text"));
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
            return String.join(" | ", values);
        }
        return "";
    }

    public static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String summarizeEvents(List<AgentEvent> events) {
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < Math.min(events.size(), 6); index++) {
            AgentEvent event = events.get(index);
            lines.add((event.type() == null ? "unknown" : event.type()) + ":" + excerpt(extractEventText(event.payload())));
        }
        return String.join(" | ", lines);
    }

    public static String extractEventText(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return "";
        }
        if (payload.isTextual()) {
            return payload.asText("");
        }
        String text = payload.path("text").asText("");
        if (!text.isBlank()) {
            return text;
        }
        text = payload.path("transcript").asText("");
        if (!text.isBlank()) {
            return text;
        }
        text = payload.path("message").asText("");
        if (!text.isBlank()) {
            return text;
        }
        return payload.toString();
    }

    public static String summarizeProcessorResponse(Exchange exchange, ObjectMapper objectMapper) {
        String body = bodyText(exchange);
        if (body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.isObject()) {
                String conversationId = root.path("conversationId").asText("");
                String assistantMessage = root.path("assistantMessage").asText("");
                boolean routedToAgent = root.path("routedToAgent").asBoolean(false);
                return "conversationId=" + conversationId
                    + ", routedToAgent=" + routedToAgent
                    + ", assistantMessage=" + excerpt(assistantMessage)
                    + ", body=" + excerpt(body);
            }
        } catch (Exception ignored) {
        }
        return excerpt(body);
    }
}