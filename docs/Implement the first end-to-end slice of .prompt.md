Implement the first end-to-end slice of outbound agent-initiated phone calling for the CamelAIAgentComponent repo in a way that follows the OpenAI Realtime SIP guide directly and keeps the design provider agnostic.

Goal
Build a reusable outbound-calling path where core owns provider-neutral telephony contracts and OpenAI Realtime SIP control, while Twilio is one SIP provider implementation behind those contracts. The application should initiate the outbound call request through a provider-neutral interface, receive and verify the OpenAI realtime.call.incoming webhook, accept or reject the call through OpenAI call-control APIs, monitor the active call over the OpenAI Realtime websocket using call_id, and persist correlation and audit state.

Scope constraints
- Implement the first slice only, but make it real and runnable.
- Follow the OpenAI Realtime SIP model directly.
- Keep provider-neutral telephony contracts and OpenAI SIP control in camel-agent-core.
- Keep Twilio-specific outbound dial or trunking logic in camel-agent-twilio.
- Do not build a custom Twilio Media Streams bridge for this slice.
- Design so another SIP provider module can be added later without changing OpenAI SIP control logic.
- Keep sample-specific blueprint, route, and bean wiring in samples/agent-support-service.
- Treat outbound call creation as asynchronous: the tool should return quickly with correlation data and not block on the live call.
- Use OpenAI call_id as the canonical live-call identifier after the webhook arrives.
- Preserve internal conversation and audit correlation, but do not force the SIP flow through the older transcript-envelope adapter path unless it cleanly fits.
- Do not introduce alternate providers or broader call-center features.
- Do not fix unrelated reactor issues outside the Twilio/OpenAI SIP path.

Existing seams to reuse
- samples/agent-support-service/src/main/resources/routes/twilio-management.camel.yaml already contains Twilio-oriented outbound scaffolding that can evolve into SIP-trunk initiation support.
- camel-agent-core already contains a generic realtime relay interface and an OpenAI realtime relay client, which is the correct side for OpenAI SIP control concerns.
- camel-agent-core already contains generic SIP-shaped models that can be expanded into provider-neutral telephony contracts.
- camel-agent-twilio already contains Twilio-specific normalization helpers and is the correct place for provider implementation code.
- camel-agent-core and the sample already contain audit, session, and tool orchestration patterns that should be reused for lifecycle tracking.
- samples/agent-support-service/src/main/java/io/dscope/camel/agent/samples/SupportTicketLifecycleProcessor.java is the closest pattern for a stateful tool processor.
- samples/agent-support-service/src/main/resources/agents/support/v2/agent.md is where the public tool should be declared.

Required deliverables
1. Add a new public tool for outbound calling in the support agent blueprint.
   Suggested name: support.call.outbound.
   The tool should accept at minimum:
   - destination phone number
   - reason or prompt context
   - optional customer name
   - optional metadata object
   The tool result should return quickly and include:
   - provider name
   - requested destination
   - internal correlation id
   - status such as requested or trunking
   - any provisional provider metadata available before the OpenAI webhook arrives

2. Add provider-neutral telephony interfaces and OpenAI SIP support in core.
   Add shared contracts and core services for:
   - outbound SIP call request and result models
   - a SipProviderClient interface
   - provider metadata and call correlation models
   - OpenAI webhook signature verification
   - OpenAI realtime.call.incoming normalization
   - OpenAI accept, reject, hangup, and optional refer request builders or clients
   - call_id based session registry or correlation support
   Keep the canonical live-call control plane in core.

3. Add a Twilio provider adapter in camel-agent-twilio.
   Implement the provider-neutral interface from core.
   Keep Twilio-specific concerns here:
   - outbound dial or SIP trunk request mapping
   - provider config validation
   - provider metadata extraction
   - optional compatibility helpers for the old adapter flow
   Do not put OpenAI webhook or call-control ownership here.

4. Add sample-side route and processor wiring for the tool.
   Create a local direct route such as direct:support-call-outbound.
   Add a processor or service in the sample that:
   - validates and normalizes tool input
   - computes or reserves correlation state before the webhook arrives
   - invokes the provider-neutral SipProviderClient
   - returns a structured asynchronous result body
   Follow the same overall pattern used for the ticket lifecycle flow where applicable.

5. Add an OpenAI webhook route in the sample.
   Add an HTTP endpoint that receives OpenAI webhook events.
   It must:
   - verify the webhook signature using OPENAI_WEBHOOK_SECRET
   - handle idempotency using webhook delivery metadata
   - process realtime.call.incoming events
   - map the OpenAI call_id and SIP headers into internal correlation state
   This is the server-side entry point for handling the live SIP call.

6. Add OpenAI call acceptance and rejection flow.
   When realtime.call.incoming is received, call the OpenAI accept endpoint using call_id if the application should answer.
   Configure the session at accept time with the required model, instructions, voice, and any session-level settings for the support experience.
   If the call should not proceed, use the reject endpoint with an appropriate optional SIP status code.

7. Add server-side realtime websocket monitoring by call_id.
   After acceptance, open a websocket to wss://api.openai.com/v1/realtime?call_id={call_id} with Authorization.
   Use it to:
   - observe the active session and call lifecycle
   - send response.create or other needed client events
   - track session closure and cleanup
   Do not add a second custom audio websocket path for the same call.

8. Persist and expose call lifecycle state.
   Track and persist:
   - destination phone number
   - internal request id
   - provider metadata
   - OpenAI project id
   - OpenAI call_id
   - internal conversation id if one is maintained separately
   - timestamps and normalized lifecycle state
   - failure reason where available
   Ensure outbound-call actions and lifecycle transitions are visible through the same audit and operational surfaces as other tools.

9. Add hangup and optional transfer controls.
   Add internal service or route hooks that can issue OpenAI hangup requests and, if practical in the first slice, OpenAI refer requests.
   Keep these aligned with OpenAI call-control semantics rather than Twilio callback semantics.

10. Add focused tests.
   Add or extend tests to cover:
   - tool route input/output normalization
   - provider-neutral interface behavior
   - Twilio adapter request mapping
   - OpenAI webhook signature verification and idempotency behavior
   - realtime.call.incoming acceptance and rejection behavior
   - OpenAI accept/reject/hangup/refer request building
   - call_id correlation and persistence
   - websocket monitor startup and cleanup behavior
   Prefer focused module/sample tests over full reactor validation if unrelated modules are broken.

11. Update documentation.
   Update the sample README and Twilio/OpenAI docs to describe:
   - the new outbound tool
   - provider-neutral contract boundaries
   - Twilio SIP trunking requirements for the Twilio implementation
   - the OpenAI SIP endpoint format sip:{projectId}@sip.api.openai.com;transport=tls
   - required OPENAI_API_KEY and OPENAI_WEBHOOK_SECRET configuration
   - webhook ingress requirements
   - the fact that OpenAI owns the live SIP session while the app owns orchestration, control, and audit

Implementation guidance
- Keep changes narrow and consistent with the existing code style.
- Do not redesign the existing agent/runtime architecture.
- Do not use TwiML or Twilio Media Streams as the primary control path for this slice.
- Prefer small reusable classes over a single large processor.
- Keep provider-neutral telephony contracts and OpenAI SIP control in camel-agent-core.
- Keep provider-specific logic in provider modules such as camel-agent-twilio and sample-specific orchestration in the sample module.
- Preserve audit and tool-result conventions already used by the sample.
- Do not revert unrelated user changes.

Files likely to change
- pom.xml
- camel-agent-twilio/pom.xml
- camel-agent-core/src/main/java/io/dscope/camel/agent/... new telephony and openai-sip classes
- camel-agent-twilio/src/main/java/io/dscope/camel/agent/twilio/... new provider adapter classes
- samples/agent-support-service/src/main/resources/agents/support/v2/agent.md
- samples/agent-support-service/src/main/resources/routes/twilio-management.camel.yaml
- samples/agent-support-service/src/main/resources/routes/... new OpenAI webhook and control routes if needed
- samples/agent-support-service/src/main/resources/application.yaml
- samples/agent-support-service/src/main/java/io/dscope/camel/agent/samples/Main.java
- samples/agent-support-service/src/main/java/io/dscope/camel/agent/samples/... new tool processor/service classes
- samples/agent-support-service/src/test/java/io/dscope/camel/agent/samples/...
- samples/agent-support-service/README.md
- docs/TWILIO_SIP_SAMPLE_SETUP.md
- docs/TWILIO_ADAPTER_FLOW_EXAMPLE.md

Acceptance criteria
- An agent-exposed tool can initiate an outbound call request and returns a structured asynchronous result.
- The application receives and verifies the OpenAI realtime.call.incoming webhook.
- The application accepts or rejects the call using the OpenAI call-control API with call_id.
- The application monitors the live call over the OpenAI realtime websocket using call_id.
- The application can swap the Twilio provider adapter for another SIP provider implementation without changing core OpenAI SIP control logic.
- Lifecycle state is correlated and persisted across the outbound request, webhook receipt, active session, and completion.
- The implementation has focused automated coverage for the new seams.
- Docs explain how to configure and run the SIP-aligned flow.

Validation expectations
- Run focused compilation/tests for camel-agent-twilio and samples/agent-support-service.
- Re-run any overlapping realtime-focused sample tests if touched.
- If a broader reactor build fails in unrelated modules, report that separately without expanding scope.

Produce code, tests, and docs changes directly in the repo.
