package io.dscope.camel.agent.realtime.sip;

import com.fasterxml.jackson.databind.JsonNode;

public record SipCallStartRequest(SipCallInfo call, JsonNode session) {
}
