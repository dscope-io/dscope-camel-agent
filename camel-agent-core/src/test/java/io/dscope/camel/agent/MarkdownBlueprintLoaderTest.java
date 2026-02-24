package io.dscope.camel.agent;

import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MarkdownBlueprintLoaderTest {

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
        Assertions.assertEquals(3, blueprint.realtime().reconnectMaxSendRetries());
        Assertions.assertEquals(8, blueprint.realtime().reconnectMaxReconnects());
        Assertions.assertEquals(150L, blueprint.realtime().reconnectInitialBackoffMs());
        Assertions.assertEquals(2000L, blueprint.realtime().reconnectMaxBackoffMs());
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
}
