package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Negotiates a browser WebRTC offer through either the Realtime or Live API. */
public class OpenAiVoiceSessionConnectProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiVoiceSessionConnectProcessor.class);
    private static final int MAX_SDP_CHARS = 65_536;
    private static final String REALTIME_ENDPOINT = "https://api.openai.com/v1/realtime/calls";
    private static final String LIVE_ENDPOINT = "https://api.openai.com/v1/live/sessions";

    private final RealtimeBrowserSessionRegistry sessionRegistry;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String realtimeEndpointOverride;
    private final String liveEndpointOverride;

    public OpenAiVoiceSessionConnectProcessor(RealtimeBrowserSessionRegistry sessionRegistry) {
        this(sessionRegistry, HttpClient.newHttpClient(), new ObjectMapper(), null, null);
    }

    OpenAiVoiceSessionConnectProcessor(RealtimeBrowserSessionRegistry sessionRegistry,
                                       HttpClient httpClient,
                                       ObjectMapper objectMapper,
                                       String realtimeEndpointOverride,
                                       String liveEndpointOverride) {
        this.sessionRegistry = sessionRegistry;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.realtimeEndpointOverride = realtimeEndpointOverride;
        this.liveEndpointOverride = liveEndpointOverride;
    }

    RealtimeBrowserSessionRegistry sessionRegistry() {
        return sessionRegistry;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String conversationId = firstNonBlank(
            exchange.getMessage().getHeader("conversationId", String.class),
            exchange.getMessage().getHeader("sessionId", String.class));
        if (conversationId.isBlank()) {
            writeError(exchange, 400, "Missing conversationId path parameter");
            return;
        }

        JsonNode requestBody;
        try {
            requestBody = objectMapper.readTree(exchange.getMessage().getBody(String.class));
        } catch (Exception invalidJson) {
            writeError(exchange, 400, "Invalid WebRTC connection request");
            return;
        }
        String sdp = text(requestBody, "sdp");
        if (sdp.isBlank()) {
            writeError(exchange, 400, "An SDP offer is required");
            return;
        }
        if (sdp.length() > MAX_SDP_CHARS) {
            writeError(exchange, 413, "SDP offer exceeds 65536 characters");
            return;
        }

        ObjectNode storedSession = sessionRegistry == null ? null : sessionRegistry.getSession(conversationId);
        if (storedSession == null) {
            writeError(exchange, 410, "Voice browser session context missing or expired; call /init first");
            return;
        }

        OpenAiVoiceApi api;
        String model;
        try {
            api = OpenAiVoiceApi.fromConfig(firstNonBlank(
                property(exchange, "agent.runtime.voice.api", ""),
                text(storedSession, "api"),
                text(storedSession, "type")));
            model = api.validateModel(firstNonBlank(
                text(storedSession, "model"),
                configuredModel(exchange, api),
                api.defaultModel()));
        } catch (IllegalArgumentException invalidConfiguration) {
            writeError(exchange, 400, invalidConfiguration.getMessage());
            return;
        }

        String apiKey = firstNonBlank(
            property(exchange, "agent.runtime.spring-ai.openai.api-key", ""),
            property(exchange, "spring.ai.openai.api-key", ""),
            property(exchange, "openai.api.key", ""),
            System.getenv("OPENAI_API_KEY"));
        if (apiKey.isBlank()) {
            writeError(exchange, 500, "Missing OpenAI API key for voice WebRTC connection");
            return;
        }

        Duration timeout = Duration.ofMillis(longProperty(exchange, 15_000L,
            "agent.runtime.voice.connect-timeout-ms", "agent.runtime.voice.connectTimeoutMs"));
        try {
            HttpResponse<String> upstream = api == OpenAiVoiceApi.LIVE
                ? createLiveSession(exchange, storedSession, sdp, model, apiKey, timeout)
                : createRealtimeSession(exchange, sdp, apiKey, timeout);
            writeUpstreamResponse(exchange, conversationId, api, model, upstream);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writeError(exchange, 502, "OpenAI voice session negotiation interrupted");
        } catch (Exception upstreamFailure) {
            LOGGER.warn("OpenAI voice session negotiation failed: conversationId={}, api={}, reason={}",
                conversationId, api.configValue(), upstreamFailure.getMessage());
            writeError(exchange, 502, "OpenAI voice session negotiation failed");
        }
    }

    private HttpResponse<String> createRealtimeSession(Exchange exchange, String sdp, String apiKey,
                                                       Duration timeout) throws Exception {
        String endpoint = firstNonBlank(realtimeEndpointOverride,
            property(exchange, "agent.runtime.realtime.webrtc-endpoint", ""), REALTIME_ENDPOINT);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/sdp")
            .POST(HttpRequest.BodyPublishers.ofString(sdp))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> createLiveSession(Exchange exchange, ObjectNode storedSession, String sdp,
                                                   String model, String apiKey, Duration timeout) throws Exception {
        String endpoint = firstNonBlank(liveEndpointOverride,
            property(exchange, "agent.runtime.live.endpoint-uri", ""),
            property(exchange, "agent.runtime.live.endpointUri", ""), LIVE_ENDPOINT);
        ObjectNode session = liveSession(storedSession, model);
        ObjectNode transport = objectMapper.createObjectNode().put("type", "webrtc").put("sdp", sdp);
        ObjectNode payload = objectMapper.createObjectNode().set("session", session);
        payload.set("transport", transport);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode liveSession(ObjectNode storedSession, String model) {
        ObjectNode session = objectMapper.createObjectNode();
        session.put("type", "live");
        session.put("model", model);
        String instructions = text(storedSession, "instructions");
        if (!instructions.isBlank()) {
            session.put("instructions", instructions);
        }
        JsonNode configuredDelegation = storedSession.path("delegation");
        if (configuredDelegation.isObject()) {
            session.set("delegation", configuredDelegation.deepCopy());
        } else {
            session.set("delegation", objectMapper.createObjectNode().put("type", "client"));
        }
        JsonNode audioOutput = storedSession.path("audio").path("output");
        if (audioOutput.isObject() && audioOutput.hasNonNull("voice")) {
            ObjectNode audio = objectMapper.createObjectNode();
            audio.set("output", objectMapper.createObjectNode().set("voice", audioOutput.path("voice").deepCopy()));
            session.set("audio", audio);
        }
        return session;
    }

    private void writeUpstreamResponse(Exchange exchange, String conversationId, OpenAiVoiceApi api,
                                       String model, HttpResponse<String> upstream) {
        int status = upstream.statusCode();
        String body = upstream.body() == null ? "" : upstream.body();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("conversationId", conversationId);
        response.put("api", api.configValue());
        response.put("model", model);
        if (api == OpenAiVoiceApi.REALTIME && status >= 200 && status < 300) {
            response.set("transport", objectMapper.createObjectNode().put("type", "webrtc").put("sdp", body));
        } else {
            try {
                JsonNode parsed = objectMapper.readTree(body);
                if (parsed.isObject()) {
                    response.setAll((ObjectNode) parsed);
                } else {
                    response.put("upstream", body);
                }
            } catch (Exception ignored) {
                response.put(status >= 200 && status < 300 ? "upstream" : "error", body);
            }
        }
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(response.toString());
        LOGGER.info("OpenAI voice WebRTC negotiation completed: conversationId={}, api={}, model={}, status={}",
            conversationId, api.configValue(), model, status);
    }

    private String configuredModel(Exchange exchange, OpenAiVoiceApi api) {
        return api == OpenAiVoiceApi.LIVE
            ? firstNonBlank(property(exchange, "agent.runtime.live.model", ""), api.defaultModel())
            : firstNonBlank(property(exchange, "agent.runtime.realtime.model", ""), api.defaultModel());
    }

    private void writeError(Exchange exchange, int status, String message) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(objectMapper.createObjectNode().put("error", message).toString());
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private String property(Exchange exchange, String key, String defaultValue) {
        try {
            String value = exchange.getContext().resolvePropertyPlaceholders("{{" + key + ":" + defaultValue + "}}");
            return value == null || value.isBlank() || value.contains("{{") ? defaultValue : value.trim();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private long longProperty(Exchange exchange, long defaultValue, String... keys) {
        for (String key : keys) {
            try {
                long value = Long.parseLong(property(exchange, key, ""));
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
