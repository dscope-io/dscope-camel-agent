package io.dscope.camel.agent.realtime.openai;

import io.dscope.camel.agent.telephony.CallLifecycleState;
import io.dscope.camel.agent.telephony.SipProviderMetadata;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class OpenAiRealtimeCallSessionRegistry {

    private final Map<String, OpenAiRealtimeCallSession> sessions = new ConcurrentHashMap<>();

    public OpenAiRealtimeCallSession register(
        String callId,
        String conversationId,
        String requestId,
        SipProviderMetadata providerMetadata,
        CallLifecycleState state
    ) {
        OpenAiRealtimeCallSession session = new OpenAiRealtimeCallSession(
            callId,
            conversationId,
            requestId,
            providerMetadata,
            state,
            Instant.now(),
            null
        );
        sessions.put(callId, session);
        return session;
    }

    public Optional<OpenAiRealtimeCallSession> find(String callId) {
        return Optional.ofNullable(sessions.get(callId));
    }

    public OpenAiRealtimeCallSession updateState(String callId, CallLifecycleState state, String failureReason) {
        OpenAiRealtimeCallSession updated = sessions.computeIfPresent(
            callId,
            (ignored, existing) -> existing.withState(state, failureReason)
        );
        if (updated == null) {
            throw new IllegalStateException("Unknown OpenAI realtime call session: " + callId);
        }
        return updated;
    }

    public Optional<OpenAiRealtimeCallSession> remove(String callId) {
        return Optional.ofNullable(sessions.remove(callId));
    }

    public Map<String, OpenAiRealtimeCallSession> snapshot() {
        return Map.copyOf(sessions);
    }
}