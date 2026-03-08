package io.dscope.camel.agent.runtime;

import java.util.Properties;

public record AgentRuntimeProperties(
    String agentsConfig,
    String blueprint,
    String initialPrompt,
    int auditTrailLimit,
    boolean auditApiEnabled,
    String auditApiHost,
    int auditApiPort
) {
    public static AgentRuntimeProperties from(Properties properties) {
        return new AgentRuntimeProperties(
            properties.getProperty("agent.agents-config", ""),
            properties.getProperty("agent.blueprint", "classpath:agents/support/agent.md"),
            properties.getProperty("agent.sample.prompt", "Find docs about persistence"),
            intProperty(properties, "agent.audit.trail.limit", 200),
            booleanProperty(properties, "agent.audit.api.enabled", false),
            properties.getProperty("agent.audit.api.host", "0.0.0.0"),
            intProperty(properties, "agent.audit.api.port", 8080)
        );
    }

    private static int intProperty(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean booleanProperty(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
}
