package io.dscope.camel.agent.runtime;

import io.dscope.camel.agent.model.AgUiPreRunSpec;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.RealtimeSpec;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.Properties;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RuntimePlaceholderResolverTest {

    @Test
    void shouldResolveCamelAndSystemPlaceholdersInExecutionMetadata() throws Exception {
        String previousValue = System.getProperty("AGENT_SECRET_TOKEN");
        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            System.setProperty("AGENT_SECRET_TOKEN", "secret-123");
            Properties properties = new Properties();
            properties.setProperty("agent.test.host", "runtime.example.test");
            properties.setProperty("agent.test.route", "tokenized-route");
            properties.setProperty("agent.test.agent-uri", "agent:support?blueprint=classpath:agents/valid-agent.md");
            properties.setProperty("agent.test.realtime-endpoint", "wss://runtime.example.test/realtime");
            camelContext.getPropertiesComponent().setInitialProperties(properties);
            camelContext.start();

            AgentBlueprint blueprint = new AgentBlueprint(
                "TokenAgent",
                "0.1.0",
                "System instruction",
                List.of(new ToolSpec(
                    "secret.tool",
                    "Runtime token tool",
                    "{{agent.test.route}}",
                    "mcp:https://{{agent.test.host}}/mcp?token=${AGENT_SECRET_TOKEN}",
                    null,
                    null,
                    new ToolPolicy(false, 0, 1000)
                )),
                List.of(),
                List.of(),
                new RealtimeSpec("openai", "gpt", "alloy", "server-relay", "{{agent.test.realtime-endpoint}}", "pcm16", "pcm16", "metadata"),
                new AgUiPreRunSpec("{{agent.test.agent-uri}}", true, "kb.search", "ticket.manage", "https://{{agent.test.host}}/kb", "https://{{agent.test.host}}/ticket?token=${AGENT_SECRET_TOKEN}", List.of("ticket"), List.of("api key")),
                List.of()
            );

            AgentBlueprint resolved = RuntimePlaceholderResolver.resolveExecutionValues(camelContext, blueprint);

            Assertions.assertEquals("tokenized-route", resolved.tools().getFirst().routeId());
            Assertions.assertEquals("mcp:https://runtime.example.test/mcp?token=secret-123", resolved.tools().getFirst().endpointUri());
            Assertions.assertEquals("agent:support?blueprint=classpath:agents/valid-agent.md", resolved.aguiPreRun().agentEndpointUri());
            Assertions.assertEquals("https://runtime.example.test/kb", resolved.aguiPreRun().kbUri());
            Assertions.assertEquals("https://runtime.example.test/ticket?token=secret-123", resolved.aguiPreRun().ticketUri());
            Assertions.assertEquals("wss://runtime.example.test/realtime", resolved.realtime().endpointUri());
        } finally {
            restoreSystemProperty("AGENT_SECRET_TOKEN", previousValue);
        }
    }

    @Test
    void shouldKeepUnknownPlaceholdersWhenNoRuntimeValueExists() {
        String unresolved = RuntimePlaceholderResolver.resolveString(null, "mcp:https://${UNKNOWN_HOST}/mcp?token=${UNKNOWN_TOKEN}");
        Assertions.assertEquals("mcp:https://${UNKNOWN_HOST}/mcp?token=${UNKNOWN_TOKEN}", unresolved);
    }

    @Test
    void shouldFailFastWhenExecutionTargetPlaceholderRemainsUnresolved() {
        IllegalArgumentException error = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> RuntimePlaceholderResolver.resolveRequiredExecutionTarget(
                null,
                "agent:${UNKNOWN_AGENT_ENDPOINT}",
                "agent.session.endpointUri"
            )
        );

        Assertions.assertEquals(
            "Unresolved runtime placeholder in agent.session.endpointUri: agent:${UNKNOWN_AGENT_ENDPOINT}",
            error.getMessage()
        );
    }

    @Test
    void shouldIncludeBlueprintAndToolNameInBlueprintResolutionFailure() {
        AgentBlueprint blueprint = new AgentBlueprint(
            "SupportAssistant",
            "0.2.0",
            "System instruction",
            List.of(new ToolSpec(
                "support.mcp",
                "CRM MCP seed",
                null,
                "mcp:https://${UNKNOWN_CRM_HOST}/mcp",
                null,
                null,
                new ToolPolicy(false, 0, 1000)
            )),
            List.of(),
            List.of(),
            null,
            null,
            List.of()
        );

        IllegalArgumentException error = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> RuntimePlaceholderResolver.resolveExecutionValues(null, blueprint)
        );

        Assertions.assertEquals(
            "Unresolved runtime placeholder in tool 'support.mcp' in blueprint 'SupportAssistant' tools[].endpointUri: mcp:https://${UNKNOWN_CRM_HOST}/mcp",
            error.getMessage()
        );
    }

    private static void restoreSystemProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, previousValue);
    }
}