package io.dscope.camel.agent.agui;

import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import io.dscope.camel.agent.context.PersistedConversationContextStore;

public final class AgUiPersistedContextCaptureProcessor<T> implements Processor {

    private final PersistedConversationContextStore<T> contextStore;

    public AgUiPersistedContextCaptureProcessor(PersistedConversationContextStore<T> contextStore) {
        this.contextStore = contextStore;
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
        String conversationId = firstNonBlank(stringValue(params.get("threadId")), stringValue(params.get("sessionId")));
        String sessionId = firstNonBlank(stringValue(params.get("sessionId")), conversationId);
        if (!conversationId.isBlank()) {
            contextStore.merge(conversationId, sessionId, stringValue(params.get("text")));
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? stringValue(second) : first;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}