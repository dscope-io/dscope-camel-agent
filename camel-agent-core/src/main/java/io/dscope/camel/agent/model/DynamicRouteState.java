package io.dscope.camel.agent.model;

import java.time.Instant;

public record DynamicRouteState(
    String routeInstanceId,
    String templateId,
    String routeId,
    String status,
    String conversationId,
    Instant createdAt,
    Instant expiresAt
) {
}
