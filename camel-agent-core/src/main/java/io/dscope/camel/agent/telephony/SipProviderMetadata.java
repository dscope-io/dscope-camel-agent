package io.dscope.camel.agent.telephony;

import com.fasterxml.jackson.databind.JsonNode;

public record SipProviderMetadata(
    String providerName,
    String providerCallId,
    JsonNode raw
) {
}