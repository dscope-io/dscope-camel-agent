package io.dscope.camel.agent.model;

public record RetryPolicySpec(
    Integer maxRetries,
    Long intervalMs,
    Boolean exponentialBackoff,
    Double multiplier,
    Long maxIntervalMs
) {
}