package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.SimpleRegistry;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RealtimeEventProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldPreferBlueprintRealtimeOverRuntimeProperties() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "agent:test?blueprint={{agent.blueprint}}");

        CamelContext context = createContext(Map.of(
            "agent.blueprint", "classpath:agents/valid-agent-with-realtime.md",
            "agent.runtime.realtime.model", "runtime-override-model",
            "agent.runtime.realtime.endpoint-uri", "wss://runtime-override.example/realtime",
            "agent.runtime.realtime.reconnect.max-send-retries", "9",
            "openai.api.key", "test-key"
        ));

        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "conv-blueprint-priority");
            exchange.getMessage().setBody("{\"type\":\"session.start\"}");

            processor.process(exchange);

            Assertions.assertEquals("conv-blueprint-priority", relayClient.lastConversationId);
            Assertions.assertEquals("gpt-4o-realtime-preview", relayClient.lastModel);
            Assertions.assertEquals("mcp:http://localhost:3001/mcp", relayClient.lastEndpointUri);
            Assertions.assertNotNull(relayClient.lastReconnectPolicy);
            Assertions.assertEquals(3, relayClient.lastReconnectPolicy.maxSendRetries());
            Assertions.assertEquals(8, relayClient.lastReconnectPolicy.maxReconnectsPerSession());
            Assertions.assertEquals(150L, relayClient.lastReconnectPolicy.initialBackoffMs());
            Assertions.assertEquals(2000L, relayClient.lastReconnectPolicy.maxBackoffMs());

            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("accepted").asBoolean());
            Assertions.assertTrue(body.path("connected").asBoolean());
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldUseAliasRealtimePropertiesWhenBlueprintHasNoRealtimeSection() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "agent:test?blueprint={{agent.blueprint}}");

        CamelContext context = createContext(Map.of(
            "agent.blueprint", "classpath:agents/valid-agent.md",
            "agent.realtime.provider", "openai",
            "agent.realtime.model", "alias-model",
            "agent.realtime.endpoint-uri", "wss://alias.example/realtime",
            "agent.realtime.reconnect.maxSendRetries", "2",
            "agent.realtime.reconnect.maxReconnects", "4",
            "agent.realtime.reconnect.initialBackoffMs", "100",
            "agent.realtime.reconnect.maxBackoffMs", "1000",
            "openai.api.key", "test-key"
        ));

        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "conv-alias-fallback");
            exchange.getMessage().setBody("{\"type\":\"session.start\"}");

            processor.process(exchange);

            Assertions.assertEquals("conv-alias-fallback", relayClient.lastConversationId);
            Assertions.assertEquals("alias-model", relayClient.lastModel);
            Assertions.assertEquals("wss://alias.example/realtime", relayClient.lastEndpointUri);
            Assertions.assertNotNull(relayClient.lastReconnectPolicy);
            Assertions.assertEquals(2, relayClient.lastReconnectPolicy.maxSendRetries());
            Assertions.assertEquals(4, relayClient.lastReconnectPolicy.maxReconnectsPerSession());
            Assertions.assertEquals(100L, relayClient.lastReconnectPolicy.initialBackoffMs());
            Assertions.assertEquals(1000L, relayClient.lastReconnectPolicy.maxBackoffMs());

            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("accepted").asBoolean());
            Assertions.assertTrue(body.path("connected").asBoolean());
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldReturnAgUiMessagesForTranscriptFinal() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "direct:test-agent");

        CamelContext context = createContext(Map.of());
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:test-agent")
                    .transform(simple("assistant: ${body}"));
            }
        });

        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "conv-transcript-agui");
            exchange.getMessage().setBody("{\"type\":\"transcript.final\",\"text\":\"open ticket for login issue\"}");

            processor.process(exchange);

            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("routedToAgent").asBoolean());
            Assertions.assertEquals("assistant: open ticket for login issue", body.path("assistantMessage").asText());

            JsonNode aguiMessages = body.path("aguiMessages");
            Assertions.assertTrue(aguiMessages.isArray());
            Assertions.assertEquals(2, aguiMessages.size());
            Assertions.assertEquals("user", aguiMessages.get(0).path("role").asText());
            Assertions.assertEquals("open ticket for login issue", aguiMessages.get(0).path("content").asText());
            Assertions.assertEquals("assistant", aguiMessages.get(1).path("role").asText());
            Assertions.assertEquals("assistant: open ticket for login issue", aguiMessages.get(1).path("content").asText());
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldMergeRealtimeSessionContextFromAgentRouteUpdate() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "direct:test-agent-update");

        RealtimeBrowserSessionRegistry sessionRegistry = new RealtimeBrowserSessionRegistry(10_000L);
        CamelContext context = createContext(Map.of(), Map.of("supportRealtimeSessionRegistry", sessionRegistry));
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:test-agent-update")
                    .setHeader("realtimeSessionUpdate")
                    .constant("{\"metadata\":{\"lastTool\":\"support.ticket.open\"},\"audio\":{\"output\":{\"voice\":\"alloy\"}}}")
                    .transform(simple("assistant update for ${body}"));
            }
        });

        try {
            sessionRegistry.putSession("conv-route-update", MAPPER.createObjectNode());

            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "conv-route-update");
            exchange.getMessage().setBody("{\"type\":\"transcript.final\",\"text\":\"open ticket for login issue\"}");

            processor.process(exchange);

            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("routedToAgent").asBoolean());
            Assertions.assertTrue(body.path("sessionContextUpdated").asBoolean());

            JsonNode session = sessionRegistry.getSession("conv-route-update");
            Assertions.assertNotNull(session);
            Assertions.assertEquals("support.ticket.open", session.path("metadata").path("lastTool").asText());
            Assertions.assertEquals("alloy", session.path("audio").path("output").path("voice").asText());
        } finally {
            context.stop();
        }
    }

    private CamelContext createContext(Map<String, String> properties) throws Exception {
        return createContext(properties, Map.of());
    }

    private CamelContext createContext(Map<String, String> properties, Map<String, Object> bindings) throws Exception {
        SimpleRegistry registry = new SimpleRegistry();
        bindings.forEach(registry::bind);
        CamelContext context = new DefaultCamelContext(registry);
        Properties initial = new Properties();
        initial.putAll(properties);
        context.getPropertiesComponent().setInitialProperties(initial);
        context.start();
        return context;
    }

    private static final class RecordingRelayClient implements RealtimeRelayClient {

        private final ObjectMapper mapper = new ObjectMapper();
        private final Map<String, Boolean> sessions = new ConcurrentHashMap<>();
        private String lastConversationId;
        private String lastEndpointUri;
        private String lastModel;
        private RealtimeReconnectPolicy lastReconnectPolicy;

        @Override
        public boolean isConnected(String conversationId) {
            return sessions.getOrDefault(conversationId, false);
        }

        @Override
        public void connect(String conversationId, String endpointUri, String model, String apiKey) {
            connect(conversationId, endpointUri, model, apiKey, new RealtimeReconnectPolicy(3, 8, 150L, 2000L));
        }

        @Override
        public void connect(String conversationId, String endpointUri, String model, String apiKey, RealtimeReconnectPolicy reconnectPolicy) {
            this.lastConversationId = conversationId;
            this.lastEndpointUri = endpointUri;
            this.lastModel = model;
            this.lastReconnectPolicy = reconnectPolicy;
            sessions.put(conversationId, true);
        }

        @Override
        public void sendEvent(String conversationId, String eventJson) {
        }

        @Override
        public ArrayNode pollEvents(String conversationId) {
            return mapper.createArrayNode();
        }

        @Override
        public void close(String conversationId) {
            sessions.remove(conversationId);
        }

        @Override
        public ObjectNode sessionState(String conversationId) {
            ObjectNode state = mapper.createObjectNode();
            state.put("connected", isConnected(conversationId));
            state.set("events", mapper.createArrayNode());
            return state;
        }
    }
}
