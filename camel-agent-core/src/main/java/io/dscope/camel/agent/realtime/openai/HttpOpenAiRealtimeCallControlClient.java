package io.dscope.camel.agent.realtime.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

public final class HttpOpenAiRealtimeCallControlClient implements OpenAiRealtimeCallControlClient {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/realtime/calls";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;

    public HttpOpenAiRealtimeCallControlClient(String apiKey) {
        this(HttpClient.newHttpClient(), new ObjectMapper(), apiKey, DEFAULT_BASE_URL);
    }

    public HttpOpenAiRealtimeCallControlClient(HttpClient httpClient, ObjectMapper objectMapper, String apiKey, String baseUrl) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Missing OpenAI API key");
        }
        this.apiKey = apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.replaceAll("/+$", "");
    }

    @Override
    public ObjectNode accept(String callId, ObjectNode payload) throws Exception {
        return post(callId, "accept", payload == null ? objectMapper.createObjectNode() : payload);
    }

    @Override
    public ObjectNode reject(String callId, ObjectNode payload) throws Exception {
        return post(callId, "reject", payload == null ? objectMapper.createObjectNode() : payload);
    }

    @Override
    public ObjectNode hangup(String callId) throws Exception {
        return post(callId, "hangup", null);
    }

    @Override
    public ObjectNode refer(String callId, ObjectNode payload) throws Exception {
        return post(callId, "refer", payload == null ? objectMapper.createObjectNode() : payload);
    }

    private ObjectNode post(String callId, String action, ObjectNode payload) throws Exception {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("Missing OpenAI callId");
        }
        String endpoint = baseUrl + "/" + callId + "/" + action;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Authorization", "Bearer " + apiKey)
            .header("OpenAI-Beta", "realtime=v1");

        if (payload == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String body = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenAI realtime call control failed: status=" + response.statusCode()
                + ", action=" + action + ", body=" + (body == null ? "" : body));
        }
        return parseObject(body);
    }

    private ObjectNode parseObject(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(body);
            if (parsed instanceof ObjectNode objectNode) {
                return objectNode;
            }
        } catch (Exception ignored) {
        }
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("raw", body);
        return raw;
    }
}