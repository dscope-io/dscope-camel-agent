package io.dscope.camel.agent.realtime.openai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class OpenAiRealtimeCallProcessor implements Processor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI callsEndpoint;
    private final String apiKey;
    private final String defaultModel;

    public OpenAiRealtimeCallProcessor(ObjectMapper objectMapper, String callsEndpoint, String apiKey, String defaultModel) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
        this.callsEndpoint = URI.create(callsEndpoint == null || callsEndpoint.isBlank()
            ? "https://api.openai.com/v1/realtime/calls"
            : callsEndpoint.trim());
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? "gpt-realtime-2" : defaultModel.trim();
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        if (apiKey.isBlank()) {
            writeError(exchange, 500, "OPENAI_API_KEY is not configured");
            return;
        }

        Map<String, Object> request = readRequest(exchange);
        String sdp = stringValue(request.get("sdp")).trim();
        if (sdp.isBlank()) {
            writeError(exchange, 400, "Missing browser SDP offer");
            return;
        }

        Map<String, Object> session = copyMap(request.get("session"));
        session.putIfAbsent("type", "realtime");
        session.putIfAbsent("model", defaultModel);

        String boundary = "----camel-agent-realtime-" + UUID.randomUUID();
        byte[] body = multipartBody(boundary, sdp, objectMapper.writeValueAsString(session));

        HttpRequest openAiRequest = HttpRequest.newBuilder(callsEndpoint)
            .timeout(Duration.ofSeconds(45))
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/sdp")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(openAiRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            writeError(exchange, 502, "Realtime call negotiation failed: " + exception.getMessage());
            return;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            writeError(exchange, response.statusCode(), response.body());
            return;
        }

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/sdp; charset=UTF-8");
        exchange.getMessage().setBody(response.body());
    }

    private Map<String, Object> readRequest(Exchange exchange) throws IOException {
        Object body = exchange.getMessage().getBody();
        if (body instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        String text = exchange.getMessage().getBody(String.class);
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(text, MAP_TYPE);
    }

    private Map<String, Object> copyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private byte[] multipartBody(String boundary, String sdp, String sessionJson) {
        String lineBreak = "\r\n";
        String body = "--" + boundary + lineBreak
            + "Content-Disposition: form-data; name=\"sdp\"" + lineBreak
            + "Content-Type: application/sdp" + lineBreak
            + lineBreak
            + sdp + lineBreak
            + "--" + boundary + lineBreak
            + "Content-Disposition: form-data; name=\"session\"" + lineBreak
            + "Content-Type: application/json" + lineBreak
            + lineBreak
            + sessionJson + lineBreak
            + "--" + boundary + "--" + lineBreak;
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private void writeError(Exchange exchange, int statusCode, String message) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=UTF-8");
        try {
            exchange.getMessage().setBody(objectMapper.writeValueAsString(Map.of(
                "error", message == null || message.isBlank() ? "Realtime call negotiation failed" : message)));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write realtime error", exception);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}