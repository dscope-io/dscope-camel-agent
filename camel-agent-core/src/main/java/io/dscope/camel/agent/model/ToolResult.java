package io.dscope.camel.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record ToolResult(
    String content,
    JsonNode data,
    List<String> artifacts
) {
}
