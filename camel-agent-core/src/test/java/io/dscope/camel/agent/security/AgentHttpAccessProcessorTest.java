package io.dscope.camel.agent.security;

import java.util.Properties;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentHttpAccessProcessorTest {

    private final AgentHttpAccessProcessor processor = new AgentHttpAccessProcessor();

    @Test
    void shouldFailClosedWhenSensitivePathHasNoConfiguredToken() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Exchange exchange = exchange(context, "/runtime/refresh");

            processor.process(exchange);

            Assertions.assertEquals(503, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertTrue(exchange.isRouteStop());
        }
    }

    @Test
    void shouldAllowMatchingBearerTokenAndLeavePublicPathsUntouched() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Properties properties = new Properties();
            properties.setProperty("agent.security.access-token", "expected-token");
            context.getPropertiesComponent().setInitialProperties(properties);
            Exchange authorized = exchange(context, "/realtime/session/conversation/token");
            authorized.getMessage().setHeader("Authorization", "Bearer expected-token");

            processor.process(authorized);

            Assertions.assertNull(authorized.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Exchange publicPath = exchange(context, "/agui/ui");
            processor.process(publicPath);
            Assertions.assertNull(publicPath.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        }
    }

    @Test
    void shouldResolveProtectedPathFromHttpUriWhenHttpPathIsUnavailable() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            Properties properties = new Properties();
            properties.setProperty("agent.security.access-token", "expected-token");
            context.getPropertiesComponent().setInitialProperties(properties);
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader(Exchange.HTTP_URI, "http://localhost:8080/mcp/admin");

            processor.process(exchange);

            Assertions.assertEquals(401, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            Assertions.assertTrue(exchange.isRouteStop());
        }
    }

    private static Exchange exchange(DefaultCamelContext context, String path) {
        Exchange exchange = new DefaultExchange(context);
        exchange.getMessage().setHeader(Exchange.HTTP_PATH, path);
        return exchange;
    }
}
