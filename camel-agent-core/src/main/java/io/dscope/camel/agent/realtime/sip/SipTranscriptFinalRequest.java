package io.dscope.camel.agent.realtime.sip;

public record SipTranscriptFinalRequest(String text, String transcript, SipTranscriptPayload payload) {
}
