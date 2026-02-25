package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public final class SipSessionInitEnvelopeProcessor implements Processor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void process(Exchange exchange) throws Exception {
        String conversationId = exchange.getMessage().getHeader("conversationId", String.class);
        String body = exchange.getMessage().getBody(String.class);
        SipCallStartRequest requestDto = parseRequestOrDefault(body);

        ObjectNode request = MAPPER.createObjectNode();
        ObjectNode session = request.putObject("session");
        session.put("type", "realtime");

        if (requestDto.session() != null && requestDto.session().isObject()) {
            session.setAll((ObjectNode) requestDto.session().deepCopy());
        }

        ObjectNode metadata = ensureObject(session, "metadata");
        ObjectNode sip = ensureObject(metadata, "sip");
        sip.put("conversationId", conversationId == null ? "" : conversationId);

        SipCallInfo callInfo = requestDto.call();
        if (callInfo != null) {
            copyIfText(callInfo.id(), sip, "callId");
            copyIfText(callInfo.from(), sip, "from");
            copyIfText(callInfo.to(), sip, "to");
        }

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(request));
    }

    private static SipCallStartRequest parseRequestOrDefault(String body) {
        if (body == null || body.isBlank()) {
            return new SipCallStartRequest(null, null);
        }
        try {
            return MAPPER.readValue(body, SipCallStartRequest.class);
        } catch (JsonProcessingException ignored) {
            return new SipCallStartRequest(null, null);
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

    private static void copyIfText(String value, ObjectNode target, String targetField) {
        if (value != null && !value.isBlank()) {
            target.put(targetField, value);
        }
    }
}