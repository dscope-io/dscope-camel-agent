package io.dscope.camel.agent.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.config.CorrelationKeys;
import io.dscope.camel.agent.model.AgentEvent;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.registry.CorrelationRegistry;
import io.dscope.camel.a2a.A2AEndpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.CamelContext;

public final class A2AToolClient {

    private final CamelContext camelContext;
    private final ObjectMapper objectMapper;
    private final PersistenceFacade persistenceFacade;
    private final A2AToolContext toolContext;
    private final HttpClient httpClient;

    public A2AToolClient(CamelContext camelContext,
                         ObjectMapper objectMapper,
                         PersistenceFacade persistenceFacade,
                         A2AToolContext toolContext) {
        this(camelContext, objectMapper, persistenceFacade, toolContext, HttpClient.newHttpClient());
    }

    A2AToolClient(CamelContext camelContext,
                  ObjectMapper objectMapper,
                  PersistenceFacade persistenceFacade,
                  A2AToolContext toolContext,
                  HttpClient httpClient) {
        this.camelContext = camelContext;
        this.objectMapper = objectMapper;
        this.persistenceFacade = persistenceFacade;
        this.toolContext = toolContext == null ? A2AToolContext.EMPTY : toolContext;
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
    }

    public ToolResult execute(String target, ToolSpec toolSpec, JsonNode arguments, ExecutionContext context) {
        if (camelContext == null) {
            throw new IllegalStateException("A2A tool execution requires CamelContext");
        }
        try {
            A2AEndpoint endpoint = camelContext.getEndpoint(target, A2AEndpoint.class);
            if (endpoint == null) {
                throw new IllegalArgumentException("Unable to resolve A2A endpoint: " + target);
            }
            String remoteRpcUrl = normalizeRpcUrl(endpoint.getConfiguration().getRemoteUrl());
            String remoteAgentId = endpoint.getAgent();
            String method = determineMethod(arguments);
            ObjectNode params = buildParams(arguments, method, remoteAgentId, context);
            String remoteConversationId = text(params, "conversationId");

            appendEvent(context, "conversation.a2a.outbound.started", Map.of(
                "toolName", toolSpec.name(),
                "target", target,
                "remoteUrl", remoteRpcUrl,
                "method", method,
                "remoteAgentId", remoteAgentId,
                "remoteConversationId", remoteConversationId
            ));

            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.put("method", method);
            envelope.put("id", UUID.randomUUID().toString());
            envelope.set("params", params);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(remoteRpcUrl))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(envelope)))
                .header("Content-Type", "application/json");
            String authToken = endpoint.getConfiguration().getAuthToken();
            if (authToken != null && !authToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + authToken.trim());
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Remote A2A call failed with HTTP " + response.statusCode() + ": " + response.body());
            }
            if (root.hasNonNull("error")) {
                throw new IllegalStateException("Remote A2A call failed: " + root.path("error").toString());
            }

            JsonNode result = root.path("result");
            bindCorrelation(context.conversationId(), remoteAgentId, remoteConversationId, result);
            appendEvent(context, "conversation.a2a.outbound.completed", Map.of(
                "toolName", toolSpec.name(),
                "target", target,
                "remoteUrl", remoteRpcUrl,
                "method", method,
                "remoteAgentId", remoteAgentId,
                "remoteConversationId", remoteConversationId,
                "remoteTaskId", remoteTaskId(result)
            ));

            String content = extractContent(result);
            return new ToolResult(content, result, List.of());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("A2A tool execution failed for target " + target, e);
        }
    }

    private ObjectNode buildParams(JsonNode arguments, String method, String remoteAgentId, ExecutionContext context) {
        ObjectNode normalized = arguments != null && arguments.isObject()
            ? ((ObjectNode) arguments.deepCopy())
            : objectMapper.createObjectNode();
        normalized.remove("method");

        return switch (method) {
            case "SendMessage", "SendStreamingMessage" -> buildSendParams(normalized, remoteAgentId, context);
            case "GetTask", "CancelTask", "SubscribeToTask" -> ensureTaskId(normalized, context);
            default -> normalized;
        };
    }

    private ObjectNode buildSendParams(ObjectNode arguments, String remoteAgentId, ExecutionContext context) {
        ObjectNode params = arguments.deepCopy();
        ObjectNode metadata = params.has("metadata") && params.get("metadata").isObject()
            ? (ObjectNode) params.get("metadata")
            : objectMapper.createObjectNode();
        params.set("metadata", metadata);

        ObjectNode camelAgent = metadata.has("camelAgent") && metadata.get("camelAgent").isObject()
            ? (ObjectNode) metadata.get("camelAgent")
            : objectMapper.createObjectNode();
        metadata.set("camelAgent", camelAgent);

        CorrelationRegistry registry = CorrelationRegistry.global();
        String linkedConversationId = registry.resolve(context.conversationId(), CorrelationKeys.A2A_LINKED_CONVERSATION_ID, "");
        String parentConversationId = fallback(context.conversationId());
        String rootConversationId = firstNonBlank(
            registry.resolve(context.conversationId(), CorrelationKeys.A2A_ROOT_CONVERSATION_ID, ""),
            parentConversationId
        );
        String aguiSessionId = registry.resolve(context.conversationId(), CorrelationKeys.AGUI_SESSION_ID, "");
        String aguiRunId = registry.resolve(context.conversationId(), CorrelationKeys.AGUI_RUN_ID, "");
        String aguiThreadId = registry.resolve(context.conversationId(), CorrelationKeys.AGUI_THREAD_ID, "");

        camelAgent.put("localConversationId", fallback(context.conversationId()));
        camelAgent.put("linkedConversationId", fallback(linkedConversationId));
        camelAgent.put("parentConversationId", parentConversationId);
        camelAgent.put("rootConversationId", rootConversationId);
        camelAgent.put("traceId", fallback(context.traceId()));
        camelAgent.put("aguiSessionId", fallback(aguiSessionId));
        camelAgent.put("aguiRunId", fallback(aguiRunId));
        camelAgent.put("aguiThreadId", fallback(aguiThreadId));
        if (!toolContext.planName().isBlank()) {
            camelAgent.put("planName", toolContext.planName());
            metadata.put("planName", toolContext.planName());
        }
        if (!toolContext.planVersion().isBlank()) {
            camelAgent.put("planVersion", toolContext.planVersion());
            metadata.put("planVersion", toolContext.planVersion());
        }
        if (!toolContext.agentName().isBlank()) {
            camelAgent.put("agentName", toolContext.agentName());
            metadata.put("agentName", toolContext.agentName());
        }
        if (!toolContext.agentVersion().isBlank()) {
            camelAgent.put("agentVersion", toolContext.agentVersion());
            metadata.put("agentVersion", toolContext.agentVersion());
        }
        metadata.put("agentId", remoteAgentId == null ? "" : remoteAgentId);
        camelAgent.put("agentId", remoteAgentId == null ? "" : remoteAgentId);
        metadata.put("linkedConversationId", fallback(linkedConversationId));
        metadata.put("parentConversationId", parentConversationId);
        metadata.put("rootConversationId", rootConversationId);
        metadata.put("aguiSessionId", fallback(aguiSessionId));
        metadata.put("aguiRunId", fallback(aguiRunId));
        metadata.put("aguiThreadId", fallback(aguiThreadId));

        if (!params.hasNonNull("conversationId")) {
            String existingRemoteConversationId =
                registry.resolve(context.conversationId(), CorrelationKeys.A2A_REMOTE_CONVERSATION_ID, "");
            params.put("conversationId", firstNonBlank(existingRemoteConversationId, context.conversationId()));
        }
        if (!params.hasNonNull("idempotencyKey")) {
            params.put("idempotencyKey", UUID.randomUUID().toString());
        }
        if (!params.has("message") || !params.get("message").isObject()) {
            params.set("message", buildMessage(arguments, remoteAgentId, context));
        }
        return params;
    }

    private ObjectNode buildMessage(ObjectNode arguments, String remoteAgentId, ExecutionContext context) {
        String text = firstNonBlank(
            text(arguments, "text"),
            text(arguments, "prompt"),
            text(arguments, "input"),
            arguments.isValueNode() ? arguments.asText("") : ""
        );
        ObjectNode part = objectMapper.createObjectNode();
        part.put("partId", UUID.randomUUID().toString());
        part.put("type", "text");
        part.put("mimeType", "text/plain");
        part.put("text", text);

        ArrayNode parts = objectMapper.createArrayNode();
        parts.add(part);

        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("agentId", fallback(remoteAgentId));
        metadata.put("localConversationId", fallback(context.conversationId()));
        metadata.put("traceId", fallback(context.traceId()));

        ObjectNode message = objectMapper.createObjectNode();
        message.put("messageId", UUID.randomUUID().toString());
        message.put("role", "user");
        message.set("parts", parts);
        message.set("metadata", metadata);
        message.put("createdAt", Instant.now().toString());
        return message;
    }

    private ObjectNode ensureTaskId(ObjectNode params, ExecutionContext context) {
        if (!params.hasNonNull("taskId")) {
            String remoteTaskId = CorrelationRegistry.global().resolve(context.conversationId(), CorrelationKeys.A2A_REMOTE_TASK_ID, "");
            if (remoteTaskId.isBlank()) {
                throw new IllegalArgumentException("A2A follow-up call requires taskId or an existing correlated remote task");
            }
            params.put("taskId", remoteTaskId);
        }
        return params;
    }

    private void bindCorrelation(String conversationId, String remoteAgentId, String remoteConversationId, JsonNode result) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        CorrelationRegistry registry = CorrelationRegistry.global();
        if (remoteAgentId != null && !remoteAgentId.isBlank()) {
            registry.bind(conversationId, CorrelationKeys.A2A_AGENT_ID, remoteAgentId);
        }
        if (remoteConversationId != null && !remoteConversationId.isBlank()) {
            registry.bind(conversationId, CorrelationKeys.A2A_REMOTE_CONVERSATION_ID, remoteConversationId);
        }
        String remoteTaskId = remoteTaskId(result);
        if (!remoteTaskId.isBlank()) {
            registry.bind(conversationId, CorrelationKeys.A2A_REMOTE_TASK_ID, remoteTaskId);
        }
        String linkedConversationId = firstNonBlank(
            text(result.path("task").path("metadata").path("camelAgent"), "linkedConversationId"),
            text(result.path("task").path("metadata").path("camelAgent"), "localConversationId"),
            text(result.path("task").path("metadata"), "linkedConversationId")
        );
        if (!linkedConversationId.isBlank()) {
            registry.bind(conversationId, CorrelationKeys.A2A_LINKED_CONVERSATION_ID, linkedConversationId);
        }
        String parentConversationId = firstNonBlank(
            text(result.path("task").path("metadata").path("camelAgent"), "parentConversationId"),
            text(result.path("task").path("metadata"), "parentConversationId")
        );
        if (!parentConversationId.isBlank()) {
            registry.bind(conversationId, CorrelationKeys.A2A_PARENT_CONVERSATION_ID, parentConversationId);
        }
        String rootConversationId = firstNonBlank(
            text(result.path("task").path("metadata").path("camelAgent"), "rootConversationId"),
            text(result.path("task").path("metadata"), "rootConversationId")
        );
        if (!rootConversationId.isBlank()) {
            registry.bind(conversationId, CorrelationKeys.A2A_ROOT_CONVERSATION_ID, rootConversationId);
        }
    }

    private void appendEvent(ExecutionContext context, String type, Map<String, Object> payload) {
        if (persistenceFacade == null || context == null || context.conversationId() == null || context.conversationId().isBlank()) {
            return;
        }
        persistenceFacade.appendEvent(
            new AgentEvent(context.conversationId(), context.taskId(), type, objectMapper.valueToTree(payload), Instant.now()),
            UUID.randomUUID().toString()
        );
    }

    private String determineMethod(JsonNode arguments) {
        String method = arguments == null ? "" : text(arguments, "method");
        return method.isBlank() ? "SendMessage" : method;
    }

    private String normalizeRpcUrl(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalArgumentException("A2A remoteUrl is required");
        }
        URI uri = URI.create(remoteUrl.trim());
        String scheme = uri.getScheme();
        String httpScheme = switch (scheme == null ? "" : scheme) {
            case "ws" -> "http";
            case "wss" -> "https";
            default -> scheme;
        };
        String path = uri.getPath() == null || uri.getPath().isBlank() ? "/a2a" : uri.getPath();
        if (!path.endsWith("/rpc")) {
            path = path.endsWith("/") ? path + "rpc" : path + "/rpc";
        }
        String authority = uri.getHost();
        if (authority == null || authority.isBlank()) {
            throw new IllegalArgumentException("A2A remoteUrl must include a host: " + remoteUrl);
        }
        if (uri.getPort() >= 0) {
            authority = authority + ":" + uri.getPort();
        }
        String query = uri.getQuery() == null || uri.getQuery().isBlank() ? "" : "?" + uri.getQuery();
        return httpScheme + "://" + authority + path + query;
    }

    private String extractContent(JsonNode result) {
        if (result == null || result.isNull() || result.isMissingNode()) {
            return "";
        }
        List<JsonNode> candidates = List.of(
            result.path("task").path("latestMessage"),
            result.path("task").path("messages").path(result.path("task").path("messages").size() - 1),
            result.path("task"),
            result
        );
        for (JsonNode candidate : candidates) {
            String text = messageText(candidate);
            if (!text.isBlank()) {
                return text;
            }
        }
        return result.isValueNode() ? result.asText("") : result.toPrettyString();
    }

    private String messageText(JsonNode message) {
        if (message == null || !message.isObject()) {
            return "";
        }
        if (message.hasNonNull("text")) {
            return message.path("text").asText("");
        }
        JsonNode parts = message.path("parts");
        if (!parts.isArray()) {
            return "";
        }
        StringBuilder combined = new StringBuilder();
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                if (combined.length() > 0) {
                    combined.append('\n');
                }
                combined.append(text);
            }
        }
        return combined.toString();
    }

    private String remoteTaskId(JsonNode result) {
        return firstNonBlank(
            text(result, "taskId"),
            text(result.path("task"), "taskId")
        );
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String fallback(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
