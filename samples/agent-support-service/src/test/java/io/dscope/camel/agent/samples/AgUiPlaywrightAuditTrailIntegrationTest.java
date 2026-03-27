package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;
import io.dscope.camel.agent.springai.SpringAiChatGateway;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgUiPlaywrightAuditTrailIntegrationTest {

    @Test
    void shouldMatchCopilotPageInputOutputWithAuditTrail() throws Exception {
        int port = randomPort();
        String previousPort = System.getProperty("agent.runtime.test-port");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));

        Main main = new Main();
        String queryToken = "pw-audit-" + System.currentTimeMillis();
        String prompt = "Please open a support ticket for repeated login failures " + queryToken;
        main.bind("springAiChatGateway", new DeterministicTicketGateway());
        main.bind("ticketLifecycleProcessor", new SupportTicketLifecycleProcessor(new ObjectMapper()));

        try {
            AgentRuntimeBootstrap.bootstrap(main, "ag-ui-playwright-audit-direct-blueprint-test.yaml");
            SampleAdminMcpBindings.bindIfMissing(main, "ag-ui-playwright-audit-direct-blueprint-test.yaml");
            main.start();

            String conversationId = "agui-http-it-" + System.currentTimeMillis();
            HttpResult uiPage = get(port, "/agui/ui");
            Assertions.assertEquals(200, uiPage.statusCode());
            Assertions.assertTrue(uiPage.body().contains("plan-name"), "UI should expose the plan selector");

            ObjectMapper mapper = new ObjectMapper();
            String requestBody = mapper.writeValueAsString(Map.of(
                "threadId", conversationId,
                "sessionId", conversationId,
                "messages", List.of(Map.of("role", "user", "content", prompt))
            ));
            HttpResult aguiResponse = post(port, "/agui/agent", requestBody);
            Assertions.assertEquals(200, aguiResponse.statusCode());
            Assertions.assertTrue(
                aguiResponse.body().contains("Support ticket created successfully"),
                "AGUI response should include deterministic ticket output"
            );

            String conversationAudit = waitForConversationHit(port, conversationId, queryToken);
            Assertions.assertTrue(
                containsAnyIgnoreCase(conversationAudit, conversationId, queryToken),
                "Audit conversation listing should include input/query token"
            );

            String auditJson = waitForAudit(port, conversationId, "Support ticket created successfully");
            Assertions.assertTrue(auditJson.contains(conversationId), "Audit trail should include same conversation id");
            Assertions.assertTrue(
                containsAnyIgnoreCase(auditJson, "Support ticket created successfully", "support.ticket.manage"),
                "Audit search should include assistant/tool output"
            );
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

    private static String waitForConversationHit(int port, String conversationId, String queryToken) throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        String lastBody = "";
        while (System.currentTimeMillis() < deadline) {
            HttpResult result = get(
                port,
                "/audit/conversations?q=" + encode(queryToken) + "&limit=50"
            );
            if (result.statusCode() == 200) {
                lastBody = result.body();
                if (containsAnyIgnoreCase(lastBody, conversationId, queryToken)) {
                    return lastBody;
                }
            }
            Thread.sleep(250L);
        }
        return lastBody;
    }

    private static String waitForAudit(int port, String conversationId, String expectedOutput) throws Exception {
        long deadline = System.currentTimeMillis() + 15000L;
        String lastBody = "";
        while (System.currentTimeMillis() < deadline) {
            HttpResult result = get(
                port,
                "/audit/search?conversationId=" + encode(conversationId) + "&limit=200"
            );
            if (result.statusCode() == 200) {
                lastBody = result.body();
                if (containsAnyIgnoreCase(lastBody, expectedOutput, "support.ticket.manage")) {
                    return lastBody;
                }
            }
            Thread.sleep(250L);
        }
        return lastBody;
    }

    private static boolean containsAnyIgnoreCase(String value, String... needles) {
        String lower = value == null ? "" : value.toLowerCase();
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && lower.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static HttpResult get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body() == null ? "" : response.body());
    }

    private static HttpResult post(int port, String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
            .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body() == null ? "" : response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static int randomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record HttpResult(int statusCode, String body) {
    }

    private static final class DeterministicTicketGateway implements SpringAiChatGateway {

        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public SpringAiChatResult generate(String systemPrompt,
                                           String userContext,
                                           List<io.dscope.camel.agent.model.ToolSpec> tools,
                                           String model,
                                           Double temperature,
                                           Integer maxTokens,
                                           java.util.function.Consumer<String> streamingTokenCallback) {
            String query = userContext == null ? "" : userContext;
            AiToolCall call = new AiToolCall("support.ticket.manage", mapper.createObjectNode().put("query", query));
            return new SpringAiChatResult("", List.of(call), true);
        }
    }
}
