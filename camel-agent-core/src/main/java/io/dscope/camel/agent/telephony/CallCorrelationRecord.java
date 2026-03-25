package io.dscope.camel.agent.telephony;

import java.time.Instant;

public record CallCorrelationRecord(
    String requestId,
    String conversationId,
    String openAiCallId,
    SipProviderMetadata providerMetadata,
    CallLifecycleState state,
    Instant updatedAt,
    String failureReason
) {
}