package io.dscope.camel.agent.twilio;

import com.fasterxml.jackson.databind.JsonNode;

public record TwilioCallPlacement(
    String providerCallId,
    JsonNode rawResponse
) {
}