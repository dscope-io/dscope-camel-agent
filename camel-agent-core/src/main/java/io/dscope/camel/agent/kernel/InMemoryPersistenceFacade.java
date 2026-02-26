package io.dscope.camel.agent.kernel;

import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.DynamicRouteState;
import io.dscope.camel.agent.model.TaskState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPersistenceFacade implements PersistenceFacade {

    private final Map<String, List<AgentEvent>> conversations = new ConcurrentHashMap<>();
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Map<String, DynamicRouteState> dynamicRoutes = new ConcurrentHashMap<>();
    private final Map<String, TaskLock> taskLocks = new ConcurrentHashMap<>();

    @Override
    public void appendEvent(AgentEvent event, String idempotencyKey) {
        conversations.computeIfAbsent(event.conversationId(), key -> new ArrayList<>()).add(event);
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
        TaskLock current = taskLocks.get(taskId);
        if (current != null && current.expiresAt().isAfter(now) && !current.ownerId().equals(ownerId)) {
            return false;
        }
        taskLocks.put(taskId, new TaskLock(ownerId, now.plusSeconds(Math.max(leaseSeconds, 1))));
        return true;
    }

    @Override
    public void releaseTaskClaim(String taskId, String ownerId) {
        TaskLock current = taskLocks.get(taskId);
        if (current != null && current.ownerId().equals(ownerId)) {
            taskLocks.remove(taskId);
        }
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
