package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.telephony.OutboundSipCallRequest;
import io.dscope.camel.agent.twilio.TwilioCallGateway;
import io.dscope.camel.agent.twilio.TwilioCallPlacement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.camel.ProducerTemplate;

final class CamelDirectTwilioCallGateway implements TwilioCallGateway {

    private final ProducerTemplate producerTemplate;
    private final ObjectMapper objectMapper;

    CamelDirectTwilioCallGateway(ProducerTemplate producerTemplate, ObjectMapper objectMapper) {
        this.producerTemplate = Objects.requireNonNull(producerTemplate, "producerTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public TwilioCallPlacement placeCall(OutboundSipCallRequest request) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", request.destination());
        body.put("from", request.callerId());
        body.put("status", "requested");
        body.put("purpose", request.purpose());
        if (request.customerName() != null && !request.customerName().isBlank()) {
            body.put("customerName", request.customerName());
        }
        if (request.metadata() != null && !request.metadata().isNull()) {
            body.put("metadata", request.metadata());
        }

        Object response = producerTemplate.requestBody("direct:twilio-outbound-call", body);
        JsonNode raw = response == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(response);
        String providerCallId = firstText(
            textAt(raw, "sid"),
            textAt(raw, "callSid"),
            textAt(raw, "id")
        );
        return new TwilioCallPlacement(providerCallId, raw);
    }

    private static String textAt(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
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