package io.dscope.camel.agent.runtime;

import java.util.List;

public record AgentPlanSpec(
    String name,
    boolean defaultPlan,
    AgentAiConfig ai,
    List<AgentPlanVersionSpec> versions
) {

    public AgentPlanSpec {
        ai = ai == null ? AgentAiConfig.empty() : ai;
        versions = versions == null ? List.of() : List.copyOf(versions);
    }
}
