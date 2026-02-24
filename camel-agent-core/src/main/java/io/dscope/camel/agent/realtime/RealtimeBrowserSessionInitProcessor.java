package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class RealtimeBrowserSessionInitProcessor implements Processor {

    private static final String DEFAULT_MODEL = "gpt-realtime";

    private final ObjectMapper objectMapper;
    private final RealtimeBrowserSessionRegistry sessionRegistry;

    public RealtimeBrowserSessionInitProcessor(RealtimeBrowserSessionRegistry sessionRegistry) {
        this.objectMapper = new ObjectMapper();
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void process(Exchange exchange) {
        String conversationId = firstNonBlank(
            exchange.getMessage().getHeader("conversationId", String.class),
            exchange.getMessage().getHeader("sessionId", String.class)
        );

        if (conversationId.isBlank()) {
            writeError(exchange, 400, "Missing conversationId path parameter");
            return;
        }

        JsonNode root = parseJson(exchange.getMessage().getBody(String.class));
        ObjectNode session = root.path("session").isObject()
            ? (ObjectNode) root.path("session").deepCopy()
            : objectMapper.createObjectNode();

        if (!session.hasNonNull("type")) {
            session.put("type", "realtime");
        }
        if (!session.hasNonNull("model")) {
            session.put("model", firstNonBlank(
                property(exchange, "agent.runtime.realtime.model", ""),
                property(exchange, "agent.realtime.model", ""),
                DEFAULT_MODEL
            ));
        }

        ObjectNode metadata = session.path("metadata").isObject()
            ? (ObjectNode) session.path("metadata")
            : objectMapper.createObjectNode();
        if (!metadata.hasNonNull("conversationId")) {
            metadata.put("conversationId", conversationId);
        }
        if (!session.path("metadata").isObject()) {
            session.set("metadata", metadata);
        }

        String configuredVoice = firstNonBlank(
            text(session.path("audio").path("output"), "voice"),
            property(exchange, "agent.runtime.realtime.voice", ""),
            property(exchange, "agent.realtime.voice", "")
        );
        if (!configuredVoice.isBlank()) {
            ObjectNode audio = session.path("audio").isObject()
                ? (ObjectNode) session.path("audio")
                : objectMapper.createObjectNode();
            ObjectNode output = audio.path("output").isObject()
                ? (ObjectNode) audio.path("output")
                : objectMapper.createObjectNode();
            if (!output.hasNonNull("voice")) {
                output.put("voice", configuredVoice);
            }
            if (!audio.path("output").isObject()) {
                audio.set("output", output);
            }
            if (!session.path("audio").isObject()) {
                session.set("audio", audio);
            }
        }

        sessionRegistry.putSession(conversationId, session);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("conversationId", conversationId);
        response.put("initialized", true);
        response.set("session", session);

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(response.toString());
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private void writeError(Exchange exchange, int statusCode, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", message == null ? "unknown error" : message);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(error.toString());
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
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
        return "";
    }

    private String property(Exchange exchange, String key, String defaultValue) {
        try {
            String value = exchange.getContext().resolvePropertyPlaceholders("{{" + key + ":" + defaultValue + "}}");
            if (value == null || value.isBlank() || value.contains("{{")) {
                return defaultValue;
            }
            return value.trim();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
