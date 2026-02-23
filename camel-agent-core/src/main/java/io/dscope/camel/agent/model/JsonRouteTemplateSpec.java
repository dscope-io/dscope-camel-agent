package io.dscope.camel.agent.model;

import com.fasterxml.jackson.databind.JsonNode;

public record JsonRouteTemplateSpec(
    String id,
    String toolName,
    String description,
    String invokeUriParam,
    JsonNode parametersSchema,
    JsonNode routeTemplate
) {
}
