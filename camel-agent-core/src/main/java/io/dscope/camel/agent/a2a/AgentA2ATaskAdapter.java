package io.dscope.camel.agent.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.a2a.model.Message;
import io.dscope.camel.a2a.model.Task;
import io.dscope.camel.a2a.model.TaskState;
import io.dscope.camel.a2a.model.dto.CancelTaskRequest;
import io.dscope.camel.a2a.model.dto.ListTasksRequest;
import io.dscope.camel.a2a.model.dto.SendMessageRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class AgentA2ATaskAdapter {

    private final io.dscope.camel.a2a.service.A2ATaskService taskService;
    private final PersistenceFacade persistenceFacade;
    private final ObjectMapper objectMapper;

    AgentA2ATaskAdapter(io.dscope.camel.a2a.service.A2ATaskService taskService,
                        PersistenceFacade persistenceFacade,
                        ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.persistenceFacade = persistenceFacade;
        this.objectMapper = objectMapper;
    }

    Task accept(SendMessageRequest request, Map<String, Object> metadata) {
        SendMessageRequest effectiveRequest = new SendMessageRequest();
        effectiveRequest.setMessage(request == null ? null : request.getMessage());
        effectiveRequest.setConversationId(request == null ? null : request.getConversationId());
        effectiveRequest.setIdempotencyKey(request == null ? null : request.getIdempotencyKey());
        effectiveRequest.setMetadata(mergeMetadata(request == null ? null : request.getMetadata(), metadata));
        Task task = taskService.sendMessage(effectiveRequest);
        mergeMetadata(task, metadata);
        return task;
    }

    boolean isResponseCompleted(Task task) {
        return "true".equalsIgnoreCase(metadataText(task, "camelAgent.responseCompleted"));
    }

    Task getTask(String taskId) {
        return taskService.getTask(taskId);
    }

    List<Task> listTasks(String state, Integer limit) {
        ListTasksRequest request = new ListTasksRequest();
        request.setState(state);
        request.setLimit(limit);
        return taskService.listTasks(request);
    }

    Task cancelTask(String taskId, String reason) {
        CancelTaskRequest request = new CancelTaskRequest();
        request.setTaskId(taskId);
        request.setReason(reason);
        return taskService.cancelTask(request);
    }

    Task complete(Task task,
                  Message responseMessage,
                  Map<String, Object> metadata,
                  String completionMessage) {
        mergeMetadata(task, metadata);
        task.setLatestMessage(responseMessage);
        task.setMessages(combineMessages(task, responseMessage));
        task.setUpdatedAt(Instant.now().toString());
        if (task.getMetadata() != null) {
            Object camelAgent = task.getMetadata().get("camelAgent");
            if (camelAgent instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mutable = new LinkedHashMap<>((Map<String, Object>) map);
                mutable.put("responseCompleted", true);
                task.getMetadata().put("camelAgent", mutable);
            }
            task.getMetadata().put("responseCompleted", true);
        }
        taskService.transitionTask(task.getTaskId(), TaskState.COMPLETED, completionMessage);
        return taskService.getTask(task.getTaskId());
    }

    void appendConversationEvent(String conversationId,
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

    private List<Message> combineMessages(Task task, Message responseMessage) {
        List<Message> messages = new ArrayList<>();
        if (task != null && task.getMessages() != null && !task.getMessages().isEmpty()) {
            messages.addAll(task.getMessages());
        } else if (task != null && task.getLatestMessage() != null) {
            messages.add(task.getLatestMessage());
        }
        if (responseMessage != null) {
            messages.add(responseMessage);
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private void mergeMetadata(Task task, Map<String, Object> metadata) {
        Map<String, Object> merged = mergeMetadata(task == null ? null : task.getMetadata(), metadata);
        if (task != null) {
            task.setMetadata(merged);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeMetadata(Map<String, Object> existingMetadata, Map<String, Object> incomingMetadata) {
        if ((existingMetadata == null || existingMetadata.isEmpty()) && (incomingMetadata == null || incomingMetadata.isEmpty())) {
            return existingMetadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existingMetadata);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existingMetadata != null && !existingMetadata.isEmpty()) {
            merged.putAll(existingMetadata);
        }
        if (incomingMetadata != null && !incomingMetadata.isEmpty()) {
            merged.putAll(incomingMetadata);
        }
        Object camelAgent = merged.get("camelAgent");
        if (camelAgent instanceof Map<?, ?> incomingCamelAgent) {
            Map<String, Object> existingCamelAgent = existingMetadata != null
                && existingMetadata.get("camelAgent") instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
            existingCamelAgent.putAll((Map<String, Object>) incomingCamelAgent);
            merged.put("camelAgent", existingCamelAgent);
        }
        return merged;
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
}
