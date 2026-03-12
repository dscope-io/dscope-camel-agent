package io.dscope.camel.agent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.TaskState;
import io.dscope.camel.agent.model.TaskStatus;
import io.dscope.camel.a2a.model.Message;
import io.dscope.camel.a2a.model.Task;
import io.dscope.camel.a2a.processor.A2AInvalidParamsException;
import io.dscope.camel.a2a.service.InMemoryTaskEventService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public final class AgentA2ATaskRepository {

    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;
    private final InMemoryTaskEventService eventService;
    private final ConcurrentMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<io.dscope.camel.a2a.model.TaskStatus>> historyByTaskId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idempotencyToTaskId = new ConcurrentHashMap<>();

    public AgentA2ATaskRepository(PersistenceFacade persistenceFacade,
                                  ObjectMapper objectMapper,
                                  InMemoryTaskEventService eventService) {
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
        this.eventService = eventService;
    }

    public Task existingByIdempotency(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String taskId = idempotencyToTaskId.get(idempotencyKey.trim());
        return taskId == null ? null : getTask(taskId);
    }

    public Task createCompletedTask(String taskId,
                                    String idempotencyKey,
                                    String conversationId,
                                    Message requestMessage,
                                    Message responseMessage,
                                    Map<String, Object> metadata) {
        String now = Instant.now().toString();
        Task task = new Task();
        task.setTaskId(taskId);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setLatestMessage(responseMessage);
        task.setMessages(List.of(requestMessage, responseMessage));
        task.setArtifacts(List.of());
        task.setMetadata(metadata == null ? Map.of() : Map.copyOf(metadata));

        List<io.dscope.camel.a2a.model.TaskStatus> history = new ArrayList<>();
        history.add(status(io.dscope.camel.a2a.model.TaskState.CREATED, "Task accepted"));
        history.add(status(io.dscope.camel.a2a.model.TaskState.RUNNING, "Camel Agent is processing the message"));
        io.dscope.camel.a2a.model.TaskStatus completed =
            status(io.dscope.camel.a2a.model.TaskState.COMPLETED, "Camel Agent completed the task");
        history.add(completed);
        task.setStatus(copyStatus(completed));

        tasks.put(taskId, task);
        historyByTaskId.put(taskId, history);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyToTaskId.putIfAbsent(idempotencyKey.trim(), taskId);
        }
        publishHistory(task, history);
        persistTask(conversationId, task);
        return copyTask(task);
    }

    public Task getTask(String taskId) {
        Task current = tasks.get(taskId);
        if (current != null) {
            return copyTask(current);
        }
        Optional<TaskState> persisted = persistenceFacade == null ? Optional.empty() : persistenceFacade.loadTask(taskId);
        if (persisted.isEmpty() || persisted.get().result() == null || persisted.get().result().isBlank()) {
            throw new A2AInvalidParamsException("Task not found: " + taskId);
        }
        try {
            Task task = objectMapper.readValue(persisted.get().result(), Task.class);
            tasks.putIfAbsent(taskId, task);
            return copyTask(task);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize A2A task: " + taskId, e);
        }
    }

    public List<Task> listTasks(String state, Integer limit) {
        List<Task> resolved = tasks.values().stream()
            .filter(task -> state == null || state.isBlank()
                || task.getStatus() != null
                && task.getStatus().getState() != null
                && task.getStatus().getState().name().equalsIgnoreCase(state))
            .map(this::copyTask)
            .collect(Collectors.toCollection(ArrayList::new));
        if (limit != null && limit > 0 && resolved.size() > limit) {
            return resolved.subList(0, limit);
        }
        return resolved;
    }

    public Task cancelTask(String taskId, String reason) {
        Task current = getTask(taskId);
        if (current.getStatus() != null && current.getStatus().getState() == io.dscope.camel.a2a.model.TaskState.CANCELED) {
            return current;
        }
        io.dscope.camel.a2a.model.TaskStatus canceled =
            status(io.dscope.camel.a2a.model.TaskState.CANCELED, reason == null || reason.isBlank() ? "Task canceled" : reason);
        current.setStatus(canceled);
        current.setUpdatedAt(Instant.now().toString());
        tasks.put(taskId, current);
        historyByTaskId.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add(copyStatus(canceled));
        eventService.publishTaskUpdate(current);
        persistTask(metadataText(current, "camelAgent.localConversationId"), current);
        return copyTask(current);
    }

    public List<io.dscope.camel.a2a.model.TaskStatus> history(String taskId) {
        getTask(taskId);
        return historyByTaskId.getOrDefault(taskId, List.of()).stream().map(this::copyStatus).toList();
    }

    public void appendConversationEvent(String conversationId,
                                        String taskId,
                                        String type,
                                        Map<String, Object> payload) {
        if (persistenceFacade == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        persistenceFacade.appendEvent(
            new AgentEvent(conversationId, taskId, type, objectMapper.valueToTree(payload == null ? Map.of() : payload), Instant.now()),
            UUID.randomUUID().toString()
        );
    }

    private void publishHistory(Task task, List<io.dscope.camel.a2a.model.TaskStatus> history) {
        for (io.dscope.camel.a2a.model.TaskStatus status : history) {
            Task copy = copyTask(task);
            copy.setStatus(copyStatus(status));
            eventService.publishTaskUpdate(copy);
        }
    }

    private void persistTask(String conversationId, Task task) {
        if (persistenceFacade == null || task == null || task.getTaskId() == null || task.getTaskId().isBlank()) {
            return;
        }
        try {
            persistenceFacade.saveTask(new TaskState(
                task.getTaskId(),
                conversationId == null ? "" : conversationId,
                toAgentTaskStatus(task.getStatus()),
                task.getStatus() == null || task.getStatus().getState() == null ? "" : task.getStatus().getState().name(),
                null,
                0,
                objectMapper.writeValueAsString(task)
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist A2A task " + task.getTaskId(), e);
        }
    }

    private TaskStatus toAgentTaskStatus(io.dscope.camel.a2a.model.TaskStatus status) {
        if (status == null || status.getState() == null) {
            return TaskStatus.CREATED;
        }
        return switch (status.getState()) {
            case CREATED, QUEUED -> TaskStatus.CREATED;
            case RUNNING -> TaskStatus.STARTED;
            case WAITING -> TaskStatus.WAITING;
            case COMPLETED -> TaskStatus.FINISHED;
            case FAILED, CANCELED -> TaskStatus.FAILED;
        };
    }

    private io.dscope.camel.a2a.model.TaskStatus status(io.dscope.camel.a2a.model.TaskState state, String message) {
        io.dscope.camel.a2a.model.TaskStatus status = new io.dscope.camel.a2a.model.TaskStatus();
        status.setState(state);
        status.setMessage(message);
        status.setUpdatedAt(Instant.now().toString());
        return status;
    }

    private String metadataText(Task task, String path) {
        try {
            JsonNode metadata = objectMapper.valueToTree(task == null ? Map.of() : task.getMetadata());
            String[] parts = path.split("\\.");
            JsonNode current = metadata;
            for (String part : parts) {
                current = current.path(part);
            }
            return current.isMissingNode() || current.isNull() ? "" : current.asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private Task copyTask(Task source) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(source), Task.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to copy A2A task", e);
        }
    }

    private io.dscope.camel.a2a.model.TaskStatus copyStatus(io.dscope.camel.a2a.model.TaskStatus source) {
        io.dscope.camel.a2a.model.TaskStatus copy = new io.dscope.camel.a2a.model.TaskStatus();
        copy.setState(source.getState());
        copy.setMessage(source.getMessage());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setDetails(source.getDetails());
        return copy;
    }
}
