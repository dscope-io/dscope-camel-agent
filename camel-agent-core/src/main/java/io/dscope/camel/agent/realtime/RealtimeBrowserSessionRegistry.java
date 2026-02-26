package io.dscope.camel.agent.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RealtimeBrowserSessionRegistry {

    private static final long DEFAULT_TTL_MS = 10 * 60 * 1000L;

    private final ObjectMapper objectMapper;
    private final Map<String, SessionEntry> sessionByConversationId;
    private final long ttlMs;

    public RealtimeBrowserSessionRegistry() {
        this(DEFAULT_TTL_MS);
    }

    public RealtimeBrowserSessionRegistry(long ttlMs) {
        this.objectMapper = new ObjectMapper();
        this.sessionByConversationId = new ConcurrentHashMap<>();
        this.ttlMs = ttlMs <= 0 ? DEFAULT_TTL_MS : ttlMs;
    }

    public void putSession(String conversationId, ObjectNode session) {
        if (conversationId == null || conversationId.isBlank() || session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        sessionByConversationId.put(conversationId, new SessionEntry(session.deepCopy(), now + ttlMs));
        evictExpired(now);
    }

    public void mergeSession(String conversationId, JsonNode update) {
        if (conversationId == null || conversationId.isBlank() || update == null || !update.isObject()) {
            return;
        }
        long now = System.currentTimeMillis();
        sessionByConversationId.compute(conversationId, (key, existingEntry) -> {
            ObjectNode target;
            if (existingEntry == null || existingEntry.expiresAtMs() <= now) {
                target = defaultSession(null, null, conversationId);
            } else {
                target = existingEntry.session().deepCopy();
            }
            mergeObject(target, update);
            return new SessionEntry(target, now + ttlMs);
        });
        evictExpired(now);
    }

    public ObjectNode getSession(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        SessionEntry entry = sessionByConversationId.get(conversationId);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMs() <= now) {
            sessionByConversationId.remove(conversationId, entry);
            return null;
        }
        return entry.session().deepCopy();
    }

    public Set<String> conversationIds() {
        long now = System.currentTimeMillis();
        evictExpired(now);
        return Set.copyOf(sessionByConversationId.keySet());
    }

    public void removeSession(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        sessionByConversationId.remove(conversationId);
    }

    public ObjectNode defaultSession(String model, String voice, String conversationId) {
        ObjectNode session = objectMapper.createObjectNode();
        session.put("type", "realtime");
        if (model != null && !model.isBlank()) {
            session.put("model", model);
        }
        if (voice != null && !voice.isBlank()) {
            ObjectNode audio = objectMapper.createObjectNode();
            ObjectNode output = objectMapper.createObjectNode();
            output.put("voice", voice);
            audio.set("output", output);
            session.set("audio", audio);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("conversationId", conversationId);
            session.set("metadata", metadata);
        }
        return session;
    }

    private void evictExpired(long now) {
        sessionByConversationId.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
    }

    private void mergeObject(ObjectNode target, JsonNode source) {
        if (target == null || source == null || !source.isObject()) {
            return;
        }
        source.fields().forEachRemaining(entry -> {
            String field = entry.getKey();
            JsonNode incoming = entry.getValue();
            JsonNode existing = target.get(field);
            if (incoming != null && incoming.isObject() && existing != null && existing.isObject()) {
                mergeObject((ObjectNode) existing, incoming);
            } else if (incoming != null) {
                target.set(field, incoming.deepCopy());
            }
        });
    }

    private record SessionEntry(ObjectNode session, long expiresAtMs) {
    }
}
