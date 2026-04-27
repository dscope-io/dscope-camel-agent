package io.dscope.camel.agent.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.a2a.A2AToolClient;
import io.dscope.camel.agent.a2a.A2AToolContext;
import io.dscope.camel.agent.api.PersistenceFacade;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.agent.runtime.RuntimePlaceholderResolver;
import io.dscope.camel.mcp.McpClient;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CamelToolExecutor implements ToolExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CamelToolExecutor.class);

    private final CamelContext camelContext;
    private final ProducerTemplate producerTemplate;
    private final ObjectMapper objectMapper;
    private final A2AToolClient a2aToolClient;

    public CamelToolExecutor(ProducerTemplate producerTemplate, ObjectMapper objectMapper) {
        this(null, producerTemplate, objectMapper, null, A2AToolContext.EMPTY);
    }

    public CamelToolExecutor(CamelContext camelContext,
                             ProducerTemplate producerTemplate,
                             ObjectMapper objectMapper,
                             PersistenceFacade persistenceFacade,
                             A2AToolContext a2aToolContext) {
        this.camelContext = camelContext;
        this.producerTemplate = producerTemplate;
        this.objectMapper = objectMapper;
        this.a2aToolClient = new A2AToolClient(camelContext, objectMapper, persistenceFacade, a2aToolContext);
    }

    @Override
    public ToolResult execute(ToolSpec toolSpec, JsonNode arguments, ExecutionContext context) {
        String target = target(toolSpec);
        if (isA2ATarget(target)) {
            return a2aToolClient.execute(target, toolSpec, arguments, context);
        }
        Map<String, Object> headers = new HashMap<>();
        headers.put(AgentHeaders.CONVERSATION_ID, context.conversationId());
        headers.put(AgentHeaders.TASK_ID, context.taskId());
        headers.put(AgentHeaders.TOOL_NAME, toolSpec.name());
        headers.put(AgentHeaders.TRACE_ID, context.traceId());

        Object response;
        if (isMcpTarget(target)) {
            Object requestArguments = requestBody(arguments);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolSpec.name());
            params.put("arguments", requestArguments);
            LOGGER.debug("MCP tool invoke started: conversationId={}, taskId={}, tool={}, endpoint={}, argumentShape={}",
                context.conversationId(),
                context.taskId(),
                toolSpec.name(),
                target,
                argumentShape(requestArguments));
            try {
                response = McpClient.callResultJson(producerTemplate, target, "tools/call", params);
                LOGGER.debug("MCP tool invoke completed: conversationId={}, taskId={}, tool={}, endpoint={}, responseShape={}",
                    context.conversationId(),
                    context.taskId(),
                    toolSpec.name(),
                    target,
                    payloadShape(response));
            } catch (RuntimeException failure) {
                Throwable root = failure;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                String rootMessage = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
                LOGGER.warn("MCP tool invoke failed: conversationId={}, taskId={}, tool={}, endpoint={}, reason={}",
                    context.conversationId(),
                    context.taskId(),
                    toolSpec.name(),
                    target,
                    rootMessage);
                LOGGER.debug("MCP tool invoke failure details", failure);
                throw failure;
            }
        } else {
            response = producerTemplate.requestBodyAndHeaders(target, requestBody(arguments), headers);
        }
        JsonNode data = objectMapper.valueToTree(response);
        String content = data.isValueNode() ? data.asText() : data.toPrettyString();
        return new ToolResult(content, data, List.of());
    }

    private Object requestBody(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return null;
        }
        if (arguments.isValueNode()) {
            return arguments.asText();
        }
        return objectMapper.convertValue(arguments, Object.class);
    }

    private String target(ToolSpec toolSpec) {
        if (toolSpec.routeId() != null && !toolSpec.routeId().isBlank()) {
            return "direct:" + RuntimePlaceholderResolver.resolveRequiredExecutionTarget(camelContext, toolSpec.routeId(), "tools[].routeId");
        }
        if (toolSpec.endpointUri() != null && !toolSpec.endpointUri().isBlank()) {
            return RuntimePlaceholderResolver.resolveRequiredExecutionTarget(camelContext, toolSpec.endpointUri(), "tools[].endpointUri");
        }
        throw new IllegalArgumentException("Tool target is missing routeId/endpointUri: " + toolSpec.name());
    }

    private boolean isMcpTarget(String target) {
        return target != null && target.startsWith("mcp:");
    }

    private boolean isA2ATarget(String target) {
        return target != null && target.startsWith("a2a:");
    }

    private String argumentShape(Object arguments) {
        if (arguments == null) {
            return "null";
        }
        if (arguments instanceof Map<?, ?> map) {
            return "map(keys=" + map.keySet() + ")";
        }
        if (arguments instanceof List<?> list) {
            return "list(size=" + list.size() + ")";
        }
        return arguments.getClass().getSimpleName();
    }

    private String payloadShape(Object payload) {
        if (payload == null) {
            return "null";
        }
        if (payload instanceof Map<?, ?> map) {
            return "map(keys=" + map.keySet() + ")";
        }
        if (payload instanceof List<?> list) {
            return "list(size=" + list.size() + ")";
        }
        return payload.getClass().getSimpleName();
    }
}
