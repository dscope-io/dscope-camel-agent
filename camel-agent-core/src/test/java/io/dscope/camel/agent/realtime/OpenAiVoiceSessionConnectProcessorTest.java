package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenAiVoiceSessionConnectProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldNegotiateRealtimeSdpThroughServer() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server("/realtime", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 201, "answer-sdp", "application/sdp");
        });
        RealtimeBrowserSessionRegistry registry = registry("r1", "realtime", "gpt-realtime-2.1");
        OpenAiVoiceSessionConnectProcessor processor = new OpenAiVoiceSessionConnectProcessor(
            registry, HttpClient.newHttpClient(), MAPPER,
            url(server, "/realtime"), url(server, "/live"));
        CamelContext context = context(Map.of("openai.api.key", "test-key", "agent.runtime.voice.api", "realtime"));
        try {
            Exchange exchange = request(context, "r1", "offer-sdp");
            processor.process(exchange);
            JsonNode response = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertEquals(201, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertEquals("application/sdp", contentType.get());
            Assertions.assertEquals("offer-sdp", body.get());
            Assertions.assertEquals("realtime", response.path("api").asText());
            Assertions.assertEquals("answer-sdp", response.path("transport").path("sdp").asText());
        } finally {
            context.stop();
            server.stop(0);
        }
    }

    @Test
    void shouldCreateLiveSessionWithClientDelegation() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = server("/live", exchange -> {
            request.set(MAPPER.readTree(exchange.getRequestBody()));
            respond(exchange, 201,
                "{\"session\":{\"id\":\"live_123\"},\"transport\":{\"type\":\"webrtc\",\"sdp\":\"live-answer\"}}",
                "application/json");
        });
        RealtimeBrowserSessionRegistry registry = registry("l1", "live", "gpt-live-1");
        registry.getSession("l1").put("instructions", "Speak briefly and delegate tasks.");
        OpenAiVoiceSessionConnectProcessor processor = new OpenAiVoiceSessionConnectProcessor(
            registry, HttpClient.newHttpClient(), MAPPER,
            url(server, "/realtime"), url(server, "/live"));
        CamelContext context = context(Map.of("openai.api.key", "test-key", "agent.runtime.voice.api", "live"));
        try {
            Exchange exchange = request(context, "l1", "offer-sdp");
            processor.process(exchange);
            JsonNode response = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertEquals(201, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertEquals("live", response.path("api").asText());
            Assertions.assertEquals("live_123", response.path("session").path("id").asText());
            Assertions.assertEquals("gpt-live-1", request.get().path("session").path("model").asText());
            Assertions.assertEquals("client", request.get().path("session").path("delegation").path("type").asText());
            Assertions.assertEquals("offer-sdp", request.get().path("transport").path("sdp").asText());
        } finally {
            context.stop();
            server.stop(0);
        }
    }

    @Test
    void shouldRejectMismatchedConfiguredModelBeforeCallingOpenAi() throws Exception {
        RealtimeBrowserSessionRegistry registry = registry("bad", "live", "gpt-realtime-2.1");
        OpenAiVoiceSessionConnectProcessor processor = new OpenAiVoiceSessionConnectProcessor(registry);
        CamelContext context = context(Map.of("openai.api.key", "test-key", "agent.runtime.voice.api", "live"));
        try {
            Exchange exchange = request(context, "bad", "offer-sdp");
            processor.process(exchange);
            Assertions.assertEquals(400, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertTrue(exchange.getMessage().getBody(String.class).contains("not compatible"));
        } finally {
            context.stop();
        }
    }

    private static RealtimeBrowserSessionRegistry registry(String id, String type, String model) {
        RealtimeBrowserSessionRegistry registry = new RealtimeBrowserSessionRegistry();
        ObjectNode session = MAPPER.createObjectNode().put("type", type).put("api", type).put("model", model);
        registry.putSession(id, session);
        return registry;
    }

    private static Exchange request(CamelContext context, String id, String sdp) throws Exception {
        Exchange exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader("conversationId", id);
        exchange.getMessage().setBody(MAPPER.createObjectNode().put("sdp", sdp).toString());
        return exchange;
    }

    private static CamelContext context(Map<String, String> values) throws Exception {
        DefaultCamelContext context = new DefaultCamelContext();
        Properties properties = new Properties();
        properties.putAll(values);
        context.getPropertiesComponent().setInitialProperties(properties);
        context.start();
        return context;
    }

    private static HttpServer server(String path, com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body,
                                String contentType) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
