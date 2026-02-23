package io.dscope.camel.agent.model;

public record ToolPolicy(
    boolean redactPii,
    int rateLimitPerMinute,
    long timeoutMs
) {
}
