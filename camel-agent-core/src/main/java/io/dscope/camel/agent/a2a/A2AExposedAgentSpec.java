package io.dscope.camel.agent.a2a;

import java.util.List;
import java.util.Map;

public class A2AExposedAgentSpec {

    private String agentId;
    private String name;
    private String description;
    private String version;
    private boolean defaultAgent;
    private String planName;
    private String planVersion;
    private List<String> skills = List.of();
    private Map<String, Object> metadata = Map.of();

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public boolean isDefaultAgent() {
        return defaultAgent;
    }

    public void setDefaultAgent(boolean defaultAgent) {
        this.defaultAgent = defaultAgent;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public String getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(String planVersion) {
        this.planVersion = planVersion;
    }

    public List<String> getSkills() {
        return skills == null ? List.of() : skills;
    }

    public void setSkills(List<String> skills) {
        this.skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public Map<String, Object> getMetadata() {
        return metadata == null ? Map.of() : metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
