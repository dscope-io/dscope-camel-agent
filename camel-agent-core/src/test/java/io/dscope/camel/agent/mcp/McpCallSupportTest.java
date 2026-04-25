package io.dscope.camel.agent.mcp;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class McpCallSupportTest {

    @Test
    void retriesRetriable404AndEventuallySucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        Map<String, Object> result = McpCallSupport.invoke(
            LoggerFactory.getLogger(McpCallSupportTest.class),
            null,
            "tool invoke",
            "mcp:https://calendar.example/mcp",
            "calendar.listAvailability",
            "conv-1",
            "task-1",
            Map.of("name", "calendar.listAvailability"),
            () -> {
                if (attempts.getAndIncrement() < 2) {
                    throw new RuntimeException(new HttpOperationFailedException(
                        "https://calendar.example/mcp",
                        404,
                        "Not Found",
                        null,
                        Map.of("Content-Type", "application/json"),
                        "{\"error\":\"temporary\"}"
                    ));
                }
                return Map.of("status", "ok");
            }
        );

        Assertions.assertEquals(3, attempts.get());
        Assertions.assertEquals("ok", result.get("status"));
    }

    @Test
    void doesNotRetryNonRetriable400() {
        AtomicInteger attempts = new AtomicInteger();

        RuntimeException thrown = Assertions.assertThrows(RuntimeException.class, () -> McpCallSupport.invoke(
            LoggerFactory.getLogger(McpCallSupportTest.class),
            null,
            "tool invoke",
            "mcp:https://calendar.example/mcp",
            "calendar.deleteAppointment",
            "conv-1",
            "task-1",
            Map.of("name", "calendar.deleteAppointment"),
            () -> {
                attempts.incrementAndGet();
                throw new RuntimeException(new HttpOperationFailedException(
                    "https://calendar.example/mcp",
                    400,
                    "Bad Request",
                    null,
                    Map.of("Content-Type", "application/json"),
                    "{\"error\":\"Event not found\"}"
                ));
            }
        ));

        Assertions.assertEquals(1, attempts.get());
        Assertions.assertNotNull(McpCallSupport.findHttpFailure(thrown));
    }

    @Test
    void redactsSensitiveFieldsInLogSnippet() {
        String snippet = McpCallSupport.toLogSnippet("""
            {
              "jsonrpc":"2.0",
              "method":"tools/call",
              "params":{
                "name":"calendar.bookAppointment",
                "arguments":{
                  "summary":"Tesla service follow-up",
                  "email":"alice.j@gmail.com",
                  "phone":"408-777-1111",
                  "token":"secret-token",
                  "attendees":[{"email":"alice.j@gmail.com"}]
                }
              }
            }
            """);

        Assertions.assertTrue(snippet.contains("\"summary\":\"Tesla service follow-up\""));
        Assertions.assertTrue(snippet.contains("\"email\":\"[REDACTED]\""));
        Assertions.assertTrue(snippet.contains("\"phone\":\"[REDACTED]\""));
        Assertions.assertTrue(snippet.contains("\"token\":\"[REDACTED]\""));
        Assertions.assertTrue(snippet.contains("\"attendees\":\"[REDACTED]\""));
        Assertions.assertFalse(snippet.contains("alice.j@gmail.com"));
        Assertions.assertFalse(snippet.contains("408-777-1111"));
        Assertions.assertFalse(snippet.contains("secret-token"));
    }

    @Test
    void extractsNestedHttpFailureFromCamelExecutionException() {
        HttpOperationFailedException httpFailure = new HttpOperationFailedException(
            "https://calendar.example/mcp",
            503,
            "Service Unavailable",
            null,
            Map.of(),
            "{}"
        );

        CamelExecutionException wrapper = new CamelExecutionException("failed", null, httpFailure);

        Assertions.assertEquals(httpFailure, McpCallSupport.findHttpFailure(wrapper));
        Assertions.assertEquals(httpFailure, McpCallSupport.findRootCause(wrapper));
    }

    @Test
    void resolvesRetrySettingsFromCamelProperties() throws Exception {
        try (DefaultCamelContext camelContext = new DefaultCamelContext()) {
            Properties properties = new Properties();
            properties.setProperty("camel.agent.mcp.retry.max-attempts", "5");
            properties.setProperty("camel.agent.mcp.retry.delay-ms", "15");
            properties.setProperty("camel.agent.mcp.retry.status-codes", "404, 409, 503");
            camelContext.getPropertiesComponent().setInitialProperties(properties);

            McpCallSupport.McpCallSettings settings = McpCallSupport.resolveSettings(camelContext);

            Assertions.assertEquals(5, settings.maxAttempts());
            Assertions.assertEquals(15L, settings.retryDelayMs());
            Assertions.assertEquals(java.util.Set.of(404, 409, 503), settings.retriableStatusCodes());
        }
    }
}