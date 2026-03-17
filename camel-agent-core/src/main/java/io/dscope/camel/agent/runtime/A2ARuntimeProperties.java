package io.dscope.camel.agent.runtime;

import java.util.Properties;

public record A2ARuntimeProperties(
    boolean enabled,
    String host,
    int port,
    String publicBaseUrl,
    String rpcPath,
    String ssePath,
    String agentCardPath,
    String agentEndpointUri,
    String exposedAgentsConfig,
    String plansConfig,
    String legacyBlueprint
) {
    public static A2ARuntimeProperties from(Properties properties) {
        String plansConfig = property(properties, "agent.agents-config", "");
        String legacyBlueprint = property(properties, "agent.blueprint", "classpath:agents/support/agent.md");
        String host = firstNonBlank(
            property(properties, "agent.runtime.a2a.host", ""),
            property(properties, "agent.runtime.a2a.bind-host", ""),
            property(properties, "agent.runtime.a2a.bindHost", ""),
            "0.0.0.0"
        );
        int port = intProperty(
            properties,
            firstPresentKey(
                properties,
                "agent.runtime.a2a.port",
                "agent.runtime.a2a.bind-port",
                "agent.runtime.a2a.bindPort",
                "agent.audit.api.port",
                "agui.rpc.port"
            ),
            8080
        );
        String publicBaseUrl = firstNonBlank(
            property(properties, "agent.runtime.a2a.public-base-url", ""),
            property(properties, "agent.runtime.a2a.publicBaseUrl", ""),
            "http://localhost:" + port
        );
        return new A2ARuntimeProperties(
            booleanProperty(properties, "agent.runtime.a2a.enabled", false),
            host,
            port,
            publicBaseUrl,
            normalizePath(firstNonBlank(
                property(properties, "agent.runtime.a2a.rpc-path", ""),
                property(properties, "agent.runtime.a2a.rpcPath", ""),
                "/a2a/rpc"
            )),
            normalizePath(firstNonBlank(
                property(properties, "agent.runtime.a2a.sse-path", ""),
                property(properties, "agent.runtime.a2a.ssePath", ""),
                "/a2a/sse"
            )),
            normalizePath(firstNonBlank(
                property(properties, "agent.runtime.a2a.agent-card-path", ""),
                property(properties, "agent.runtime.a2a.agentCardPath", ""),
                "/.well-known/agent-card.json"
            )),
            firstNonBlank(
                property(properties, "agent.runtime.a2a.agent-endpoint-uri", ""),
                property(properties, "agent.runtime.a2a.agentEndpointUri", ""),
                defaultAgentEndpointUri(plansConfig, legacyBlueprint)
            ),
            firstNonBlank(
                property(properties, "agent.runtime.a2a.exposed-agents-config", ""),
                property(properties, "agent.runtime.a2a.exposedAgentsConfig", "")
            ),
            plansConfig,
            legacyBlueprint
        );
    }

    public String rpcEndpointUrl() {
        return trimTrailingSlash(publicBaseUrl) + normalizePath(rpcPath);
    }

    public String sseBaseUrl() {
        return trimTrailingSlash(publicBaseUrl) + normalizePath(ssePath);
    }

    private static String defaultAgentEndpointUri(String plansConfig, String legacyBlueprint) {
        StringBuilder uri = new StringBuilder("agent:a2a");
        if ((plansConfig != null && !plansConfig.isBlank()) || (legacyBlueprint != null && !legacyBlueprint.isBlank())) {
            uri.append('?');
            boolean hasQuery = false;
            if (plansConfig != null && !plansConfig.isBlank()) {
                uri.append("plansConfig=").append(plansConfig);
                hasQuery = true;
            }
            if (legacyBlueprint != null && !legacyBlueprint.isBlank()) {
                if (hasQuery) {
                    uri.append('&');
                }
                uri.append("blueprint=").append(legacyBlueprint);
            }
        }
        return uri.toString();
    }

    private static String property(Properties properties, String key, String fallback) {
        String value = properties == null ? null : properties.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean booleanProperty(Properties properties, String key, boolean fallback) {
        String value = properties == null ? null : properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static String firstPresentKey(Properties properties, String... keys) {
        for (String key : keys) {
            if (properties != null && properties.getProperty(key) != null) {
                return key;
            }
        }
        return keys.length == 0 ? null : keys[0];
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "/";
        }
        return value.startsWith("/") ? value.trim() : "/" + value.trim();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
