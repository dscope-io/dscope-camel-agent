package io.dscope.camel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.kernel.DefaultAgentKernel;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.kernel.StaticAiModelClient;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.TaskStatus;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.registry.DefaultToolRegistry;
import io.dscope.camel.agent.validation.SchemaValidator;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DefaultAgentKernelTest {

    @Test
    void shouldReturnAssistantResponseWithoutToolCall() {
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = new AgentBlueprint("demo", "0.1", "system", List.of(toolSpec));
        ToolExecutor noOpExecutor = (tool, args, ctx) -> new ToolResult("ok", new ObjectMapper().createObjectNode(), List.of());

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            noOpExecutor,
            new StaticAiModelClient(),
            new InMemoryPersistenceFacade(),
            new SchemaValidator(),
            new ObjectMapper()
        );

        var response = kernel.handleUserMessage("c1", "hello");
        Assertions.assertEquals("c1", response.conversationId());
        Assertions.assertFalse(response.message().isBlank());
        Assertions.assertTrue(response.events().stream().anyMatch(e -> "assistant.message".equals(e.type())));
    }

    @Test
    void shouldTransitionTaskToWaitingAndResume() {
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = new AgentBlueprint("demo", "0.1", "system", List.of(toolSpec));
        ToolExecutor noOpExecutor = (tool, args, ctx) -> new ToolResult("ok", new ObjectMapper().createObjectNode(), List.of());
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            noOpExecutor,
            new StaticAiModelClient(),
            persistence,
            new SchemaValidator(),
            new ObjectMapper()
        );

        var waiting = kernel.handleUserMessage("c2", "task.async collect-metrics");
        Assertions.assertEquals(TaskStatus.WAITING, waiting.taskState().status());
        Assertions.assertTrue(waiting.events().stream().anyMatch(e -> "task.waiting".equals(e.type())));

        var resumed = kernel.resumeTask(waiting.taskState().taskId());
        Assertions.assertEquals(TaskStatus.FINISHED, resumed.taskState().status());
        Assertions.assertTrue(resumed.events().stream().anyMatch(e -> "task.resumed".equals(e.type())));
    }

    @Test
    void shouldPersistDynamicRouteLifecycle() {
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = new AgentBlueprint("demo", "0.1", "system", List.of(toolSpec));
        ToolExecutor noOpExecutor = (tool, args, ctx) -> new ToolResult("ok", new ObjectMapper().createObjectNode(), List.of());
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            noOpExecutor,
            new StaticAiModelClient(),
            persistence,
            new SchemaValidator(),
            new ObjectMapper()
        );

        var response = kernel.handleUserMessage("c3", "route.instantiate http.request");
        Assertions.assertTrue(response.message().startsWith("Dynamic route started:"));
        Assertions.assertFalse(persistence.loadDynamicRoutes(10).isEmpty());
        Assertions.assertEquals("STARTED", persistence.loadDynamicRoutes(10).getFirst().status());
    }

    @Test
    void shouldPreventResumeWhenTaskOwnedByAnotherNode() {
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = new AgentBlueprint("demo", "0.1", "system", List.of(toolSpec));
        ToolExecutor noOpExecutor = (tool, args, ctx) -> new ToolResult("ok", new ObjectMapper().createObjectNode(), List.of());
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();

        DefaultAgentKernel nodeA = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            noOpExecutor,
            new StaticAiModelClient(),
            persistence,
            new SchemaValidator(),
            new ObjectMapper(),
            "node-a",
            300
        );
        DefaultAgentKernel nodeB = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            noOpExecutor,
            new StaticAiModelClient(),
            persistence,
            new SchemaValidator(),
            new ObjectMapper(),
            "node-b",
            300
        );

        var waiting = nodeA.handleUserMessage("c-lock", "task.async lock-check");
        Assertions.assertTrue(persistence.tryClaimTask(waiting.taskState().taskId(), "node-a", 300));

        var response = nodeB.resumeTask(waiting.taskState().taskId());
        Assertions.assertEquals("Task is currently owned by another node", response.message());
        Assertions.assertTrue(response.events().stream().anyMatch(e -> "task.locked".equals(e.type())));
    }
}
