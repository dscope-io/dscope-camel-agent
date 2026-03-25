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
    AgUiPreRunSpec aguiPreRun,
    List<ResolvedBlueprintResource> resources
) {

    public AgentBlueprint {
        tools = tools == null ? List.of() : List.copyOf(tools);
        jsonRouteTemplates = jsonRouteTemplates == null ? List.of() : List.copyOf(jsonRouteTemplates);
        mcpToolCatalogs = mcpToolCatalogs == null ? List.of() : List.copyOf(mcpToolCatalogs);
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), null, null, List.of());
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        List<JsonNode> mcpToolCatalogs
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, mcpToolCatalogs, null, null, List.of());
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        RealtimeSpec realtime
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), realtime, null, List.of());
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
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), realtime, aguiPreRun, List.of());
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        RealtimeSpec realtime,
        AgUiPreRunSpec aguiPreRun,
        List<ResolvedBlueprintResource> resources
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), realtime, aguiPreRun, resources);
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
        this(name, version, systemInstruction, tools, jsonRouteTemplates, mcpToolCatalogs, realtime, null, List.of());
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        List<JsonNode> mcpToolCatalogs,
        RealtimeSpec realtime,
        AgUiPreRunSpec aguiPreRun
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, mcpToolCatalogs, realtime, aguiPreRun, List.of());
    }
}
