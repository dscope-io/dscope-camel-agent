package io.dscope.camel.agent.twilio;

import io.dscope.camel.agent.telephony.CallLifecycleState;
import io.dscope.camel.agent.telephony.OutboundSipCallRequest;
import io.dscope.camel.agent.telephony.OutboundSipCallResult;
import io.dscope.camel.agent.telephony.SipProviderClient;
import io.dscope.camel.agent.telephony.SipProviderMetadata;
import java.util.Objects;
import java.util.UUID;

public final class TwilioSipProviderClient implements SipProviderClient {

    private final TwilioCallGateway callGateway;
    private final TwilioProviderMetadataMapper metadataMapper;

    public TwilioSipProviderClient(TwilioCallGateway callGateway) {
        this(callGateway, new TwilioProviderMetadataMapper());
    }

    public TwilioSipProviderClient(TwilioCallGateway callGateway, TwilioProviderMetadataMapper metadataMapper) {
        this.callGateway = Objects.requireNonNull(callGateway, "callGateway");
        this.metadataMapper = Objects.requireNonNull(metadataMapper, "metadataMapper");
    }

    @Override
    public String providerName() {
        return "twilio";
    }

    @Override
    public OutboundSipCallResult placeOutboundCall(OutboundSipCallRequest request) throws Exception {
        TwilioCallPlacement placement = callGateway.placeCall(request);
        SipProviderMetadata providerMetadata = metadataMapper.map(placement);
        String requestId = UUID.randomUUID().toString();
        return new OutboundSipCallResult(
            providerName(),
            requestId,
            providerMetadata.providerCallId(),
            CallLifecycleState.REQUESTED,
            request.requestedConversationId(),
            providerMetadata
        );
    }
}