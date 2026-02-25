package io.dscope.camel.agent.samples;

import com.fasterxml.jackson.databind.JsonNode;

public record SipCallStartRequest(SipCallInfo call, JsonNode session) {
}
