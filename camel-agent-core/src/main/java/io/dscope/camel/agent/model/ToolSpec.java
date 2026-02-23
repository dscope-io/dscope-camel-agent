package io.dscope.camel.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolSpec(
    String name,
    String description,
    String routeId,
    String endpointUri,
    JsonNode inputSchema,
    JsonNode outputSchema,
    ToolPolicy policy
) {
}
