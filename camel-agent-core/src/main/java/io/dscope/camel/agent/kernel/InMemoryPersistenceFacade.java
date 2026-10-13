package io.dscope.camel.agent.kernel;

import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.DynamicRouteState;
import io.dscope.camel.agent.model.TaskState;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPersistenceFacade implements PersistenceFacade {

    private static final int DEFAULT_MAX_EVENTS_PER_CONVERSATION = 500;
    private final Map<String, List<AgentEvent>> conversations = new ConcurrentHashMap<>();
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Map<String, DynamicRouteState> dynamicRoutes = new ConcurrentHashMap<>();
    private final Map<String, TaskLock> taskLocks = new ConcurrentHashMap<>();
    private final int maxEventsPerConversation;

    public InMemoryPersistenceFacade() {
        this(DEFAULT_MAX_EVENTS_PER_CONVERSATION);
    }

    public InMemoryPersistenceFacade(int maxEventsPerConversation) {
        this.maxEventsPerConversation = Math.max(1, maxEventsPerConversation);
    }

    @Override
    public void appendEvent(AgentEvent event, String idempotencyKey) {
        List<AgentEvent> events = conversations.computeIfAbsent(event.conversationId(), key -> new CopyOnWriteArrayList<>());
        events.add(event);
        while (events.size() > maxEventsPerConversation) {
            events.removeFirst();
        }
    }

    @Override
    public List<AgentEvent> loadConversation(String conversationId, int limit) {
        return conversations.getOrDefault(conversationId, List.of())
            .stream()
            .sorted(Comparator.comparing(AgentEvent::timestamp))
            .skip(Math.max(0, conversations.getOrDefault(conversationId, List.of()).size() - limit))
            .toList();
    }

    @Override
    public List<String> listConversationIds(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return conversations.entrySet().stream()
            .sorted((left, right) -> latestTimestamp(right.getValue()).compareTo(latestTimestamp(left.getValue())))
            .map(Map.Entry::getKey)
            .limit(limit)
            .toList();
    }

    @Override
    public void saveTask(TaskState taskState) {
        tasks.put(taskState.taskId(), taskState);
    }

    @Override
    public Optional<TaskState> loadTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public void saveDynamicRoute(DynamicRouteState routeState) {
        dynamicRoutes.put(routeState.routeInstanceId(), routeState);
    }

    @Override
    public Optional<DynamicRouteState> loadDynamicRoute(String routeInstanceId) {
        return Optional.ofNullable(dynamicRoutes.get(routeInstanceId));
    }

    @Override
    public List<DynamicRouteState> loadDynamicRoutes(int limit) {
        return dynamicRoutes.values().stream().limit(limit).toList();
    }

    @Override
    public boolean tryClaimTask(String taskId, String ownerId, int leaseSeconds) {
        Instant now = Instant.now();
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        taskLocks.compute(taskId, (ignored, current) -> {
            if (current != null && current.expiresAt().isAfter(now) && !current.ownerId().equals(ownerId)) {
                return current;
            }
            claimed.set(true);
            return new TaskLock(ownerId, now.plusSeconds(Math.max(leaseSeconds, 1)));
        });
        return claimed.get();
    }

    @Override
    public void releaseTaskClaim(String taskId, String ownerId) {
        taskLocks.computeIfPresent(taskId, (ignored, current) -> current.ownerId().equals(ownerId) ? null : current);
    }

    @Override
    public boolean isTaskClaimedBy(String taskId, String ownerId) {
        TaskLock current = taskLocks.get(taskId);
        return current != null && current.ownerId().equals(ownerId) && current.expiresAt().isAfter(Instant.now());
    }

    private record TaskLock(String ownerId, Instant expiresAt) {
    }

    private static Instant latestTimestamp(List<AgentEvent> events) {
        if (events == null || events.isEmpty()) {
            return Instant.EPOCH;
        }
        AgentEvent event = events.get(events.size() - 1);
        return event.timestamp() == null ? Instant.EPOCH : event.timestamp();
    }
}
