package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.telephony.OutboundSipCallRequest;
import io.dscope.camel.agent.telephony.OutboundSipCallResult;
import io.dscope.camel.agent.telephony.SipProviderClient;
import io.dscope.camel.agent.twilio.TwilioSipProviderClient;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

class SupportOutboundCallProcessor implements Processor {

    private final ObjectMapper objectMapper;
    private final SupportCallRegistry registry;

    SupportOutboundCallProcessor(ObjectMapper objectMapper, SupportCallRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Message in = exchange.getIn();
        JsonNode request = normalizeRequest(in.getBody());
        String destination = firstText(
            textAt(request, "destination"),
            textAt(request, "phoneNumber"),
            textAt(request, "phone"),
            textAt(request, "to")
        );
        if (destination == null || destination.isBlank()) {
            writeError(exchange, 400, "Missing destination phone number");
            return;
        }

        String conversationId = readConversationId(in);
        String callerId = property(exchange, "agent.runtime.telephony.twilio.from", "");
        String purpose = firstText(textAt(request, "query"), textAt(request, "reason"), textAt(request, "purpose"));
        String customerName = firstText(textAt(request, "customerName"), textAt(request, "customer"));
        JsonNode metadata = request.path("metadata");

        OutboundSipCallRequest outbound = new OutboundSipCallRequest(
            normalizePhone(destination),
            callerId,
            purpose,
            customerName,
            metadata == null || metadata.isMissingNode() ? null : metadata,
            property(exchange, "agent.runtime.telephony.openai.project-id", ""),
            conversationId
        );

        SipProviderClient client = createProviderClient(exchange);
        OutboundSipCallResult result = client.placeOutboundCall(outbound);
        SupportCallRegistry.PendingCall pending = registry.registerPending(normalizePhone(destination), conversationId, result);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("provider", result.providerName());
        response.put("requestId", pending.requestId());
        response.put("providerReference", nullToEmpty(result.providerReference()));
        response.put("status", result.status().name().toLowerCase(Locale.ROOT));
        response.put("conversationId", result.conversationId());
        response.put("destination", pending.destination());

        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
    }

    protected SipProviderClient createProviderClient(Exchange exchange) {
        return new TwilioSipProviderClient(
            new CamelDirectTwilioCallGateway(exchange.getContext().createProducerTemplate(), objectMapper)
        );
    }

    private JsonNode normalizeRequest(Object body) {
        if (body instanceof Map<?, ?> map) {
            return objectMapper.valueToTree(map);
        }
        if (body == null) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(String.valueOf(body));
        } catch (Exception ignored) {
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("query", String.valueOf(body));
            return fallback;
        }
    }

    private void writeError(Exchange exchange, int statusCode, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", message);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(error.toString());
    }

    private String readConversationId(Message in) {
        String conversationId = in.getHeader(AgentHeaders.CONVERSATION_ID, String.class);
        if (conversationId == null || conversationId.isBlank()) {
            return "support-call-" + UUID.randomUUID();
        }
        return conversationId.trim();
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

    private static String normalizePhone(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
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
                return value.trim();
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}