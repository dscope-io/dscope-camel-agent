package io.dscope.camel.agent.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.ToolExecutor;
import io.dscope.camel.agent.config.AgentHeaders;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.ProducerTemplate;

public class CamelToolExecutor implements ToolExecutor {

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

        Object response = producerTemplate.requestBodyAndHeaders(target, requestBody(arguments), headers);
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
}
