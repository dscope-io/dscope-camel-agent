package io.dscope.camel.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgentBlueprint(
    String name,
    String version,
    String systemInstruction,
    List<ToolSpec> tools,
    List<JsonRouteTemplateSpec> jsonRouteTemplates,
    List<JsonNode> mcpToolCatalogs,
    RealtimeSpec realtime,
    AgUiPreRunSpec aguiPreRun
) {

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), null, null);
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        List<JsonNode> mcpToolCatalogs
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, mcpToolCatalogs, null, null);
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        RealtimeSpec realtime
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), realtime, null);
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        RealtimeSpec realtime,
        AgUiPreRunSpec aguiPreRun
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), realtime, aguiPreRun);
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        List<JsonNode> mcpToolCatalogs,
        RealtimeSpec realtime
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, mcpToolCatalogs, realtime, null);
    }
}
