package io.dscope.camel.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.dscope.camel.agent.model.ExecutionContext;
import io.dscope.camel.agent.model.ToolResult;
import io.dscope.camel.agent.model.ToolSpec;

public interface ToolExecutor {

    ToolResult execute(ToolSpec toolSpec, JsonNode arguments, ExecutionContext context);
}
