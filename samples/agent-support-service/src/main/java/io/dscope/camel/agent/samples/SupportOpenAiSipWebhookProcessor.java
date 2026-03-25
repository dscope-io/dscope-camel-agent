package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.realtime.OpenAiRealtimeRelayClient;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallControlClient;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallControlRequestFactory;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallSessionRegistry;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeIncomingCallEvent;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeWebhookVerificationException;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeWebhookVerifier;
import io.dscope.camel.agent.telephony.CallLifecycleState;
import io.dscope.camel.agent.telephony.SipProviderMetadata;
import io.dscope.camel.agent.telephony.sip.SipHeader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

final class SupportOpenAiSipWebhookProcessor implements Processor {

    private final ObjectMapper objectMapper;
    private final SupportCallRegistry supportCallRegistry;
    private final OpenAiRealtimeWebhookVerifier webhookVerifier;
    private final OpenAiRealtimeCallControlClient callControlClient;
    private final OpenAiRealtimeCallControlRequestFactory requestFactory;
    private final OpenAiRealtimeCallSessionRegistry sessionRegistry;
    private final OpenAiRealtimeRelayClient relayClient;

    SupportOpenAiSipWebhookProcessor(
        ObjectMapper objectMapper,
        SupportCallRegistry supportCallRegistry,
        OpenAiRealtimeWebhookVerifier webhookVerifier,
        OpenAiRealtimeCallControlClient callControlClient,
        OpenAiRealtimeCallControlRequestFactory requestFactory,
        OpenAiRealtimeCallSessionRegistry sessionRegistry,
        OpenAiRealtimeRelayClient relayClient
    ) {
        this.objectMapper = objectMapper;
        this.supportCallRegistry = supportCallRegistry;
        this.webhookVerifier = webhookVerifier;
        this.callControlClient = callControlClient;
        this.requestFactory = requestFactory;
        this.sessionRegistry = sessionRegistry;
        this.relayClient = relayClient;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        if (webhookVerifier == null) {
            writeError(exchange, 500, "Missing OpenAI webhook verifier configuration");
            return;
        }
        if (callControlClient == null) {
            writeError(exchange, 500, "Missing OpenAI realtime call control client configuration");
            return;
        }

        String payload = exchange.getMessage().getBody(String.class);
        Map<String, String> headers = stringHeaders(exchange);
        try {
            webhookVerifier.verify(payload, headers);
        } catch (OpenAiRealtimeWebhookVerificationException e) {
            writeError(exchange, 400, e.getMessage());
            return;
        }

        JsonNode event = parseJson(payload);
        String eventType = textAt(event, "type");
        if (!"realtime.call.incoming".equals(eventType)) {
            ObjectNode ack = objectMapper.createObjectNode();
            ack.put("processed", false);
            ack.put("eventType", eventType == null ? "" : eventType);
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 202);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
            exchange.getMessage().setBody(objectMapper.writeValueAsString(ack));
            return;
        }

        OpenAiRealtimeIncomingCallEvent incoming = OpenAiRealtimeIncomingCallEvent.fromEvent(event);
        String destination = normalizeSipAddress(findHeader(incoming, "To"));
        String providerReference = firstText(
            normalizeSipAddress(findHeader(incoming, "X-Twilio-CallSid")),
            normalizeSipAddress(findHeader(incoming, "X-CallSid")),
            normalizeSipAddress(findHeader(incoming, "Call-ID"))
        );
        Optional<SupportCallRegistry.PendingCall> pending = supportCallRegistry.findPending(destination, providerReference);
        String conversationId = pending.map(SupportCallRegistry.PendingCall::conversationId)
            .orElseGet(() -> "sip:openai:" + incoming.callId());
        String requestId = pending.map(SupportCallRegistry.PendingCall::requestId).orElse(incoming.callId());
        SipProviderMetadata providerMetadata = pending.map(SupportCallRegistry.PendingCall::providerMetadata)
            .orElse(new SipProviderMetadata("unknown", null, objectMapper.valueToTree(incoming.rawData())));

        sessionRegistry.register(
            incoming.callId(),
            conversationId,
            requestId,
            providerMetadata,
            CallLifecycleState.INCOMING_WEBHOOK_RECEIVED
        );

        ObjectNode acceptPayload = requestFactory.acceptRequest(
            property(exchange, "agent.runtime.realtime.model", "gpt-realtime"),
            property(exchange, "agent.runtime.telephony.openai.instructions", "You are a support agent handling an outbound customer call."),
            property(exchange, "agent.runtime.realtime.voice", "alloy"),
            null,
            objectMapper.createObjectNode()
        );
        callControlClient.accept(incoming.callId(), acceptPayload);
        boolean monitorConnected = connectRealtimeMonitor(exchange, incoming.callId(), conversationId);
        sessionRegistry.updateState(incoming.callId(), monitorConnected ? CallLifecycleState.ACTIVE : CallLifecycleState.ACCEPTED, null);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("processed", true);
        response.put("action", "accepted");
        response.put("callId", incoming.callId());
        response.put("requestId", requestId);
        response.put("conversationId", conversationId);
        response.put("destination", destination == null ? "" : destination);
        response.put("providerReference", providerReference == null ? "" : providerReference);
        response.put("monitorConnected", monitorConnected);
        response.put("webhookId", headers.getOrDefault("webhook-id", ""));

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(response));
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

    private boolean connectRealtimeMonitor(Exchange exchange, String callId, String conversationId) {
        if (relayClient == null) {
            return false;
        }
        String apiKey = resolveOpenAiApiKey(exchange);
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        try {
            relayClient.connectToCall(conversationId, callId, apiKey);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, String> stringHeaders(Exchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getMessage().getHeaders().forEach((key, value) -> headers.put(key, value == null ? "" : String.valueOf(value)));
        return headers;
    }

    private String resolveOpenAiApiKey(Exchange exchange) {
        return firstText(
            property(exchange, "agent.runtime.spring-ai.openai.api-key", ""),
            property(exchange, "spring.ai.openai.api-key", ""),
            property(exchange, "openai.api.key", ""),
            System.getenv("OPENAI_API_KEY"),
            System.getProperty("openai.api.key")
        );
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

    private static String findHeader(OpenAiRealtimeIncomingCallEvent incoming, String name) {
        for (SipHeader header : incoming.sipHeaders()) {
            if (header.name() != null && header.name().equalsIgnoreCase(name)) {
                return header.value();
            }
        }
        return null;
    }

    private static String normalizeSipAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("sip:")) {
            normalized = normalized.substring(4);
        }
        int atIndex = normalized.indexOf('@');
        if (atIndex > 0) {
            normalized = normalized.substring(0, atIndex);
        }
        return normalized;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String textAt(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private void writeError(Exchange exchange, int statusCode, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", message);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(error.toString());
    }
}