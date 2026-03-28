package io.dscope.camel.agent.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
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
        Assertions.assertEquals("wss://api.openai.com/v1/responses", relay.lastEndpointUri);
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
        Assertions.assertTrue(relay.sentEvents.stream().anyMatch(OpenAiRealtimeResponsesGatewayTest::hasMediumReasoningEffort));
    }

    @Test
    void shouldCaptureTokenUsageFromResponseDoneEvent() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.request-timeout-ms", "2000");
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.poll-interval-ms", "1");
        properties.setProperty("agent.runtime.model-cost.openai.gpt-realtime.prompt-usd-per-1m", "1.25");
        properties.setProperty("agent.runtime.model-cost.openai.gpt-realtime.completion-usd-per-1m", "2.50");

        FakeRelay relay = new FakeRelay();
        relay.enqueue(responseDone("ok", 21, 8, 29));

        OpenAiRealtimeResponsesGateway gateway = new OpenAiRealtimeResponsesGateway(properties, relay);

        SpringAiChatGateway.SpringAiChatResult result = gateway.generate(
            "responses-ws",
            "system",
            "user",
            List.of(),
            "gpt-realtime",
            0.2,
            200,
            "test-key",
            "https://api.openai.com",
            null
        );

        Assertions.assertNotNull(result.tokenUsage());
        Assertions.assertTrue(java.util.Objects.equals(21, result.tokenUsage().promptTokens()));
        Assertions.assertTrue(java.util.Objects.equals(8, result.tokenUsage().completionTokens()));
        Assertions.assertTrue(java.util.Objects.equals(29, result.tokenUsage().totalTokens()));
        Assertions.assertNotNull(result.modelUsage());
        Assertions.assertEquals("openai", result.modelUsage().provider());
        Assertions.assertEquals("gpt-realtime", result.modelUsage().model());
        Assertions.assertEquals(new BigDecimal("0.00004625"), result.modelUsage().totalCostUsd());
    }

    @Test
    void shouldIncludePromptCacheFieldsInResponsesWsRequest() {
        Properties properties = new Properties();
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.request-timeout-ms", "2000");
        properties.setProperty("agent.runtime.spring-ai.openai.responses-ws.poll-interval-ms", "1");
        properties.setProperty("agent.runtime.spring-ai.openai.prompt-cache.enabled", "true");
        properties.setProperty("agent.runtime.spring-ai.openai.prompt-cache.key", "support-cache-v1");
        properties.setProperty("agent.runtime.spring-ai.openai.prompt-cache.retention", "in-memory");

        FakeRelay relay = new FakeRelay();
        relay.enqueue(responseDone("ok"));

        OpenAiRealtimeResponsesGateway gateway = new OpenAiRealtimeResponsesGateway(properties, relay);
        gateway.generate(
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

        Assertions.assertTrue(relay.sentEvents.stream().anyMatch(eventJson -> {
            try {
                JsonNode node = MAPPER.readTree(eventJson);
                return "support-cache-v1".equals(node.path("prompt_cache_key").asText())
                    && "in-memory".equals(node.path("prompt_cache_retention").asText());
            } catch (java.io.IOException ignored) {
                return false;
            }
        }));
    }

    private static boolean hasMediumReasoningEffort(String eventJson) {
        try {
            JsonNode node = MAPPER.readTree(eventJson);
            return "response.create".equals(node.path("type").asText())
                && "medium".equals(node.path("reasoning").path("effort").asText());
        } catch (java.io.IOException ignored) {
            return false;
        }
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
        return responseDone(text, null, null, null);
    }

    private static ObjectNode responseDone(String text, Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", "response.done");
        ObjectNode response = MAPPER.createObjectNode();
        if (inputTokens != null || outputTokens != null || totalTokens != null) {
            ObjectNode usage = MAPPER.createObjectNode();
            if (inputTokens != null) {
                usage.put("input_tokens", inputTokens);
            }
            if (outputTokens != null) {
                usage.put("output_tokens", outputTokens);
            }
            if (totalTokens != null) {
                usage.put("total_tokens", totalTokens);
            }
            response.set("usage", usage);
        }
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
