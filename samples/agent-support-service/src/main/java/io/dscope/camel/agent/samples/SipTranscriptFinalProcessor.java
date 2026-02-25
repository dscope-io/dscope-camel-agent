package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public final class SipTranscriptFinalProcessor implements Processor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void process(Exchange exchange) throws Exception {
        String body = exchange.getMessage().getBody(String.class);
        SipTranscriptFinalRequest request = parseRequestOrDefault(body);
        String transcript = extractTranscript(request);
        if (transcript == null || transcript.isBlank()) {
            ObjectNode error = MAPPER.createObjectNode();
            error.put("error", "Missing transcript text; provide 'text', 'transcript', or 'payload.text'");
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setBody(MAPPER.writeValueAsString(error));
            exchange.setRouteStop(true);
            return;
        }

        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", "transcript.final");
        event.put("text", transcript);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(MAPPER.writeValueAsString(event));
    }

    private static SipTranscriptFinalRequest parseRequestOrDefault(String body) {
        if (body == null || body.isBlank()) {
            return new SipTranscriptFinalRequest(null, null, null);
        }
        try {
            return MAPPER.readValue(body, SipTranscriptFinalRequest.class);
        } catch (JsonProcessingException ignored) {
            return new SipTranscriptFinalRequest(null, null, null);
        }
    }

    private static String extractTranscript(SipTranscriptFinalRequest request) {
        if (request == null) {
            return null;
        }
        String text = textValue(request.text());
        if (text != null && !text.isBlank()) {
            return text;
        }
        String transcript = textValue(request.transcript());
        if (transcript != null && !transcript.isBlank()) {
            return transcript;
        }
        SipTranscriptPayload payload = request.payload();
        if (payload != null) {
            String payloadText = textValue(payload.text());
            if (payloadText != null && !payloadText.isBlank()) {
                return payloadText;
            }
        }
        return null;
    }

    private static String textValue(String value) {
        return value == null ? null : value;
    }
}