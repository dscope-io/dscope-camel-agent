**Plan: Provider-Agnostic SIP Calling With OpenAI Realtime Control**

Refactor the outbound phone flow so OpenAI Realtime SIP integration is owned by core, while Twilio is only one SIP provider implementation behind a provider-neutral telephony abstraction. The application should remain responsible for agent-side call initiation, OpenAI webhook handling, call acceptance and control, websocket monitoring, correlation, audit, and operational state, but provider-specific call initiation and metadata mapping should live in provider modules such as Twilio.

**Architecture direction**
- Core should own provider-neutral telephony contracts and OpenAI Realtime SIP control.
- Twilio should implement a provider adapter behind those core contracts, ideally via Elastic SIP Trunking or an equivalent SIP-capable outbound path.
- The selected SIP provider should target the OpenAI SIP endpoint in the form sip:{projectId}@sip.api.openai.com;transport=tls.
- OpenAI should invoke the application webhook with realtime.call.incoming when SIP traffic reaches the configured project.
- Core should accept, reject, monitor, transfer, or hang up calls using OpenAI Realtime call-control endpoints.
- Core should monitor the active call over the OpenAI Realtime websocket using call_id.
- The application should keep its own internal correlation and audit model, mapping OpenAI call_id and provider metadata into the existing operational surfaces.

**Steps**
1. Define provider-neutral telephony contracts and normalized lifecycle models in core.
	Add contracts such as OutboundSipCallRequest, OutboundSipCallResult, SipProviderClient, SipProviderMetadata, and CallCorrelationRecord. Include destination phone number, purpose, optional customer metadata, OpenAI project id, OpenAI call_id, provider metadata, backend conversation id, and normalized lifecycle states such as requested, trunking, incoming-webhook-received, accepted, active, transferred, hung-up, completed, failed, busy, and rejected.
	Make OpenAI call_id the primary live-call identifier once the webhook arrives.

2. Add reusable OpenAI Realtime SIP control support in core.
	Add OpenAI-facing helpers and services for:
	- building accept-call payloads
	- building reject, refer, and hangup requests
	- mapping OpenAI call_id to internal correlation state
	- verifying OpenAI webhooks using the configured webhook secret
	- normalizing OpenAI webhook events into internal lifecycle events
	- monitoring active calls over the OpenAI realtime websocket
	Core should own the canonical live-call control plane.

3. Add a Twilio provider adapter behind the core telephony interface.
	Implement Twilio-specific outbound dial or SIP trunk initiation in camel-agent-twilio behind SipProviderClient. Keep Twilio-only request mapping, configuration validation, and provider metadata extraction there. Existing envelope processors can remain only as optional compatibility helpers and should not define the new canonical path.

4. Add a new sample-side outbound tool.
	Introduce a public tool such as support.call.outbound, backed by a local route and processor following the same general pattern as the ticket lifecycle flow.
	The tool should:
	- validate and normalize destination and context
	- invoke the provider-neutral SipProviderClient to place the SIP-routed call
	- create or reserve correlation state before the webhook arrives
	- return quickly with a structured asynchronous result that includes the requested destination and correlation data
	The tool must not wait for the call to be answered.

5. Add an OpenAI webhook endpoint in the sample for SIP call events.
	Expose an HTTP webhook route that receives OpenAI events, verifies the OpenAI webhook signature, handles idempotency using webhook delivery metadata, and processes realtime.call.incoming.
	This route should be the server-side entry point for call acceptance and rejection.

6. Accept or reject incoming SIP calls using OpenAI call-control APIs.
	When the webhook event realtime.call.incoming is received, call the OpenAI accept endpoint using the call_id if the application chooses to answer the call.
	Configure the realtime session at accept time with the model, instructions, voice, tools, and any session-level settings required for the support agent.
	If the call should not be handled, use the reject endpoint and provide an optional SIP status code.

7. Monitor and control the live call over the OpenAI Realtime websocket.
	After accepting the call, open a server-side websocket using wss://api.openai.com/v1/realtime?call_id={call_id} with Authorization.
	Use that websocket to:
	- monitor live call and model events
	- send response.create or other client events when needed
	- observe session progression and termination
	Do not create a second custom media path for the same call.

8. Integrate the SIP call session into the existing repo conversation model.
	Preserve internal correlation with a stable backend conversation id and keep the existing identity convention for operational continuity where useful, but store OpenAI call_id as the canonical live-session id.
	Reuse the current audit, transcript, and session metadata conventions where they fit, but do not force the SIP flow through the existing Twilio Media Streams-shaped adapter routes.

9. Add call lifecycle persistence and operational visibility.
	Persist the relationship among requested phone number, internal request id, provider metadata, OpenAI project id, OpenAI call_id, backend conversation id, current state, timestamps, and failure reason.
	Ensure outbound-call actions and call state transitions are visible through the same audit and operational surfaces as other tools.

10. Add support for hangup and optional transfer controls.
	Add internal route or service hooks so the application can issue OpenAI hangup requests and optionally refer requests for transfers.
	This should be implemented against OpenAI call-control APIs rather than Twilio Media Streams control flows.

11. Add focused tests across all seams.
	 Cover:
	 - outbound tool input/output normalization
	 - provider adapter outbound request construction
	 - OpenAI webhook signature verification and idempotency handling
	 - realtime.call.incoming acceptance and rejection behavior
	 - accept/reject/hangup/refer request mapping
	 - call_id correlation and persistence
	 - websocket monitor startup and shutdown behavior
	 Prefer focused module and sample tests over full-reactor validation when unrelated modules fail.

12. Update configuration and docs.
	 Document:
	 - provider-neutral telephony contract boundaries
	 - Twilio SIP trunking requirements for the Twilio implementation
	 - the OpenAI SIP target URI format
	 - the OpenAI project webhook configuration
	 - required OPENAI_API_KEY and OPENAI_WEBHOOK_SECRET settings
	 - public webhook ingress requirements
	 - TLS transport expectations
	 - optional IP allowlisting for OpenAI SIP ranges
	 - the division of responsibility between Twilio trunking, OpenAI SIP, and the application

**Relevant files**
- /Users/roman/Projects/DScope/CamelAIAgentComponent/pom.xml
- /Users/roman/Projects/DScope/CamelAIAgentComponent/camel-agent-core/src/main/java/io/dscope/camel/agent/realtime/RealtimeRelayClient.java
- /Users/roman/Projects/DScope/CamelAIAgentComponent/camel-agent-core/src/main/java/io/dscope/camel/agent/realtime/OpenAiRealtimeRelayClient.java
- /Users/roman/Projects/DScope/CamelAIAgentComponent/camel-agent-core/src/main/java/io/dscope/camel/agent/realtime/sip/SipCallStartRequest.java
- /Users/roman/Projects/DScope/CamelAIAgentComponent/camel-agent-core/src/main/java/io/dscope/camel/agent/realtime/sip/SipCallInfo.java
- /Users/roman/Projects/DScope/CamelAIAgentComponent/camel-agent-twilio/pom.xml
- /Users/roman/Projects/DScope/CamelAIAgentComponent/camel-agent-twilio/src/main/java/io/dscope/camel/agent/twilio/TwilioCallStartEnvelopeProcessor.java
- /Users/roman/Projects/DScope/CamelAIAgentComponent/camel-agent-twilio/src/main/java/io/dscope/camel/agent/twilio/TwilioTranscriptEnvelopeProcessor.java
- /Users/roman/Projects/DScope/CamelAIAgentComponent/samples/agent-support-service/src/main/resources/routes/twilio-management.camel.yaml
- /Users/roman/Projects/DScope/CamelAIAgentComponent/samples/agent-support-service/src/main/resources/routes/ag-ui-platform.camel.yaml
- /Users/roman/Projects/DScope/CamelAIAgentComponent/camel-agent-core/src/main/java/io/dscope/camel/agent/realtime/RealtimeEventProcessor.java
- /Users/roman/Projects/DScope/CamelAIAgentComponent/samples/agent-support-service/src/main/resources/agents/support/v2/agent.md
- /Users/roman/Projects/DScope/CamelAIAgentComponent/samples/agent-support-service/src/main/java/io/dscope/camel/agent/samples/Main.java
- /Users/roman/Projects/DScope/CamelAIAgentComponent/samples/agent-support-service/src/main/java/io/dscope/camel/agent/samples/SupportTicketLifecycleProcessor.java
- /Users/roman/Projects/DScope/CamelAIAgentComponent/samples/agent-support-service/src/test/java/io/dscope/camel/agent/samples/SpringAiAuditTrailIntegrationTest.java
- /Users/roman/Projects/DScope/CamelAIAgentComponent/samples/agent-support-service/README.md
- /Users/roman/Projects/DScope/CamelAIAgentComponent/docs/TWILIO_SIP_SAMPLE_SETUP.md
- /Users/roman/Projects/DScope/CamelAIAgentComponent/docs/TWILIO_ADAPTER_FLOW_EXAMPLE.md

**Verification**
1. Unit test OpenAI webhook signature verification and idempotent event handling.
2. Unit test accept, reject, refer, and hangup request builders against the documented OpenAI call-control contracts.
3. Unit test the provider-neutral telephony interface and the Twilio implementation mapping independently.
4. Integration test the outbound tool route so it returns a structured asynchronous result before the call is answered.
5. Integration test realtime.call.incoming webhook handling and confirm that the app accepts or rejects using call_id correctly.
6. Integration test realtime websocket monitor startup using call_id and confirm that lifecycle events are correlated into internal state.
7. Run a manual smoke flow with a Twilio SIP-routed call: tool-triggered outbound request, OpenAI webhook receipt, accept-call request, active websocket monitoring, and final completion or hangup state visible in the app.

**Decisions**
- Included: outbound call initiation, OpenAI webhook handling, OpenAI accept/reject/hangup/refer control, and websocket monitoring.
- Primary media/session owner: OpenAI Realtime SIP, not a custom in-repo provider media bridge.
- Packaging: provider-neutral telephony contracts and OpenAI SIP control in core, provider implementation logic in provider modules such as Twilio, sample limited to blueprint, route, and bean wiring.
- Identifier rule: use OpenAI call_id as the canonical live-call session id after webhook receipt, with internal conversation ids and provider metadata stored as correlated identifiers.
- Operational rule: outbound call creation remains asynchronous and returns correlation data immediately.
- Excluded from the first slice: custom TwiML-driven media bridging, provider-specific media transport, alternate control planes, and broader call-center features.

**Open design choices with recommendation**
1. Correlation key precedence: store both internal conversation id and OpenAI call_id, but treat call_id as canonical for active SIP call control.
2. Greeting behavior: include initial model instructions in the accept payload and optionally send an initial response.create after the websocket is open.
3. Persistence scope: persist lifecycle snapshots and webhook deliveries for auditability, and keep only minimal in-memory state for active websocket handles.

The plan is saved in session memory as /memories/session/plan.md, but this version is the OpenAI SIP-aligned rewrite.

---

**Plan Extension: Static Resources Declared In Agent Blueprint**

Add a new blueprint-level `resources` section so an agent can declare static knowledge inputs such as markdown, plain text, HTML, JSON, and PDF documents that should be loaded into chat context, realtime context, or both. The design should reuse Camel-style transport concepts for acquisition and should align with the existing runtime resource staging model instead of introducing a second unrelated fetch pipeline.

**Goals**
- Let agent authors declare local or remote resources directly in `agent.md`.
- Support loading resource content into chat and realtime agent context without requiring new tools for every document.
- Reuse existing runtime bootstrap and refresh behavior where possible.
- Keep transport acquisition separate from content extraction and context injection.
- Preserve provider-neutral core behavior so the same resource mechanism can be used by chat-only and realtime-capable agents.

**Non-goals for the first slice**
- Full arbitrary repository sync from git URLs.
- Binary passthrough into model context without normalization.
- Large-document semantic indexing or vector search as part of the initial blueprint feature.
- Continuous remote polling beyond explicit runtime refresh or startup load.

**Proposed blueprint contract**
Add a top-level fenced YAML section:

```yaml
resources:
  - name: support-handbook
	 uri: classpath:agents/support/docs/handbook.md
	 kind: document
	 format: markdown
	 includeIn: [chat, realtime]
	 loadPolicy: startup
	 optional: false
	 maxBytes: 262144
  - name: billing-policy
	 uri: https://example.org/docs/billing-policy.pdf
	 kind: document
	 format: pdf
	 includeIn: [chat]
	 loadPolicy: startup
	 refreshPolicy: manual
	 extract:
		mode: text
		maxChars: 24000
```

Recommended fields:
- `name`: stable logical identifier used in audit, diagnostics, and future references.
- `uri`: source location. Support `classpath:`, `file:`, `http:`, `https:` in the first slice. Plan for `ftp:` and `sftp:` in the next slice. Treat `git:` as deferred unless a concrete fetch strategy is added.
- `kind`: start with `document` and reserve room for future values such as `prompt-fragment`, `dataset`, or `attachment`.
- `format`: optional hint such as `markdown`, `text`, `html`, `json`, `pdf`. If omitted, infer from content type or extension.
- `includeIn`: `chat`, `realtime`, or both.
- `loadPolicy`: `startup`, `lazy`, or `manual`.
- `refreshPolicy`: `manual` for the first slice, with room for timed refresh later.
- `optional`: whether startup should continue if the resource cannot be resolved.
- `maxBytes`: acquisition guard before extraction.
- `extract`: extraction policy, especially for PDF and HTML text normalization.
- `chunking`: deferred unless needed for large chat-context resources.

**Architecture direction**
1. Extend `AgentBlueprint` with a typed list of resource specs.
2. Extend `MarkdownBlueprintLoader` with `parseResources(...)` alongside tools and realtime.
3. Introduce a core `BlueprintResourceSpec` model and a resolved runtime representation such as `ResolvedBlueprintResource`.
4. Reuse `RuntimeResourceBootstrapper` patterns for staging remote resources, but generalize it beyond blueprint/routes/kamelets so blueprint resources can be downloaded into a managed local cache.
5. Add a dedicated resource loader pipeline in core:
	- acquire bytes from URI
	- enforce size and MIME guards
	- normalize to text or structured metadata
	- inject into chat context and/or realtime session bootstrap
6. Keep resource acquisition transport-neutral and content extraction format-aware.

**Transport plan**
Phase 1 transport support:
- `classpath:` direct read through existing resource loading rules
- `file:` direct local file read
- `http:` and `https:` via staged download, reusing current runtime bootstrap style

Phase 2 transport support:
- `ftp:` and `sftp:` through Camel-backed fetch support or equivalent runtime staging
- authenticated HTTP variants if needed

Deferred transport support:
- `git:` URIs unless the repo adds a concrete and bounded strategy such as archive download or shallow checkout

Recommendation: do not promise generic `git:` in the first implementation. It sounds flexible but is underspecified and creates authentication, caching, and branch-selection complexity that does not exist for file/http/ftp.

**Content normalization plan**
- Markdown and plain text: inject directly after size trimming and newline normalization.
- HTML: strip markup to readable text before injection.
- JSON: either pretty-print as text or extract a configured subtree later; default to bounded pretty-printed text.
- PDF: extract text before injection. Do not place raw binary or base64 PDF into chat or realtime context.

Recommendation: use a dedicated extraction dependency for binary/text conversion. `camel-tika` is a reasonable fit if the project wants Camel-aligned extraction. Apache PDFBox is a smaller alternative if PDF-only extraction is needed. For the plan, keep the extractor behind an interface so the dependency choice stays open.

**Context injection rules**
Chat context:
- Loaded resources should become part of the agent prompt assembly path, not ad hoc tool output.
- Preserve resource boundaries by prefixing each injected block with resource name and provenance.
- Apply a hard per-resource character budget and a total resource budget.

Realtime context:
- Realtime should receive a condensed form, not the full raw corpus.
- For startup-loaded resources, merge a bounded summary or extracted text into session instructions/profile assembly.
- Avoid pushing large documents into every realtime session. Use lower limits than chat.

Recommendation: define separate budgets for `chat` and `realtime` in config, and prefer summarized or truncated resource text for realtime.

**Runtime behavior**
Startup path:
- Load blueprint.
- Resolve declared resources.
- Stage remote resources where necessary.
- Extract normalized text.
- Cache normalized resource payloads in memory for the loaded blueprint version.

Refresh path:
- Extend `RuntimeResourceRefreshProcessor` so a runtime refresh also re-resolves blueprint resources.
- Report which blueprint resources changed, failed, or were skipped.
- Invalidate chat/realtime caches that depend on the blueprint resource set.

Operational visibility:
- Add blueprint resource status to audit/diagnostic surfaces.
- Expose source URI, staged location, content type, byte size, extraction status, and last refresh timestamp.

**Model changes**
- Add `BlueprintResourceSpec` to core model.
- Extend `AgentBlueprint` with `List<BlueprintResourceSpec> resources`.
- Update constructors to preserve compatibility.
- Extend blueprint audit output so declared resources are visible.

**Parser changes**
- Add `parseResources(JsonNode root)` to `MarkdownBlueprintLoader`.
- Support both `uri` and `url`, but normalize internally to `uri`.
- Validate `name` and `uri` as required.
- Default `includeIn` to `chat` if omitted.
- Default `loadPolicy` to `startup`.

**Runtime service additions**
- `BlueprintResourceResolver` for URI resolution and staged local access.
- `BlueprintResourceExtractor` for text extraction and normalization.
- `BlueprintResourceRegistry` or equivalent cache for resolved resource payloads.
- `BlueprintContextAssembler` updates so chat and realtime processors can consume normalized resource text.

**Reuse points in existing code**
- `RuntimeResourceBootstrapper` already stages remote blueprint, route, and kamelet resources and can be generalized for remote blueprint resources.
- `RuntimeResourceRefreshProcessor` is the natural refresh entry point.
- `MarkdownBlueprintLoader` is the natural contract/parser entry point.
- Existing MCP resource list/read processors are separate protocol surfaces and should not be treated as the storage model for blueprint-declared static resources.

**Implementation slices**
1. Blueprint contract and parser
	Add resource spec model, extend `AgentBlueprint`, parse `resources` from markdown, and cover parsing with unit tests.

2. Startup resolution for local and HTTP resources
	Resolve `classpath:`, `file:`, `http:`, and `https:` resources. Normalize markdown/text payloads. Add caching and audit visibility.

3. Chat-context integration
	Inject normalized resource text into the agent prompt assembly path with bounded size controls.

4. Realtime-context integration
	Add smaller-budget resource injection into realtime session initialization or agent profile assembly.

5. Binary document extraction
	Add PDF text extraction and tests for extraction failure, size caps, and optional resources.

6. Refresh and diagnostics
	Extend runtime refresh output and blueprint audit output to include resource state and refresh results.

7. Additional transports
	Add `ftp:` and `sftp:` once the generic staged-fetch abstraction is proven.

**Testing**
- Unit test blueprint parsing for valid and invalid resource declarations.
- Unit test URI normalization and scheme gating.
- Unit test startup resolution for `classpath:`, `file:`, and `http:`.
- Unit test optional versus required resource failure behavior.
- Unit test max-size guards and extraction limits.
- Unit test chat-context and realtime-context budget enforcement.
- Integration test runtime refresh with changed remote resources.
- Integration test a sample blueprint containing markdown and PDF resources.

**Configuration and documentation**
Document:
- supported URI schemes by phase
- size and character budgets
- extraction behavior for markdown, HTML, JSON, and PDF
- refresh semantics
- failure behavior for optional versus required resources
- the difference between blueprint-declared resources and user-invoked MCP resources

**Recommended first implementation boundary**
Implement only `classpath:`, `file:`, `http:`, and `https:` acquisition first, plus text and markdown normalization. Keep PDF extraction in the same feature track if the dependency decision is straightforward; otherwise make it the next slice. Defer `ftp:` and `git:` until the fetch abstraction is stable.
