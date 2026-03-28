package io.dscope.camel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.kernel.DefaultAgentKernel;
import io.dscope.camel.agent.kernel.InMemoryPersistenceFacade;
import io.dscope.camel.agent.kernel.StaticAiModelClient;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.ModelResponse;
import io.dscope.camel.agent.model.TokenUsage;
import io.dscope.camel.agent.model.TaskStatus;
import io.dscope.camel.agent.model.ToolPolicy;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.registry.DefaultToolRegistry;
import io.dscope.camel.agent.validation.SchemaValidator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DefaultAgentKernelTest {

    private AgentBlueprint blueprint(ToolSpec toolSpec) {
        return new AgentBlueprint("demo", "0.1", "system", List.of(toolSpec), List.of());
    }

    private AgentBlueprint blueprint(ToolSpec toolSpec, List<com.fasterxml.jackson.databind.JsonNode> mcpCatalogs) {
        return new AgentBlueprint("demo", "0.1", "system", List.of(toolSpec), List.of(), mcpCatalogs);
    }

    @Test
    void shouldReturnAssistantResponseWithoutToolCall() {
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = blueprint(toolSpec);
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
        Assertions.assertTrue(response.events().stream().anyMatch(e -> "agent.message".equals(e.type())));
    }

    @Test
    void shouldContinueWhenEventPersistenceFails() {
        ToolSpec toolSpec = new ToolSpec("support.ticket.open", "ticket", "ticket", null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = blueprint(toolSpec);
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger toolCalls = new AtomicInteger();
        ToolExecutor toolExecutor = (tool, args, ctx) -> {
            toolCalls.incrementAndGet();
            return new ToolResult("ok", mapper.createObjectNode().put("status", "OPEN"), List.of());
        };
        InMemoryPersistenceFacade flakyPersistence = new InMemoryPersistenceFacade() {
            @Override
            public void appendEvent(io.dscope.camel.agent.model.AgentEvent event, String idempotencyKey) {
                throw new RuntimeException("persistence unavailable");
            }
        };

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            toolExecutor,
            (systemPrompt, history, tools, options, callback) -> new ModelResponse("", List.of(new AiToolCall("support.ticket.open", mapper.createObjectNode())), true),
            flakyPersistence,
            new SchemaValidator(),
            mapper
        );

        var response = kernel.handleUserMessage("c-persist-fail", "open support ticket");
        Assertions.assertEquals(1, toolCalls.get());
        Assertions.assertFalse(response.message().isBlank());
        Assertions.assertEquals(TaskStatus.FINISHED, response.taskState().status());
    }

    @Test
    void shouldTransitionTaskToWaitingAndResume() {
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = blueprint(toolSpec);
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
        AgentBlueprint blueprint = blueprint(toolSpec);
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
        AgentBlueprint blueprint = blueprint(toolSpec);
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

    @Test
    void shouldPersistDynamicRouteFromToolResultPayload() {
        ToolSpec toolSpec = new ToolSpec("route.template.http", "dynamic", null, null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = blueprint(toolSpec);
        ObjectMapper mapper = new ObjectMapper();
        ToolExecutor dynamicExecutor = (tool, args, ctx) -> {
            var payload = mapper.createObjectNode();
            var dynamic = mapper.createObjectNode()
                .put("routeInstanceId", "ri-1")
                .put("templateId", "http.request")
                .put("routeId", "agent.dynamic.http.request.001")
                .put("status", "STARTED")
                .put("conversationId", ctx.conversationId())
                .put("createdAt", java.time.Instant.now().toString())
                .put("expiresAt", java.time.Instant.now().plusSeconds(300).toString());
            payload.set("dynamicRoute", dynamic);
            return new ToolResult("ok", payload, List.of());
        };
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            dynamicExecutor,
            (systemPrompt, history, tools, options, callback) -> new io.dscope.camel.agent.model.ModelResponse("", List.of(new io.dscope.camel.agent.model.AiToolCall("route.template.http", mapper.createObjectNode())), true),
            persistence,
            new SchemaValidator(),
            mapper
        );

        kernel.handleUserMessage("c-dyn", "create route");
        var saved = persistence.loadDynamicRoute("ri-1");
        Assertions.assertTrue(saved.isPresent());
        Assertions.assertEquals("STARTED", saved.get().status());
    }

    @Test
    void shouldEmitMcpDiscoveryEventOncePerConversation() {
        ObjectMapper mapper = new ObjectMapper();
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        var catalog = mapper.createObjectNode()
            .put("serviceTool", "support.mcp")
            .put("endpointUri", "mcp:http://localhost:3001")
            .set("result", mapper.createObjectNode().putArray("tools"));
        AgentBlueprint blueprint = blueprint(toolSpec, List.of(catalog));
        ToolExecutor noOpExecutor = (tool, args, ctx) -> new ToolResult("ok", mapper.createObjectNode(), List.of());
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            noOpExecutor,
            new StaticAiModelClient(),
            persistence,
            new SchemaValidator(),
            mapper
        );

        kernel.handleUserMessage("c-mcp", "hello");
        kernel.handleUserMessage("c-mcp", "hello again");

        long discoveryEvents = persistence.loadConversation("c-mcp", 100).stream()
            .filter(event -> "mcp.tools.discovered".equals(event.type()))
            .count();
        Assertions.assertEquals(1, discoveryEvents);
    }

    @Test
    void shouldAlwaysIncludeSystemInstructionAndMcpCatalogInModelCall() {
        ObjectMapper mapper = new ObjectMapper();
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        var catalog = mapper.createObjectNode()
            .put("serviceTool", "support.mcp")
            .put("endpointUri", "mcp:http://localhost:3001")
            .set("result", mapper.createObjectNode().putArray("tools").add(mapper.createObjectNode().put("name", "support.ticket.open").put("description", "Open support ticket")));
        AgentBlueprint blueprint = new AgentBlueprint("demo", "0.1", "system", List.of(toolSpec), List.of(), List.of(catalog));
        ToolExecutor noOpExecutor = (tool, args, ctx) -> new ToolResult("ok", mapper.createObjectNode(), List.of());
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();
        AtomicInteger calls = new AtomicInteger();

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            noOpExecutor,
            (systemPrompt, history, tools, options, callback) -> {
                calls.incrementAndGet();
                Assertions.assertEquals("system", systemPrompt);
                Assertions.assertTrue(history.stream().anyMatch(event -> "mcp.tools.discovered".equals(event.type())));
                Assertions.assertTrue(history.stream()
                    .filter(event -> "mcp.tools.discovered".equals(event.type()))
                    .anyMatch(event -> event.payload() != null && event.payload().toString().contains("support.ticket.open")));
                return new ModelResponse("ok", List.of(), true);
            },
            persistence,
            new SchemaValidator(),
            mapper
        );

        kernel.handleUserMessage("c-mcp-always", "hello one");
        kernel.handleUserMessage("c-mcp-always", "hello two");

        Assertions.assertEquals(2, calls.get());
    }

    @Test
    void shouldRouteRealtimeTranscriptFinalIntoUserMessageFlow() {
        ObjectMapper mapper = new ObjectMapper();
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = blueprint(toolSpec);
        ToolExecutor noOpExecutor = (tool, args, ctx) -> new ToolResult("ok", mapper.createObjectNode(), List.of());
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            noOpExecutor,
            new StaticAiModelClient(),
            persistence,
            new SchemaValidator(),
            mapper
        );

        var realtimePayload = mapper.createObjectNode().put("text", "voice transcript hello");
        var response = kernel.handleRealtimeEvent("c-voice", "transcript.final", realtimePayload);

        Assertions.assertEquals("c-voice", response.conversationId());
        Assertions.assertTrue(response.events().stream().anyMatch(e -> "realtime.transcript.final".equals(e.type())));
        Assertions.assertTrue(response.events().stream().anyMatch(e -> "agent.message".equals(e.type())));
    }

    @Test
    void shouldFallbackAssistantMessageToToolResultWhenModelMessageBlank() {
        ObjectMapper mapper = new ObjectMapper();
        ToolSpec toolSpec = new ToolSpec("support.ticket.open", "ticket", null, null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = blueprint(toolSpec);
        ToolExecutor toolExecutor = (tool, args, ctx) -> {
            var data = mapper.createObjectNode()
                .put("ticketId", "TCK-123")
                .put("status", "OPEN");
            return new ToolResult("", data, List.of());
        };
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            toolExecutor,
            (systemPrompt, history, tools, options, callback) -> new ModelResponse(
                "",
                List.of(new AiToolCall("support.ticket.open", mapper.createObjectNode())),
                true
            ),
            persistence,
            new SchemaValidator(),
            mapper
        );

        var response = kernel.handleUserMessage("c-tool-fallback", "open ticket");
        Assertions.assertFalse(response.message().isBlank());
        Assertions.assertTrue(response.message().contains("ticketId"));
    }

    @Test
    void shouldIncludeTargetDetailsInToolStartEvent() {
        ObjectMapper mapper = new ObjectMapper();
        ToolSpec toolSpec = new ToolSpec("customerLookup", "crm lookup", null, "mcp:https://crm.example/mcp", null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = blueprint(toolSpec);
        ToolExecutor toolExecutor = (tool, args, ctx) -> new ToolResult("ok", mapper.createObjectNode().put("status", "ok"), List.of());

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            toolExecutor,
            (systemPrompt, history, tools, options, callback) -> new ModelResponse(
                "done",
                List.of(new AiToolCall("customerLookup", mapper.createObjectNode().put("phone", "421222200096"))),
                true
            ),
            new InMemoryPersistenceFacade(),
            new SchemaValidator(),
            mapper
        );

        var response = kernel.handleUserMessage("c-tool-start-target", "find customer by phone");
        var toolStart = response.events().stream()
            .filter(e -> "tool.start".equals(e.type()))
            .findFirst()
            .orElseThrow();

        Assertions.assertEquals("mcp:https://crm.example/mcp", toolStart.payload().path("target").asText());
        Assertions.assertTrue(toolStart.payload().path("mcpTarget").asBoolean());
    }

    @Test
    void shouldPersistModelUsageEventWhenReported() {
        ObjectMapper mapper = new ObjectMapper();
        ToolSpec toolSpec = new ToolSpec("echo", "echo", "echo", null, null, null, new ToolPolicy(false, 0, 1000));
        AgentBlueprint blueprint = blueprint(toolSpec);
        ToolExecutor noOpExecutor = (tool, args, ctx) -> new ToolResult("ok", mapper.createObjectNode(), List.of());
        InMemoryPersistenceFacade persistence = new InMemoryPersistenceFacade();

        DefaultAgentKernel kernel = new DefaultAgentKernel(
            blueprint,
            new DefaultToolRegistry(blueprint.tools()),
            noOpExecutor,
            (systemPrompt, history, tools, options, callback) -> new ModelResponse(
                "done",
                List.of(),
                true,
                TokenUsage.of(11, 5, 16)
            ),
            persistence,
            new SchemaValidator(),
            mapper
        );

        var response = kernel.handleUserMessage("c-usage", "hello");

        var usageEvent = response.events().stream()
            .filter(event -> "model.usage".equals(event.type()))
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(11, usageEvent.payload().path("promptTokens").asInt());
        Assertions.assertEquals(5, usageEvent.payload().path("completionTokens").asInt());
        Assertions.assertEquals(16, usageEvent.payload().path("totalTokens").asInt());
        Assertions.assertTrue(persistence.loadConversation("c-usage", 20).stream().anyMatch(event -> "model.usage".equals(event.type())));
    }
}
