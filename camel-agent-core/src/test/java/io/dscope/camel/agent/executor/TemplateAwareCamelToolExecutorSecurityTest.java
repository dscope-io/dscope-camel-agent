package io.dscope.camel.agent.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.a2a.A2AToolContext;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.JsonRouteTemplateSpec;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.List;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Negative-path security tests for the URI scheme allowlist enforced by
 * {@code TemplateAwareCamelToolExecutor.validateToUri()}. Each test verifies
 * that a disallowed scheme is rejected before any route is added to the
 * Camel context.
 */
class TemplateAwareCamelToolExecutorSecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ExecutionContext CTX = new ExecutionContext("conv-sec", "task-sec", "trace-sec");

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Build a template whose {@code to} step uses {@code toUri} as the target.
     */
    private static JsonRouteTemplateSpec templateWithToUri(String toUri) throws Exception {
        String routeJson = """
            {
              "route": {
                "id": "sec-test-to",
                "from": {
                  "uri": "direct:sec-from",
                  "steps": [
                    { "to": { "uri": "%s" } }
                  ]
                }
              }
            }
            """.formatted(toUri);
        return new JsonRouteTemplateSpec(
            "sec.test.to",
            "sec.tool.to",
            "security test tool – to step",
            null,
            MAPPER.createObjectNode(),
            MAPPER.readTree(routeJson)
        );
    }

    /**
     * Build a template whose {@code toD} step uses {@code toUri} as the target.
     */
    private static JsonRouteTemplateSpec templateWithToDUri(String toUri) throws Exception {
        String routeJson = """
            {
              "route": {
                "id": "sec-test-tod",
                "from": {
                  "uri": "direct:sec-from",
                  "steps": [
                    { "toD": { "uri": "%s" } }
                  ]
                }
              }
            }
            """.formatted(toUri);
        return new JsonRouteTemplateSpec(
            "sec.test.tod",
            "sec.tool.tod",
            "security test tool – toD step",
            null,
            MAPPER.createObjectNode(),
            MAPPER.readTree(routeJson)
        );
    }

    /**
     * Build a template whose {@code from.uri} is set to {@code fromUri}.
     */
    private static JsonRouteTemplateSpec templateWithFromUri(String fromUri) throws Exception {
        String routeJson = """
            {
              "route": {
                "id": "sec-test-from",
                "from": {
                  "uri": "%s",
                  "steps": []
                }
              }
            }
            """.formatted(fromUri);
        return new JsonRouteTemplateSpec(
            "sec.test.from",
            "sec.tool.from",
            "security test tool – from uri",
            null,
            MAPPER.createObjectNode(),
            MAPPER.readTree(routeJson)
        );
    }

    /**
     * Build a template spec; pass {@code invokeUri} as the {@code fromUri}
     * argument so it becomes the invoke URI resolved by the executor.
     */
    private static JsonRouteTemplateSpec templateForInvokeUri() throws Exception {
        return new JsonRouteTemplateSpec(
            "sec.test.invoke",
            "sec.tool.invoke",
            "security test tool – invokeUri",
            "fromUri",
            MAPPER.createObjectNode(),
            MAPPER.readTree("""
                {
                  "route": {
                    "id": "sec-test-invoke",
                    "from": {
                      "uri": "direct:sec-invoke-from",
                      "steps": []
                    }
                  }
                }
                """)
        );
    }

    private static ToolSpec toolSpecFor(JsonRouteTemplateSpec spec) {
        return new ToolSpec(
            spec.toolName(),
            spec.description(),
            null,
            null,
            spec.parametersSchema(),
            null,
            new ToolPolicy(false, 0, 5_000)
        );
    }

    // -----------------------------------------------------------------------
    // Tests – disallowed schemes in 'to' steps
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "'to' step rejects scheme [{0}]")
    @ValueSource(strings = {
        "file:///etc/passwd",
        "exec:rm+-rf+/",
        "http://169.254.169.254/latest/meta-data/",
        "https://169.254.169.254/latest/meta-data/",
        "ftp://internal-host/data",
        "sftp://internal-host/data"
    })
    void shouldRejectDisallowedSchemeInToStep(String disallowedUri) throws Exception {
        JsonRouteTemplateSpec spec = templateWithToUri(disallowedUri);
        try (DefaultCamelContext ctx = new DefaultCamelContext()) {
            ctx.start();
            TemplateAwareCamelToolExecutor executor = new TemplateAwareCamelToolExecutor(
                ctx,
                ctx.createProducerTemplate(),
                MAPPER,
                List.of(spec),
                null,
                A2AToolContext.EMPTY
            );

            IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(toolSpecFor(spec), MAPPER.createObjectNode(), CTX)
            );
            Assertions.assertTrue(
                ex.getMessage().contains("disallowed scheme") || (ex.getCause() != null && ex.getCause().getMessage().contains("disallowed scheme")),
                "Expected 'disallowed scheme' in exception message but got: " + ex.getMessage()
            );
        }
    }

    // -----------------------------------------------------------------------
    // Tests – disallowed schemes in 'toD' steps
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "'toD' step rejects scheme [{0}]")
    @ValueSource(strings = {
        "file:///etc/passwd",
        "exec:rm+-rf+/",
        "http://169.254.169.254/latest/meta-data/",
        "ftp://internal-host/data"
    })
    void shouldRejectDisallowedSchemeInToDStep(String disallowedUri) throws Exception {
        JsonRouteTemplateSpec spec = templateWithToDUri(disallowedUri);
        try (DefaultCamelContext ctx = new DefaultCamelContext()) {
            ctx.start();
            TemplateAwareCamelToolExecutor executor = new TemplateAwareCamelToolExecutor(
                ctx,
                ctx.createProducerTemplate(),
                MAPPER,
                List.of(spec),
                null,
                A2AToolContext.EMPTY
            );

            IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(toolSpecFor(spec), MAPPER.createObjectNode(), CTX)
            );
            Assertions.assertTrue(
                ex.getMessage().contains("disallowed scheme") || (ex.getCause() != null && ex.getCause().getMessage().contains("disallowed scheme")),
                "Expected 'disallowed scheme' in exception message but got: " + ex.getMessage()
            );
        }
    }

    // -----------------------------------------------------------------------
    // Tests – disallowed schemes in 'from.uri'
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "'from.uri' rejects scheme [{0}]")
    @ValueSource(strings = {
        "file:///etc/passwd",
        "exec:rm+-rf+/",
        "http://169.254.169.254/latest/meta-data/",
        "ftp://internal-host/data"
    })
    void shouldRejectDisallowedSchemeInFromUri(String disallowedUri) throws Exception {
        JsonRouteTemplateSpec spec = templateWithFromUri(disallowedUri);
        try (DefaultCamelContext ctx = new DefaultCamelContext()) {
            ctx.start();
            TemplateAwareCamelToolExecutor executor = new TemplateAwareCamelToolExecutor(
                ctx,
                ctx.createProducerTemplate(),
                MAPPER,
                List.of(spec),
                null,
                A2AToolContext.EMPTY
            );

            IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(toolSpecFor(spec), MAPPER.createObjectNode(), CTX)
            );
            Assertions.assertTrue(
                ex.getMessage().contains("disallowed scheme") || (ex.getCause() != null && ex.getCause().getMessage().contains("disallowed scheme")),
                "Expected 'disallowed scheme' in exception message but got: " + ex.getMessage()
            );
        }
    }

    // -----------------------------------------------------------------------
    // Tests – disallowed schemes in invokeUri (supplied via arguments)
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "invokeUri rejects scheme [{0}]")
    @ValueSource(strings = {
        "file:///etc/passwd",
        "exec:rm+-rf+/",
        "http://169.254.169.254/latest/meta-data/",
        "ftp://internal-host/data"
    })
    void shouldRejectDisallowedSchemeInInvokeUri(String disallowedUri) throws Exception {
        JsonRouteTemplateSpec spec = templateForInvokeUri();
        try (DefaultCamelContext ctx = new DefaultCamelContext()) {
            ctx.start();
            TemplateAwareCamelToolExecutor executor = new TemplateAwareCamelToolExecutor(
                ctx,
                ctx.createProducerTemplate(),
                MAPPER,
                List.of(spec),
                null,
                A2AToolContext.EMPTY
            );

            // invokeUriParam defaults to "fromUri"; supply disallowed URI here
            var args = MAPPER.readTree("{\"fromUri\": \"" + disallowedUri + "\"}");

            IllegalArgumentException ex = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(toolSpecFor(spec), args, CTX)
            );
            Assertions.assertTrue(
                ex.getMessage().contains("disallowed scheme"),
                "Expected 'disallowed scheme' in exception message but got: " + ex.getMessage()
            );
        }
    }

    // -----------------------------------------------------------------------
    // Tests – allowed schemes must NOT be rejected
    // -----------------------------------------------------------------------

    @Test
    void shouldPermitAllowedSchemesInToStep() throws Exception {
        // log: is in the allowlist and can execute without requiring a consumer.
        String routeJson = """
            {
              "route": {
                "id": "sec-allow-to",
                "from": {
                  "uri": "direct:sec-allow-from",
                  "steps": [
                    { "to": { "uri": "log:sec-allow-to-target" } }
                  ]
                }
              }
            }
            """;
        JsonRouteTemplateSpec spec = new JsonRouteTemplateSpec(
            "sec.allow.to",
            "sec.tool.allow.to",
            "allowed 'to' step",
            null,
            MAPPER.createObjectNode(),
            MAPPER.readTree(routeJson)
        );
        try (DefaultCamelContext ctx = new DefaultCamelContext()) {
            ctx.start();
            TemplateAwareCamelToolExecutor executor = new TemplateAwareCamelToolExecutor(
                ctx,
                ctx.createProducerTemplate(),
                MAPPER,
                List.of(spec),
                null,
                A2AToolContext.EMPTY
            );

            // The important assertion is no IllegalArgumentException about "disallowed scheme".
            try {
                executor.execute(toolSpecFor(spec), MAPPER.createObjectNode(), CTX);
            } catch (IllegalArgumentException e) {
                Assertions.assertFalse(
                    e.getMessage().contains("disallowed scheme"),
                    "Allowed scheme 'log:' must not be rejected. Error: " + e.getMessage()
                );
            }
        }
    }
}
