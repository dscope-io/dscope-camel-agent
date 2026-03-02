package io.dscope.camel.agent.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.SimpleRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;

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

    @Test
    void shouldRestoreRealtimeSessionContextOnSessionStartReconnect() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "direct:test-agent-reconnect");

        RealtimeBrowserSessionRegistry sessionRegistry = new RealtimeBrowserSessionRegistry(10_000L);
        CamelContext context = createContext(Map.of(), Map.of("supportRealtimeSessionRegistry", sessionRegistry));

        try {
            String conversationId = "conv-reconnect-restore";
            ObjectNode existingSession = MAPPER.createObjectNode();
            ObjectNode metadata = MAPPER.createObjectNode();
            ObjectNode camelAgent = MAPPER.createObjectNode();
            camelAgent.put("lastTranscript", "existing transcript");
            camelAgent.put("lastAssistantMessage", "existing assistant");
            metadata.set("camelAgent", camelAgent);
            existingSession.set("metadata", metadata);
            ObjectNode audio = MAPPER.createObjectNode();
            ObjectNode output = MAPPER.createObjectNode();
            output.put("voice", "alloy");
            audio.set("output", output);
            existingSession.set("audio", audio);
            sessionRegistry.putSession(conversationId, existingSession);

            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", conversationId);
            exchange.getMessage().setBody("{\"type\":\"session.start\",\"session\":{\"audio\":{\"output\":{\"voice\":\"nova\"}},\"turn_detection\":{\"type\":\"server_vad\"}}}");

            processor.process(exchange);

            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("accepted").asBoolean());
            Assertions.assertTrue(body.path("connected").asBoolean());

            JsonNode sessionUpdateEvent = relayClient.firstSentEvent(conversationId, "session.update");
            Assertions.assertNotNull(sessionUpdateEvent);
            JsonNode session = sessionUpdateEvent.path("session");
            Assertions.assertEquals("existing transcript", session.path("metadata").path("camelAgent").path("lastTranscript").asText());
            Assertions.assertEquals("existing assistant", session.path("metadata").path("camelAgent").path("lastAssistantMessage").asText());
            Assertions.assertEquals("nova", session.path("audio").path("output").path("voice").asText());
            Assertions.assertEquals("server_vad", session.path("turn_detection").path("type").asText());

            JsonNode stored = sessionRegistry.getSession(conversationId);
            Assertions.assertNotNull(stored);
            Assertions.assertEquals("existing transcript", stored.path("metadata").path("camelAgent").path("lastTranscript").asText());
            Assertions.assertEquals("nova", stored.path("audio").path("output").path("voice").asText());
            Assertions.assertEquals("server_vad", stored.path("turn_detection").path("type").asText());
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldInitializeSessionStartPayloadWhenNoStoredContextExists() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "direct:test-agent-reconnect-no-stored");

        RealtimeBrowserSessionRegistry sessionRegistry = new RealtimeBrowserSessionRegistry(10_000L);
        CamelContext context = createContext(Map.of(), Map.of("supportRealtimeSessionRegistry", sessionRegistry));

        try {
            String conversationId = "conv-reconnect-no-stored";

            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", conversationId);
            exchange.getMessage().setBody("{\"type\":\"session.start\",\"session\":{\"turn_detection\":{\"type\":\"server_vad\"},\"audio\":{\"output\":{\"voice\":\"nova\"}}}}}");

            processor.process(exchange);

            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("accepted").asBoolean());
            Assertions.assertTrue(body.path("connected").asBoolean());

            JsonNode sessionUpdateEvent = relayClient.firstSentEvent(conversationId, "session.update");
            Assertions.assertNotNull(sessionUpdateEvent);
            JsonNode session = sessionUpdateEvent.path("session");
            Assertions.assertEquals(conversationId, session.path("metadata").path("conversationId").asText());
            Assertions.assertEquals("nova", session.path("audio").path("output").path("voice").asText());
            Assertions.assertEquals("server_vad", session.path("turn_detection").path("type").asText());

            JsonNode stored = sessionRegistry.getSession(conversationId);
            Assertions.assertNotNull(stored);
            Assertions.assertEquals(conversationId, stored.path("metadata").path("conversationId").asText());
            Assertions.assertEquals("nova", stored.path("audio").path("output").path("voice").asText());
            Assertions.assertEquals("server_vad", stored.path("turn_detection").path("type").asText());
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldStartRealtimeVoiceBranchOnlyAfterSessionContextUpdateApplied() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "direct:test-agent-voice");

        RealtimeBrowserSessionRegistry sessionRegistry = new RealtimeBrowserSessionRegistry(10_000L);
        CamelContext context = createContext(Map.of(), Map.of("supportRealtimeSessionRegistry", sessionRegistry));
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:test-agent-voice")
                    .transform(simple("assistant: ${body}"));
            }
        });

        try {
            String conversationId = "conv-voice-branch-after-context";
            sessionRegistry.putSession(conversationId, MAPPER.createObjectNode());
            relayClient.forceConnected(conversationId);

            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", conversationId);
            exchange.getMessage().setBody("{\"type\":\"transcript.final\",\"text\":\"check order\"}");

            processor.process(exchange);

            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertTrue(body.path("sessionContextUpdated").asBoolean());
            Assertions.assertTrue(body.path("realtimeVoiceBranchStarted").asBoolean());

            JsonNode session = sessionRegistry.getSession(conversationId);
            Assertions.assertEquals("check order", session.path("metadata").path("camelAgent").path("lastTranscript").asText());
            Assertions.assertEquals("assistant: check order", session.path("metadata").path("camelAgent").path("lastAssistantMessage").asText());
            JsonNode recentTurns = session.path("metadata").path("camelAgent").path("context").path("recentTurns");
            Assertions.assertTrue(recentTurns.isArray());
            Assertions.assertTrue(recentTurns.size() >= 2);
            Assertions.assertEquals("user", recentTurns.get(recentTurns.size() - 2).path("role").asText());
            Assertions.assertEquals("check order", recentTurns.get(recentTurns.size() - 2).path("text").asText());
            Assertions.assertEquals("assistant", recentTurns.get(recentTurns.size() - 1).path("role").asText());
            Assertions.assertEquals("assistant: check order", recentTurns.get(recentTurns.size() - 1).path("text").asText());

            Assertions.assertTrue(relayClient.sentEventTypes(conversationId).contains("response.create"));
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldSkipRealtimeVoiceBranchWhenSessionContextUpdateCannotBeApplied() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "direct:test-agent-no-registry");

        CamelContext context = createContext(Map.of());
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:test-agent-no-registry")
                    .transform(simple("assistant: ${body}"));
            }
        });

        try {
            String conversationId = "conv-no-context-update";
            relayClient.forceConnected(conversationId);

            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", conversationId);
            exchange.getMessage().setBody("{\"type\":\"transcript.final\",\"text\":\"no session registry\"}");

            processor.process(exchange);

            JsonNode body = MAPPER.readTree(exchange.getMessage().getBody(String.class));
            Assertions.assertFalse(body.path("sessionContextUpdated").asBoolean());
            Assertions.assertFalse(body.path("realtimeVoiceBranchStarted").asBoolean());
            Assertions.assertFalse(relayClient.sentEventTypes(conversationId).contains("response.create"));
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldPersistAllIncomingRealtimeVoiceEventsToAuditTrail() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "direct:test-agent-persistence");

        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();
        CamelContext context = createContext(Map.of(), Map.of("persistenceFacade", persistence));

        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "conv-audit-events");
            exchange.getMessage().setBody("{\"type\":\"input_audio_buffer.append\",\"audio\":\"base64-chunk\"}");

            processor.process(exchange);

            List<AgentEvent> events = persistence.loadConversation("conv-audit-events", 10);
            Assertions.assertEquals(1, events.size());
            Assertions.assertEquals("realtime.input_audio_buffer.append", events.get(0).type());
            Assertions.assertEquals("input_audio_buffer.append", events.get(0).payload().path("type").asText());
            Assertions.assertEquals("base64-chunk", events.get(0).payload().path("audio").asText());
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldPersistRealtimeTranscriptTurnMessagesToAuditTrail() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "direct:test-agent-transcript-persistence");

        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();
        CamelContext context = createContext(Map.of(), Map.of("persistenceFacade", persistence));
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:test-agent-transcript-persistence")
                    .transform(simple("assistant: ${body}"));
            }
        });

        try {
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", "conv-realtime-turn-audit");
            exchange.getMessage().setBody("{\"type\":\"transcript.final\",\"text\":\"please open ticket\"}");

            processor.process(exchange);

            List<AgentEvent> events = persistence.loadConversation("conv-realtime-turn-audit", 20);
            Assertions.assertTrue(events.stream().anyMatch(event -> "realtime.transcript.final".equals(event.type())));
            Assertions.assertTrue(events.stream().anyMatch(event -> "user.message".equals(event.type())
                && "please open ticket".equals(event.payload().asText())));
            Assertions.assertTrue(events.stream().anyMatch(event -> "assistant.message".equals(event.type())
                && "assistant: please open ticket".equals(event.payload().asText())));
        } finally {
            context.stop();
        }
    }

    @Test
    void shouldLogRealtimeContextCheckpointsInOrder() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "direct:test-agent-log-order");

        RealtimeBrowserSessionRegistry sessionRegistry = new RealtimeBrowserSessionRegistry(10_000L);
        CamelContext context = createContext(Map.of(), Map.of("supportRealtimeSessionRegistry", sessionRegistry));
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:test-agent-log-order")
                    .transform(simple("assistant: ${body}"));
            }
        });

        Logger logger = (Logger) LoggerFactory.getLogger(RealtimeEventProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);

        try {
            String conversationId = "conv-log-order";
            sessionRegistry.putSession(conversationId, MAPPER.createObjectNode());
            relayClient.forceConnected(conversationId);

            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", conversationId);
            exchange.getMessage().setBody("{\"type\":\"transcript.final\",\"text\":\"log order check\"}");

            processor.process(exchange);

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            int composedIndex = indexOf(messages, "Realtime context update composed: conversationId=conv-log-order");
            int appliedIndex = indexOf(messages, "Realtime route session update applied: conversationId=conv-log-order");
            int applyResultIndex = indexOf(messages, "Realtime context update apply result: conversationId=conv-log-order, updated=true");
            int branchStartedIndex = indexOf(messages, "Realtime voice branch started from backend: conversationId=conv-log-order");

            Assertions.assertTrue(composedIndex >= 0, "missing composed log checkpoint");
            Assertions.assertTrue(appliedIndex > composedIndex, "session update applied should be after compose");
            Assertions.assertTrue(applyResultIndex > appliedIndex, "apply result should be after update applied");
            Assertions.assertTrue(branchStartedIndex > applyResultIndex, "voice branch start should be after successful context update");
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            context.stop();
        }
    }

    @Test
    void shouldNotLogVoiceBranchStartedWhenContextUpdateFails() throws Exception {
        RecordingRelayClient relayClient = new RecordingRelayClient();
        RealtimeEventProcessor processor = new RealtimeEventProcessor(relayClient, "direct:test-agent-log-no-branch");

        CamelContext context = createContext(Map.of());
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:test-agent-log-no-branch")
                    .transform(simple("assistant: ${body}"));
            }
        });

        Logger logger = (Logger) LoggerFactory.getLogger(RealtimeEventProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);

        try {
            String conversationId = "conv-log-no-branch";
            relayClient.forceConnected(conversationId);

            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader("conversationId", conversationId);
            exchange.getMessage().setBody("{\"type\":\"transcript.final\",\"text\":\"no context registry\"}");

            processor.process(exchange);

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            int applyFalseIndex = indexOf(messages, "Realtime context update apply result: conversationId=conv-log-no-branch, updated=false");
            int skippedIndex = indexOf(messages, "Realtime voice branch skipped because session context update was not applied: conversationId=conv-log-no-branch");
            int branchStartedIndex = indexOf(messages, "Realtime voice branch started from backend: conversationId=conv-log-no-branch");

            Assertions.assertTrue(applyFalseIndex >= 0, "missing update=false checkpoint");
            Assertions.assertTrue(skippedIndex > applyFalseIndex, "skip checkpoint should follow update=false checkpoint");
            Assertions.assertEquals(-1, branchStartedIndex, "voice branch start log should not be present when update fails");
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            context.stop();
        }
    }

    private int indexOf(List<String> messages, String token) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).contains(token)) {
                return i;
            }
        }
        return -1;
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
        private final Map<String, List<String>> sentEventTypesByConversation = new ConcurrentHashMap<>();
        private final Map<String, List<JsonNode>> sentEventsByConversation = new ConcurrentHashMap<>();

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
            JsonNode event = parse(eventJson);
            String type = event.path("type").asText("unknown");
            sentEventTypesByConversation
                .computeIfAbsent(conversationId, key -> new ArrayList<>())
                .add(type);
            sentEventsByConversation
                .computeIfAbsent(conversationId, key -> new ArrayList<>())
                .add(event);
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

        private JsonNode parse(String value) {
            try {
                return mapper.readTree(value);
            } catch (Exception ignored) {
                return mapper.createObjectNode();
            }
        }

        private void forceConnected(String conversationId) {
            sessions.put(conversationId, true);
        }

        private List<String> sentEventTypes(String conversationId) {
            return sentEventTypesByConversation.getOrDefault(conversationId, List.of());
        }

        private JsonNode firstSentEvent(String conversationId, String type) {
            List<JsonNode> events = sentEventsByConversation.getOrDefault(conversationId, List.of());
            for (JsonNode event : events) {
                if (type.equals(event.path("type").asText(""))) {
                    return event;
                }
            }
            return null;
        }
    }
}
