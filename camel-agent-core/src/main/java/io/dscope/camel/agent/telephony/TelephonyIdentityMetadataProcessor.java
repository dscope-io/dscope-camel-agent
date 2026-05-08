package io.dscope.camel.agent.telephony;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class TelephonyIdentityMetadataProcessor implements Processor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void process(Exchange exchange) throws Exception {
        JsonNode body = parseBody(exchange.getMessage().getBody(String.class));
        if (!(body instanceof ObjectNode root)) {
            return;
        }

        String callerId = firstText(
            textAt(root, "session.metadata.sip.callerId"),
            textAt(root, "call.from"),
            textAt(root, "from")
        );
        String fromNumber = firstText(textAt(root, "session.metadata.sip.fromNumber"), callerId);

        if (callerId == null && fromNumber == null) {
            return;
        }

        ObjectNode session = ensureObject(root, "session");
        ObjectNode metadata = ensureObject(session, "metadata");
        ObjectNode sip = ensureObject(metadata, "sip");

        putIfText(sip, "callerId", callerId);
        putIfText(sip, "fromNumber", fromNumber);

        JsonNode twilioNode = metadata.get("twilio");
        if (twilioNode instanceof ObjectNode twilio) {
            putIfText(twilio, "fromNumber", fromNumber);
        }

        if (callerId != null) {
            exchange.getMessage().setHeader("callerId", callerId);
        }
        if (fromNumber != null) {
            exchange.getMessage().setHeader("fromNumber", fromNumber);
            exchange.getMessage().setHeader("twilio.fromNumber", fromNumber);
        }

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(root));
    }

    private static JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (JsonProcessingException ignored) {
            return MAPPER.createObjectNode();
        }
    }

    private static JsonNode nodeAt(JsonNode node, String path) {
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
            current = current.path(part);
        }
        return current == null || current.isMissingNode() || current.isNull() ? null : current;
    }

    private static String textAt(JsonNode node, String path) {
        JsonNode value = nodeAt(node, path);
        return value == null || !value.isValueNode() ? null : value.asText(null);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void putIfText(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank() && !target.hasNonNull(field)) {
            target.put(field, value);
        }
    }

    private static ObjectNode ensureObject(ObjectNode parent, String field) {
        JsonNode existing = parent.get(field);
        if (existing instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode replacement = MAPPER.createObjectNode();
        parent.set(field, replacement);
        return replacement;
    }
}