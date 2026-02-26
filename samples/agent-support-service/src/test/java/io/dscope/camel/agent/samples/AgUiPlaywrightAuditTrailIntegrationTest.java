package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
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

        try {
            AgentRuntimeBootstrap.bootstrap(main, "ag-ui-playwright-audit-test.yaml");
            main.start();

            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                        .setHeadless(true)
                );
                try {
                    Page page = browser.newPage();
                    page.navigate("http://127.0.0.1:" + port + "/agui/ui");

                    String conversationId = String.valueOf(page.evaluate("() => String(sessionId || '')"));
                    Assertions.assertFalse(conversationId.isBlank(), "UI conversation/session id should be initialized");

                    page.locator("#prompt").fill(prompt);
                    page.locator("button[type='submit']").click();

                    page.locator(".msg.agent").last().waitFor();
                    String uiOutput = page.locator(".msg.agent").last().innerText();

                    Assertions.assertTrue(
                        uiOutput.contains("Support ticket created successfully"),
                        "UI should render deterministic ticket response"
                    );

                    String conversationAudit = waitForConversationHit(port, conversationId, queryToken);
                    Assertions.assertTrue(
                        containsAnyIgnoreCase(conversationAudit, conversationId, queryToken),
                        "Audit conversation listing should include input/query token"
                    );

                    String auditJson = waitForAudit(port, conversationId, "Support ticket created successfully");
                    Assertions.assertTrue(auditJson.contains(conversationId), "Audit trail should include same conversation id");
                    Assertions.assertTrue(
                        containsAnyIgnoreCase(auditJson, "Support ticket created successfully", "support.ticket.open"),
                        "Audit search should include assistant/tool output"
                    );
                } finally {
                    browser.close();
                }
            }
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
                if (containsAnyIgnoreCase(lastBody, expectedOutput, "support.ticket.open")) {
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
            AiToolCall call = new AiToolCall("support.ticket.open", mapper.createObjectNode().put("query", query));
            return new SpringAiChatResult("", List.of(call), true);
        }
    }
}
