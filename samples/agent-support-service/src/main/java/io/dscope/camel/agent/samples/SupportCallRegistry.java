package io.dscope.camel.agent.samples;

import io.dscope.camel.agent.telephony.CallLifecycleState;
import io.dscope.camel.agent.telephony.OutboundSipCallResult;
import io.dscope.camel.agent.telephony.SipProviderMetadata;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class SupportCallRegistry {

    private final ConcurrentMap<String, PendingCall> pendingByRequestId = new ConcurrentHashMap<>();

    PendingCall registerPending(String destination, String conversationId, OutboundSipCallResult result) {
        PendingCall call = new PendingCall(
            result.requestId(),
            destination,
            conversationId,
            result.providerName(),
            result.providerReference(),
            result.providerMetadata(),
            result.status(),
            Instant.now()
        );
        pendingByRequestId.put(call.requestId(), call);
        return call;
    }

    Optional<PendingCall> findPendingByDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return Optional.empty();
        }
        return pendingByRequestId.values().stream()
            .filter(call -> destination.equals(call.destination()))
            .sorted(Comparator.comparing(PendingCall::createdAt).reversed())
            .findFirst();
    }

    Optional<PendingCall> findPending(String destination, String providerReference) {
        if (providerReference != null && !providerReference.isBlank()) {
            Optional<PendingCall> byProviderReference = pendingByRequestId.values().stream()
                .filter(call -> providerReference.equals(call.providerReference()))
                .sorted(Comparator.comparing(PendingCall::createdAt).reversed())
                .findFirst();
            if (byProviderReference.isPresent()) {
                return byProviderReference;
            }
        }
        return findPendingByDestination(destination);
    }

    Map<String, PendingCall> snapshot() {
        return Map.copyOf(pendingByRequestId);
    }

    record PendingCall(
        String requestId,
        String destination,
        String conversationId,
        String providerName,
        String providerReference,
        SipProviderMetadata providerMetadata,
        CallLifecycleState state,
        Instant createdAt
    ) {
    }
}