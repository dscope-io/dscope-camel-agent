package io.dscope.camel.agent.agui;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentAgUiPreRunTextProcessorTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldUseBlueprintDerivedTicketFallbackRoute() throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.setProperty("agent.blueprint", "classpath:agents/valid-agent-with-ticket-tools.md");
        initial.setProperty("agent.runtime.agui.pre-run.agent-endpoint-uri", "direct:agent-llm");
        context.getPropertiesComponent().setInitialProperties(initial);

        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:agent-llm").setBody(constant("OpenAI API key is missing"));
                from("direct:support-ticket-manage").setBody(constant("ticket-fallback-ok"));
                from("direct:kb-search").setBody(constant("kb-fallback-ok"));
            }
        });

        context.start();
        try {
            AgentAgUiPreRunTextProcessor processor = new AgentAgUiPreRunTextProcessor();
            var exchange = new DefaultExchange(context);
            Map<String, Object> params = new HashMap<>();
            params.put("text", "please open a support ticket for login issue");
            params.put("threadId", "thread-1");
            params.put("sessionId", "session-1");
            exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

            processor.process(exchange);

            Map<String, Object> out = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
            Assertions.assertNotNull(out);
            Assertions.assertEquals("ticket-fallback-ok", out.get("text"));
            Assertions.assertEquals("thread-1", out.get("threadId"));
            Assertions.assertEquals("session-1", out.get("sessionId"));
            Assertions.assertNotNull(out.get("runId"));
        } finally {
            context.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPreferBlueprintAgUiPreRunMetadataOverRuntimeProperties() throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.setProperty("agent.blueprint", "classpath:agents/valid-agent-with-agui-prerun.md");
        initial.setProperty("agent.runtime.agui.pre-run.agent-endpoint-uri", "direct:agent-llm-runtime");
        initial.setProperty("agent.runtime.agui.pre-run.fallback.ticket-keywords", "ticket,open,create");
        initial.setProperty("agent.runtime.agui.pre-run.fallback.ticket-tool-name", "support.ticket.manage");
        context.getPropertiesComponent().setInitialProperties(initial);

        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:agent-llm-blueprint").setBody(constant("OpenAI API key is missing"));
                from("direct:agent-llm-runtime").setBody(constant("runtime-path-should-not-be-used"));
                from("direct:ticket-custom").setBody(constant("ticket-custom-fallback-ok"));
                from("direct:kb-custom").setBody(constant("kb-custom-fallback-ok"));
                from("direct:support-ticket-manage").setBody(constant("runtime-ticket-fallback-should-not-be-used"));
            }
        });

        context.start();
        try {
            AgentAgUiPreRunTextProcessor processor = new AgentAgUiPreRunTextProcessor();
            var exchange = new DefaultExchange(context);
            Map<String, Object> params = new HashMap<>();
            params.put("text", "please escalate this issue");
            params.put("threadId", "thread-2");
            params.put("sessionId", "session-2");
            exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

            processor.process(exchange);

            Map<String, Object> out = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
            Assertions.assertNotNull(out);
            Assertions.assertEquals("ticket-custom-fallback-ok", out.get("text"));
            Assertions.assertEquals("thread-2", out.get("threadId"));
            Assertions.assertEquals("session-2", out.get("sessionId"));
            Assertions.assertNotNull(out.get("runId"));
        } finally {
            context.stop();
        }
    }
}
