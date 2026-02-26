package io.dscope.camel.agent.api;

import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.DynamicRouteState;
import io.dscope.camel.agent.model.TaskState;
import java.util.List;
import java.util.Optional;

public interface PersistenceFacade {

    void appendEvent(AgentEvent event, String idempotencyKey);

    List<AgentEvent> loadConversation(String conversationId, int limit);

    default List<String> listConversationIds(int limit) {
        return List.of();
    }

    void saveTask(TaskState taskState);

    Optional<TaskState> loadTask(String taskId);

    void saveDynamicRoute(DynamicRouteState routeState);

    Optional<DynamicRouteState> loadDynamicRoute(String routeInstanceId);

    List<DynamicRouteState> loadDynamicRoutes(int limit);

    boolean tryClaimTask(String taskId, String ownerId, int leaseSeconds);

    void releaseTaskClaim(String taskId, String ownerId);

    boolean isTaskClaimedBy(String taskId, String ownerId);
}
