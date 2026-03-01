package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.runtime.AgentRuntimeBootstrap;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class McpAdminApiIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @Timeout(60)
    void shouldServeMcpAdminToolsAndExecuteAuditRoundtrip() throws Exception {
        int port = randomPort();
        String previousPort = System.getProperty("agent.runtime.test-port");
        System.setProperty("agent.runtime.test-port", Integer.toString(port));

        Main main = new Main();
        try {
            AgentRuntimeBootstrap.bootstrap(main, "ag-ui-playwright-audit-test.yaml");
            main.start();

            JsonNode initialize = mcpCall(port, "initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "clientInfo", Map.of("name", "mcp-admin-test", "version", "1.0.0")
            ));
            Assertions.assertEquals("2025-06-18", initialize.path("protocolVersion").asText());

            JsonNode toolsList = mcpCall(port, "tools/list", Map.of());
            Assertions.assertTrue(
                containsTool(toolsList.path("tools"), "audit.events.search"),
                "tools/list should include audit.events.search"
            );

            String conversationId = "mcp-it-" + System.currentTimeMillis();

            JsonNode appendResult = mcpToolCall(port, "audit.conversation.agentMessage", Map.of(
                "conversationId", conversationId,
                "message", "hello from mcp integration test"
            ));
            JsonNode appendStructured = appendResult.path("structuredContent");
            Assertions.assertTrue(appendStructured.path("accepted").asBoolean(false));
            Assertions.assertEquals(conversationId, appendStructured.path("conversationId").asText());

            JsonNode searchResult = mcpToolCall(port, "audit.events.search", Map.of(
                "conversationId", conversationId,
                "limit", 50
            ));
            JsonNode searchStructured = searchResult.path("structuredContent");
            Assertions.assertEquals(conversationId, searchStructured.path("conversationId").asText());
            Assertions.assertTrue(searchStructured.path("count").asInt(0) >= 1);

            JsonNode resources = mcpCall(port, "resources/list", Map.of());
            Assertions.assertTrue(resources.path("resources").isArray());
            Assertions.assertTrue(resources.path("resources").size() >= 1);
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

    private static JsonNode mcpToolCall(int port, String toolName, Map<String, Object> arguments) throws Exception {
        return mcpCall(port, "tools/call", Map.of(
            "name", toolName,
            "arguments", arguments == null ? Map.of() : arguments
        ));
    }

    private static JsonNode mcpCall(int port, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", "test-" + method + "-" + System.nanoTime(),
            "method", method,
            "params", params == null ? Map.of() : params
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/mcp/admin"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request)))
            .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), "MCP call should return HTTP 200");

        JsonNode envelope = MAPPER.readTree(response.body());
        Assertions.assertTrue(envelope.path("error").isMissingNode() || envelope.path("error").isNull(),
            "MCP envelope should not contain error: " + response.body());

        return envelope.path("result");
    }

    private static boolean containsTool(JsonNode tools, String expectedName) {
        if (tools == null || !tools.isArray()) {
            return false;
        }
        for (JsonNode tool : tools) {
            if (expectedName.equals(tool.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private static int randomPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
