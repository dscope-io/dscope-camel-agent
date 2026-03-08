package io.dscope.camel.agent.runtime;

public record AgentPlanVersionSpec(
    String version,
    boolean defaultVersion,
    String blueprint
) {
}
