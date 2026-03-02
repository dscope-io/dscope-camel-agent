package io.dscope.camel.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class RuntimeConversationPersistenceProcessor implements Processor {

    private final ObjectMapper objectMapper;
    private final ConversationArchiveService conversationArchiveService;

    public RuntimeConversationPersistenceProcessor(ObjectMapper objectMapper,
                                                  ConversationArchiveService conversationArchiveService) {
        this.objectMapper = objectMapper;
        this.conversationArchiveService = conversationArchiveService;
    }

    @Override
    public void process(Exchange exchange) {
        String requested = firstNonBlank(
            exchange.getMessage().getHeader("enabled", String.class),
            exchange.getMessage().getHeader("conversationPersistenceEnabled", String.class),
            bodyEnabled(exchange)
        );

        Boolean parsedEnabled = parseBoolean(requested);
        boolean updated = false;
        if (parsedEnabled != null) {
            conversationArchiveService.setEnabled(parsedEnabled);
            updated = true;
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("updated", updated);
        body.put("conversationPersistenceEnabled", conversationArchiveService.enabled());
        body.put("requested", requested);

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(body.toString());
    }

    private String bodyEnabled(Exchange exchange) {
        try {
            String raw = exchange.getMessage().getBody(String.class);
            if (raw == null || raw.isBlank()) {
                return "";
            }
            JsonNode root = objectMapper.readTree(raw);
            return firstNonBlank(text(root, "enabled"), text(root, "conversationPersistenceEnabled"));
        } catch (IOException ignored) {
            return "";
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private Boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> null;
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
