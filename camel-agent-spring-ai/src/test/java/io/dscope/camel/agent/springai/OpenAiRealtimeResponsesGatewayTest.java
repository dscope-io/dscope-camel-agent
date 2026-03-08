package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.realtime.RealtimeReconnectPolicy;
import io.dscope.camel.agent.realtime.RealtimeRelayClient;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenAiRealtimeResponsesGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldReturnTextAndToolCallsFromRealtimeEvents() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.request-timeout-ms", "2000");
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.poll-interval-ms", "1");

        FakeRelay relay = new FakeRelay();
        relay.enqueue(event("response.output_text.delta", "delta", "hello "));
        relay.enqueue(functionCallDone("support.ticket.open", "{\"query\":\"login failed\"}"));
        relay.enqueue(responseDone("done"));

        OpenAiRealtimeResponsesGateway gateway = new OpenAiRealtimeResponsesGateway(properties, relay);
        List<String> streamed = new ArrayList<>();

        SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
            "responses-ws",
            "system",
            "user context",
            List.of(new ToolSpec("support.ticket.open", "", "route", null, null, null, new ToolPolicy(false, 0, 1000))),
            "gpt-realtime",
            0.2,
            200,
            "test-key",
            "https://api.openai.com",
            streamed::add
        );

        Assertions.assertFalse(result.terminal());
        Assertions.assertEquals(1, result.toolCalls().size());
        Assertions.assertEquals("support.ticket.open", result.toolCalls().getFirst().name());
        Assertions.assertTrue(result.message().contains("hello"));
        Assertions.assertFalse(streamed.isEmpty());
        Assertions.assertTrue(relay.closed);
        Assertions.assertEquals("wss://api.openai.com/v1/realtime", relay.lastEndpointUri);
        Assertions.assertEquals("gpt-realtime", relay.lastModel);
    }

    @Test
    void shouldTimeoutWhenNoDoneEventArrives() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.request-timeout-ms", "10");
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.poll-interval-ms", "1");

        FakeRelay relay = new FakeRelay();
        OpenAiRealtimeResponsesGateway gateway = new OpenAiRealtimeResponsesGateway(properties, relay);

        SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
            "responses-ws",
            "system",
            "user context",
            List.of(),
            "gpt-realtime",
            0.2,
            200,
            "test-key",
            "https://api.openai.com",
            null
        );

        Assertions.assertTrue(result.terminal());
        Assertions.assertTrue(result.message().contains("timed out"));
    }

    @Test
    void shouldUseConfiguredEndpointAndModelOverrides() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.endpoint-uri", "wss://relay.example.test/v1/realtime");
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.model", "gpt-realtime-custom");
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.reasoning-effort", "medium");
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.request-timeout-ms", "2000");
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.poll-interval-ms", "1");

        FakeRelay relay = new FakeRelay();
        relay.enqueue(responseDone("ok"));

        OpenAiRealtimeResponsesGateway gateway = new OpenAiRealtimeResponsesGateway(properties, relay);

        SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
            "responses-ws",
            "system",
            "user",
            List.of(),
            null,
            0.2,
            200,
            "test-key",
            "https://api.openai.com",
            null
        );

        Assertions.assertTrue(result.terminal());
        Assertions.assertEquals("wss://relay.example.test/v1/realtime", relay.lastEndpointUri);
        Assertions.assertEquals("gpt-realtime-custom", relay.lastModel);
        Assertions.assertTrue(relay.sentEvents.stream().anyMatch(eventJson -> {
            try {
                JsonNode node = MAPPER.readTree(eventJson);
                return "response.create".equals(node.path("type").asText())
                    && "medium".equals(node.path("response").path("reasoning_effort").asText());
            } catch (Exception ignored) {
                return false;
            }
        }));
    }

    private static ObjectNode event(String type, String field, String value) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", type);
        event.put(field, value);
        return event;
    }

    private static ObjectNode functionCallDone(String name, String arguments) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", "response.output_item.done");
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "function_call");
        item.put("name", name);
        item.put("arguments", arguments);
        event.set("item", item);
        return event;
    }

    private static ObjectNode responseDone(String text) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", "response.done");
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode output = MAPPER.createArrayNode();
        ObjectNode message = MAPPER.createObjectNode();
        message.put("type", "message");
        ArrayNode content = MAPPER.createArrayNode();
        ObjectNode chunk = MAPPER.createObjectNode();
        chunk.put("text", text);
        content.add(chunk);
        message.set("content", content);
        output.add(message);
        response.set("output", output);
        event.set("response", response);
        return event;
    }

    private static final class FakeRelay implements RealtimeRelayClient {

        private final ArrayDeque<ObjectNode> queue = new ArrayDeque<>();
        private boolean connected;
        private boolean closed;
        private String lastEndpointUri;
        private String lastModel;
        private final List<String> sentEvents = new ArrayList<>();

        private void enqueue(ObjectNode node) {
            queue.add(node);
        }

        @Override
        public boolean isConnected(String conversationId) {
            return connected;
        }

        @Override
        public void connect(String conversationId, String endpointUri, String model, String apiKey) {
            this.connected = true;
            this.lastEndpointUri = endpointUri;
            this.lastModel = model;
        }

        @Override
        public void connect(String conversationId,
                            String endpointUri,
                            String model,
                            String apiKey,
                            RealtimeReconnectPolicy reconnectPolicy) {
            connect(conversationId, endpointUri, model, apiKey);
        }

        @Override
        public void sendEvent(String conversationId, String eventJson) {
            sentEvents.add(eventJson);
        }

        @Override
        public ArrayNode pollEvents(String conversationId) {
            ArrayNode events = MAPPER.createArrayNode();
            ObjectNode node = queue.poll();
            if (node != null) {
                events.add(node);
            }
            return events;
        }

        @Override
        public void close(String conversationId) {
            this.connected = false;
            this.closed = true;
        }

        @Override
        public ObjectNode sessionState(String conversationId) {
            return MAPPER.createObjectNode();
        }
    }
}
