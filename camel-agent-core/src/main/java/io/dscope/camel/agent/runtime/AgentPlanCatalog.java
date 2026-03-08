package io.dscope.camel.agent.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AgentPlanCatalog(List<AgentPlanSpec> plans) {

    public AgentPlanCatalog {
        plans = plans == null ? List.of() : List.copyOf(plans);
        validate(plans);
    }

    public AgentPlanSpec defaultPlan() {
        return plans.stream()
            .filter(AgentPlanSpec::defaultPlan)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("agents.yaml must define exactly one default plan"));
    }

    public AgentPlanSpec requirePlan(String planName) {
        return plans.stream()
            .filter(plan -> Objects.equals(plan.name(), planName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown plan: " + planName));
    }

    public AgentPlanVersionSpec defaultVersion(String planName) {
        return requireVersion(planName, null);
    }

    public AgentPlanVersionSpec requireVersion(String planName, String version) {
        AgentPlanSpec plan = requirePlan(planName);
        if (version == null || version.isBlank()) {
            return plan.versions().stream()
                .filter(AgentPlanVersionSpec::defaultVersion)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Plan " + planName + " must define exactly one default version"));
        }
        return plan.versions().stream()
            .filter(candidate -> Objects.equals(candidate.version(), version))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown version " + version + " for plan " + planName));
    }

    private static void validate(List<AgentPlanSpec> plans) {
        if (plans == null || plans.isEmpty()) {
            throw new IllegalArgumentException("agents.yaml must contain at least one plan");
        }

        long defaultPlans = plans.stream().filter(AgentPlanSpec::defaultPlan).count();
        if (defaultPlans != 1) {
            throw new IllegalArgumentException("agents.yaml must define exactly one default plan");
        }

        Set<String> names = new LinkedHashSet<>();
        for (AgentPlanSpec plan : plans) {
            if (plan == null || blank(plan.name())) {
                throw new IllegalArgumentException("Each plan requires a non-empty name");
            }
            if (!names.add(plan.name())) {
                throw new IllegalArgumentException("Duplicate plan name: " + plan.name());
            }
            List<AgentPlanVersionSpec> versions = plan.versions();
            if (versions == null || versions.isEmpty()) {
                throw new IllegalArgumentException("Plan " + plan.name() + " must define at least one version");
            }
            long defaults = versions.stream().filter(AgentPlanVersionSpec::defaultVersion).count();
            if (defaults != 1) {
                throw new IllegalArgumentException("Plan " + plan.name() + " must define exactly one default version");
            }
            Set<String> versionNames = new LinkedHashSet<>();
            for (AgentPlanVersionSpec version : versions) {
                if (version == null || blank(version.version()) || blank(version.blueprint())) {
                    throw new IllegalArgumentException("Plan " + plan.name() + " versions require version and blueprint");
                }
                if (!versionNames.add(version.version())) {
                    throw new IllegalArgumentException("Duplicate version " + version.version() + " for plan " + plan.name());
                }
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
