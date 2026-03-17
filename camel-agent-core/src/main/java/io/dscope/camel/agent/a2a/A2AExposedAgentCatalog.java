package io.dscope.camel.agent.a2a;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class A2AExposedAgentCatalog {

    private final List<A2AExposedAgentSpec> agents;
    private final Map<String, A2AExposedAgentSpec> byId;
    private final A2AExposedAgentSpec defaultAgent;

    public A2AExposedAgentCatalog(List<A2AExposedAgentSpec> agents) {
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("A2A exposed-agents catalog must contain at least one agent");
        }
        Map<String, A2AExposedAgentSpec> mapped = new LinkedHashMap<>();
        A2AExposedAgentSpec resolvedDefault = null;
        for (A2AExposedAgentSpec agent : agents) {
            if (agent == null) {
                throw new IllegalArgumentException("A2A exposed-agents entries must not be null");
            }
            String agentId = required(agent.getAgentId(), "agentId");
            String name = required(agent.getName(), "name");
            String planName = required(agent.getPlanName(), "planName");
            String planVersion = required(agent.getPlanVersion(), "planVersion");
            agent.setAgentId(agentId);
            agent.setName(name);
            agent.setPlanName(planName);
            agent.setPlanVersion(planVersion);
            if (mapped.putIfAbsent(agentId, agent) != null) {
                throw new IllegalArgumentException("Duplicate A2A exposed agentId: " + agentId);
            }
            if (agent.isDefaultAgent()) {
                if (resolvedDefault != null) {
                    throw new IllegalArgumentException("Exactly one A2A exposed agent must be marked default");
                }
                resolvedDefault = agent;
            }
        }
        if (resolvedDefault == null) {
            throw new IllegalArgumentException("One A2A exposed agent must be marked default");
        }
        this.agents = List.copyOf(agents);
        this.byId = Map.copyOf(mapped);
        this.defaultAgent = resolvedDefault;
    }

    public List<A2AExposedAgentSpec> agents() {
        return agents;
    }

    public A2AExposedAgentSpec defaultAgent() {
        return defaultAgent;
    }

    public A2AExposedAgentSpec requireAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return defaultAgent;
        }
        A2AExposedAgentSpec resolved = byId.get(agentId.trim());
        if (resolved == null) {
            throw new IllegalArgumentException("Unknown A2A exposed agent: " + agentId);
        }
        return resolved;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A2A exposed agent " + field + " is required");
        }
        return value.trim();
    }
}
