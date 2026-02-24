package io.dscope.camel.agent.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AgentKernel;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.api.ToolRegistry;
import io.dscope.camel.agent.model.AgentBlueprint;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.AgentResponse;
import io.dscope.camel.agent.model.AiToolCall;
import io.dscope.camel.agent.model.DynamicRouteState;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.ModelOptions;
import io.dscope.camel.agent.model.ModelResponse;
import io.dscope.camel.agent.model.TaskState;
import io.dscope.camel.agent.model.TaskStatus;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.validation.SchemaValidator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultAgentKernel implements AgentKernel {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultAgentKernel.class);

    private final AgentBlueprint blueprint;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final AiModelClient aiModelClient;
    private final PersistenceFacade persistenceFacade;
    private final SchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final String nodeOwnerId;
    private final int taskClaimLeaseSeconds;

    public DefaultAgentKernel(AgentBlueprint blueprint,
                              ToolRegistry toolRegistry,
                              ToolExecutor toolExecutor,
                              AiModelClient aiModelClient,
                              PersistenceFacade persistenceFacade,
                              SchemaValidator schemaValidator,
                              ObjectMapper objectMapper) {
        this(blueprint, toolRegistry, toolExecutor, aiModelClient, persistenceFacade, schemaValidator, objectMapper,
            "node-" + UUID.randomUUID(), 120);
    }

    public DefaultAgentKernel(AgentBlueprint blueprint,
                              ToolRegistry toolRegistry,
                              ToolExecutor toolExecutor,
                              AiModelClient aiModelClient,
                              PersistenceFacade persistenceFacade,
                              SchemaValidator schemaValidator,
                              ObjectMapper objectMapper,
                              String nodeOwnerId,
                              int taskClaimLeaseSeconds) {
        this.blueprint = blueprint;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.aiModelClient = aiModelClient;
        this.persistenceFacade = persistenceFacade;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
        this.nodeOwnerId = nodeOwnerId == null || nodeOwnerId.isBlank() ? "node-" + UUID.randomUUID() : nodeOwnerId;
        this.taskClaimLeaseSeconds = Math.max(1, taskClaimLeaseSeconds);
    }

    @Override
    public AgentResponse handleUserMessage(String conversationId, String userMessage) {
        LOGGER.info("Kernel handleUserMessage started: conversationId={}, chars={}",
            conversationId,
            userMessage == null ? 0 : userMessage.length());
        List<AgentEvent> emitted = new ArrayList<>();
        AgentEvent userEvent = event(conversationId, null, "user.message", objectMapper.valueToTree(userMessage));
        persist(userEvent);
        emitted.add(userEvent);

        if (userMessage != null && userMessage.startsWith("task.async ")) {
            String taskId = UUID.randomUUID().toString();
            TaskState waiting = new TaskState(
                taskId,
                conversationId,
                TaskStatus.WAITING,
                userMessage.substring("task.async ".length()).trim(),
                Instant.now().plus(5, ChronoUnit.MINUTES),
                0,
                null
            );
            persistenceFacade.saveTask(waiting);
            AgentEvent waitingEvent = event(conversationId, taskId, "task.waiting", taskPayload(waiting));
            persist(waitingEvent);
            emitted.add(waitingEvent);
            return new AgentResponse(conversationId, "Task accepted and waiting for resume", emitted, waiting);
        }

        if (userMessage != null && userMessage.startsWith("route.instantiate ")) {
            String templateId = userMessage.substring("route.instantiate ".length()).trim();
            if (templateId.isEmpty()) {
                templateId = "dynamic.template";
            }
            String routeInstanceId = UUID.randomUUID().toString();
            String routeId = "agent." + blueprint.name().toLowerCase() + "." + templateId.replace(' ', '.') + "."
                + UUID.randomUUID().toString().substring(0, 8);
            DynamicRouteState created = new DynamicRouteState(
                routeInstanceId,
                templateId,
                routeId,
                "CREATED",
                conversationId,
                Instant.now(),
                Instant.now().plus(1, ChronoUnit.HOURS)
            );
            persistenceFacade.saveDynamicRoute(created);
            persistenceFacade.saveDynamicRoute(new DynamicRouteState(
                created.routeInstanceId(),
                created.templateId(),
                created.routeId(),
                "STARTED",
                created.conversationId(),
                created.createdAt(),
                created.expiresAt()
            ));
            AgentEvent registered = event(conversationId, null, "tool.result", dynamicRoutePayload(created));
            persist(registered);
            emitted.add(registered);
            TaskState finished = new TaskState(UUID.randomUUID().toString(), conversationId, TaskStatus.FINISHED, "route", null, 0, routeId);
            persistenceFacade.saveTask(finished);
            return new AgentResponse(conversationId, "Dynamic route started: " + routeId, emitted, finished);
        }

        List<AgentEvent> history = new ArrayList<>(persistenceFacade.loadConversation(conversationId, 20));
        emitMcpToolsDiscoveredIfNeeded(conversationId, history, emitted);
        StringBuilder streamed = new StringBuilder();
        String lastToolResultFallback = "";

        ModelResponse modelResponse = aiModelClient.generate(
            blueprint.systemInstruction(),
            history,
            toolRegistry.listTools(),
            new ModelOptions(null, 0.2, 1024, true),
            token -> {
                streamed.append(token);
                AgentEvent delta = event(conversationId, null, "assistant.delta", objectMapper.valueToTree(token));
                persist(delta);
                emitted.add(delta);
            }
        );
        LOGGER.debug("Kernel model response received: conversationId={}, toolCalls={}, assistantMessageChars={}, streamedChars={}",
            conversationId,
            modelResponse.toolCalls() == null ? 0 : modelResponse.toolCalls().size(),
            modelResponse.assistantMessage() == null ? 0 : modelResponse.assistantMessage().length(),
            streamed.length());

        for (AiToolCall toolCall : modelResponse.toolCalls()) {
            LOGGER.info("Kernel tool execution started: conversationId={}, tool={}",
                conversationId,
                toolCall == null ? "" : toolCall.name());
            ToolSpec toolSpec = toolRegistry.getTool(toolCall.name())
                .orElseThrow(() -> new IllegalArgumentException("Tool not allowed: " + toolCall.name()));

            schemaValidator.validate(toolSpec.inputSchema(), toolCall.arguments(), "tool input " + toolCall.name());
            AgentEvent start = event(conversationId, null, "tool.start", objectMapper.valueToTree(toolCall));
            persist(start);
            emitted.add(start);

            ToolResult toolResult = toolExecutor.execute(
                toolSpec,
                toolCall.arguments(),
                new ExecutionContext(conversationId, null, UUID.randomUUID().toString())
            );
            LOGGER.info("Kernel tool execution completed: conversationId={}, tool={}, contentChars={}, hasData={}",
                conversationId,
                toolCall.name(),
                toolResult.content() == null ? 0 : toolResult.content().length(),
                toolResult.data() != null && !toolResult.data().isNull());
            schemaValidator.validate(toolSpec.outputSchema(), toolResult.data(), "tool output " + toolCall.name());
            persistDynamicRouteIfPresent(conversationId, toolResult.data());

            AgentEvent resultEvent = event(conversationId, null, "tool.result", objectMapper.valueToTree(toolResult));
            persist(resultEvent);
            emitted.add(resultEvent);

            String toolFallback = toolResult.content();
            if ((toolFallback == null || toolFallback.isBlank()) && toolResult.data() != null && !toolResult.data().isNull()) {
                toolFallback = toolResult.data().toString();
            }
            if (toolFallback != null && !toolFallback.isBlank()) {
                lastToolResultFallback = toolFallback;
                LOGGER.debug("Kernel tool fallback captured: conversationId={}, tool={}, chars={}",
                    conversationId,
                    toolCall.name(),
                    toolFallback.length());
            }
        }

        String finalMessage = modelResponse.assistantMessage();
        if (finalMessage == null || finalMessage.isBlank()) {
            finalMessage = streamed.toString().trim();
        }
        if ((finalMessage == null || finalMessage.isBlank()) && !lastToolResultFallback.isBlank()) {
            finalMessage = lastToolResultFallback;
            LOGGER.debug("Kernel assistant fallback used from tool result: conversationId={}, chars={}",
                conversationId,
                finalMessage.length());
        }

        LOGGER.info("Kernel final assistant message ready: conversationId={}, chars={}",
            conversationId,
            finalMessage == null ? 0 : finalMessage.length());

        AgentEvent assistant = event(conversationId, null, "assistant.message", objectMapper.valueToTree(finalMessage));
        persist(assistant);
        emitted.add(assistant);

        AgentEvent snapshot = event(conversationId, null, "snapshot.written", objectMapper.valueToTree("ok"));
        persist(snapshot);
        emitted.add(snapshot);

        TaskState taskState = new TaskState(
            UUID.randomUUID().toString(),
            conversationId,
            TaskStatus.FINISHED,
            "final",
            null,
            0,
            finalMessage
        );
        persistenceFacade.saveTask(taskState);

        LOGGER.info("Kernel handleUserMessage completed: conversationId={}, emittedEvents={}",
            conversationId,
            emitted.size());

        return new AgentResponse(conversationId, finalMessage, emitted, taskState);
    }

    @Override
    public AgentResponse resumeTask(String taskId) {
        LOGGER.info("Kernel resumeTask requested: taskId={}, owner={}", taskId, nodeOwnerId);
        TaskState task = persistenceFacade.loadTask(taskId)
            .orElse(new TaskState(taskId, "unknown", TaskStatus.WAITING, "missing", null, 0, null));
        if (!persistenceFacade.tryClaimTask(taskId, nodeOwnerId, taskClaimLeaseSeconds)) {
            LOGGER.warn("Kernel resumeTask lock conflict: taskId={}, owner={}", taskId, nodeOwnerId);
            AgentEvent lockConflict = event(task.conversationId(), task.taskId(), "task.locked",
                objectMapper.createObjectNode().put("taskId", taskId).put("owner", nodeOwnerId));
            persist(lockConflict);
            return new AgentResponse(task.conversationId(), "Task is currently owned by another node", List.of(lockConflict), task);
        }

        try {
        TaskState resumed = new TaskState(
            task.taskId(),
            task.conversationId(),
            TaskStatus.RESUMED,
            task.checkpoint(),
            task.nextWakeup(),
            task.retries(),
            task.result()
        );
        persistenceFacade.saveTask(resumed);
        AgentEvent resumedEvent = event(task.conversationId(), task.taskId(), "task.resumed", taskPayload(resumed));
        persist(resumedEvent);

        TaskState finished = new TaskState(
            task.taskId(),
            task.conversationId(),
            TaskStatus.FINISHED,
            task.checkpoint(),
            null,
            task.retries(),
            "Resumed task completed"
        );
        persistenceFacade.saveTask(finished);
        AgentEvent assistant = event(task.conversationId(), task.taskId(), "assistant.message",
            objectMapper.valueToTree("Resumed task completed"));
        persist(assistant);
        return new AgentResponse(task.conversationId(), "Resumed task completed", List.of(resumedEvent, assistant), finished);
        } finally {
            if (persistenceFacade.isTaskClaimedBy(taskId, nodeOwnerId)) {
                persistenceFacade.releaseTaskClaim(taskId, nodeOwnerId);
                LOGGER.debug("Kernel resumeTask claim released: taskId={}, owner={}", taskId, nodeOwnerId);
            }
        }
    }

    @Override
    public AgentResponse handleRealtimeEvent(String conversationId, String eventType, com.fasterxml.jackson.databind.JsonNode payload) {
        String safeConversationId = (conversationId == null || conversationId.isBlank()) ? UUID.randomUUID().toString() : conversationId;
        String safeEventType = (eventType == null || eventType.isBlank()) ? "unknown" : eventType;
        com.fasterxml.jackson.databind.JsonNode safePayload = payload == null ? objectMapper.nullNode() : payload;

        LOGGER.info("Kernel realtime event received: conversationId={}, eventType={}", safeConversationId, safeEventType);

        List<AgentEvent> emitted = new ArrayList<>();
        AgentEvent realtimeEvent = event(
            safeConversationId,
            null,
            "realtime." + safeEventType,
            safePayload
        );
        persist(realtimeEvent);
        emitted.add(realtimeEvent);

        String transcript = extractTranscript(safePayload);
        if ("transcript.final".equals(safeEventType) && transcript != null && !transcript.isBlank()) {
            LOGGER.info("Kernel realtime transcript routing: conversationId={}, chars={}", safeConversationId, transcript.length());
            AgentResponse response = handleUserMessage(safeConversationId, transcript);
            emitted.addAll(response.events());
            return new AgentResponse(
                response.conversationId(),
                response.message(),
                emitted,
                response.taskState()
            );
        }

        TaskState state = new TaskState(
            UUID.randomUUID().toString(),
            safeConversationId,
            TaskStatus.FINISHED,
            "realtime",
            null,
            0,
            "Realtime event accepted"
        );
        persistenceFacade.saveTask(state);
        LOGGER.debug("Kernel realtime event accepted without transcript routing: conversationId={}, eventType={}",
            safeConversationId,
            safeEventType);
        return new AgentResponse(safeConversationId, "Realtime event accepted", emitted, state);
    }

    private AgentEvent event(String conversationId, String taskId, String type, com.fasterxml.jackson.databind.JsonNode payload) {
        return new AgentEvent(conversationId, taskId, type, payload, Instant.now());
    }

    private void persist(AgentEvent event) {
        persistenceFacade.appendEvent(event, UUID.randomUUID().toString());
    }

    private com.fasterxml.jackson.databind.JsonNode taskPayload(TaskState taskState) {
        return objectMapper.createObjectNode()
            .put("taskId", taskState.taskId())
            .put("conversationId", taskState.conversationId())
            .put("status", taskState.status().name())
            .put("checkpoint", taskState.checkpoint())
            .put("nextWakeup", taskState.nextWakeup() == null ? null : taskState.nextWakeup().toString())
            .put("retries", taskState.retries())
            .put("result", taskState.result());
    }

    private com.fasterxml.jackson.databind.JsonNode dynamicRoutePayload(DynamicRouteState routeState) {
        return objectMapper.createObjectNode()
            .put("routeInstanceId", routeState.routeInstanceId())
            .put("templateId", routeState.templateId())
            .put("routeId", routeState.routeId())
            .put("status", routeState.status())
            .put("conversationId", routeState.conversationId())
            .put("createdAt", routeState.createdAt() == null ? null : routeState.createdAt().toString())
            .put("expiresAt", routeState.expiresAt() == null ? null : routeState.expiresAt().toString());
    }

    private void persistDynamicRouteIfPresent(String conversationId, com.fasterxml.jackson.databind.JsonNode toolData) {
        if (toolData == null || toolData.isNull()) {
            return;
        }
        com.fasterxml.jackson.databind.JsonNode dynamicRoute = toolData.path("dynamicRoute");
        if (!dynamicRoute.isObject()) {
            return;
        }
        String routeInstanceId = dynamicRoute.path("routeInstanceId").asText(null);
        String templateId = dynamicRoute.path("templateId").asText(null);
        String routeId = dynamicRoute.path("routeId").asText(null);
        if (routeInstanceId == null || templateId == null || routeId == null) {
            return;
        }
        String status = dynamicRoute.path("status").asText("STARTED");
        String persistedConversationId = dynamicRoute.path("conversationId").asText(conversationId);
        Instant createdAt = parseInstant(dynamicRoute.path("createdAt").asText(null), Instant.now());
        Instant expiresAt = parseInstant(dynamicRoute.path("expiresAt").asText(null), Instant.now().plus(1, ChronoUnit.HOURS));

        persistenceFacade.saveDynamicRoute(new DynamicRouteState(
            routeInstanceId,
            templateId,
            routeId,
            status,
            persistedConversationId,
            createdAt,
            expiresAt
        ));
        LOGGER.info("Kernel dynamic route persisted: conversationId={}, routeInstanceId={}, routeId={}, status={}",
            persistedConversationId,
            routeInstanceId,
            routeId,
            status);
    }

    private Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void emitMcpToolsDiscoveredIfNeeded(String conversationId, List<AgentEvent> history, List<AgentEvent> emitted) {
        if (blueprint.mcpToolCatalogs() == null || blueprint.mcpToolCatalogs().isEmpty()) {
            return;
        }
        boolean alreadyEmitted = history.stream().anyMatch(event -> "mcp.tools.discovered".equals(event.type()));
        if (alreadyEmitted) {
            return;
        }

        com.fasterxml.jackson.databind.node.ObjectNode payload = objectMapper.createObjectNode();
        payload.set("services", objectMapper.valueToTree(blueprint.mcpToolCatalogs()));
        AgentEvent event = event(conversationId, null, "mcp.tools.discovered", payload);
        persist(event);
        emitted.add(event);
        history.add(event);
    }

    private String extractTranscript(com.fasterxml.jackson.databind.JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        if (payload.isTextual()) {
            return payload.asText();
        }
        String text = payload.path("text").asText(null);
        if (text != null && !text.isBlank()) {
            return text;
        }
        text = payload.path("transcript").asText(null);
        if (text != null && !text.isBlank()) {
            return text;
        }
        return null;
    }
}
