package io.dscope.camel.agent.twilio;

import io.dscope.camel.agent.telephony.SipProviderMetadata;

public final class TwilioProviderMetadataMapper {

    public SipProviderMetadata map(TwilioCallPlacement placement) {
        if (placement == null) {
            return new SipProviderMetadata("twilio", null, null);
        }
        return new SipProviderMetadata("twilio", placement.providerCallId(), placement.rawResponse());
    }
}