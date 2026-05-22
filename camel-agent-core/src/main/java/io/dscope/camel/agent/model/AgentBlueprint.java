package io.dscope.camel.agent.model;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentBlueprint(
    String name,
    String version,
    String systemInstruction,
    List<ToolSpec> tools,
    List<JsonRouteTemplateSpec> jsonRouteTemplates,
    List<JsonNode> mcpToolCatalogs,
    RealtimeSpec realtime,
    AgUiPreRunSpec aguiPreRun,
    List<ResolvedBlueprintResource> resources,
    A2UiSpec a2ui,
    List<ExceptionPolicySpec> exceptionPolicies
) {

    public AgentBlueprint {
        tools = tools == null ? List.of() : List.copyOf(tools);
        jsonRouteTemplates = jsonRouteTemplates == null ? List.of() : List.copyOf(jsonRouteTemplates);
        mcpToolCatalogs = mcpToolCatalogs == null ? List.of() : List.copyOf(mcpToolCatalogs);
        a2ui = a2ui == null ? new A2UiSpec(List.of()) : a2ui;
        resources = resources == null ? List.of() : List.copyOf(resources);
        exceptionPolicies = exceptionPolicies == null ? List.of() : List.copyOf(exceptionPolicies);
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), null, null, List.of(), null, List.of());
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        List<JsonNode> mcpToolCatalogs
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, mcpToolCatalogs, null, null, List.of(), null, List.of());
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        RealtimeSpec realtime
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), realtime, null, List.of(), null, List.of());
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
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), realtime, aguiPreRun, List.of(), null, List.of());
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
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), realtime, aguiPreRun, resources, null, List.of());
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
        this(name, version, systemInstruction, tools, jsonRouteTemplates, mcpToolCatalogs, realtime, null, List.of(), null, List.of());
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        RealtimeSpec realtime,
        AgUiPreRunSpec agUiPreRun,
        A2UiSpec a2ui,
        List<ResolvedBlueprintResource> resources
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, List.of(), realtime, agUiPreRun, resources, a2ui, List.of());
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        List<JsonNode> mcpToolCatalogs,
        RealtimeSpec realtime,
        AgUiPreRunSpec agUiPreRun,
        List<ResolvedBlueprintResource> resources,
        A2UiSpec a2ui
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, mcpToolCatalogs, realtime, agUiPreRun, resources, a2ui, List.of());
    }

    public AgentBlueprint(
        String name,
        String version,
        String systemInstruction,
        List<ToolSpec> tools,
        List<JsonRouteTemplateSpec> jsonRouteTemplates,
        List<JsonNode> mcpToolCatalogs,
        RealtimeSpec realtime,
        AgUiPreRunSpec agUiPreRun,
        List<ResolvedBlueprintResource> resources
    ) {
        this(name, version, systemInstruction, tools, jsonRouteTemplates, mcpToolCatalogs, realtime, agUiPreRun, resources, null, List.of());
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
        this(name, version, systemInstruction, tools, jsonRouteTemplates, mcpToolCatalogs, realtime, aguiPreRun, List.of(), null, List.of());
    }
}
