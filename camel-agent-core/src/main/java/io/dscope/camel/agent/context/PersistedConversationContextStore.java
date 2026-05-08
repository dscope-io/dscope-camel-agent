package io.dscope.camel.agent.context;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;

public final class PersistedConversationContextStore<T> {

    private static final Logger LOG = LoggerFactory.getLogger(PersistedConversationContextStore.class);

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;
    private final String eventType;
    private final Class<T> contextType;
    private final T emptyContext;
    private final ConversationContextExtractor<T> extractor;
    private final ConversationContextMerger<T> merger;
    private final ConversationContextRenderer<T> renderer;
    private final ConcurrentMap<String, T> fallbackContexts = new ConcurrentHashMap<>();

    public PersistedConversationContextStore(PersistenceFacade persistenceFacade,
                                             ObjectMapper objectMapper,
                                             String eventType,
                                             Class<T> contextType,
                                             T emptyContext,
                                             ConversationContextExtractor<T> extractor,
                                             ConversationContextMerger<T> merger,
                                             ConversationContextRenderer<T> renderer) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
        this.eventType = eventType == null || eventType.isBlank() ? "agent.conversation.context" : eventType;
        this.contextType = contextType;
        this.emptyContext = emptyContext;
        this.extractor = extractor;
        this.merger = merger;
        this.renderer = renderer;
    }

    public T load(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return emptyContext;
        }
        try {
            List<AgentEvent> events = persistenceFacade.loadConversation(conversationId, 200);
            T context = emptyContext;
            for (AgentEvent event : events) {
                if (!eventType.equals(event.type())) {
                    continue;
                }
                context = merger.merge(context, fromPayload(event.payload()));
            }
            return isBlank(context) ? fallbackContexts.getOrDefault(conversationId, emptyContext) : context;
        } catch (RuntimeException exception) {
            LOG.warn("Persisted context load failed: conversationId={}, eventType={}, error={}", conversationId, eventType, exception.getMessage());
            return fallbackContexts.getOrDefault(conversationId, emptyContext);
        }
    }

    public T merge(String conversationId, String sessionId, String text) {
        if (conversationId == null || conversationId.isBlank() || text == null || text.isBlank()) {
            return load(conversationId);
        }
        T extracted = extractor.extract(text);
        if (isBlank(extracted)) {
            return load(conversationId);
        }
        T merged = merger.merge(load(conversationId), extracted);
        fallbackContexts.put(conversationId, merged);
        try {
            persistenceFacade.appendEvent(
                new AgentEvent(conversationId, blankToNull(sessionId), eventType, objectMapper.valueToTree(merged), Instant.now()),
                sessionId
            );
        } catch (RuntimeException exception) {
            LOG.warn("Persisted context store failed: conversationId={}, eventType={}, error={}", conversationId, eventType, exception.getMessage());
        }
        return merged;
    }

    public String renderHint(T context) {
        return renderer == null ? "" : renderer.render(context);
    }

    public boolean isBlank(T context) {
        return context == null || context.equals(emptyContext);
    }

    private T fromPayload(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return emptyContext;
        }
        return objectMapper.convertValue(payload, contextType);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}