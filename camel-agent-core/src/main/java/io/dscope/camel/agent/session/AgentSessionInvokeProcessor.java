package io.dscope.camel.agent.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.TaskState;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class AgentSessionInvokeProcessor implements Processor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AgentSessionService sessionService;
    private final ObjectMapper objectMapper;

    public AgentSessionInvokeProcessor() {
        this(new AgentSessionService(), new ObjectMapper());
    }

    public AgentSessionInvokeProcessor(AgentSessionService sessionService, ObjectMapper objectMapper) {
        this.sessionService = sessionService == null ? new AgentSessionService() : sessionService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.objectMapper.findAndRegisterModules();
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        AgentSessionRequest request = readRequest(exchange);
        AgentSessionResponse response = sessionService.invoke(exchange, request);
        exchange.getMessage().setHeader("Content-Type", "application/json");
        exchange.getMessage().setBody(objectMapper.writeValueAsString(toJson(response)));
    }

    private AgentSessionRequest readRequest(Exchange exchange) throws Exception {
        Object body = exchange.getMessage().getBody();
        AgentSessionRequest request;
        if (body instanceof AgentSessionRequest sessionRequest) {
            request = sessionRequest;
        } else if (body instanceof Map<?, ?> map) {
            request = toRequest(objectMapper.convertValue(map, MAP_TYPE));
        } else if (body instanceof String text) {
            String trimmed = text.trim();
            if (!trimmed.isEmpty() && trimmed.startsWith("{")) {
                JsonNode root = objectMapper.readTree(trimmed);
                request = toRequest(objectMapper.convertValue(root, MAP_TYPE));
            } else {
                request = new AgentSessionRequest(text, "", "", "", "", "", Map.of());
            }
        } else if (body == null) {
            request = new AgentSessionRequest("", "", "", "", "", "", Map.of());
        } else {
            request = new AgentSessionRequest(exchange.getMessage().getBody(String.class), "", "", "", "", "", Map.of());
        }
        return overlayHeaders(exchange, request);
    }

    private AgentSessionRequest toRequest(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return new AgentSessionRequest("", "", "", "", "", "", Map.of());
        }
        String prompt = text(map.get("prompt"));
        if (prompt.isBlank()) {
            prompt = text(map.get("text"));
        }
        if (prompt.isBlank()) {
            prompt = text(map.get("message"));
        }
        Map<String, Object> params = nestedMap(map.get("params"));
        if (params.isEmpty()) {
            params = nestedMap(map.get("parameters"));
        }
        return new AgentSessionRequest(
            prompt,
            firstNonBlank(text(map.get("conversationId")), text(map.get("conversation-id"))),
            text(map.get("sessionId")),
            text(map.get("threadId")),
            text(map.get("planName")),
            text(map.get("planVersion")),
            params
        );
    }

    private AgentSessionRequest overlayHeaders(Exchange exchange, AgentSessionRequest request) {
        return new AgentSessionRequest(
            request.prompt(),
            firstNonBlank(
                request.conversationId(),
                exchange.getMessage().getHeader("conversationId", String.class),
                exchange.getMessage().getHeader("conversation-id", String.class),
                exchange.getMessage().getHeader("agent.conversationId", String.class)
            ),
            firstNonBlank(request.sessionId(), exchange.getMessage().getHeader("sessionId", String.class), exchange.getMessage().getHeader("agent.agui.sessionId", String.class)),
            firstNonBlank(request.threadId(), exchange.getMessage().getHeader("threadId", String.class), exchange.getMessage().getHeader("agent.agui.threadId", String.class)),
            firstNonBlank(request.planName(), exchange.getMessage().getHeader("planName", String.class), exchange.getMessage().getHeader("agent.planName", String.class)),
            firstNonBlank(request.planVersion(), exchange.getMessage().getHeader("planVersion", String.class), exchange.getMessage().getHeader("agent.planVersion", String.class)),
            request.params()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private ObjectNode toJson(AgentSessionResponse response) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("conversationId", text(response.conversationId()));
        root.put("sessionId", text(response.sessionId()));
        root.put("created", response.created());
        root.put("message", text(response.message()));
        root.put("resolvedPlanName", text(response.resolvedPlanName()));
        root.put("resolvedPlanVersion", text(response.resolvedPlanVersion()));
        root.put("resolvedBlueprint", text(response.resolvedBlueprint()));
        root.set("resolvedAi", objectMapper.valueToTree(response.resolvedAi()));
        root.set("params", objectMapper.valueToTree(response.params()));
        root.set("modelUsage", objectMapper.valueToTree(response.modelUsage()));
        root.set("events", eventsToJson(response.events()));
        root.set("taskState", taskStateToJson(response.taskState()));
        return root;
    }

    private ArrayNode eventsToJson(java.util.List<AgentEvent> events) {
        ArrayNode array = objectMapper.createArrayNode();
        if (events == null) {
            return array;
        }
        for (AgentEvent event : events) {
            if (event == null) {
                continue;
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("conversationId", text(event.conversationId()));
            node.put("taskId", text(event.taskId()));
            node.put("type", text(event.type()));
            node.put("timestamp", event.timestamp() == null ? "" : event.timestamp().toString());
            node.set("payload", event.payload() == null ? objectMapper.nullNode() : event.payload());
            array.add(node);
        }
        return array;
    }

    private JsonNode taskStateToJson(TaskState taskState) {
        if (taskState == null) {
            return objectMapper.nullNode();
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("taskId", text(taskState.taskId()));
        node.put("conversationId", text(taskState.conversationId()));
        node.put("status", taskState.status() == null ? "" : taskState.status().name());
        node.put("checkpoint", text(taskState.checkpoint()));
        node.put("nextWakeup", taskState.nextWakeup() == null ? "" : taskState.nextWakeup().toString());
        node.put("retries", taskState.retries());
        node.put("result", text(taskState.result()));
        return node;
    }
}