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

public class RealtimeBrowserTokenProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeBrowserTokenProcessor.class);

    private static final String OPENAI_CLIENT_SECRETS_ENDPOINT = "https://api.openai.com/v1/realtime/client_secrets";
    private static final String DEFAULT_MODEL = "gpt-realtime-2.1";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RealtimeBrowserSessionRegistry sessionRegistry;
    private final String clientSecretsEndpoint;

    public RealtimeBrowserTokenProcessor(RealtimeBrowserSessionRegistry sessionRegistry) {
        this(sessionRegistry, HttpClient.newHttpClient(), OPENAI_CLIENT_SECRETS_ENDPOINT);
    }

    RealtimeBrowserTokenProcessor(RealtimeBrowserSessionRegistry sessionRegistry, HttpClient httpClient,
                                  String clientSecretsEndpoint) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.sessionRegistry = sessionRegistry;
        this.clientSecretsEndpoint = clientSecretsEndpoint;
    }

    RealtimeBrowserSessionRegistry sessionRegistry() {
        return sessionRegistry;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        String conversationId = firstNonBlank(
            exchange.getMessage().getHeader("conversationId", String.class),
            exchange.getMessage().getHeader("sessionId", String.class)
        );

        JsonNode requestBody = parseJson(exchange.getMessage().getBody(String.class));
        boolean requireInitSession = booleanProperty(exchange, true,
            "agent.runtime.realtime.require-init-session",
            "agent.runtime.realtime.requireInitSession");
        ObjectNode session = sessionRegistry == null ? null : sessionRegistry.getSession(conversationId);

        if (requireInitSession) {
            if (conversationId == null || conversationId.isBlank()) {
                writeError(exchange, 400, "Missing conversationId path parameter");
                return;
            }
            if (session == null) {
                writeError(exchange, 410, "Realtime browser session context missing or expired; call /init first");
                return;
            }
        }

        if (session != null && "live".equalsIgnoreCase(firstNonBlank(
            session.path("api").asText(""), session.path("type").asText("")))) {
            writeError(exchange, 409,
                "GPT-Live does not use Realtime client secrets; use /voice/session/{conversationId}/connect");
            return;
        }

        String apiKey = firstNonBlank(
            property(exchange, "agent.runtime.spring-ai.openai.api-key", ""),
            property(exchange, "spring.ai.openai.api-key", ""),
            property(exchange, "openai.api.key", ""),
            System.getenv("OPENAI_API_KEY")
        );

        if (apiKey.isBlank()) {
            writeError(exchange, 500, "Missing OpenAI API key for realtime browser token");
            return;
        }

        if (session == null) {
            session = sessionRegistry == null
                ? objectMapper.createObjectNode()
                : sessionRegistry.defaultSession(
                    firstNonBlank(
                        property(exchange, "agent.runtime.realtime.model", ""),
                        property(exchange, "agent.realtime.model", ""),
                        DEFAULT_MODEL
                    ),
                    firstNonBlank(
                        property(exchange, "agent.runtime.realtime.voice", ""),
                        property(exchange, "agent.realtime.voice", "")
                    ),
                    conversationId
                );
        }

        if (requestBody.path("session").isObject()) {
            mergeObject(session, requestBody.path("session"));
        }

        if (!session.hasNonNull("type")) {
            session.put("type", "realtime");
        }
        if (!session.hasNonNull("model")) {
            session.put("model", firstNonBlank(
                property(exchange, "agent.runtime.realtime.model", ""),
                property(exchange, "agent.realtime.model", ""),
                DEFAULT_MODEL
            ));
        }

        ObjectNode outboundSession = session.deepCopy();
        outboundSession.remove("metadata");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("session", outboundSession);
        String payloadJson = objectMapper.writeValueAsString(payload);
        LOGGER.debug("Realtime client secret payload: conversationId={}, body={}", conversationId, payloadJson);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(clientSecretsEndpoint))
            .timeout(Duration.ofMillis(longProperty(exchange, 15_000L,
                "agent.runtime.realtime.client-secret-timeout-ms", "agent.runtime.realtime.clientSecretTimeoutMs")))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String responseBody = response.body() == null || response.body().isBlank() ? "{}" : response.body();

        JsonNode parsed = parseJson(responseBody);
        ObjectNode outbound = parsed.isObject() ? (ObjectNode) parsed.deepCopy() : objectMapper.createObjectNode().put("raw", responseBody);
        if (conversationId != null && !conversationId.isBlank()) {
            outbound.put("conversationId", conversationId);
        }

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(outbound.toString());

        if (status >= 200 && status < 300) {
            LOGGER.info("Realtime browser token minted: conversationId={}, status={}", conversationId, status);
        } else {
            LOGGER.warn("Realtime browser token mint failed: conversationId={}, status={}, body={}", conversationId, status, responseBody);
        }
    }

    private void mergeObject(ObjectNode target, JsonNode source) {
        if (target == null || source == null || !source.isObject()) {
            return;
        }
        source.properties().forEach(entry -> {
            String field = entry.getKey();
            JsonNode incoming = entry.getValue();
            JsonNode existing = target.get(field);
            if (incoming != null && incoming.isObject() && existing != null && existing.isObject()) {
                mergeObject((ObjectNode) existing, incoming);
            } else {
                target.set(field, incoming.deepCopy());
            }
        });
    }

    private void writeError(Exchange exchange, int statusCode, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", message == null ? "unknown error" : message);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(error.toString());
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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

    private boolean booleanProperty(Exchange exchange, boolean defaultValue, String... keys) {
        for (String key : keys) {
            String value = property(exchange, key, "");
            if (value != null && !value.isBlank()) {
                return Boolean.parseBoolean(value.trim());
            }
        }
        return defaultValue;
    }

    private long longProperty(Exchange exchange, long defaultValue, String... keys) {
        for (String key : keys) {
            try {
                long value = Long.parseLong(property(exchange, key, "").trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
}
