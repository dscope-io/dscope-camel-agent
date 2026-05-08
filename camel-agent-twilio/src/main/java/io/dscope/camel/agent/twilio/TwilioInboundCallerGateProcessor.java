package io.dscope.camel.agent.twilio;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TwilioInboundCallerGateProcessor implements Processor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> allowedCallers;

    public TwilioInboundCallerGateProcessor(String allowedCallersCsv) {
        this.allowedCallers = parseAllowedCallers(allowedCallersCsv);
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        if (allowedCallers.isEmpty()) {
            return;
        }

        Map<String, Object> payload = readPayload(exchange);
        String caller = resolveCaller(payload);
        if (caller != null) {
            exchange.setProperty("twilio.inboundCaller", caller);
            exchange.getMessage().setHeader("twilio.inboundCaller", caller);
        }

        if (caller == null) {
            deny(exchange, "Inbound caller phone number is required");
            return;
        }

        if (!allowedCallers.contains(caller)) {
            deny(exchange, "Inbound caller is not allowed");
        }
    }

    private Map<String, Object> readPayload(Exchange exchange) throws Exception {
        Object body = exchange.getMessage().getBody();
        if (body instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) rawMap;
            return payload;
        }

        String json = exchange.getMessage().getBody(String.class);
        if (json == null || json.isBlank()) {
            json = "{}";
        }
        return objectMapper.readValue(json, MAP_TYPE);
    }

    private String resolveCaller(Map<String, Object> payload) {
        String directFrom = PhoneNumberSupport.normalizePhone(asString(payload.get("from")));
        if (directFrom != null) {
            return directFrom;
        }

        Object call = payload.get("call");
        if (call instanceof Map<?, ?> callMap) {
            return PhoneNumberSupport.normalizePhone(asString(callMap.get("from")));
        }

        return null;
    }

    private Set<String> parseAllowedCallers(String allowedCallersCsv) {
        Set<String> values = new LinkedHashSet<>();
        if (allowedCallersCsv == null || allowedCallersCsv.isBlank()) {
            return values;
        }

        Arrays.stream(allowedCallersCsv.split(","))
            .map(PhoneNumberSupport::normalizePhone)
            .filter(value -> value != null && !value.isBlank())
            .forEach(values::add);
        return values;
    }

    private void deny(Exchange exchange, String message) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 403);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody("{\"error\":\"" + escapeJson(message) + "\"}");
        exchange.setProperty("abortRoute", true);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}