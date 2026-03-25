package io.dscope.camel.agent.realtime.openai;

import io.dscope.camel.agent.telephony.CallLifecycleState;
import io.dscope.camel.agent.telephony.SipProviderMetadata;
import java.time.Instant;

public record OpenAiRealtimeCallSession(
    String callId,
    String conversationId,
    String requestId,
    SipProviderMetadata providerMetadata,
    CallLifecycleState state,
    Instant updatedAt,
    String failureReason
) {

    public OpenAiRealtimeCallSession withState(CallLifecycleState nextState, String nextFailureReason) {
        return new OpenAiRealtimeCallSession(
            callId,
            conversationId,
            requestId,
            providerMetadata,
            nextState,
            Instant.now(),
            nextFailureReason
        );
    }
}