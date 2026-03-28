package io.dscope.camel.agent.runtime;

public record ResolvedAgentPlan(
    String planName,
    String planVersion,
    String blueprint,
    AgentAiConfig ai,
    boolean explicitPlan,
    boolean explicitVersion,
    boolean selectionPersistRequired,
    boolean legacyMode
) {

    public ResolvedAgentPlan {
        ai = ai == null ? AgentAiConfig.empty() : ai;
    }

    public static ResolvedAgentPlan legacy(String blueprint) {
        return new ResolvedAgentPlan("", "", blueprint, AgentAiConfig.empty(), false, false, false, true);
    }
}
