**Provider-Agnostic SIP Refactor Map**

**Target module boundaries**
1. camel-agent-core
- Own provider-neutral telephony contracts
- Own OpenAI Realtime SIP webhook, call-control, and websocket-monitoring support
- Own canonical call correlation and lifecycle state

2. camel-agent-twilio
- Own Twilio-specific SIP provider implementation
- Own Twilio-specific request mapping, config validation, and provider metadata extraction
- Keep existing Twilio envelope processors only as compatibility helpers, not as the canonical path

3. samples/agent-support-service
- Own tool declaration, local route wiring, bean registration, and runtime configuration
- Depend only on provider-neutral core contracts plus selected provider module beans

**Class-by-class refactor target**

**In camel-agent-core**
1. Add io.dscope.camel.agent.telephony.OutboundSipCallRequest
- Purpose: provider-neutral outbound call request model
- Fields: destination, callerId, purpose, customerName, metadata, openAiProjectId, requestedConversationId

2. Add io.dscope.camel.agent.telephony.OutboundSipCallResult
- Purpose: provider-neutral asynchronous call-init result model
- Fields: providerName, requestId, providerReference, status, conversationId, metadata

3. Add io.dscope.camel.agent.telephony.SipProviderClient
- Purpose: provider-neutral interface implemented by Twilio and future providers
- Method: placeOutboundCall(OutboundSipCallRequest request)

4. Add io.dscope.camel.agent.telephony.SipProviderMetadata
- Purpose: generic holder for provider details mapped from provider-specific responses

5. Add io.dscope.camel.agent.telephony.CallCorrelationRecord
- Purpose: canonical mapping among requestId, provider metadata, OpenAI call_id, conversationId, and lifecycle state

6. Add io.dscope.camel.agent.realtime.openai.OpenAiRealtimeIncomingCallEvent
- Purpose: normalized model for realtime.call.incoming webhook payload

7. Add io.dscope.camel.agent.realtime.openai.OpenAiRealtimeWebhookVerifier
- Purpose: verify OpenAI webhook signatures and expose idempotency-related fields
- First slice can expose a verifier contract and a minimal implementation seam if full SDK integration is deferred

8. Add io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallControlRequestFactory
- Purpose: build accept, reject, hangup, and refer request payloads

9. Add io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallControlClient
- Purpose: execute OpenAI call-control HTTP requests

10. Add io.dscope.camel.agent.realtime.openai.OpenAiRealtimeCallSessionRegistry
- Purpose: maintain active call_id to conversation correlation and websocket monitor handles

11. Optionally evolve io.dscope.camel.agent.realtime.RealtimeRelayClient or add a sibling abstraction
- Purpose: support call_id-oriented monitoring without forcing all flows through conversationId-only semantics

**Existing core classes to keep and reuse**
1. io.dscope.camel.agent.realtime.RealtimeRelayClient
- Keep as the generic relay abstraction

2. io.dscope.camel.agent.realtime.OpenAiRealtimeRelayClient
- Keep in core because it is OpenAI-specific and already correctly placed
- Extend or adapt for call_id-based websocket attachment if needed

3. io.dscope.camel.agent.realtime.sip.SipCallInfo
- Keep as generic SIP model in core

4. io.dscope.camel.agent.realtime.sip.SipCallStartRequest
- Keep as generic SIP model in core

**In camel-agent-twilio**
1. Add io.dscope.camel.agent.twilio.TwilioSipProviderClient
- Implements SipProviderClient
- Owns Twilio-specific outbound dial or trunk-init mapping

2. Add io.dscope.camel.agent.twilio.TwilioSipProviderProperties or equivalent
- Owns Twilio-specific runtime configuration and validation

3. Add io.dscope.camel.agent.twilio.TwilioProviderMetadataMapper
- Maps Twilio response fields into SipProviderMetadata

4. Keep io.dscope.camel.agent.twilio.TwilioCallStartEnvelopeProcessor only as compatibility helper
5. Keep io.dscope.camel.agent.twilio.TwilioTranscriptEnvelopeProcessor only as compatibility helper

**In sample module**
1. Add outbound-call tool declaration in agents/support/v2/agent.md
2. Add tool route and processor using SipProviderClient
3. Add OpenAI webhook route using core verifier and call-control client
4. Bind selected SipProviderClient implementation from Twilio module

**Migration order**
1. Add core telephony contracts
2. Add core OpenAI SIP call-control contracts and request factory
3. Add Twilio provider implementation against SipProviderClient
4. Update sample wiring to depend on SipProviderClient
5. Add OpenAI webhook handling and call acceptance flow
6. Extend tests and docs
