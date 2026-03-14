package io.dscope.camel.agent.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.runtime.A2ARuntimeProperties;
import io.dscope.camel.agent.runtime.AgentPlanSelectionResolver;
import io.dscope.camel.a2a.A2AComponentApplicationSupport;
import io.dscope.camel.a2a.model.Message;
import io.dscope.camel.a2a.model.Part;
import io.dscope.camel.a2a.model.Task;
import io.dscope.camel.a2a.model.dto.SendMessageRequest;
import io.dscope.camel.a2a.service.A2APushNotificationConfigService;
import io.dscope.camel.a2a.service.A2ATaskService;
import io.dscope.camel.a2a.service.InMemoryA2ATaskService;
import io.dscope.camel.a2a.service.InMemoryPushNotificationConfigService;
import io.dscope.camel.a2a.service.InMemoryTaskEventService;
import io.dscope.camel.a2a.service.WebhookPushNotificationNotifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.apache.camel.main.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentA2AProtocolSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void reusesPreboundSharedTaskInfrastructure() throws Exception {
        Path plansConfig = tempDir.resolve("agents.yaml");
        Files.writeString(plansConfig, """
            plans:
              - name: support
                default: true
                versions:
                  - version: v1
                    default: true
                    blueprint: classpath:agents/support/agent.md
            """);
        Path exposedAgentsConfig = tempDir.resolve("a2a-agents.yaml");
        Files.writeString(exposedAgentsConfig, """
            agents:
              - agentId: support-public
                name: Support Public Agent
                description: Handles support requests
                defaultAgent: true
                version: 1.0.0
                planName: support
                planVersion: v1
            """);

        Main main = new Main();
        InMemoryTaskEventService taskEventService = new InMemoryTaskEventService();
        A2ATaskService taskService = new InMemoryA2ATaskService(taskEventService);
        A2APushNotificationConfigService pushConfigService =
            new InMemoryPushNotificationConfigService(new WebhookPushNotificationNotifier());
        taskEventService.addListener(pushConfigService::onTaskEvent);

        main.bind(A2AComponentApplicationSupport.BEAN_TASK_EVENT_SERVICE, taskEventService);
        main.bind(A2AComponentApplicationSupport.BEAN_TASK_SERVICE, taskService);
        main.bind(A2AComponentApplicationSupport.BEAN_PUSH_CONFIG_SERVICE, pushConfigService);

        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();
        ObjectMapper objectMapper = new ObjectMapper();
        AgentPlanSelectionResolver planSelectionResolver = new AgentPlanSelectionResolver(persistence, objectMapper);

        AgentA2AProtocolSupport.bindIfEnabled(
            main,
            new Properties(),
            new A2ARuntimeProperties(
                true,
                "0.0.0.0",
                8080,
                "http://localhost:8080",
                "/a2a/rpc",
                "/a2a/sse",
                "/.well-known/agent-card.json",
                "direct:agent",
                exposedAgentsConfig.toString(),
                plansConfig.toString(),
                "classpath:agents/support/agent.md"
            ),
            persistence,
            planSelectionResolver,
            objectMapper
        );

        assertSame(taskEventService, main.lookup(A2AComponentApplicationSupport.BEAN_TASK_EVENT_SERVICE, InMemoryTaskEventService.class));
        assertSame(taskService, main.lookup(A2AComponentApplicationSupport.BEAN_TASK_SERVICE, A2ATaskService.class));
        assertSame(pushConfigService, main.lookup(A2AComponentApplicationSupport.BEAN_PUSH_CONFIG_SERVICE, A2APushNotificationConfigService.class));

        SendMessageRequest request = new SendMessageRequest();
        request.setMessage(message("user", "Check shared task"));
        Task created = taskService.sendMessage(request);

        A2ATaskService boundTaskService = main.lookup(A2AComponentApplicationSupport.BEAN_TASK_SERVICE, A2ATaskService.class);
        assertEquals(created.getTaskId(), boundTaskService.getTask(created.getTaskId()).getTaskId());
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
}
