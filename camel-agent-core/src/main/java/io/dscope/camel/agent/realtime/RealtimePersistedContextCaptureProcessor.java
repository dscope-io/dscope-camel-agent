package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.context.PersistedConversationContextStore;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public final class RealtimePersistedContextCaptureProcessor<T> implements Processor {

    private final PersistedConversationContextStore<T> contextStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RealtimePersistedContextCaptureProcessor(PersistedConversationContextStore<T> contextStore) {
        this.contextStore = contextStore;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        if (contextStore == null) {
            return;
        }
        String conversationId = conversationId(exchange);
        if (conversationId.isBlank()) {
            return;
        }
        String body = exchange.getMessage().getBody(String.class);
        if (body == null || body.isBlank()) {
            return;
        }
        JsonNode root = objectMapper.readTree(body);
        String assistantMessage = text(root, "assistantMessage");
        if (!assistantMessage.isBlank()) {
            contextStore.merge(conversationId, conversationId, assistantMessage);
        }
    }

    private static String conversationId(Exchange exchange) {
        Object value = exchange.getMessage().getHeader("conversationId");
        return value == null ? "" : String.valueOf(value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }
}