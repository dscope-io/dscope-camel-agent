package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Properties;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RealtimeBrowserSessionInitProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldPreserveExistingSessionContextWhenInitCalledWithoutSessionPayload() throws Exception {
        RealtimeBrowserSessionRegistry registry = new RealtimeBrowserSessionRegistry();
        String conversationId = "preserve-init-context";

        ObjectNode seededSession = MAPPER.createObjectNode();
        seededSession.put("type", "realtime");
        seededSession.put("model", "gpt-4o-realtime-preview");

        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("conversationId", conversationId);
        ObjectNode camelAgent = MAPPER.createObjectNode();
        ObjectNode context = MAPPER.createObjectNode();
        ArrayNode recentTurns = MAPPER.createArrayNode();
        recentTurns.add(MAPPER.createObjectNode().put("role", "user").put("text", "Need help").put("at", "2025-01-01T00:00:00Z"));
        context.set("recentTurns", recentTurns);
        camelAgent.set("context", context);
        metadata.set("camelAgent", camelAgent);
        seededSession.set("metadata", metadata);

        registry.putSession(conversationId, seededSession);

        RealtimeBrowserSessionInitProcessor processor = new RealtimeBrowserSessionInitProcessor(registry);
        CamelContext camelContext = new DefaultCamelContext();
        camelContext.start();
        try {
            Exchange exchange = new DefaultExchange(camelContext);
            exchange.getMessage().setHeader("conversationId", conversationId);
            exchange.getMessage().setBody("{}");

            processor.process(exchange);

            Assertions.assertEquals(200, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));

            JsonNode response = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            JsonNode session = response.path("session");
            Assertions.assertEquals(conversationId, session.path("metadata").path("conversationId").asText());
            Assertions.assertEquals(1, session.path("metadata").path("camelAgent").path("context").path("recentTurns").size());
            Assertions.assertEquals(
                "Need help",
                session.path("metadata").path("camelAgent").path("context").path("recentTurns").get(0).path("text").asText()
            );
        } finally {
            camelContext.stop();
        }
    }

    @Test
    void shouldSeedBlueprintAgentProfileIntoSessionContextBeforeConversation() throws Exception {
        RealtimeBrowserSessionRegistry registry = new RealtimeBrowserSessionRegistry();
        RealtimeBrowserSessionInitProcessor processor = new RealtimeBrowserSessionInitProcessor(registry);

        CamelContext camelContext = createContext(Map.of("agent.blueprint", "classpath:agents/valid-agent.md"));
        try {
            Exchange exchange = new DefaultExchange(camelContext);
            exchange.getMessage().setHeader("conversationId", "seed-profile");
            exchange.getMessage().setBody("{}");

            processor.process(exchange);

            Assertions.assertEquals(200, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));

            JsonNode response = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            JsonNode profile = response.path("session").path("metadata").path("camelAgent").path("agentProfile");
            JsonNode context = response.path("session").path("metadata").path("camelAgent").path("context");

            Assertions.assertEquals("SupportAssistant", profile.path("name").asText());
            Assertions.assertEquals("0.1.0", profile.path("version").asText());
            Assertions.assertEquals("You are a support agent.", profile.path("purpose").asText());
            Assertions.assertTrue(profile.path("tools").isArray());
            Assertions.assertTrue(profile.path("tools").toString().contains("kb.search"));
            Assertions.assertEquals("You are a support agent.", context.path("agentPurpose").asText());
            Assertions.assertTrue(context.path("agentFocusHint").asText().contains("configured agent purpose"));
        } finally {
            camelContext.stop();
        }
    }

    @Test
    void shouldAllowUnlimitedAgentPurposeLengthWhenConfigured() throws Exception {
        RealtimeBrowserSessionRegistry registry = new RealtimeBrowserSessionRegistry();
        RealtimeBrowserSessionInitProcessor processor = new RealtimeBrowserSessionInitProcessor(registry);

        CamelContext camelContext = createContext(Map.of(
            "agent.blueprint", "classpath:agents/valid-agent-with-long-system.md",
            "agent.runtime.realtime.agent-profile-purpose-max-chars", "0"
        ));
        try {
            Exchange exchange = new DefaultExchange(camelContext);
            exchange.getMessage().setHeader("conversationId", "seed-profile-unlimited");
            exchange.getMessage().setBody("{}");

            processor.process(exchange);

            JsonNode response = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            String purpose = response.path("session")
                .path("metadata")
                .path("camelAgent")
                .path("agentProfile")
                .path("purpose")
                .asText("");

            Assertions.assertTrue(purpose.length() > 240);
            Assertions.assertTrue(purpose.contains("handoff-ready summaries"));
        } finally {
            camelContext.stop();
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
}
