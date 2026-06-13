# Agent Implementation Planner Skill

## Purpose

Use this skill to produce a complete implementation plan for a new agent that is built on top of CamelAIAgentComponent.

The output of this skill is a practical, execution-ready plan that covers:

- architecture choices
- AI runtime bootstrap strategy (runtime mode, provider/api-mode, gateway binding, schema compatibility)
- fault/exception policy strategy (retry, rethrow, terminate, resolve, scope, and status-code mapping)
- resource layout (agent blueprint, routes, kamelets)
- plan catalog strategy (`agent.agents-config`, multi-plan/version layout, per-plan AI overrides) when the app owns more than one agent surface
- A2UI template/catalog strategy for structured app-owned UI surfaces (when required)
- Spring application bootstrap path and bean wiring (when Spring Boot is in scope)
- persistence and audit strategy
- AGUI, realtime, and optional SIP behavior (if required)
- testing strategy (unit, integration, smoke)
- rollout, observability, and rollback
- required Maven dependencies (runtime/test/optional)
- runtime MCP control surface (discovery, live config updates, conversation archive reads)

## When to Use

Use this skill when any of the following is requested:

- create a new agent using this project as a base
- implement a new business domain agent in support-service or a new sample
- add tool routes/kamelets for a new agent
- define delivery plan before coding
- estimate scope and phases for agent development

Do not use this skill for single-file quick fixes.

## Inputs Required

Collect these inputs before writing the plan:

1. Business goal
- what user problem the agent solves
- expected success criteria

2. Interaction channels
- AGUI chat only
- AGUI + realtime voice
- AGUI + A2UI structured surfaces
- SIP adapter + realtime backend
- OpenAI-managed SIP + realtime webhook flow
- backend-only orchestration

3. Tooling scope
- required tools (internal/external)
- whether kamelets are needed
- expected tool call volume and latency sensitivity

3.5. Structured UI scope
- whether the agent must return legacy `widget`, A2UI payloads, or both
- whether app-owned A2UI catalog/surface/locale JSON assets are needed
- supported catalog negotiation requirements (`a2uiSupportedCatalogIds` or equivalent client contract)

4. Persistence and audit requirements
- runtime store mode: in-memory, jdbc, redis, redis_jdbc
- audit granularity: none, info, debug
- split audit store needed or not
- separate conversation archive persistence needed or not
- conversation archive default enablement in configuration
- retention/purge policy requirements

4.5. Fault and exception policy requirements
- whether blueprint-level `exceptionPolicies` are needed at all
- which scopes need policy coverage: `tool.execute`, `agui.pre-run`, or both
- which failures should be treated as technical versus business
- which HTTP or provider status codes need explicit handling
- whether the terminal behavior should be `retry`, `rethrow`, `terminate`, or `resolve`
- whether `resolve` needs policy-specific prompt instructions for model-driven recovery

5. Environment and delivery constraints
- local-only or CI/CD deployment
- Camel Main, Spring Boot, or mixed runtime deployment model
- required databases and auth mode
- non-functional constraints (security, latency, cost)

5.5. Plan catalog and UI ownership constraints
- single blueprint only or multi-plan `agents.yaml`
- whether plans need versioned resource folders such as `agents/<plan>/<version>/agent.md`
- whether per-plan AI overrides are required in `agents.yaml`
- whether the application owns a branded static AGUI UI under `src/main/resources/ui`
- whether route loading should be narrowed by environment-specific include patterns instead of loading all routes

6. Dependency constraints
- allowed external libraries
- version alignment requirements (BOM/parent-managed vs explicit version)
- licensing/security constraints for new dependencies

7. AI runtime and model execution requirements
- whether model execution is supplied by runtime bootstrap, explicit bean wiring, or an external realtime relay
- runtime AI mode: `spring-ai`, `realtime`, or explicit custom binding
- provider choice: `openai`, `gemini`, `claude` / `anthropic`
- OpenAI API mode: `chat`, `responses-http`, or `responses-ws`
- whether strict tool-schema compatibility must be planned explicitly for OpenAI Responses flows

8. Voice and telephony requirements
- whether SIP is in scope at all, and whether it is adapter-driven or OpenAI-managed SIP
- stable call-to-conversation identity format (for example `sip:{tenant}:{callId}`)
- whether caller identity must be preserved as `callerId`, `fromNumber`, and `agent.session.params.*`
- whether the backend owns only the realtime agent flow or also onboarding/call-control surfaces

## Project-Specific Architecture Defaults

When requirements are ambiguous, use these defaults:

- agent blueprint: markdown blueprint loaded via agent.blueprint
- when the application owns multiple agent experiences, prefer `agent.agents-config` with versioned plan folders over a single loose blueprint file
- route loading: classpath routes include pattern
- tool implementation: Camel routes + optional kamelets
- persistence: jdbc for non-trivial implementations
- audit granularity: info by default, debug in test/staging
- when failures are business-meaningful or upstreams are flaky, prefer explicit blueprint `exceptionPolicies` over implicit route-local error handling
- AGUI enabled for human-facing workflows
- when structured UI is required, prefer blueprint-declared `a2ui.surfaces[]` plus app-owned catalog/surface/locale JSON assets
- when browser or mobile clients are user-facing, plan both legacy `widget` compatibility and top-level `a2ui` payloads unless requirements say otherwise
- when the app owns AGUI/browser UX, plan a static UI root under `src/main/resources/ui` and record brand/default-plan defaults explicitly
- when Camel Main runtime bootstrap owns model execution, prefer `agent.runtime.ai.mode=spring-ai`
- Spring Boot embedding via `camel-agent-starter` when the agent must run inside an application instead of only Camel Main
- for live Spring Boot model execution, plan an explicit `AiModelClient` bean instead of relying on the starter default noop gateway
- keep `agent.runtime.agent-routes-enabled=true` unless the built-in sample route builder would conflict with the planned entrypoints
- keep `agent.diagnostics.trace.enabled=true` for local AGUI/realtime bring-up, then explicitly decide whether to reduce it outside dev/test
- keep SIP runtime processor binding disabled by default unless SIP ingress is part of the design (`agent.runtime.sip.bind-processors=true`)
- runtime admin endpoints enabled for refresh/close/purge preview
- conversation archive persistence default disabled via `agent.conversation.persistence.enabled=false`
- MCP Streamable HTTP usage with `Accept: application/json, text/event-stream`

## Recent Platform Changes (March 2026)

Use these as current-state anchors when drafting plans:

- A2A is now a first-class planning dimension when agents must expose or consume peer agents:
  - runtime config surface: `agent.runtime.a2a.*`
  - exposed-agent mapping file: `agent.runtime.a2a.exposed-agents-config`
  - sample public mapping: `samples/agent-support-service/src/main/resources/agents/a2a-exposed-agents.yaml`
  - core config source: `camel-agent-core/src/main/java/io/dscope/camel/agent/runtime/A2ARuntimeProperties.java`
  - plan explicitly whether the agent is only an A2A caller, only an exposed A2A service, or both
  - if exposed publicly, include RPC/SSE/agent-card endpoint ownership, public URL, and task correlation expectations in the plan

- Runtime MCP control methods are available and should be considered in operational design:
  - `runtime.audit.granularity.get`
  - `runtime.audit.granularity.set`
  - `runtime.conversation.persistence.get`
  - `runtime.conversation.persistence.set`
  - `audit.conversation.sessionData`

- Spring application bootstrap is now a first-class planning concern:
  - starter module: `camel-agent-starter`
  - product guide bootstrap reference: `docs/PRODUCT_GUIDE.md`
  - starter auto-configures `AgentKernel`, `PersistenceFacade`, `BlueprintLoader`, and optional chat memory
  - starter default `AiModelClient` uses `NoopSpringAiChatGateway`, so live-provider plans must include a replacement `AiModelClient` bean or equivalent override strategy

- Runtime AI client bootstrap is now a first-class planning concern even outside Spring Boot:
  - runtime config keys: `agent.runtime.ai.mode`, `agent.runtime.spring-ai.gateway-class`
  - supported runtime modes: `spring-ai`, `realtime`, or explicit custom binding
  - `spring-ai` mode auto-binds `SpringAiModelClient` when runtime classes are present
  - `realtime` mode binds `StaticAiModelClient` for relay-oriented flows where model execution is handled elsewhere
  - if runtime bootstrap should instantiate a custom gateway, include the fully qualified gateway class and constructor assumptions in the plan

- Current sample Spring AI defaults should be treated as the most recent planning baseline unless requirements say otherwise:
  - `agent.runtime.spring-ai.provider=openai`
  - `agent.runtime.spring-ai.model=gpt-5.4`
  - `agent.runtime.spring-ai.openai.api-mode=chat`
  - `agent.runtime.spring-ai.openai.responses-ws.endpoint-uri=wss://api.openai.com/v1/responses`
  - `agent.runtime.spring-ai.openai.responses-ws.model=gpt-5.4`

- Provider and API-mode planning is now broader than the original OpenAI chat-only path:
  - supported providers in the runtime gateway are `openai`, `gemini`, `claude` / `anthropic`
  - OpenAI supports `chat`, `responses-http`, and `responses-ws`
  - `responses-http` is the planning path that exercises strict OpenAI Responses tool validation and schema normalization
  - `responses-ws` should include timeout, polling, and reconnect expectations in the plan when it is selected

- OpenAI Responses strict schema compatibility is now a planning concern for tool-heavy agents:
  - every object schema node should resolve explicitly
  - object schema nodes should be planned with `additionalProperties: false`
  - object schema nodes with `properties` should have a matching `required` list
  - intentionally open container objects should still be represented as explicit empty object schemas
  - live validation should use a model-executed sample path, not only deterministic AGUI/realtime fallback flows

- Conversation archive persistence is now a separate capability from core audit trail:
  - default flag: `agent.conversation.persistence.enabled`
  - optional dedicated backend mapping: `agent.conversation.persistence.*`
  - archive write/read service: `camel-agent-core/src/main/java/io/dscope/camel/agent/runtime/ConversationArchiveService.java`

- Runtime bootstrap wires mutable control state and MCP processors:
  - `camel-agent-core/src/main/java/io/dscope/camel/agent/runtime/AgentRuntimeBootstrap.java`
  - `camel-agent-core/src/main/java/io/dscope/camel/agent/runtime/RuntimeControlState.java`
  - `camel-agent-core/src/main/java/io/dscope/camel/agent/runtime/RuntimeAuditGranularityProcessor.java`
  - `camel-agent-core/src/main/java/io/dscope/camel/agent/runtime/RuntimeConversationPersistenceProcessor.java`

- Runtime bootstrap now also exposes planning-relevant toggles for default route wiring, AGUI/realtime bean binding, and diagnostics:
  - `agent.runtime.agent-routes-enabled`
  - `agent.runtime.agui.bind-default-beans`
  - `agent.runtime.agui.bind-pre-run-processor`
  - `agent.runtime.realtime.bind-relay`
  - `agent.runtime.realtime.bind-processor`
  - `agent.runtime.realtime.bind-token-processor`
  - `agent.runtime.realtime.browser-session-ttl-ms`
  - `agent.runtime.sip.bind-processors`
  - `agent.diagnostics.trace.enabled`
  - plans should explicitly say whether they rely on these auto-bind defaults or override them

- A2UI is now a first-class planning dimension for structured UX:
  - blueprint declaration seam: `AgentBlueprint.a2ui`
  - blueprint loader parses `a2ui.surfaces[]` from markdown
  - surface spec fields include `widgetTemplate`, `surfaceIdTemplate`, `catalogId`, `catalogResource`, `surfaceResource`, `matchFields`, and `localeResources`
  - runtime placeholder resolution supports `a2ui` execution targets, so plans can use classpath/file/http(s) resources consistently with the rest of the runtime
  - app-owned catalog and surface JSON should be known at deploy time rather than fetched dynamically from the advertised `catalogId`
  - clients may negotiate supported catalogs; plans should say whether the frontend advertises supported catalog ids and whether legacy widget fallback remains required

- Multi-plan embedded-service layout is now a proven planning pattern, not just a sample edge case:
  - use `agent.agents-config` for a plan catalog when one service owns multiple agent experiences
  - plan folders can be versioned, for example `agents/customer/v1/agent.md` and `agents/service/v3/agent.md`
  - per-plan AI overrides may live in `agents.yaml` under `plans[].ai.properties.*`
  - AGUI/browser defaults may also be app-owned through config like `agui.ui.default-plan-version`, `agui.ui.default-theme`, and `agui.ui.static-root`
  - route loading may be intentionally narrowed with `agent.runtime.routes-include-pattern` so admin, AGUI, SIP, or domain routes can be enabled selectively per deployment

- SIP + realtime is now an optional planning path with two distinct integration models:
  - adapter contract path:
    - `POST /sip/adapter/v1/session/{conversationId}/start`
    - `POST /sip/adapter/v1/session/{conversationId}/turn`
    - `POST /sip/adapter/v1/session/{conversationId}/end`
    - adapter maps provider call identity to one stable backend `conversationId`
    - adapter normalizes call start into realtime `/init` and final transcript turns into realtime `transcript.final`
  - OpenAI-managed SIP path:
    - Twilio Elastic SIP Trunk points at the generated OpenAI SIP URI
    - OpenAI sends verified webhook events to `/openai/realtime/sip/webhook`
    - backend keeps using the same conversation, audit, and SIP projection model
  - plans should explicitly choose which SIP model applies and avoid mixing them accidentally

- Realtime SIP and telephony correlation have planning implications:
  - caller identity should be preserved when available as `callerId`, `fromNumber`, and matching `agent.session.params.*` values
  - SIP projections and audit views should remain queryable through the same backend conversation id
  - if onboarding or provider call control is in scope, plan whether to expose reusable onboarding endpoints or only internal services

- Blueprint exception policies are now a first-class planning dimension for runtime fault handling:
  - blueprint fields: `exceptionPolicies`, `exceptionPoliciesRef`, `exceptionPoliciesUri`
  - supported scopes: currently `tool.execute` and `agui.pre-run`
  - supported categories: `technical` and `business`
  - supported actions: `retry`, `rethrow`, `terminate`, `resolve`
  - retry policies support max retries, interval, exponential backoff, multiplier, and max interval
  - ordered policy chaining matters: a retry policy may be followed by a non-retry fallback policy after retries are exhausted
  - `resolve` uses the existing agent context and can add policy-specific prompt instructions on top of the injected root error
  - plans should decide fault semantics explicitly instead of leaving business conflicts and transient upstream failures to generic exception handling

- MCP method catalog/dispatch include new control and archive-read methods:
  - `camel-agent-core/src/main/resources/mcp/audit-methods.yaml`
  - `camel-agent-core/src/main/java/io/dscope/camel/agent/audit/mcp/AuditMcpToolsCallProcessor.java`
  - `camel-agent-core/src/main/java/io/dscope/camel/agent/audit/AuditConversationSessionDataProcessor.java`

- Realtime and AGUI paths append archive events when enabled:
  - AGUI pre-run append: `camel-agent-core/src/main/java/io/dscope/camel/agent/agui/AgentAgUiPreRunTextProcessor.java`
  - realtime observed/final append: `camel-agent-core/src/main/java/io/dscope/camel/agent/realtime/RealtimeEventProcessor.java`

### Known-Good WebRTC UI Flow (Pin This in Plans)

When a request says WebRTC flow is the working baseline (and relay is not the focus), plan against the dedicated WebRTC page and keep these settings explicit in the implementation plan:

- Source-of-truth rule: define WebRTC flow from `samples/agent-support-service/src/main/resources/frontend/webrtc-test.html` only (do not derive WebRTC flow settings from `index.html`).

- Primary reference page: `samples/agent-support-service/src/main/resources/frontend/webrtc-test.html`
- Transport selector:
  - `#transport-mode` is disabled and fixed to `webrtc`
  - option text: `Browser WebRTC (direct)`
- AGUI selector:
  - `#agui-transport-mode` default `post` (`POST + SSE`)
- Voice defaults:
  - `#duplex-mode=half`
  - `#vad-pause=normal` (1200ms)
  - `#voice-setting=alloy`
- Instruction debug panel:
  - keep `Instruction seed (debug)` panel and auto-open behavior for WebRTC mode
- Transcript diagnostics:
  - keep `WebRTC transcript log` panel + clear action

Plan rule for this mode:

- Do not introduce relay-specific finalize/commit orchestration into the WebRTC baseline flow.
- If both relay and WebRTC are in scope, treat WebRTC as a separate transport path with independent stop/finalize behavior.

- Persistence bootstrap delegates backend selection to Camel Persistence and supports audit override mapping:
  - `camel-agent-persistence-dscope/src/main/java/io/dscope/camel/agent/persistence/dscope/DscopePersistenceFactory.java`
  - `camel-persistence-core` `FlowStateStoreFactory`
  - `camel-agent-persistence-dscope/src/main/resources/db/persistence/postgres-flow-state.sql`
  - `camel-agent-persistence-dscope/src/main/resources/db/persistence/snowflake-flow-state.sql`

### Decision Guide: Audit Trail vs Conversation Archive

Use this table during planning when deciding where events should go:

| Need | Prefer Audit Trail (`user.message`, `tool.*`, `realtime.*`) | Prefer Conversation Archive (`conversation.*`) |
|---|---|---|
| Execution debugging and step-by-step diagnostics | ✅ Yes | ❌ Not primary |
| Human-readable conversational transcript history | ⚠️ Possible but noisy | ✅ Yes |
| Include tool lifecycle and orchestration internals | ✅ Yes | ❌ No |
| Store only user/assistant turns (plus observed transcript snapshots) | ❌ No | ✅ Yes |
| Runtime verbosity control via granularity | ✅ `runtime.audit.granularity.*` | ❌ Not granularity-based |
| Runtime on/off control for conversation transcript persistence | ❌ No | ✅ `runtime.conversation.persistence.*` |
| MCP retrieval for session-focused conversation feed | ⚠️ Use search/view methods | ✅ `audit.conversation.sessionData` |

Practical rule:

- If the requirement is **operator diagnostics**, plan on audit trail first.
- If the requirement is **conversation replay/transcript UX**, plan on conversation archive first.
- For mature agents, use **both** with distinct retention policies.

## Planning Workflow

### Phase 1: Scope and Boundaries

Define:

- in-scope outcomes
- out-of-scope items
- acceptance criteria

Output:

- concise problem statement
- measurable done conditions

### Phase 2: Agent Contract Design

Define:

- agent name/title
- user intents and expected responses
- tool contract per intent

Output:

- intent-to-tool mapping
- fallback behavior when tools fail
- A2A boundary decisions for each externalized tool or service surface
- exception-policy coverage for technical failures, business conflicts, and recovery paths when applicable

Blueprint tools format rule (required):

- In blueprint markdown, `## Tools` must contain a fenced YAML block with top-level `tools:`.
- Do not write tool definitions as prose bullets under `## Tools`.
- For MCP service seed tools, use `endpointUri: mcp:<url>` in the YAML tool entry.

Required structure example:

```markdown
## Tools

~~~yaml
tools:
  - name: calendar.mcp
    description: Calendar MCP service seed
    endpointUri: mcp:http://localhost:8080/mcp
    inputSchemaInline:
      type: object
      properties: {}
~~~
```

### Phase 3: Resource Design

Plan resource artifacts:

- blueprint file path
- `agents.yaml` plan catalog when the service owns multiple plans or versions
- inline `exceptionPolicies` block or referenced policy YAML when explicit fault handling is required
- route files (yaml/xml)
- kamelets if reusable endpoints are needed
- A2A exposure file if public agent ids must map to local plans
- A2UI catalog/surface/locale JSON assets when structured surfaces are required
- branded static UI assets under `src/main/resources/ui` when the application owns AGUI/browser UX

Output:

- file tree for new agent resources
- naming conventions for route ids and tool names
- naming conventions for A2UI catalog ids, surface ids, template names, and locale bundles when applicable
- naming conventions for plan names, versions, and route include slices when applicable
- naming conventions for exception-policy names and scope ownership when applicable

### Phase 4: Persistence, Audit, and Lifecycle

Plan:

- persistence backend and DB config
- audit strategy and required events
- conversation lifecycle operations
  - refresh all/single
  - close conversation instance
  - purge preview and purge criteria
  - runtime audit granularity get/set
  - runtime conversation archive persistence get/set
  - session conversation archive read via MCP

Output:

- configuration matrix by environment
- retention/purge policy mapping
- MCP runtime control matrix (method name, inputs, expected state transition)

### Phase 5: Integration, Spring Bootstrap, and UX

If Spring Boot embedding, AGUI, or realtime is needed, define:

- Spring Boot bootstrap path
- application properties to externalize
- required beans and override points
- how routes call the `agent:` endpoint
- whether the app uses `camel-agent-starter`, direct core wiring, or both
- whether runtime bootstrap or explicit bean wiring owns `AiModelClient`
- whether sample route builder and AGUI/realtime auto-bind toggles stay enabled or are overridden
- diagnostics trace policy for local, CI, and production-like environments
- whether the app uses a single `agent.blueprint` or a multi-plan `agent.agents-config` catalog
- how route include patterns and UI defaults vary by environment or deployment surface
- whether fault handling lives in blueprint `exceptionPolicies`, route logic, or both

If A2A exposure or consumption is needed, also define:

- whether the agent consumes peer agents through `a2a:` tools, exposes a public A2A identity, or both
- `agent.runtime.a2a.public-base-url` ownership and environment-specific values
- exposed-agent mapping file contents and linkage to `agent.agents-config`
- which HTTP port/path set owns `/a2a/rpc`, `/a2a/sse/{taskId}`, and `/.well-known/agent-card.json`
- fallback behavior when remote A2A peers are unavailable

If AGUI or realtime is needed, also define:

- AGUI routes/endpoints and expected UI controls
- realtime session init/token/event behavior
- session metadata/context update rules
- request-scoped metadata strategy for standard runtime headers and `agent.session.params.*`
- audit explorer behavior for archived `conversation.*` events

If structured UI is needed, also define:

- whether the agent returns `widget`, `a2ui`, or both
- blueprint `a2ui.surfaces[]` layout and matching strategy
- where app-owned catalog, surface, and locale JSON assets live
- how supported catalog negotiation works between backend and clients
- how A2UI maps back to any legacy widget/template fallback
- whether the app also owns AGUI static HTML/assets, branding, and default plan/version selection

If SIP with realtime API is needed, also define:

- whether the backend uses the SIP adapter contract or the OpenAI-managed SIP webhook path
- inbound endpoint ownership for `/sip/adapter/v1/session/{conversationId}/{start|turn|end}` and/or `/openai/realtime/sip/webhook`
- whether `agent.runtime.sip.bind-processors` is enabled and which processor bean names are expected
- conversation id mapping strategy from provider call identifiers
- caller identity propagation and privacy/compliance handling for SIP metadata
- whether the backend also owns onboarding, audit projection, or outbound call-control surfaces

Output:

- endpoint list
- Spring bootstrap design notes tied to actual beans, config keys, and route entrypoints
- UX behavior notes tied to endpoints
- A2A endpoint and mapping design notes tied to public agent ids and local plans
- A2UI surface/catalog notes tied to blueprint fields and client rendering expectations
- SIP/realtime ingress notes tied to endpoint contracts, processor bindings, and conversation correlation
- exception-policy notes tied to blueprint scopes, status-code classes, and fallback action after retry exhaustion
- MCP method list for UI/ops integration:
  - `runtime.audit.granularity.get`
  - `runtime.audit.granularity.set`
  - `runtime.conversation.persistence.get`
  - `runtime.conversation.persistence.set`
  - `audit.conversation.sessionData`

If WebRTC is selected as primary voice channel, include a “WebRTC Flow Settings” subsection that records at minimum:

- page path (`webrtc-test.html`)
- transport selector mode/value
- AGUI mode default
- duplex, pause profile, voice defaults
- transcript log and instruction seed debug behavior

### Phase 6: Test Strategy

Plan tests at three levels:

1. Unit tests
- blueprint parsing, processors, tool selection logic
- runtime bootstrap selection logic for `agent.runtime.ai.mode` and gateway binding when applicable
- A2UI blueprint parsing and surface matching when structured UI is in scope
- plan-catalog parsing when `agent.agents-config` and multiple plan versions are in scope
- exception-policy parsing and ordered fallback behavior when explicit fault handling is in scope

2. Integration tests
- route invocation
- persistence/audit verification
- close/refresh behavior
- A2A RPC/agent-card verification when `agent.runtime.a2a.enabled=true`
- runtime MCP control verification (`get/set/get` flows)
- archived conversation read verification (`audit.conversation.sessionData` non-empty after fresh turn)
- live model-path verification when OpenAI Responses tool calling is selected
- AGUI/realtime auto-bind or override verification when those defaults are part of the design
- structured UI verification for both `widget` and `a2ui` payload contracts when browser/mobile UX depends on them
- SIP adapter or OpenAI SIP webhook verification when telephony is in scope
- plan/version routing verification when multiple plans are served from one application
- retry/rethrow/terminate/resolve behavior verification when exception policies are in scope

3. Environment smoke tests
- script-driven local checks
- optional Docker-backed Postgres flow
- MCP Streamable HTTP checks with required `Accept` header
- sample-service model-path checks when strict tool-schema behavior must be proven
- SIP start/turn/end smoke or OpenAI SIP webhook smoke when telephony is in scope

Output:

- explicit test cases with expected assertions
- commands to run each test group

### Phase 6.5: Maven Dependency Resolution

For the planned agent, derive dependency changes before implementation starts.

Identify:

- existing dependencies already available via parent/module POMs
- new runtime dependencies required for the agent features
- new test-only dependencies required for integration/smoke tests
- module target for each dependency change (which `pom.xml`)

Output:

- a dependency table with: groupId, artifactId, scope, version source, reason, target module
- a "no-change" note when existing dependencies are sufficient
- conflict notes for any potential version overlaps

### Phase 7: Rollout and Operations

Plan:

- migration and deployment order
- observability checks
- rollback steps

Output:

- release checklist
- rollback checklist

## Output Format Required

When using this skill, always produce the plan with this exact section order:

1. Goal and Scope
2. Assumptions
3. Architecture Decisions
  - Must include subsection: `Maven Dependencies`
  - Must include subsection: `AI Runtime Bootstrap` when runtime bootstrap or live model execution is in scope
  - Must include subsection: `Fault and Exception Policies` when explicit runtime fault handling is in scope
  - Must include subsection: `Spring Application Bootstrap` when Spring Boot or application embedding is in scope
  - Must include subsection: `A2UI Templates` when structured UI is in scope
  - Must include subsection: `SIP and Realtime Telephony` when telephony is in scope
4. Implementation Phases
5. Test Plan
6. Deployment and Rollback
7. Risks and Mitigations
8. Open Questions

Companion fill-in template:

- `docs/skills/agent-implementation-planner/PLAN_TEMPLATE.md`
- worked Spring Boot sample plan: `docs/skills/agent-implementation-planner/SPRING_BOOT_EXAMPLE_PLAN.md`

## Plan Quality Rules

A valid plan must:

- map each user requirement to at least one implementation task
- include at least one verification step per phase
- include at least one rollback or fallback mechanism
- identify unknowns explicitly in Open Questions
- avoid broad placeholders such as "implement feature" without file/component targets
- include an explicit Maven dependency decision for each major feature area (reuse existing vs add new)
- include target module `pom.xml` for every new dependency
- include an explicit `agent.runtime.ai.mode` decision when runtime bootstrap or realtime behavior is in scope
- include provider and API-mode selection when live model execution is in scope
- include strict tool-schema compatibility notes when OpenAI `responses-http` or `responses-ws` is in scope
- include explicit exception-policy decisions when business conflicts, transient upstream errors, or AGUI pre-run failures need deterministic handling
- include scope/category/action coverage for `exceptionPolicies` when fault handling is part of the design
- include explicit Spring bootstrap notes when the agent must run inside a Spring application
- include the live-model bean strategy when Spring Boot + real provider execution is in scope
- include explicit A2UI catalog/surface/locale asset decisions when structured UI is in scope
- include legacy widget fallback expectations when frontend compatibility depends on them
- include an explicit `agent.agents-config` versus `agent.blueprint` decision when the application owns multiple agent experiences or versions
- include route include-pattern decisions when the application should expose only a subset of AGUI/admin/SIP/domain routes per deployment
- include app-owned UI static-root and brand/default-plan decisions when the service embeds its own AGUI frontend
- include explicit decisions for runtime auto-bind toggles and diagnostics tracing when AGUI, realtime, or sample-style runtime bootstrap is in scope
- include explicit SIP mode selection, endpoint ownership, and conversation-id mapping when telephony is in scope
- include explicit runtime control plan when audit or conversation persistence is in scope
- include at least one validation step that exercises the real model path when the plan depends on OpenAI Responses strict-schema behavior
- include at least one MCP smoke step that proves `tools/list` and one `tools/call` path

## Suggested Commands Reference

Use these as planning anchors (adapt per scope):

- Build/compile:
  mvn -q -pl camel-agent-core,samples/agent-support-service -am -DskipTests compile

- Dependency inspection:
  mvn -q -pl samples/agent-support-service -am dependency:tree
  mvn -q -pl camel-agent-core -am dependency:tree

- Live sample model-path validation when strict tool schemas matter:
  replay the sample-service `POST /sample/agent/session` path instead of relying only on deterministic AGUI or realtime ticket flows

- Structured UI planning anchors:
  use blueprint `a2ui.surfaces[]`, sample-owned assets under `agents/**/a2ui`, and client catalog registry patterns such as `frontend/a2ui/catalog-registry.json`

- SIP/realtime planning anchors:
  use `bash scripts/sip-adapter-v1-smoke.sh` for the sample adapter contract path and document whether OpenAI-managed SIP webhook validation is also required

- Fault-policy planning anchors:
  use blueprint `exceptionPolicies` for business conflicts and transient upstream handling, and document whether policies live inline or in referenced YAML via `exceptionPoliciesRef` / `exceptionPoliciesUri`

- Sample run:
  samples/agent-support-service/run-sample.sh

- MCP tools discovery (Streamable HTTP headers required):
  curl -sS -X POST http://localhost:8082/mcp/admin \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2025-06-18' \
    -d '{"jsonrpc":"2.0","id":"tools-list","method":"tools/list","params":{}}'

- MCP runtime controls:
  curl -sS -X POST http://localhost:8082/mcp/admin \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2025-06-18' \
    -d '{"jsonrpc":"2.0","id":"set-gran","method":"tools/call","params":{"name":"runtime.audit.granularity.set","arguments":{"granularity":"debug"}}}'

- MCP archived conversation read:
  curl -sS -X POST http://localhost:8082/mcp/admin \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2025-06-18' \
    -d '{"jsonrpc":"2.0","id":"session-read","method":"tools/call","params":{"name":"audit.conversation.sessionData","arguments":{"conversationId":"<id>","limit":20}}}'

- Postgres integration flow:
  bash scripts/postgres-it.sh local
  bash scripts/postgres-it.sh ci
  bash scripts/postgres-it.sh down

## Definition of Done for Planning

The planning task is complete when:

- all required sections are present
- all major components are mapped to concrete files/modules
- tests and rollout are explicit
- known risks and unknowns are documented
- next implementation action can start without additional clarification
- Maven dependency plan is complete with module targets and scopes
- Spring application bootstrap steps are explicit when Spring deployment is in scope
- runtime control and archive-read verification steps are explicitly defined when applicable
