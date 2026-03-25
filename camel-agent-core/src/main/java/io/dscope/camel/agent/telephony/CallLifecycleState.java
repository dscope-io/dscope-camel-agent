package io.dscope.camel.agent.telephony;

public enum CallLifecycleState {
    REQUESTED,
    TRUNKING,
    INCOMING_WEBHOOK_RECEIVED,
    ACCEPTED,
    ACTIVE,
    TRANSFERRED,
    HUNG_UP,
    COMPLETED,
    FAILED,
    BUSY,
    REJECTED
}