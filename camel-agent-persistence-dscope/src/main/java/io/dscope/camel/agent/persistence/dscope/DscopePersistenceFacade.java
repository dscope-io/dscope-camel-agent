package io.dscope.camel.agent.persistence.dscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.AuditGranularity;
import io.dscope.camel.agent.model.DynamicRouteState;
import io.dscope.camel.agent.model.TaskState;
import io.dscope.camel.agent.model.TaskStatus;
import io.dscope.camel.persistence.core.FlowStateStore;
import io.dscope.camel.persistence.core.PersistedEvent;
import io.dscope.camel.persistence.core.exception.OptimisticConflictException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class DscopePersistenceFacade implements PersistenceFacade {

    public static final String FLOW_CONVERSATION = "agent.conversation";
    public static final String FLOW_TASK = "agent.task";
    public static final String FLOW_DYNAMIC_ROUTE = "agent.dynamicRoute";
    public static final String FLOW_TASK_LOCK = "agent.task.lock";

    private final FlowStateStore flowStateStore;
    private final ObjectMapper objectMapper;
    private final AuditGranularity auditGranularity;

    public DscopePersistenceFacade(FlowStateStore flowStateStore, ObjectMapper objectMapper) {
        this(flowStateStore, objectMapper, AuditGranularity.INFO);
    }

    public DscopePersistenceFacade(FlowStateStore flowStateStore, ObjectMapper objectMapper, AuditGranularity auditGranularity) {
        this.flowStateStore = flowStateStore;
        this.objectMapper = objectMapper;
        this.auditGranularity = auditGranularity == null ? AuditGranularity.INFO : auditGranularity;
    }

    @Override
    public void appendEvent(AgentEvent event, String idempotencyKey) {
        if (!shouldPersist(event)) {
            return;
        }
        final int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long expectedVersion = resolveConversationVersion(event.conversationId());
            PersistedEvent persistedEvent = new PersistedEvent(
                UUID.randomUUID().toString(),
                FLOW_CONVERSATION,
                event.conversationId(),
                expectedVersion + 1,
                event.type(),
                auditPayload(event, idempotencyKey),
                event.timestamp().toString(),
                idempotencyKey
            );
            try {
                flowStateStore.appendEvents(FLOW_CONVERSATION, event.conversationId(), expectedVersion, List.of(persistedEvent), idempotencyKey);
                return;
            } catch (OptimisticConflictException ex) {
                if (attempt == maxAttempts) {
                    throw ex;
                }
            }
        }
    }

    @Override
    public List<AgentEvent> loadConversation(String conversationId, int limit) {
        List<PersistedEvent> events = flowStateStore.readEvents(FLOW_CONVERSATION, conversationId, 0L, limit);
        List<AgentEvent> result = new ArrayList<>();
        for (PersistedEvent event : events) {
            JsonNode payload = unwrapPayload(event.payload());
            result.add(new AgentEvent(conversationId, null, event.eventType(), payload, Instant.parse(event.occurredAt())));
        }
        return result;
    }

    @Override
    public void saveTask(TaskState taskState) {
        var rehydrated = flowStateStore.rehydrate(FLOW_TASK, taskState.taskId());
        long nextVersion = rehydrated.envelope() == null ? 1L : rehydrated.envelope().version() + 1L;
        JsonNode snapshot = objectMapper.createObjectNode()
            .put("taskId", taskState.taskId())
            .put("conversationId", taskState.conversationId())
            .put("status", taskState.status().name())
            .put("checkpoint", taskState.checkpoint())
            .put("nextWakeup", taskState.nextWakeup() == null ? null : taskState.nextWakeup().toString())
            .put("retries", taskState.retries())
            .put("result", taskState.result());
        flowStateStore.writeSnapshot(FLOW_TASK, taskState.taskId(), nextVersion, snapshot, Map.of("conversationId", taskState.conversationId()));
    }

    @Override
    public Optional<TaskState> loadTask(String taskId) {
        JsonNode snapshot = flowStateStore.rehydrate(FLOW_TASK, taskId).envelope().snapshot();
        if (snapshot == null || snapshot.isMissingNode() || snapshot.isNull() || snapshot.isEmpty()) {
            return Optional.empty();
        }
        Instant nextWakeup = snapshot.path("nextWakeup").isMissingNode() || snapshot.path("nextWakeup").isNull()
            ? null : Instant.parse(snapshot.path("nextWakeup").asText());
        String statusText = snapshot.path("status").asText(TaskStatus.FAILED.name());
        TaskStatus status = TaskStatus.valueOf(statusText);
        return Optional.of(new TaskState(
            snapshot.path("taskId").asText(taskId),
            snapshot.path("conversationId").asText("unknown"),
            status,
            snapshot.path("checkpoint").asText(null),
            nextWakeup,
            snapshot.path("retries").asInt(0),
            snapshot.path("result").asText(null)
        ));
    }

    @Override
    public void saveDynamicRoute(DynamicRouteState routeState) {
        var rehydrated = flowStateStore.rehydrate(FLOW_DYNAMIC_ROUTE, routeState.routeInstanceId());
        long nextVersion = rehydrated.envelope() == null ? 1L : rehydrated.envelope().version() + 1L;
        JsonNode snapshot = objectMapper.createObjectNode()
            .put("routeInstanceId", routeState.routeInstanceId())
            .put("templateId", routeState.templateId())
            .put("routeId", routeState.routeId())
            .put("status", routeState.status())
            .put("conversationId", routeState.conversationId())
            .put("createdAt", routeState.createdAt() == null ? null : routeState.createdAt().toString())
            .put("expiresAt", routeState.expiresAt() == null ? null : routeState.expiresAt().toString());
        flowStateStore.writeSnapshot(FLOW_DYNAMIC_ROUTE, routeState.routeInstanceId(), nextVersion, snapshot, Map.of("updatedAt", Instant.now().toString()));
    }

    @Override
    public Optional<DynamicRouteState> loadDynamicRoute(String routeInstanceId) {
        JsonNode snapshot = flowStateStore.rehydrate(FLOW_DYNAMIC_ROUTE, routeInstanceId).envelope().snapshot();
        if (snapshot == null || snapshot.isMissingNode() || snapshot.isNull() || snapshot.isEmpty()) {
            return Optional.empty();
        }
        Instant createdAt = snapshot.path("createdAt").isMissingNode() || snapshot.path("createdAt").isNull()
            ? null : Instant.parse(snapshot.path("createdAt").asText());
        Instant expiresAt = snapshot.path("expiresAt").isMissingNode() || snapshot.path("expiresAt").isNull()
            ? null : Instant.parse(snapshot.path("expiresAt").asText());
        return Optional.of(new DynamicRouteState(
            snapshot.path("routeInstanceId").asText(routeInstanceId),
            snapshot.path("templateId").asText(),
            snapshot.path("routeId").asText(),
            snapshot.path("status").asText(),
            snapshot.path("conversationId").asText(null),
            createdAt,
            expiresAt
        ));
    }

    @Override
    public List<DynamicRouteState> loadDynamicRoutes(int limit) {
        // FlowStateStore does not provide listing by flowType; callers should resolve known ids externally.
        return List.of();
    }

    @Override
    public boolean tryClaimTask(String taskId, String ownerId, int leaseSeconds) {
        Instant now = Instant.now();
        TaskLockState lockState = resolveTaskLock(taskId);
        if (lockState.active(now) && !ownerId.equals(lockState.ownerId())) {
            return false;
        }

        var rehydrated = flowStateStore.rehydrate(FLOW_TASK_LOCK, taskId);
        long expectedVersion = rehydrated.envelope() == null ? 0L : rehydrated.envelope().version();
        Instant leaseUntil = now.plusSeconds(Math.max(1, leaseSeconds));
        ObjectNode payload = objectMapper.createObjectNode()
            .put("ownerId", ownerId)
            .put("leaseUntil", leaseUntil.toString())
            .put("claimedAt", now.toString());
        PersistedEvent claim = new PersistedEvent(
            UUID.randomUUID().toString(),
            FLOW_TASK_LOCK,
            taskId,
            expectedVersion + 1,
            "task.lock.claim",
            payload,
            now.toString(),
            ownerId + ":" + leaseUntil.toString()
        );
        try {
            flowStateStore.appendEvents(FLOW_TASK_LOCK, taskId, expectedVersion, List.of(claim), claim.idempotencyKey());
            return true;
        } catch (OptimisticConflictException ex) {
            return false;
        }
    }

    @Override
    public void releaseTaskClaim(String taskId, String ownerId) {
        TaskLockState lockState = resolveTaskLock(taskId);
        if (!ownerId.equals(lockState.ownerId())) {
            return;
        }
        var rehydrated = flowStateStore.rehydrate(FLOW_TASK_LOCK, taskId);
        long expectedVersion = rehydrated.envelope() == null ? 0L : rehydrated.envelope().version();
        ObjectNode payload = objectMapper.createObjectNode()
            .put("ownerId", ownerId)
            .put("releasedAt", Instant.now().toString());
        PersistedEvent release = new PersistedEvent(
            UUID.randomUUID().toString(),
            FLOW_TASK_LOCK,
            taskId,
            expectedVersion + 1,
            "task.lock.release",
            payload,
            Instant.now().toString(),
            ownerId + ":release"
        );
        try {
            flowStateStore.appendEvents(FLOW_TASK_LOCK, taskId, expectedVersion, List.of(release), release.idempotencyKey());
        } catch (OptimisticConflictException ignored) {
            // Best effort release; conflicting release does not compromise safety.
        }
    }

    @Override
    public boolean isTaskClaimedBy(String taskId, String ownerId) {
        TaskLockState lockState = resolveTaskLock(taskId);
        return ownerId.equals(lockState.ownerId()) && lockState.active(Instant.now());
    }

    private boolean shouldPersist(AgentEvent event) {
        return switch (auditGranularity) {
            case NONE -> false;
            case INFO -> true;
            case ERROR -> true;
            case DEBUG -> true;
        };
    }

    private boolean isErrorEvent(AgentEvent event) {
        String type = event.type() == null ? "" : event.type().toLowerCase();
        return type.contains("error") || type.contains("failed") || type.equals("policy.blocked");
    }

    private JsonNode unwrapPayload(JsonNode stored) {
        if (stored != null && stored.isObject() && stored.has("payload")) {
            return stored.path("payload");
        }
        return stored;
    }

    private JsonNode auditPayload(AgentEvent event, String idempotencyKey) {
        return switch (auditGranularity) {
            case NONE -> objectMapper.nullNode();
            case INFO -> objectMapper.createObjectNode()
                .put("step", event.type())
                .put("timestamp", event.timestamp().toString());
            case ERROR -> {
                ObjectNode node = objectMapper.createObjectNode()
                    .put("step", event.type())
                    .put("timestamp", event.timestamp().toString());
                node.set("error", isErrorEvent(event) ? event.payload() : objectMapper.nullNode());
                yield node;
            }
            case DEBUG -> {
                ObjectNode metadata = objectMapper.createObjectNode()
                    .put("conversationId", event.conversationId())
                    .put("taskId", event.taskId())
                    .put("timestamp", event.timestamp().toString())
                    .put("idempotencyKey", idempotencyKey);
                ObjectNode node = objectMapper.createObjectNode()
                    .put("step", event.type());
                node.set("payload", event.payload());
                node.set("metadata", metadata);
                yield node;
            }
        };
    }

    private TaskLockState resolveTaskLock(String taskId) {
        List<PersistedEvent> events = flowStateStore.readEvents(FLOW_TASK_LOCK, taskId, 0L, 500);
        String ownerId = null;
        Instant leaseUntil = null;
        for (PersistedEvent event : events) {
            if ("task.lock.claim".equals(event.eventType())) {
                ownerId = event.payload().path("ownerId").asText(null);
                String leaseText = event.payload().path("leaseUntil").asText(null);
                leaseUntil = leaseText == null || leaseText.isBlank() ? null : Instant.parse(leaseText);
            } else if ("task.lock.release".equals(event.eventType())) {
                String releasedBy = event.payload().path("ownerId").asText(null);
                if (ownerId != null && ownerId.equals(releasedBy)) {
                    ownerId = null;
                    leaseUntil = null;
                }
            }
        }
        return new TaskLockState(ownerId, leaseUntil);
    }

    private long resolveConversationVersion(String conversationId) {
        var rehydrated = flowStateStore.rehydrate(FLOW_CONVERSATION, conversationId);
        long envelopeVersion = rehydrated.envelope() == null ? 0L : rehydrated.envelope().version();
        long eventVersion = flowStateStore.readEvents(FLOW_CONVERSATION, conversationId, 0L, 10_000).size();
        return Math.max(envelopeVersion, eventVersion);
    }

    private record TaskLockState(String ownerId, Instant leaseUntil) {
        boolean active(Instant now) {
            return ownerId != null && leaseUntil != null && leaseUntil.isAfter(now);
        }
    }
}
