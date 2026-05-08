package io.dscope.camel.agent.agui;

import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dscope.camel.agent.context.PersistedConversationContextStore;

public final class AgUiPersistedContextProcessor<T> implements Processor {

    private static final Logger LOG = LoggerFactory.getLogger(AgUiPersistedContextProcessor.class);

    private final PersistedConversationContextStore<T> contextStore;

    private final String marker;

    
    public AgUiPersistedContextProcessor(PersistedConversationContextStore<T> contextStore) {
        this(contextStore, "[server persisted context]");
    }

    public AgUiPersistedContextProcessor(PersistedConversationContextStore<T> contextStore, String marker) {
        this.contextStore = contextStore;
        this.marker = marker == null || marker.isBlank() ? "[server persisted context]" : marker;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        if (contextStore == null) {
            return;
        }
        Map<String, Object> params = exchange.getProperty(AgentAgUiExchangeProperties.PARAMS, Map.class);
        if (params == null || params.isEmpty()) {
            return;
        }
        String text = stringValue(params.get("text"));
        if (text.isBlank() || text.contains(marker)) {
            return;
        }
        String conversationId = firstNonBlank(stringValue(params.get("threadId")), stringValue(params.get("sessionId")));
        String sessionId = firstNonBlank(stringValue(params.get("sessionId")), conversationId);
        if (conversationId.isBlank()) {
            return;
        }

        T context = contextStore.merge(conversationId, sessionId, text);
        String hint = contextStore.renderHint(context);
        if (hint.isBlank()) {
            return;
        }

        String enriched = hint + "\n\n" + text;
        params.put("text", enriched);
        exchange.setProperty(AgentAgUiExchangeProperties.PARAMS, params);
        LOG.info("AGUI persisted context injected: conversationId={}, sessionId={}, textChars={}", conversationId, sessionId, enriched.length());
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? stringValue(second) : first;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}