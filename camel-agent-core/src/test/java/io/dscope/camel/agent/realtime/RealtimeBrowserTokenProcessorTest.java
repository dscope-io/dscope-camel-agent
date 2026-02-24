package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RealtimeBrowserTokenProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenTtlScenarios")
    void shouldValidateTokenTtlScenarios(String name,
                                         RealtimeBrowserSessionRegistry registry,
                                         String conversationId,
                                         String expectedErrorSnippet,
                                         Consumer<RealtimeBrowserSessionRegistry> setup) throws Exception {
        if (setup != null) {
            setup.accept(registry);
        }
        RealtimeBrowserTokenProcessor processor = new RealtimeBrowserTokenProcessor(registry);
        CamelContext context = createContext(Map.of());
        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", conversationId);
            exchange.getMessage().setBody("{}");

            processor.process(exchange);

            Assertions.assertEquals(410, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("error").asText().contains(expectedErrorSnippet));
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldAllowMissingInitWhenStrictModeDisabled() throws Exception {
        RealtimeBrowserSessionRegistry registry = new RealtimeBrowserSessionRegistry(50L);
        RealtimeBrowserTokenProcessor processor = new RealtimeBrowserTokenProcessor(registry);

        CamelContext context = createContext(Map.of("agent.runtime.realtime.require-init-session", "false"));
        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "strict-off");
            exchange.getMessage().setBody("{}");

            processor.process(exchange);

            Assertions.assertEquals(500, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("error").asText().contains("Missing OpenAI API key"));
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldAllowMissingInitWhenPreferCoreEnabledButStrictExplicitlyDisabled() throws Exception {
        RealtimeBrowserSessionRegistry registry = new RealtimeBrowserSessionRegistry(50L);
        RealtimeBrowserTokenProcessor processor = new RealtimeBrowserTokenProcessor(registry);

        CamelContext context = createContext(Map.of(
            "agent.runtime.realtime.prefer-core-token-processor", "true",
            "agent.runtime.realtime.require-init-session", "false"
        ));
        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "prefer-core-strict-explicit-off");
            exchange.getMessage().setBody("{}");

            processor.process(exchange);

            Assertions.assertEquals(500, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("error").asText().contains("Missing OpenAI API key"));
        } finally {
            context.stop();
        }
    }

    private CamelContext createContext(Map<String, String> properties) throws Exception {
        CamelContext context = new DefaultCamelContext();
        Properties initial = new Properties();
        initial.putAll(properties);
        context.getPropertiesComponent().setInitialProperties(initial);
        context.start();
        return context;
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> tokenTtlScenarios() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(
                "missing init session",
                new RealtimeBrowserSessionRegistry(50L),
                "ttl-missing",
                "/init",
                null
            ),
            org.junit.jupiter.params.provider.Arguments.of(
                "expired init session",
                new RealtimeBrowserSessionRegistry(20L),
                "ttl-expired",
                "expired",
                (Consumer<RealtimeBrowserSessionRegistry>) registry -> {
                    ObjectNode seeded = MAPPER.createObjectNode();
                    seeded.put("type", "realtime");
                    seeded.put("model", "gpt-realtime");
                    registry.putSession("ttl-expired", seeded);
                    try {
                        Thread.sleep(40L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            )
        );
    }
}
