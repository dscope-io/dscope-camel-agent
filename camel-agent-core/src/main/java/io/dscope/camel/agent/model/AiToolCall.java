package io.dscope.camel.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

public record AiToolCall(
    String name,
    JsonNode arguments
) {
}
