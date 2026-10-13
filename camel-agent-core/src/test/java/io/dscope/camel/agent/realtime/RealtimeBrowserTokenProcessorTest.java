package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RealtimeBrowserTokenProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldRejectRealtimeTokenForLiveSession() throws Exception {
        RealtimeBrowserSessionRegistry registry = new RealtimeBrowserSessionRegistry();
        registry.putSession("live-token", MAPPER.createObjectNode()
            .put("api", "live")
            .put("type", "live")
            .put("model", "gpt-live-1"));
        RealtimeBrowserTokenProcessor processor = new RealtimeBrowserTokenProcessor(registry);
        CamelContext context = createContext(Map.of());
        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "live-token");
            exchange.getMessage().setBody("{}");
            processor.process(exchange);

            Assertions.assertEquals(409, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertTrue(exchange.getMessage().getBody(String.class).contains("does not use Realtime client secrets"));
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldMintGaClientSecretWithNestedSessionPayload() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/realtime/client_secrets", httpExchange -> {
            requestPath.set(httpExchange.getRequestURI().getPath());
            requestBody.set(new String(httpExchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"value\":\"ek_test\",\"expires_at\":1234}".getBytes(StandardCharsets.UTF_8);
            httpExchange.sendResponseHeaders(200, response.length);
            httpExchange.getResponseBody().write(response);
            httpExchange.close();
        });
        server.start();

        RealtimeBrowserSessionRegistry registry = new RealtimeBrowserSessionRegistry();
        ObjectNode session = MAPPER.createObjectNode();
        session.put("type", "realtime");
        session.put("model", "gpt-realtime-2.1");
        session.putObject("audio").putObject("output").put("voice", "marin");
        session.putObject("metadata").put("conversationId", "ga-secret");
        registry.putSession("ga-secret", session);
        RealtimeBrowserTokenProcessor processor = new RealtimeBrowserTokenProcessor(
            registry,
            HttpClient.newHttpClient(),
            "http://localhost:" + server.getAddress().getPort() + "/v1/realtime/client_secrets"
        );

        CamelContext context = createContext(Map.of("openai.api.key", "test-key"));
        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "ga-secret");
            exchange.getMessage().setBody("{}");

            processor.process(exchange);

            Assertions.assertEquals(200, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertEquals("/v1/realtime/client_secrets", requestPath.get());
            JsonNode payload = MAPPER.readTree(requestBody.get());
            Assertions.assertEquals("realtime", payload.path("session").path("type").asText());
            Assertions.assertEquals("gpt-realtime-2.1", payload.path("session").path("model").asText());
            Assertions.assertEquals("marin", payload.path("session").path("audio").path("output").path("voice").asText());
            Assertions.assertFalse(payload.path("session").has("metadata"));
            Assertions.assertEquals("ga-secret", registry.getSession("ga-secret").path("metadata").path("conversationId").asText());
        } finally {
            context.stop();
            server.stop(0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenTtlScenarios")
    void shouldValidateTokenTtlScenarios(String name,
                                         RealtimeBrowserSessionRegistry registry,
                                         String conversationId,
                                         String expectedErrorSnippet,
                                         Consumer<RealtimeBrowserSessionRegistry> setup) throws Exception {
        if (setup != null) {
            setup.accept(registry);
        }
        RealtimeBrowserTokenProcessor processor = new RealtimeBrowserTokenProcessor(registry);
        CamelContext context = createContext(Map.of());
        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", conversationId);
            exchange.getMessage().setBody("{}");

            processor.process(exchange);

            Assertions.assertEquals(410, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("error").asText().contains(expectedErrorSnippet));
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldAllowMissingInitWhenStrictModeDisabled() throws Exception {
        RealtimeBrowserSessionRegistry registry = new RealtimeBrowserSessionRegistry(50L);
        RealtimeBrowserTokenProcessor processor = new RealtimeBrowserTokenProcessor(registry);

        CamelContext context = createContext(Map.of("agent.runtime.realtime.require-init-session", "false"));
        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "strict-off");
            exchange.getMessage().setBody("{}");

            processor.process(exchange);

            Assertions.assertEquals(500, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("error").asText().contains("Missing OpenAI API key"));
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldAllowMissingInitWhenPreferCoreEnabledButStrictExplicitlyDisabled() throws Exception {
        RealtimeBrowserSessionRegistry registry = new RealtimeBrowserSessionRegistry(50L);
        RealtimeBrowserTokenProcessor processor = new RealtimeBrowserTokenProcessor(registry);

        CamelContext context = createContext(Map.of(
            "agent.runtime.realtime.prefer-core-token-processor", "true",
            "agent.runtime.realtime.require-init-session", "false"
        ));
        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "prefer-core-strict-explicit-off");
            exchange.getMessage().setBody("{}");

            processor.process(exchange);

            Assertions.assertEquals(500, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("error").asText().contains("Missing OpenAI API key"));
        } finally {
            context.stop();
        }
    }

    private CamelContext createContext(Map<String, String> properties) throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.putAll(properties);
        context.getPropertiesComponent().setInitialProperties(initial);
        context.start();
        return context;
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> tokenTtlScenarios() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(
                "missing init session",
                new RealtimeBrowserSessionRegistry(50L),
                "ttl-missing",
                "/init",
                null
            ),
            org.junit.jupiter.params.provider.Arguments.of(
                "expired init session",
                new RealtimeBrowserSessionRegistry(20L),
                "ttl-expired",
                "expired",
                (Consumer<RealtimeBrowserSessionRegistry>) registry -> {
                    ObjectNode seeded = MAPPER.createObjectNode();
                    seeded.put("type", "realtime");
                    seeded.put("model", "gpt-realtime");
                    registry.putSession("ttl-expired", seeded);
                    try {
                        Thread.sleep(40L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            )
        );
    }
}
