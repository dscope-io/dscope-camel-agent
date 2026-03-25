package io.dscope.camel.agent.samples;

import io.dscope.camel.agent.blueprint.MarkdownBlueprintLoader;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.runtime.RuntimePlaceholderResolver;
import java.util.Properties;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BlueprintRuntimePlaceholderFailureTest {

    @Test
    void shouldFailFastForUnresolvedTokenizedSampleExecutionTarget() throws Exception {
        String previousValue = System.getProperty("AGENT_SUPPORT_CRM_MCP_HOST");
        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            System.clearProperty("AGENT_SUPPORT_CRM_MCP_HOST");
            Properties properties = new Properties();
            properties.setProperty(
                "agent.runtime.support.crm-mcp-endpoint-uri",
                "mcp:https://${AGENT_SUPPORT_CRM_MCP_HOST}/mcp"
            );
            properties.setProperty(
                "agent.runtime.support.agui-agent-endpoint-uri",
                "agent:support?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}"
            );
            properties.setProperty("agent.runtime.a2a.public-base-url", "http://127.0.0.1:8080");
            properties.setProperty("agent.agents-config", "classpath:agents/agents.yaml");
            properties.setProperty("agent.blueprint", "classpath:agents/support/v2/agent.md");
            camelContext.getPropertiesComponent().setInitialProperties(properties);
            camelContext.start();

            AgentBlueprint blueprint = new MarkdownBlueprintLoader().load("classpath:agents/support/v2/agent.md");

            IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> RuntimePlaceholderResolver.resolveExecutionValues(camelContext, blueprint)
            );

            Assertions.assertEquals(
                "Unresolved runtime placeholder in tool 'support.mcp' in blueprint 'SupportAssistant' tools[].endpointUri: mcp:https://${AGENT_SUPPORT_CRM_MCP_HOST}/mcp",
                error.getMessage()
            );
        } finally {
            restoreSystemProperty("AGENT_SUPPORT_CRM_MCP_HOST", previousValue);
        }
    }

    @Test
    void shouldFailFastForUnresolvedTokenizedSampleAguiEndpoint() throws Exception {
        String previousValue = System.getProperty("AGENT_SUPPORT_AGUI_ENDPOINT");
        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            System.clearProperty("AGENT_SUPPORT_AGUI_ENDPOINT");
            Properties properties = new Properties();
            properties.setProperty(
                "agent.runtime.support.crm-mcp-endpoint-uri",
                "mcp:https://camel-crm-service-702748800338.europe-west1.run.app/mcp"
            );
            properties.setProperty(
                "agent.runtime.support.agui-agent-endpoint-uri",
                "agent:${AGENT_SUPPORT_AGUI_ENDPOINT}"
            );
            properties.setProperty("agent.runtime.a2a.public-base-url", "http://127.0.0.1:8080");
            properties.setProperty("agent.agents-config", "classpath:agents/agents.yaml");
            properties.setProperty("agent.blueprint", "classpath:agents/support/v2/agent.md");
            camelContext.getPropertiesComponent().setInitialProperties(properties);
            camelContext.start();

            AgentBlueprint blueprint = new MarkdownBlueprintLoader().load("classpath:agents/support/v2/agent.md");

            IllegalArgumentException error = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> RuntimePlaceholderResolver.resolveExecutionValues(camelContext, blueprint)
            );

            Assertions.assertEquals(
                "Unresolved runtime placeholder in blueprint 'SupportAssistant' aguiPreRun.agentEndpointUri: agent:${AGENT_SUPPORT_AGUI_ENDPOINT}",
                error.getMessage()
            );
        } finally {
            restoreSystemProperty("AGENT_SUPPORT_AGUI_ENDPOINT", previousValue);
        }
    }

    private static void restoreSystemProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, previousValue);
    }
}