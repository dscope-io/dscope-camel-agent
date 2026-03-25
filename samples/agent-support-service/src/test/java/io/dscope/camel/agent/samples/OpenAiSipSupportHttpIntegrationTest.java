package io.dscope.camel.agent.samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.dscope.camel.agent.realtime.OpenAiRealtimeRelayClient;
import io.dscope.camel.agent.realtime.openai.HttpOpenAiRealtimeCallControlClient;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallControlRequestFactory;
import io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallSessionRegistry;
import io.dscope.camel.agent.realtime.openai.StandardOpenAiRealtimeWebhookVerifier;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;
import io.dscope.camel.agent.telephony.CallLifecycleState;
import io.dscope.camel.agent.telephony.OutboundSipCallResult;
import io.dscope.camel.agent.telephony.SipProviderMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Test;

class OpenAiSipSupportHttpIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldAcceptIncomingWebhookAndStartCallMonitor() throws Exception {
        int port = randomPort();
        HttpServer acceptServer = HttpServer.create(new InetSocketAddress(0), 0);
        RecordingAcceptHandler acceptHandler = new RecordingAcceptHandler();
        acceptServer.createContext("/v1/realtime/calls", acceptHandler);
        acceptServer.start();

        String previousPort = System.getProperty("agent.runtime.test-port");
        String previousApiKey = System.getProperty("openai.api.key");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));
        System.setProperty("openai.api.key", "test-key");

        String secretValue = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        String webhookSecret = "whsec_" + secretValue;

        Main main = new Main();
        SupportCallRegistry callRegistry = new SupportCallRegistry();
        callRegistry.registerPending(
            "+15551230001",
            "conv-123",
            new OutboundSipCallResult(
                "twilio",
                "req-1",
                "tw-1",
                CallLifecycleState.REQUESTED,
                "conv-123",
                new SipProviderMetadata("twilio", "tw-1", null)
            )
        );
        OpenAiRealtimeCallSessionRegistry sessionRegistry = new OpenAiRealtimeCallSessionRegistry();
        RecordingRelayClient relayClient = new RecordingRelayClient();
        main.bind("supportCallRegistry", callRegistry);
        main.bind("openAiRealtimeCallSessionRegistry", sessionRegistry);
        main.bind("openAiRealtimeCallControlRequestFactory", new OpenAiRealtimeCallControlRequestFactory());
        main.bind("supportOpenAiSipWebhookProcessor", new SupportOpenAiSipWebhookProcessor(
            MAPPER,
            callRegistry,
            new StandardOpenAiRealtimeWebhookVerifier(webhookSecret),
            new HttpOpenAiRealtimeCallControlClient(HttpClient.newHttpClient(), MAPPER, "test-key",
                "http://localhost:" + acceptServer.getAddress().getPort() + "/v1/realtime/calls"),
            new OpenAiRealtimeCallControlRequestFactory(),
            sessionRegistry,
            relayClient
        ));

        try {
            AgentRuntimeBootstrap.bootstrap(main, "openai-sip-support-http-test.yaml");
            main.start();

            String payload = """
                {
                  "type": "realtime.call.incoming",
                  "data": {
                    "call_id": "call_123",
                    "sip_headers": [
                      {"name": "To", "value": "sip:+15551230001@sip.example.com"},
                      {"name": "X-Twilio-CallSid", "value": "tw-1"}
                    ]
                  }
                }
                """;
            String timestamp = Long.toString(java.time.Instant.now().getEpochSecond());
            String webhookId = "wh_123";
            String signature = sign(secretValue, webhookId + "." + timestamp + "." + payload);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/openai/realtime/sip/webhook"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("webhook-id", webhookId)
                .header("webhook-timestamp", timestamp)
                .header("webhook-signature", "v1," + signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode responseBody = MAPPER.readTree(response.body());

            assertEquals(200, response.statusCode());
            assertTrue(responseBody.path("processed").asBoolean());
            assertTrue(responseBody.path("monitorConnected").asBoolean());
            assertEquals("call_123", responseBody.path("callId").asText());
            assertEquals("conv-123", responseBody.path("conversationId").asText());
            assertEquals("/v1/realtime/calls/call_123/accept", acceptHandler.lastPath);
            assertEquals("Bearer test-key", acceptHandler.lastAuthorization);
            assertTrue(acceptHandler.lastBody.contains("gpt-realtime"));
            assertEquals("call_123", relayClient.lastCallId);
            assertEquals(CallLifecycleState.ACTIVE, sessionRegistry.find("call_123").orElseThrow().state());
        } finally {
            acceptServer.stop(0);
            safeStop(main);
            restoreProperty("agent.runtime.test-port", previousPort);
            restoreProperty("openai.api.key", previousApiKey);
        }
    }

    private static int randomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void safeStop(Main main) {
        try {
            main.stop();
        } catch (Exception ignored) {
        }
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    private static String sign(String base64Secret, String content) throws Exception {
        byte[] secret = Base64.getDecoder().decode(base64Secret);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class RecordingRelayClient extends OpenAiRealtimeRelayClient {
        private String lastCallId;

        @Override
        public void connectToCall(String conversationId, String callId, String apiKey) {
            this.lastCallId = callId;
        }
    }

    private static final class RecordingAcceptHandler implements HttpHandler {
        private String lastPath = "";
        private String lastAuthorization = "";
        private String lastBody = "";

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            lastPath = exchange.getRequestURI().getPath();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastBody = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            byte[] response = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private byte[] readAll(InputStream inputStream) throws IOException {
            return inputStream.readAllBytes();
        }
    }
}