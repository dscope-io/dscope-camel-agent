package io.dscope.camel.agent.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.a2a.model.Message;
import io.dscope.camel.a2a.model.Part;
import io.dscope.camel.a2a.model.Task;
import io.dscope.camel.a2a.model.TaskState;
import io.dscope.camel.a2a.model.dto.SendMessageRequest;
import io.dscope.camel.a2a.service.InMemoryA2ATaskService;
import io.dscope.camel.a2a.service.InMemoryTaskEventService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentA2ATaskAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsCompletesAndAuditsUsingSharedTaskService() {
        InMemoryTaskEventService taskEventService = new InMemoryTaskEventService();
        InMemoryA2ATaskService taskService = new InMemoryA2ATaskService(taskEventService);
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();
        AgentA2ATaskAdapter adapter = new AgentA2ATaskAdapter(taskService, persistence, objectMapper);

        SendMessageRequest request = new SendMessageRequest();
        request.setIdempotencyKey("idem-1");
        request.setMessage(message("user", "Need ticket update"));
        request.setMetadata(Map.of("source", "non-agent", "camelAgent", Map.of("origin", "shared")));

        Task accepted = adapter.accept(request, agentMetadata("conv-1"));

        assertEquals(TaskState.RUNNING, accepted.getStatus().getState());
        assertEquals("support-public", accepted.getMetadata().get("agentId"));
        assertEquals("conv-1", nestedText(accepted, "camelAgent", "localConversationId"));
        assertEquals("shared", nestedText(accepted, "camelAgent", "origin"));

        Task completed = adapter.complete(
            accepted,
            message("assistant", "Ticket updated"),
            agentMetadata("conv-1"),
            "Agent completed task"
        );

        assertEquals(TaskState.COMPLETED, completed.getStatus().getState());
        assertEquals("true", nestedText(completed, "camelAgent", "responseCompleted"));

        adapter.appendConversationEvent("conv-1", completed.getTaskId(), "conversation.a2a.response.completed", Map.of("status", "ok"));
        assertEquals(1, persistence.loadConversation("conv-1", 10).size());
    }

    @Test
    void reusesSharedTaskServiceIdempotency() {
        InMemoryTaskEventService taskEventService = new InMemoryTaskEventService();
        InMemoryA2ATaskService taskService = new InMemoryA2ATaskService(taskEventService);
        AgentA2ATaskAdapter adapter = new AgentA2ATaskAdapter(taskService, new InMemoryPersistenceFacade(), objectMapper);

        SendMessageRequest request = new SendMessageRequest();
        request.setIdempotencyKey("idem-2");
        request.setMessage(message("user", "Open ticket"));

        Task first = adapter.accept(request, agentMetadata("conv-2"));
        Task second = adapter.accept(request, agentMetadata("conv-2"));

        assertEquals(first.getTaskId(), second.getTaskId());
        assertSame(taskService.getTask(first.getTaskId()), taskService.getTask(second.getTaskId()));
        assertTrue(taskService.listTasks(null).size() >= 1);
    }

    private static Map<String, Object> agentMetadata(String conversationId) {
        Map<String, Object> camelAgent = new LinkedHashMap<>();
        camelAgent.put("localConversationId", conversationId);
        camelAgent.put("linkedConversationId", conversationId);
        camelAgent.put("planName", "support");
        camelAgent.put("planVersion", "v1");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("camelAgent", camelAgent);
        metadata.put("agentId", "support-public");
        return metadata;
    }

    private static Message message(String role, String text) {
        Part part = new Part();
        part.setType("text");
        part.setMimeType("text/plain");
        part.setText(text);

        Message message = new Message();
        message.setRole(role);
        message.setParts(List.of(part));
        message.setMessageId(role + "-message-" + text.hashCode());
        return message;
    }

    @SuppressWarnings("unchecked")
    private static String nestedText(Task task, String topLevelKey, String nestedKey) {
        Object topLevel = task.getMetadata().get(topLevelKey);
        if (!(topLevel instanceof Map<?, ?> map)) {
            return "";
        }
        Object value = ((Map<String, Object>) map).get(nestedKey);
        return value == null ? "" : String.valueOf(value);
    }
}
