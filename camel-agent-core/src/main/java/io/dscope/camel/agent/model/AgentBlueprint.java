package io.dscope.camel.agent.model;

import java.util.List;

public record AgentBlueprint(
    String name,
    String version,
    String systemInstruction,
    List<ToolSpec> tools
) {
}
