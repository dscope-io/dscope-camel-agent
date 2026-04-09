package io.dscope.camel.agent.telephony.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TelephonyOnboardingPersistenceService {

    public static final String EVENT_TYPE = "sip.onboarding.saved";

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;

    public TelephonyOnboardingPersistenceService(PersistenceFacade persistenceFacade, ObjectMapper objectMapper) {
        this.persistenceFacade = Objects.requireNonNull(persistenceFacade, "persistenceFacade");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void save(String conversationId, ObjectNode payload) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("Missing onboarding conversationId");
        }
        ObjectNode persisted = payload == null ? objectMapper.createObjectNode() : payload.deepCopy();
        persisted.put("savedAt", Instant.now().toString());
        persistenceFacade.appendEvent(
            new AgentEvent(conversationId.trim(), null, EVENT_TYPE, persisted, Instant.now()),
            "sip-onboarding-" + UUID.randomUUID()
        );
    }

    public Optional<ObjectNode> loadLatest(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        List<AgentEvent> events = persistenceFacade.loadConversation(conversationId.trim(), 200);
        for (int index = events.size() - 1; index >= 0; index--) {
            AgentEvent event = events.get(index);
            if (event == null || !EVENT_TYPE.equals(event.type())) {
                continue;
            }
            JsonNode payload = event.payload();
            if (payload instanceof ObjectNode objectNode) {
                return Optional.of(objectNode.deepCopy());
            }
            ObjectNode copy = objectMapper.createObjectNode();
            if (payload != null && !payload.isMissingNode() && !payload.isNull()) {
                copy.set("payload", payload.deepCopy());
            }
            return Optional.of(copy);
        }
        return Optional.empty();
    }

    public static String conversationId(String tenantId, String agentId) {
        return "telephony:onboarding:" + normalizeSegment(tenantId, "default") + ":" + normalizeSegment(agentId, "default");
    }

    private static String normalizeSegment(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "-");
    }
}