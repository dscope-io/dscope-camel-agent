package io.dscope.camel.agent.runtime;

public record ResolvedAgentPlan(
    String planName,
    String planVersion,
    String blueprint,
    boolean explicitPlan,
    boolean explicitVersion,
    boolean selectionPersistRequired,
    boolean legacyMode
) {

    public static ResolvedAgentPlan legacy(String blueprint) {
        return new ResolvedAgentPlan("", "", blueprint, false, false, false, true);
    }
}
