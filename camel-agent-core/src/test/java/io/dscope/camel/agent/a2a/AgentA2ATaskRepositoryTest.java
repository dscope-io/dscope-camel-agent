package io.dscope.camel.agent.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.a2a.model.Message;
import io.dscope.camel.a2a.model.Part;
import io.dscope.camel.a2a.model.Task;
import io.dscope.camel.a2a.model.TaskState;
import io.dscope.camel.a2a.processor.A2AInvalidParamsException;
import io.dscope.camel.a2a.service.InMemoryTaskEventService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentA2ATaskRepositoryTest {

    @Test
    void createsPersistsAndCancelsTasks() {
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();
        AgentA2ATaskRepository repository =
            new AgentA2ATaskRepository(persistence, new ObjectMapper(), new InMemoryTaskEventService());

        Task created = repository.createCompletedTask(
            "task-1",
            "idem-1",
            "conv-1",
            message("user", "Need help"),
            message("assistant", "Here is the answer"),
            metadata("conv-1")
        );

        assertEquals(TaskState.COMPLETED, created.getStatus().getState());
        assertEquals(1, repository.listTasks(null, null).size());
        assertEquals("task-1", repository.existingByIdempotency("idem-1").getTaskId());

        Task canceled = repository.cancelTask("task-1", "caller canceled");
        assertEquals(TaskState.CANCELED, canceled.getStatus().getState());
        assertEquals(TaskState.CANCELED, repository.getTask("task-1").getStatus().getState());

        repository.appendConversationEvent("conv-1", "task-1", "conversation.a2a.request.accepted", Map.of("agentId", "support-public"));
        assertEquals(1, persistence.loadConversation("conv-1", 10).size());
    }

    @Test
    void failsForUnknownTask() {
        AgentA2ATaskRepository repository =
            new AgentA2ATaskRepository(new InMemoryPersistenceFacade(), new ObjectMapper(), new InMemoryTaskEventService());

        assertThrows(A2AInvalidParamsException.class, () -> repository.getTask("missing-task"));
    }

    private static Map<String, Object> metadata(String conversationId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> camelAgent = new LinkedHashMap<>();
        camelAgent.put("localConversationId", conversationId);
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
        message.setMessageId(role + "-msg");
        assertNotNull(message.getMessageId());
        return message;
    }
}
