package io.dscope.camel.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import io.dscope.camel.agent.blueprint.BlueprintInstructionRenderer;
import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;

class MarkdownBlueprintLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldParseBlueprintFromClasspath() {
        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("classpath:agents/valid-agent.md");

        Assertions.assertEquals("SupportAssistant", blueprint.name());
        Assertions.assertEquals("0.1.0", blueprint.version());
        Assertions.assertEquals(1, blueprint.tools().size());
        Assertions.assertEquals("kb.search", blueprint.tools().getFirst().name());
        Assertions.assertTrue(blueprint.jsonRouteTemplates().isEmpty());
    }

    @Test
    void shouldParseJsonRouteTemplatesAndExposeAsTools() {
        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("classpath:agents/valid-agent-with-json-template.md");

        Assertions.assertEquals(1, blueprint.jsonRouteTemplates().size());
        Assertions.assertEquals("http.request", blueprint.jsonRouteTemplates().getFirst().id());
        Assertions.assertEquals("route.template.http.request", blueprint.jsonRouteTemplates().getFirst().toolName());
        Assertions.assertTrue(blueprint.tools().stream().anyMatch(t -> "route.template.http.request".equals(t.name())));
    }

    @Test
    void shouldParseRealtimeSection() {
        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("classpath:agents/valid-agent-with-realtime.md");

        Assertions.assertNotNull(blueprint.realtime());
        Assertions.assertEquals("openai", blueprint.realtime().provider());
        Assertions.assertEquals("gpt-4o-realtime-preview", blueprint.realtime().model());
        Assertions.assertEquals("alloy", blueprint.realtime().voice());
        Assertions.assertEquals("server-relay", blueprint.realtime().transport());
        Assertions.assertEquals("mcp:http://localhost:3001/mcp", blueprint.realtime().endpointUri());
        Assertions.assertEquals("pcm16", blueprint.realtime().inputAudioFormat());
        Assertions.assertEquals("pcm16", blueprint.realtime().outputAudioFormat());
        Assertions.assertEquals("metadata_transcript", blueprint.realtime().retentionPolicy());
        Assertions.assertEquals(Integer.valueOf(3), blueprint.realtime().reconnectMaxSendRetries());
        Assertions.assertEquals(Integer.valueOf(8), blueprint.realtime().reconnectMaxReconnects());
        Assertions.assertEquals(Long.valueOf(150L), blueprint.realtime().reconnectInitialBackoffMs());
        Assertions.assertEquals(Long.valueOf(2000L), blueprint.realtime().reconnectMaxBackoffMs());
    }

    @Test
    void shouldParseAgUiPreRunSection() {
        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("classpath:agents/valid-agent-with-agui-prerun.md");

        Assertions.assertNotNull(blueprint.aguiPreRun());
        Assertions.assertEquals("direct:agent-llm-blueprint", blueprint.aguiPreRun().agentEndpointUri());
        Assertions.assertEquals(Boolean.TRUE, blueprint.aguiPreRun().fallbackEnabled());
        Assertions.assertEquals("knowledge.lookup", blueprint.aguiPreRun().kbToolName());
        Assertions.assertEquals("case.open", blueprint.aguiPreRun().ticketToolName());
        Assertions.assertEquals(2, blueprint.aguiPreRun().ticketKeywords().size());
        Assertions.assertTrue(blueprint.aguiPreRun().ticketKeywords().contains("escalate"));
        Assertions.assertEquals(1, blueprint.aguiPreRun().fallbackErrorMarkers().size());
        Assertions.assertEquals("api key is missing", blueprint.aguiPreRun().fallbackErrorMarkers().getFirst());
    }

    @Test
    void shouldParseA2UiSection() {
        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("classpath:agents/valid-agent-with-realtime.md");

        Assertions.assertNotNull(blueprint.a2ui());
        Assertions.assertEquals(1, blueprint.a2ui().surfaces().size());
        Assertions.assertEquals("support-ticket-card-v2", blueprint.a2ui().surfaces().getFirst().name());
        Assertions.assertEquals("ticket-card", blueprint.a2ui().surfaces().getFirst().widgetTemplate());
        Assertions.assertEquals("classpath:agents/a2ui/support-v2.catalog.json", blueprint.a2ui().surfaces().getFirst().catalogResource());
        Assertions.assertEquals("classpath:agents/a2ui/support-v2.surface.json", blueprint.a2ui().surfaces().getFirst().surfaceResource());
        Assertions.assertEquals("classpath:agents/a2ui/locales/support-v2.fr.json", blueprint.a2ui().surfaces().getFirst().localeResources().get("fr-CA"));
    }

    @Test
    void shouldParseKameletToolAsEndpointUri() {
        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("classpath:agents/valid-agent-with-kamelet-tool.md");

        Assertions.assertEquals(1, blueprint.tools().size());
        var tool = blueprint.tools().getFirst();
        Assertions.assertEquals("customer.enrich", tool.name());
        Assertions.assertEquals("kamelet:jsonpath-action/sink?expression=%24.customer.id&allowSimple=false", tool.endpointUri());
        Assertions.assertNull(tool.routeId());
    }

    @Test
    void shouldParseMultiLineSystemInstructionWithBlankLineAfterHeading() {
        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("classpath:agents/valid-agent-with-system-block.md");

        Assertions.assertEquals(
            "You are a support agent focused on login and ticket workflows.\nKeep the conversation concise and action-oriented.",
            blueprint.systemInstruction()
        );
    }

    @Test
    void shouldParseAndResolveClasspathResources() {
        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("classpath:agents/valid-agent-with-resources.md");

        Assertions.assertEquals(2, blueprint.resources().size());
        Assertions.assertEquals("classpath-guide", blueprint.resources().getFirst().spec().name());
        Assertions.assertTrue(blueprint.resources().getFirst().text().contains("password reset"));
    }

    @Test
    void shouldRenderChatAndRealtimeInstructionsDifferently() {
        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("classpath:agents/valid-agent-with-resources.md");

        String chatInstruction = BlueprintInstructionRenderer.renderForChat(blueprint);
        String realtimeInstruction = BlueprintInstructionRenderer.renderForRealtime(blueprint, 20_000);

        Assertions.assertTrue(chatInstruction.contains("classpath-guide"));
        Assertions.assertFalse(chatInstruction.contains("realtime-only-note"));
        Assertions.assertTrue(realtimeInstruction.contains("classpath-guide"));
        Assertions.assertTrue(realtimeInstruction.contains("realtime-only-note"));
    }

    @Test
    void shouldResolveFileResource() throws Exception {
        Path resourceFile = tempDir.resolve("file-resource.md");
        Files.writeString(resourceFile, "# File Resource\n\nUse the manual review path.", StandardCharsets.UTF_8);
        Path blueprintFile = tempDir.resolve("agent-with-file-resource.md");
        Files.writeString(blueprintFile, """
            # Agent: FileResourceAssistant

            Version: 0.1.0

            ## System

            You are a file-resource-enabled assistant.

            ## Tools

            ```yaml
            tools:
              - name: kb.search
                description: Search local support articles
                routeId: kb-search
                inputSchemaInline:
                  type: object
                  required: [query]
                  properties:
                    query:
                      type: string
            ```

            ## Resources

            ```yaml
            resources:
              - name: file-guide
                uri: file:%s
                format: markdown
                includeIn: [chat]
            ```
            """.formatted(resourceFile.toString().replace("\\", "\\\\")), StandardCharsets.UTF_8);

        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("file:" + blueprintFile);

        Assertions.assertEquals(1, blueprint.resources().size());
        Assertions.assertTrue(blueprint.resources().getFirst().text().contains("manual review path"));
    }

    @Test
    void shouldResolveHttpResource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/resource.md", new StaticHandler("# Remote Resource\n\nEscalate after confirming identity."));
        server.start();
        try {
            Path blueprintFile = tempDir.resolve("agent-with-http-resource.md");
            Files.writeString(blueprintFile, """
                # Agent: HttpResourceAssistant

                Version: 0.1.0

                ## System

                You are an assistant with a remote resource.

                ## Tools

                ```yaml
                tools:
                  - name: kb.search
                    description: Search local support articles
                    routeId: kb-search
                    inputSchemaInline:
                      type: object
                      required: [query]
                      properties:
                        query:
                          type: string
                ```

                ## Resources

                ```yaml
                resources:
                  - name: remote-guide
                    uri: http://127.0.0.1:%d/resource.md
                    format: markdown
                    includeIn: [chat]
                ```
                """.formatted(server.getAddress().getPort()), StandardCharsets.UTF_8);

            MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
            var blueprint = loader.load("file:" + blueprintFile);

            Assertions.assertEquals(1, blueprint.resources().size());
            Assertions.assertTrue(blueprint.resources().getFirst().text().contains("confirming identity"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldResolvePdfResourceUsingCamelPdf() throws Exception {
        Path pdfFile = tempDir.resolve("resource.pdf");
        createPdf(pdfFile, "PDF extraction works for support playbooks.");
        Path blueprintFile = tempDir.resolve("agent-with-pdf-resource.md");
        Files.writeString(blueprintFile, """
            # Agent: PdfResourceAssistant

            Version: 0.1.0

            ## System

            You are an assistant with a PDF resource.

            ## Tools

            ```yaml
            tools:
              - name: kb.search
                description: Search local support articles
                routeId: kb-search
                inputSchemaInline:
                  type: object
                  required: [query]
                  properties:
                    query:
                      type: string
            ```

            ## Resources

            ```yaml
            resources:
              - name: pdf-guide
                uri: file:%s
                format: pdf
                includeIn: [chat]
                maxBytes: 65536
            ```
            """.formatted(pdfFile.toString().replace("\\", "\\\\")), StandardCharsets.UTF_8);

        MarkdownBlueprintLoader loader = new MarkdownBlueprintLoader();
        var blueprint = loader.load("file:" + blueprintFile);

        Assertions.assertEquals(1, blueprint.resources().size());
        Assertions.assertTrue(blueprint.resources().getFirst().text().contains("PDF extraction works"));
    }

    private void createPdf(Path pdfFile, String text) throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.start();
            byte[] pdf = context.createProducerTemplate().requestBody("pdf:create", text, byte[].class);
            Files.write(pdfFile, pdf);
        }
    }

    private static final class StaticHandler implements HttpHandler {
        private final byte[] body;

        private StaticHandler(String body) {
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/markdown");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        }
    }
}
