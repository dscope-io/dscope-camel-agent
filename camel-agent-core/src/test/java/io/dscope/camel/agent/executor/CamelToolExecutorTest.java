package io.dscope.camel.agent.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.a2a.A2AToolContext;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.Properties;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CamelToolExecutorTest {

    @Test
    void shouldResolveTokenizedEndpointUriAtExecutionTime() throws Exception {
        String previousToken = System.getProperty("AGENT_ROUTE_TOKEN");
        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            System.setProperty("AGENT_ROUTE_TOKEN", "resolved-route");
            Properties properties = new Properties();
            properties.setProperty("agent.test.route-prefix", "direct");
            camelContext.getPropertiesComponent().setInitialProperties(properties);
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:resolved-route")
                        .setBody(simple("${body[query]}"));
                }
            });
            camelContext.start();

            CamelToolExecutor executor = new CamelToolExecutor(
                camelContext,
                camelContext.createProducerTemplate(),
                new ObjectMapper(),
                null,
                A2AToolContext.EMPTY
            );
            ToolSpec toolSpec = new ToolSpec(
                "route.tool",
                "Tokenized route tool",
                null,
                "{{agent.test.route-prefix}}:${AGENT_ROUTE_TOKEN}",
                null,
                null,
                new ToolPolicy(false, 0, 1000)
            );

            ToolResult result = executor.execute(
                toolSpec,
                new ObjectMapper().readTree("""
                    {
                      "query": "resolved"
                    }
                    """),
                new ExecutionContext("conv-1", "task-1", "trace-1")
            );

            Assertions.assertEquals("resolved", result.content());
        } finally {
            if (previousToken == null) {
                System.clearProperty("AGENT_ROUTE_TOKEN");
            } else {
                System.setProperty("AGENT_ROUTE_TOKEN", previousToken);
            }
        }
    }
}