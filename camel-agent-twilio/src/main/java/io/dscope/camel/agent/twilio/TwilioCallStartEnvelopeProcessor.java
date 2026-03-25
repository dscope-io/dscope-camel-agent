package io.dscope.camel.agent.twilio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public final class TwilioCallStartEnvelopeProcessor implements Processor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void process(Exchange exchange) throws Exception {
        JsonNode incoming = parseBody(exchange.getMessage().getBody(String.class));
        String callSid = firstText(
            exchange.getMessage().getHeader("callSid", String.class),
            textAt(incoming, "callSid"),
            textAt(incoming, "call.id"),
            textAt(incoming, "call.callSid"),
            textAt(incoming, "twilio.callSid")
        );

        ObjectNode request = MAPPER.createObjectNode();
        ObjectNode call = request.putObject("call");
        putIfText(call, "id", callSid);
        putIfText(call, "from", firstText(textAt(incoming, "from"), textAt(incoming, "call.from")));
        putIfText(call, "to", firstText(textAt(incoming, "to"), textAt(incoming, "call.to")));

        ObjectNode session = request.putObject("session");
        JsonNode sessionNode = nodeAt(incoming, "session");
        if (sessionNode instanceof ObjectNode objectNode) {
            session.setAll((ObjectNode) objectNode.deepCopy());
        }

        String voice = firstText(textAt(incoming, "voice"), textAt(incoming, "session.audio.output.voice"));
        if (voice != null && !voice.isBlank()) {
            ensureObject(ensureObject(session, "audio"), "output").put("voice", voice);
        }

        ObjectNode metadata = ensureObject(session, "metadata");
        ObjectNode twilio = ensureObject(metadata, "twilio");
        putIfText(twilio, "callSid", callSid);
        putIfText(twilio, "accountSid", firstText(textAt(incoming, "accountSid"), textAt(incoming, "twilio.accountSid")));
        putIfText(twilio, "streamSid", firstText(textAt(incoming, "streamSid"), textAt(incoming, "twilio.streamSid")));

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

    private static void putIfText(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
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