package io.dscope.camel.agent.runtime;

import java.util.List;

public record AgentPlanSpec(
    String name,
    boolean defaultPlan,
    List<AgentPlanVersionSpec> versions
) {
}
