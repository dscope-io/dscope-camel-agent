package io.dscope.camel.agent.telephony;

public record OutboundSipCallResult(
    String providerName,
    String requestId,
    String providerReference,
    CallLifecycleState status,
    String conversationId,
    SipProviderMetadata providerMetadata
) {
}