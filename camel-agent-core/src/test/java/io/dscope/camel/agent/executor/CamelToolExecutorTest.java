package io.dscope.camel.agent.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.a2a.A2AToolContext;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.ExceptionAction;
import io.dscope.camel.agent.model.ExceptionCategory;
import io.dscope.camel.agent.model.ExceptionPolicySpec;
import io.dscope.camel.agent.model.RetryPolicySpec;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void shouldRetryToolExecutionForConfiguredTechnicalFailures() throws Exception {
        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            AtomicInteger attempts = new AtomicInteger();
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:retryable-tool")
                        .process(exchange -> {
                            int current = attempts.incrementAndGet();
                            if (current < 3) {
                                throw new RuntimeException("Upstream status 503");
                            }
                            exchange.getMessage().setBody("ok-after-retry");
                        });
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
                "retry.tool",
                "Retryable tool",
                "retryable-tool",
                null,
                null,
                null,
                new ToolPolicy(false, 0, 1000)
            );
            ExceptionPolicySpec retryPolicy = new ExceptionPolicySpec(
                "tool-tech-retry",
                "tool.execute",
                ExceptionCategory.TECHNICAL,
                List.of(503),
                ExceptionAction.RETRY,
                new RetryPolicySpec(2, 0L, false, null, null),
                null
            );

            ToolResult result = executor.execute(
                toolSpec,
                new ObjectMapper().readTree("{\"query\":\"x\"}"),
                new ExecutionContext("conv-1", "task-1", "trace-1", List.of(retryPolicy))
            );

            Assertions.assertEquals("ok-after-retry", result.content());
            Assertions.assertEquals(3, attempts.get());
        }
    }

    @Test
    void shouldRethrowBusinessFailuresWithoutRetryByDefault() throws Exception {
        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            AtomicInteger attempts = new AtomicInteger();
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:business-failure-tool")
                        .process(exchange -> {
                            attempts.incrementAndGet();
                            throw new RuntimeException("MCP conflict 409");
                        });
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
                "business.tool",
                "Business tool",
                "business-failure-tool",
                null,
                null,
                null,
                new ToolPolicy(false, 0, 1000)
            );

            RuntimeException failure = Assertions.assertThrows(RuntimeException.class, () -> executor.execute(
                toolSpec,
                new ObjectMapper().readTree("{\"query\":\"x\"}"),
                new ExecutionContext("conv-1", "task-1", "trace-1")
            ));

            Assertions.assertTrue(containsTokenInCauseChain(failure, "409"));
            Assertions.assertEquals(1, attempts.get());
        }
    }

    @Test
    void shouldTerminateWhenRetryPolicyIsExhaustedAndChainedTerminateMatches() throws Exception {
        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            AtomicInteger attempts = new AtomicInteger();
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:retry-then-terminate-tool")
                        .process(exchange -> {
                            attempts.incrementAndGet();
                            throw new RuntimeException("Upstream status 503");
                        });
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
                "retry.terminate.tool",
                "Retry then terminate tool",
                "retry-then-terminate-tool",
                null,
                null,
                null,
                new ToolPolicy(false, 0, 1000)
            );
            ExceptionPolicySpec retryPolicy = new ExceptionPolicySpec(
                "tool-tech-retry",
                "tool.execute",
                ExceptionCategory.TECHNICAL,
                List.of(503),
                ExceptionAction.RETRY,
                new RetryPolicySpec(1, 0L, false, null, null),
                null
            );
            ExceptionPolicySpec terminatePolicy = new ExceptionPolicySpec(
                "tool-tech-exhausted-terminate",
                "tool.execute",
                ExceptionCategory.TECHNICAL,
                List.of(503),
                ExceptionAction.TERMINATE,
                null,
                null
            );

            IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class, () -> executor.execute(
                toolSpec,
                new ObjectMapper().readTree("{\"query\":\"x\"}"),
                new ExecutionContext("conv-1", "task-1", "trace-1", List.of(retryPolicy, terminatePolicy))
            ));

            Assertions.assertTrue(containsTokenInCauseChain(failure, "503"));
            Assertions.assertEquals(2, attempts.get());
        }
    }

    @Test
    void shouldResolveToolExceptionWithLlmUsingPolicyPromptAndImplicitError() throws Exception {
        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            AtomicInteger toolAttempts = new AtomicInteger();
            AtomicInteger resolverCalls = new AtomicInteger();
            Properties properties = new Properties();
            properties.setProperty("agent.runtime.exception-policy.resolve.endpoint-uri", "direct:tool-error-resolver");
            camelContext.getPropertiesComponent().setInitialProperties(properties);
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:failing-resolve-tool")
                        .process(exchange -> {
                            toolAttempts.incrementAndGet();
                            throw new RuntimeException("MCP call failed with status 502 Bad Gateway");
                        });
                    from("direct:tool-error-resolver")
                        .process(exchange -> {
                            resolverCalls.incrementAndGet();
                            String prompt = exchange.getMessage().getBody(String.class);
                            Assertions.assertTrue(prompt.contains("Error:"));
                            Assertions.assertTrue(prompt.contains("502"));
                            Assertions.assertTrue(prompt.contains("Keep the response concise"));
                            exchange.getMessage().setBody("resolved-by-llm");
                        });
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
                "resolve.tool",
                "Resolve tool",
                "failing-resolve-tool",
                null,
                null,
                null,
                new ToolPolicy(false, 0, 1000)
            );
            ExceptionPolicySpec resolvePolicy = new ExceptionPolicySpec(
                "tool-tech-resolve",
                "tool.execute",
                ExceptionCategory.TECHNICAL,
                List.of(502),
                ExceptionAction.RESOLVE,
                null,
                "Keep the response concise"
            );

            ToolResult result = executor.execute(
                toolSpec,
                new ObjectMapper().readTree("{\"query\":\"x\"}"),
                new ExecutionContext("conv-1", "task-1", "trace-1", List.of(resolvePolicy))
            );

            Assertions.assertEquals("resolved-by-llm", result.content());
            Assertions.assertEquals(1, toolAttempts.get());
            Assertions.assertEquals(1, resolverCalls.get());
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