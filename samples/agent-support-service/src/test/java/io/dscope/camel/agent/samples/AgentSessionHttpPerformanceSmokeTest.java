package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.ModelUsage;
import io.dscope.camel.agent.model.TokenUsage;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;
import io.dscope.camel.agent.springai.SpringAiChatGateway;
import io.dscope.camel.agent.testing.TestArtifactSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentSessionHttpPerformanceSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldCaptureHttpLayerPerformanceReport() throws Exception {
        int port = randomPort();
        String previousPort = System.getProperty("agent.runtime.test-port");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));

        Main main = new Main();
        HttpClient client = HttpClient.newHttpClient();
        main.bind("springAiChatGateway", new DeterministicTicketGateway());
        main.bind("ticketLifecycleProcessor", new SupportTicketLifecycleProcessor(MAPPER));
        main.bind("agUiPlanVersionSelector", new AgUiPlanVersionSelectorProcessor());

        try {
            AgentRuntimeBootstrap.bootstrap(main, "ag-ui-playwright-audit-direct-blueprint-test.yaml");
            SampleAdminMcpBindings.bindIfMissing(main, "ag-ui-playwright-audit-direct-blueprint-test.yaml");
            main.start();

            for (int i = 0; i < 2; i++) {
                JsonNode warmup = invokeSession(client, port, "perf-http-warmup-" + i, "Warmup request " + i);
                Assertions.assertEquals("Route session test response", warmup.path("message").asText());
            }

            List<Long> samplesNanos = new ArrayList<>();
            JsonNode lastResponse = MAPPER.createObjectNode();
            for (int i = 0; i < 8; i++) {
                long started = System.nanoTime();
                JsonNode response = invokeSession(client, port, "perf-http-" + i, "Measure request " + i);
                samplesNanos.add(System.nanoTime() - started);
                lastResponse = response;
                Assertions.assertEquals("Route session test response", response.path("message").asText());
                Assertions.assertFalse(response.path("conversationId").asText().isBlank());
            }

            TestArtifactSupport.ArtifactBundle artifacts = TestArtifactSupport.bundle(getClass(), "agent-session-http");
            TestArtifactSupport.PerformanceSummary summary = TestArtifactSupport.summarize("http-endpoint", samplesNanos);
            artifacts.writeJson("performance-report.json", Map.of(
                "capturedAt", Instant.now().toString(),
                "layer", "http-endpoint",
                "scenario", "/sample/agent/session",
                "iterations", samplesNanos.size(),
                "samplesNanos", samplesNanos,
                "summary", summary
            ));
            artifacts.writeJson("last-response.json", lastResponse);
            artifacts.writeIndex("HTTP endpoint performance smoke test artifacts");

            Assertions.assertEquals(8, summary.iterations());
            Assertions.assertTrue(summary.minNanos() > 0L, "Measured durations must be positive");
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

    private static JsonNode invokeSession(HttpClient client, int port, String sessionId, String prompt) throws IOException, InterruptedException {
        String requestBody = MAPPER.writeValueAsString(Map.of(
            "prompt", prompt,
            "sessionId", sessionId,
            "params", Map.of("channel", "perf")
        ));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/sample/agent/session"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private static int randomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class DeterministicTicketGateway implements SpringAiChatGateway {

        @Override
        public SpringAiChatResult generate(String systemPrompt,
                                           String userContext,
                                           List<io.dscope.camel.agent.model.ToolSpec> tools,
                                           String model,
                                           Double temperature,
                                           Integer maxTokens,
                                           java.util.function.Consumer<String> streamingTokenCallback) {
            ModelUsage modelUsage = ModelUsage.of(
                "openai",
                model == null || model.isBlank() ? "gpt-5.4" : model,
                "chat",
                TokenUsage.of(15, 9, 24),
                new BigDecimal("0.00015"),
                new BigDecimal("0.00009"),
                new BigDecimal("0.00024")
            );
            return new SpringAiChatResult("Route session test response", List.of(), false, null, modelUsage);
        }
    }
}