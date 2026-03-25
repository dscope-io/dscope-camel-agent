package io.dscope.camel.agent.twilio;

import io.dscope.camel.agent.telephony.OutboundSipCallRequest;

public interface TwilioCallGateway {

    TwilioCallPlacement placeCall(OutboundSipCallRequest request) throws Exception;
}