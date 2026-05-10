package io.dscope.camel.agent.realtime.openai;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

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
        String sdp = stringValue(request.get("sdp"));
        if (sdp.isBlank()) {
            writeError(exchange, 400, "Missing browser SDP offer");
            return;
        }

        String model = resolveModel(request);
        URI endpointWithModel = endpointWithModel(callsEndpoint, model);

        HttpRequest openAiRequest = HttpRequest.newBuilder(endpointWithModel)
            .timeout(Duration.ofSeconds(45))
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/sdp")
            .header("Content-Type", "application/sdp")
            .POST(HttpRequest.BodyPublishers.ofString(sdp, StandardCharsets.UTF_8))
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

    private Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private String resolveModel(Map<String, Object> request) {
        String model = stringValue(request.get("model")).trim();
        if (!model.isBlank()) {
            return model;
        }
        Object sessionObject = request.get("session");
        if (sessionObject instanceof Map<?, ?> sessionMap) {
            model = stringValue(sessionMap.get("model")).trim();
            if (!model.isBlank()) {
                return model;
            }
        }
        return defaultModel;
    }

    private URI endpointWithModel(URI baseEndpoint, String model) {
        String endpoint = baseEndpoint.toString();
        if (endpoint.contains("model=")) {
            return baseEndpoint;
        }
        String separator = endpoint.contains("?") ? "&" : "?";
        String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
        return URI.create(endpoint + separator + "model=" + encodedModel);
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
