package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.context.PersistedConversationContextStore;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RealtimePersistedContextProcessor<T> implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimePersistedContextProcessor.class);
    private final PersistedConversationContextStore<T> contextStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RealtimePersistedContextProcessor(PersistedConversationContextStore<T> contextStore) {
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
        if (!root.isObject()) {
            return;
        }
        String transcript = firstNonBlank(text(root, "transcript"), text(root.path("payload"), "transcript"));
        if (transcript.isBlank()) {
            return;
        }
        T context = contextStore.merge(conversationId, conversationId, transcript);
        String hint = contextStore.renderHint(context);
        if (hint.isBlank()) {
            return;
        }
        String enrichedTranscript = hint + "\n\n" + transcript;
        ObjectNode object = (ObjectNode) root;
        if (object.has("transcript")) {
            object.put("transcript", enrichedTranscript);
        } else if (object.path("payload").isObject()) {
            ((ObjectNode) object.path("payload")).put("transcript", enrichedTranscript);
        } else {
            object.put("transcript", enrichedTranscript);
        }
        exchange.getMessage().setBody(objectMapper.writeValueAsString(object));
        LOGGER.info("Realtime persisted context injected: conversationId={}, transcriptChars={}", conversationId, enrichedTranscript.length());
    }

    private static String conversationId(Exchange exchange) {
        Object value = exchange.getMessage().getHeader("conversationId");
        return value == null ? "" : String.valueOf(value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? stringValue(second) : first;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}