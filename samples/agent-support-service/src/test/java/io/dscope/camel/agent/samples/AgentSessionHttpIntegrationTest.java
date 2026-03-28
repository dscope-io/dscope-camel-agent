package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;
import io.dscope.camel.agent.model.ModelUsage;
import io.dscope.camel.agent.model.TokenUsage;
import io.dscope.camel.agent.springai.SpringAiChatGateway;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentSessionHttpIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldInvokeAgentThroughDirectRouteFromRestPost() throws Exception {
        int port = randomPort();
        String previousPort = System.getProperty("agent.runtime.test-port");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));

        Main main = new Main();
        String sessionId = "rest-agent-session-" + System.currentTimeMillis();
        main.bind("springAiChatGateway", new DeterministicTicketGateway());
        main.bind("ticketLifecycleProcessor", new SupportTicketLifecycleProcessor(MAPPER));
        main.bind("agUiPlanVersionSelector", new AgUiPlanVersionSelectorProcessor());

        try {
            AgentRuntimeBootstrap.bootstrap(main, "ag-ui-playwright-audit-direct-blueprint-test.yaml");
            SampleAdminMcpBindings.bindIfMissing(main, "ag-ui-playwright-audit-direct-blueprint-test.yaml");
            main.start();

            String requestBody = MAPPER.writeValueAsString(Map.of(
                "prompt", "Please open a support ticket for repeated login failures from the REST route",
                "sessionId", sessionId,
                "params", Map.of("channel", "rest")
            ));

            HttpResponse<String> response = post(port, "/sample/agent/session", requestBody);
            Assertions.assertEquals(200, response.statusCode(), response.body());

            JsonNode json = MAPPER.readTree(response.body());
            Assertions.assertFalse(json.path("conversationId").asText().isBlank());
            Assertions.assertFalse(json.path("sessionId").asText().isBlank());
            Assertions.assertEquals("Route session test response", json.path("message").asText());
            Assertions.assertTrue(
                containsEventType(json.path("events"), "user.message") && containsEventType(json.path("events"), "agent.message"),
                "Structured session response should include persisted turn events"
            );
            Assertions.assertEquals(1, json.path("modelUsage").path("turn").path("callCount").asInt());
            Assertions.assertEquals(24, json.path("modelUsage").path("turn").path("totals").path("totalTokens").asInt());
            Assertions.assertEquals("gpt-5.4", json.path("modelUsage").path("turn").path("byModel").get(0).path("model").asText());
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

    private static boolean containsEventType(JsonNode events, String type) {
        if (events == null || !events.isArray()) {
            return false;
        }
        for (JsonNode event : events) {
            if (type.equals(event.path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    private static HttpResponse<String> post(int port, String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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