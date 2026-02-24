package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;
import io.dscope.camel.agent.realtime.RealtimeBrowserSessionRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RealtimeTokenHttpTtlIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenTtlScenarios")
    void shouldValidateTokenTtlScenarios(String name,
                                         String conversationId,
                                         boolean useMockedTokenProcessor) throws Exception {
        int port = randomPort();
        String previousPort = System.getProperty("agent.runtime.test-port");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));

        Main main = new Main();
        if (useMockedTokenProcessor) {
            main.bind("supportRealtimeTokenProcessor", new MockTokenProcessor());
        }
        try {
            AgentRuntimeBootstrap.bootstrap(main, "realtime-token-ttl-http-test.yaml");
            main.start();

            HttpResult init = postJson(
                port,
                "/realtime/session/" + conversationId + "/init",
                "{\"session\":{\"type\":\"realtime\",\"model\":\"gpt-realtime\"}}"
            );
            Assertions.assertEquals(200, init.statusCode());
            JsonNode initBody = MAPPER.readTree(init.body());
            Assertions.assertTrue(initBody.path("initialized").asBoolean());

            if (useMockedTokenProcessor) {
                HttpResult immediateToken = postJson(port, "/realtime/session/" + conversationId + "/token", "{}");
                Assertions.assertEquals(200, immediateToken.statusCode());
                JsonNode immediateBody = MAPPER.readTree(immediateToken.body());
                Assertions.assertTrue(immediateBody.path("mocked").asBoolean());
                Assertions.assertEquals(conversationId, immediateBody.path("conversationId").asText());
            }

            Thread.sleep(120L);

            HttpResult expiredToken = postJson(port, "/realtime/session/" + conversationId + "/token", "{}");
            Assertions.assertEquals(410, expiredToken.statusCode());
            JsonNode expiredBody = MAPPER.readTree(expiredToken.body());
            Assertions.assertTrue(expiredBody.path("error").asText().contains("/init"));
        } finally {
            try {
                main.stop();
            } catch (Exception ignored) {
            }
            if (previousPort == null) {
                System.clearProperty("agent.runtime.test-port");
            } else {
                System.setProperty("agent.runtime.test-port", previousPort);
            }
        }
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> tokenTtlScenarios() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(
                "real-token-processor expires context",
                "ttl-http-case",
                false
            ),
            org.junit.jupiter.params.provider.Arguments.of(
                "mock-token-processor works then expires",
                "ttl-http-success",
                true
            )
        );
    }

    @Test
    void shouldRequireInitWhenPreferCoreTokenProcessorEnabledWithoutExplicitRequireFlag() throws Exception {
        int port = randomPort();
        String previousPort = System.getProperty("agent.runtime.test-port");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));

        Main main = new Main();
        main.bind("supportRealtimeTokenProcessor", new MockTokenProcessor());
        try {
            AgentRuntimeBootstrap.bootstrap(main, "realtime-token-prefer-core-strict-test.yaml");
            main.start();

            HttpResult tokenWithoutInit = postJson(port, "/realtime/session/prefer-core-no-init/token", "{}");
            Assertions.assertEquals(410, tokenWithoutInit.statusCode());
            JsonNode tokenBody = MAPPER.readTree(tokenWithoutInit.body());
            Assertions.assertTrue(tokenBody.path("error").asText().contains("/init"));
            Assertions.assertFalse(tokenBody.path("mocked").asBoolean(false));
        } finally {
            try {
                main.stop();
            } catch (Exception ignored) {
            }
            if (previousPort == null) {
                System.clearProperty("agent.runtime.test-port");
            } else {
                System.setProperty("agent.runtime.test-port", previousPort);
            }
        }
    }

    private HttpResult postJson(int port, String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body() == null ? "" : response.body());
    }

    private int randomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record HttpResult(int statusCode, String body) {
    }

    private static final class MockTokenProcessor implements Processor {

        @Override
        public void process(Exchange exchange) throws Exception {
            String conversationId = exchange.getMessage().getHeader("conversationId", String.class);
            RealtimeBrowserSessionRegistry registry = exchange.getContext().getRegistry()
                .lookupByNameAndType("supportRealtimeSessionRegistry", RealtimeBrowserSessionRegistry.class);

            ObjectNode body = MAPPER.createObjectNode();
            if (registry == null || registry.getSession(conversationId) == null) {
                body.put("error", "Realtime browser session context missing or expired; call /init first");
                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 410);
                exchange.getMessage().setBody(body.toString());
                return;
            }

            body.put("mocked", true);
            body.put("conversationId", conversationId);
            body.put("value", "mock-token");
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setBody(body.toString());
        }
    }
}
