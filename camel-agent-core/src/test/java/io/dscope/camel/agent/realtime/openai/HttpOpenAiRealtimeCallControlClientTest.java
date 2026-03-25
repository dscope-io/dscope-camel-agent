package io.dscope.camel.agent.realtime.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpOpenAiRealtimeCallControlClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
    private final AtomicReference<String> lastAuth = new AtomicReference<>("");
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastBeta = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/realtime/calls", new RecordingHandler());
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort() + "/v1/realtime/calls";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsAcceptPayload() throws Exception {
        HttpOpenAiRealtimeCallControlClient client = new HttpOpenAiRealtimeCallControlClient(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            "test-key",
            baseUrl
        );
        ObjectNode payload = new ObjectMapper().createObjectNode().put("type", "realtime").put("model", "gpt-realtime");

        client.accept("call_123", payload);

        assertEquals("/v1/realtime/calls/call_123/accept", lastPath.get());
        assertEquals("Bearer test-key", lastAuth.get());
        assertEquals("realtime=v1", lastBeta.get());
        assertEquals("{\"type\":\"realtime\",\"model\":\"gpt-realtime\"}", lastBody.get());
    }

    @Test
    void postsHangupWithoutBody() throws Exception {
        HttpOpenAiRealtimeCallControlClient client = new HttpOpenAiRealtimeCallControlClient(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            "test-key",
            baseUrl
        );

        client.hangup("call_456");

        assertEquals("/v1/realtime/calls/call_456/hangup", lastPath.get());
        assertEquals("", lastBody.get());
    }

    @Test
    void throwsOnNonSuccess() {
        HttpOpenAiRealtimeCallControlClient client = new HttpOpenAiRealtimeCallControlClient(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            "test-key",
            baseUrl + "/fail"
        );

        assertThrows(IllegalStateException.class, () -> client.hangup("call_789"));
    }

    private final class RecordingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBeta.set(exchange.getRequestHeaders().getFirst("OpenAI-Beta"));
            lastBody.set(read(exchange.getRequestBody()));

            int status = exchange.getRequestURI().getPath().contains("/fail/") ? 500 : 200;
            byte[] body = (status == 200 ? "{\"ok\":true}" : "{\"error\":\"bad\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private String read(InputStream inputStream) throws IOException {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}