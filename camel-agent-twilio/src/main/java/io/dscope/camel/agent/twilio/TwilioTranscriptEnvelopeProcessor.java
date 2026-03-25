package io.dscope.camel.agent.twilio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public final class TwilioTranscriptEnvelopeProcessor implements Processor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void process(Exchange exchange) throws Exception {
        JsonNode incoming = parseBody(exchange.getMessage().getBody(String.class));
        String transcript = firstText(
            textAt(incoming, "text"),
            textAt(incoming, "transcript"),
            textAt(incoming, "speech.text"),
            textAt(incoming, "media.transcript"),
            textAt(incoming, "payload.text")
        );

        ObjectNode request = MAPPER.createObjectNode();
        if (transcript != null && !transcript.isBlank()) {
            request.put("transcript", transcript);
        }
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(request));
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
}