package io.dscope.camel.agent.agui;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;

class AgentAgUiPreRunTextProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TICKET_JSON_RESPONSE =
        "{\"ticketId\":\"TCK-42\",\"status\":\"OPEN\",\"summary\":\"Need billing help\",\"assignedQueue\":\"BILLING\",\"message\":\"Ticket created\",\"action\":\"create\"}";

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

    @Test
    void shouldRequireExplicitFallbackRouteConfiguration() throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.setProperty("agent.runtime.agui.pre-run.agent-endpoint-uri", "direct:agent-llm");
        context.getPropertiesComponent().setInitialProperties(initial);

        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:agent-llm").setBody(constant("OpenAI API key is missing"));
            }
        });

        context.start();
        try {
            AgentAgUiPreRunTextProcessor processor = new AgentAgUiPreRunTextProcessor();
            var exchange = new DefaultExchange(context);
            Map<String, Object> params = new HashMap<>();
            params.put("text", "please open a support ticket for login issue");
            exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

            IllegalStateException error = Assertions.assertThrows(IllegalStateException.class, () -> processor.process(exchange));
            Assertions.assertEquals(
                "AGUI deterministic ticket fallback was selected, but no fallback route is configured. "
                    + "Set aguiPreRun.fallback.ticketUri, aguiPreRun.fallback.ticketToolName, or agent.runtime.agui.pre-run.fallback.ticket-uri.",
                error.getMessage()
            );
        } finally {
            context.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAttachWidgetAndA2UiPayloadForTicketJsonResponses() throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.setProperty("agent.blueprint", "classpath:agents/valid-agent.md");
        initial.setProperty("agent.agents-config", "classpath:runtime/test-agents.yaml");
        initial.setProperty("agent.runtime.agui.pre-run.agent-endpoint-uri", "direct:agent-llm-ticket-json");
        context.getRegistry().bind("agentPlanSelectionResolver", new AgentPlanSelectionResolver(new InMemoryPersistenceFacade(), MAPPER));
        context.getPropertiesComponent().setInitialProperties(initial);

        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:agent-llm-ticket-json")
                    .setBody(constant(TICKET_JSON_RESPONSE));
            }
        });

        context.start();
        try {
            AgentAgUiPreRunTextProcessor processor = new AgentAgUiPreRunTextProcessor();
            var exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("Accept-Language", "fr-CA,fr;q=0.8");
            Map<String, Object> params = new HashMap<>();
            params.put("text", "please open a billing ticket");
            params.put("threadId", "thread-a2ui");
            params.put("sessionId", "session-a2ui");
            params.put("planName", "support");
            params.put("planVersion", "v2");
            params.put("a2uiSupportedCatalogIds", java.util.List.of("urn:io.dscope.test:a2ui:support-ticket-card:v2"));
            exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

            processor.process(exchange);

            Map<String, Object> out = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
            Assertions.assertNotNull(out);
            Assertions.assertEquals("fr-CA", out.get("locale"));
            Assertions.assertEquals("support", out.get("planName"));
            Assertions.assertEquals("v2", out.get("planVersion"));
            Assertions.assertEquals(TICKET_JSON_RESPONSE, out.get("text"));

            Map<String, Object> widget = (Map<String, Object>) out.get("widget");
            Assertions.assertNotNull(widget);
            Assertions.assertEquals("ticket-card", widget.get("template"));
            Map<String, Object> widgetData = (Map<String, Object>) widget.get("data");
            Assertions.assertEquals("TCK-42", widgetData.get("ticketId"));
            Assertions.assertEquals("BILLING", widgetData.get("assignedQueue"));

            Map<String, Object> a2ui = (Map<String, Object>) out.get("a2ui");
            Assertions.assertNotNull(a2ui);
            Assertions.assertEquals("fr-CA", a2ui.get("locale"));
            Assertions.assertEquals("urn:io.dscope.test:a2ui:support-ticket-card:v2", a2ui.get("catalogId"));
        } finally {
            context.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRepairTruncatedNestedJsonAndPreserveOriginalText() throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.setProperty("agent.blueprint", "classpath:agents/valid-agent.md");
        initial.setProperty("agent.agents-config", "classpath:runtime/test-agents.yaml");
        initial.setProperty("agent.runtime.agui.pre-run.agent-endpoint-uri", "direct:agent-llm-truncated-ticket-json");
        context.getRegistry().bind("agentPlanSelectionResolver", new AgentPlanSelectionResolver(new InMemoryPersistenceFacade(), MAPPER));
        context.getPropertiesComponent().setInitialProperties(initial);

        String truncated = "{\"ticketId\":\"TCK-99\",\"status\":\"OPEN\",\"summary\":\"Nested repair\",\"items\":[{\"id\":1";
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:agent-llm-truncated-ticket-json")
                    .setBody(constant(truncated));
            }
        });

        context.start();
        try {
            AgentAgUiPreRunTextProcessor processor = new AgentAgUiPreRunTextProcessor();
            var exchange = new DefaultExchange(context);
            Map<String, Object> params = new HashMap<>();
            params.put("text", "please open a support ticket");
            params.put("threadId", "thread-truncated-a2ui");
            params.put("sessionId", "session-truncated-a2ui");
            params.put("planName", "support");
            params.put("planVersion", "v1");
            params.put("a2uiSupportedCatalogIds", java.util.List.of("urn:io.dscope.test:a2ui:support-ticket-card:v1"));
            exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

            processor.process(exchange);

            Map<String, Object> out = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
            Assertions.assertNotNull(out);
            Assertions.assertEquals(truncated, out.get("text"));

            Map<String, Object> widget = (Map<String, Object>) out.get("widget");
            Assertions.assertNotNull(widget);
            Map<String, Object> widgetData = (Map<String, Object>) widget.get("data");
            Assertions.assertEquals("TCK-99", widgetData.get("ticketId"));

            Map<String, Object> a2ui = (Map<String, Object>) out.get("a2ui");
            Assertions.assertNotNull(a2ui);
            Assertions.assertEquals("support-ticket-tck-99", a2ui.get("surfaceId"));
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldNotFallbackWhenPrimaryFailureIsToolLevelConflict409() throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.setProperty("agent.runtime.agui.pre-run.agent-endpoint-uri", "direct:agent-llm");
        context.getPropertiesComponent().setInitialProperties(initial);

        AtomicInteger fallbackCalls = new AtomicInteger();
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:agent-llm")
                    .process(exchange -> {
                        throw new RuntimeException("MCP tool call failed with status 409 Conflict");
                    });
                from("direct:support-ticket-manage")
                    .process(exchange -> fallbackCalls.incrementAndGet())
                    .setBody(constant("ticket-fallback-ok"));
                from("direct:kb-search")
                    .setBody(constant("kb-fallback-ok"));
            }
        });

        context.start();
        try {
            AgentAgUiPreRunTextProcessor processor = new AgentAgUiPreRunTextProcessor();
            var exchange = new DefaultExchange(context);
            Map<String, Object> params = new HashMap<>();
            params.put("text", "please open a support ticket for login issue");
            exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

            RuntimeException error = Assertions.assertThrows(RuntimeException.class, () -> processor.process(exchange));
            Assertions.assertTrue(containsTokenInCauseChain(error, "409"));
            Assertions.assertEquals(0, fallbackCalls.get(), "Fallback route should not execute for tool-level 409 conflicts");
        } finally {
            context.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRetryPrimaryAgentForConfiguredTechnicalFailures() throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.setProperty("agent.blueprint", "classpath:agents/valid-agent-with-agui-prerun.md");
        context.getPropertiesComponent().setInitialProperties(initial);

        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:agent-llm-blueprint")
                    .process(exchange -> {
                        int call = primaryCalls.incrementAndGet();
                        if (call < 3) {
                            throw new RuntimeException("Upstream failure: 503 Service Unavailable");
                        }
                        exchange.getMessage().setBody("primary-success-after-retry");
                    });
                from("direct:ticket-custom")
                    .process(exchange -> fallbackCalls.incrementAndGet())
                    .setBody(constant("ticket-custom-fallback-ok"));
                from("direct:kb-custom")
                    .process(exchange -> fallbackCalls.incrementAndGet())
                    .setBody(constant("kb-custom-fallback-ok"));
            }
        });

        context.start();
        try {
            AgentAgUiPreRunTextProcessor processor = new AgentAgUiPreRunTextProcessor();
            var exchange = new DefaultExchange(context);
            Map<String, Object> params = new HashMap<>();
            params.put("text", "check order status");
            params.put("threadId", "thread-retry");
            params.put("sessionId", "session-retry");
            exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

            processor.process(exchange);

            Map<String, Object> out = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
            Assertions.assertNotNull(out);
            Assertions.assertEquals("primary-success-after-retry", out.get("text"));
            Assertions.assertEquals(3, primaryCalls.get());
            Assertions.assertEquals(0, fallbackCalls.get());
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldTerminateWhenAgUiRetryPolicyIsExhaustedAndChainedTerminateMatches() throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.setProperty("agent.blueprint", "classpath:agents/valid-agent-with-agui-prerun.md");
        context.getPropertiesComponent().setInitialProperties(initial);

        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:agent-llm-blueprint")
                    .process(exchange -> {
                        primaryCalls.incrementAndGet();
                        throw new RuntimeException("Upstream failure: 503 Service Unavailable");
                    });
                from("direct:ticket-custom")
                    .process(exchange -> fallbackCalls.incrementAndGet())
                    .setBody(constant("ticket-custom-fallback-ok"));
                from("direct:kb-custom")
                    .process(exchange -> fallbackCalls.incrementAndGet())
                    .setBody(constant("kb-custom-fallback-ok"));
            }
        });

        context.start();
        try {
            AgentAgUiPreRunTextProcessor processor = new AgentAgUiPreRunTextProcessor();
            var exchange = new DefaultExchange(context);
            Map<String, Object> params = new HashMap<>();
            params.put("text", "check order status");
            params.put("threadId", "thread-term");
            params.put("sessionId", "session-term");
            exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

            IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class, () -> processor.process(exchange));
            Assertions.assertTrue(containsTokenInCauseChain(failure, "503"));
            Assertions.assertEquals(3, primaryCalls.get());
            Assertions.assertEquals(0, fallbackCalls.get());
        } finally {
            context.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldResolveAgUiExceptionWithLlmUsingPolicyPromptAndImplicitError() throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.setProperty("agent.blueprint", "classpath:agents/valid-agent-with-agui-prerun-resolve.md");
        context.getPropertiesComponent().setInitialProperties(initial);

        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:agent-llm-blueprint-resolve")
                    .process(exchange -> {
                        String body = exchange.getMessage().getBody(String.class);
                        int calls = primaryCalls.incrementAndGet();
                        if (body != null && body.contains("Additional instructions:")) {
                            Assertions.assertTrue(body.contains("Error:"));
                            Assertions.assertTrue(body.contains("409"));
                            exchange.getMessage().setBody("resolved-by-agui-llm");
                            return;
                        }
                        if (calls == 1) {
                            throw new RuntimeException("MCP conflict 409");
                        }
                        exchange.getMessage().setBody("unexpected");
                    });
                from("direct:kb-custom")
                    .process(exchange -> fallbackCalls.incrementAndGet())
                    .setBody(constant("kb-custom-fallback-ok"));
            }
        });

        context.start();
        try {
            AgentAgUiPreRunTextProcessor processor = new AgentAgUiPreRunTextProcessor();
            var exchange = new DefaultExchange(context);
            Map<String, Object> params = new HashMap<>();
            params.put("text", "please help with a blocked action");
            params.put("threadId", "thread-resolve");
            params.put("sessionId", "session-resolve");
            exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);

            processor.process(exchange);

            Map<String, Object> out = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
            Assertions.assertNotNull(out);
            Assertions.assertEquals("resolved-by-agui-llm", out.get("text"));
            Assertions.assertEquals(2, primaryCalls.get());
            Assertions.assertEquals(0, fallbackCalls.get());
        } finally {
            context.stop();
        }
    }

    private static boolean containsTokenInCauseChain(Throwable failure, String token) {
        if (failure == null || token == null || token.isBlank()) {
            return false;
        }
        String normalized = token.toLowerCase();
        Throwable cursor = failure;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.toLowerCase().contains(normalized)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
