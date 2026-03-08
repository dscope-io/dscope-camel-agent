package io.dscope.camel.agent.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import io.dscope.camel.mcp.McpClient;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CamelToolExecutor implements ToolExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CamelToolExecutor.class);

    private final ProducerTemplate producerTemplate;
    private final ObjectMapper objectMapper;

    public CamelToolExecutor(ProducerTemplate producerTemplate, ObjectMapper objectMapper) {
        this.producerTemplate = producerTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolResult execute(ToolSpec toolSpec, JsonNode arguments, ExecutionContext context) {
        String target = target(toolSpec);
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
            return "direct:" + toolSpec.routeId();
        }
        if (toolSpec.endpointUri() != null && !toolSpec.endpointUri().isBlank()) {
            return toolSpec.endpointUri();
        }
        throw new IllegalArgumentException("Tool must define routeId or endpointUri: " + toolSpec.name());
    }

    private boolean isMcpTarget(String target) {
        return target != null && target.startsWith("mcp:");
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
