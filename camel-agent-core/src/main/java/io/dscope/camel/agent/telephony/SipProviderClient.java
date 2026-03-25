package io.dscope.camel.agent.telephony;

public interface SipProviderClient {

    String providerName();

    OutboundSipCallResult placeOutboundCall(OutboundSipCallRequest request) throws Exception;
}