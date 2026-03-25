package io.dscope.camel.agent.telephony;

import com.fasterxml.jackson.databind.JsonNode;

public record OutboundSipCallRequest(
    String destination,
    String callerId,
    String purpose,
    String customerName,
    JsonNode metadata,
    String openAiProjectId,
    String requestedConversationId
) {
}