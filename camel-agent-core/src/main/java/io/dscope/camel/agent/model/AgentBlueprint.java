package io.dscope.camel.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgentBlueprint(
    String name,
    String version,
    String systemInstruction,
    List<ToolSpec> tools,
    List<JsonRouteTemplateSpec> jsonRouteTemplates,
    List<JsonNode> mcpToolCatalogs
) {

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of());
    }
}
