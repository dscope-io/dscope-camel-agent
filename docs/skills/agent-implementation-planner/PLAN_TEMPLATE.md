# New Agent Implementation Plan Template

Companion examples:

- generic fill-in template: this file
- worked Spring Boot example: `docs/skills/agent-implementation-planner/SPRING_BOOT_EXAMPLE_PLAN.md`

## 1. Goal and Scope

### Goal
- Business objective:
- Primary users:
- Success criteria (measurable):

### In Scope
- 
- 

### Out of Scope
- 
- 

## 2. Assumptions

- 
- 
- 

## 3. Architecture Decisions

### Agent Identity
- Agent name/title:
- Blueprint path:
- Versioning approach:
- Plan catalog mode: single `agent.blueprint` / multi-plan `agent.agents-config`
- Plan/version folder layout if multi-plan:

### Spring Application Bootstrap (Required when Spring Boot or application embedding is in scope)
- Deployment style: Camel Main / Spring Boot / both
- Starter usage: `camel-agent-starter` yes/no
- Spring Boot module or sample target:
- Blueprint resource path inside the application:
- Externalized configuration keys:
  - `agent.blueprint`
  - `agent.agents-config`
  - `agent.persistence-mode`
  - `agent.audit-granularity`
  - `agent.chat-memory-enabled`
- Live model bean strategy:
  - default starter limitation: `AiModelClient` falls back to noop gateway unless overridden
  - planned replacement bean or override path:
- Route invocation pattern inside the application:
  - `to("agent:...")`
  - `ProducerTemplate`
- Credentials strategy:
  - OpenAI / Anthropic / Gemini source:
- Reference docs:
  - `docs/PRODUCT_GUIDE.md`

### AI Runtime Bootstrap (Required when runtime bootstrap or live model execution is in scope)
- Execution owner: runtime bootstrap / explicit bean wiring / external relay
- `agent.runtime.ai.mode`: `spring-ai` / `realtime` / explicit custom binding
- Runtime gateway override class (`agent.runtime.spring-ai.gateway-class`) if used:
- Provider choice: `openai` / `gemini` / `claude` / `anthropic`
- Provider/model defaults and overrides:
- OpenAI API mode if applicable: `chat` / `responses-http` / `responses-ws`
- Strict tool-schema compatibility decision if OpenAI Responses is used:
- Responses WebSocket timeout/backoff expectations if `responses-ws` is used:
- Real model-path validation strategy:

### Fault and Exception Policies (Required when explicit runtime fault handling is in scope)
- Policy location: inline `exceptionPolicies` / referenced YAML / route-local only
- Covered scopes: `tool.execute` / `agui.pre-run` / both
- Technical failure classes and status codes:
- Business failure classes and status codes:
- Retry policy names and thresholds:
- Retry exhaustion fallback action:
- `resolve` prompt strategy if used:
- Route-level versus blueprint-level ownership split:

### Interaction Model
- Channels: AGUI / Realtime / Backend-only
- Primary request-response pattern:
- Fallback behavior:
- MCP admin transport requirements (Streamable HTTP headers, protocol version):
- Runtime route-builder toggle (`agent.runtime.agent-routes-enabled`):
- Runtime route include pattern (`agent.runtime.routes-include-pattern`):
- Diagnostics trace policy (`agent.diagnostics.trace.enabled`):

### A2UI Templates (Required when structured UI is in scope)
- Response contract: legacy `widget` / top-level `a2ui` / both
- Blueprint `a2ui.surfaces[]` design:
- Catalog ids and supported-catalog negotiation strategy:
- Catalog JSON resource paths:
- Surface JSON resource paths:
- Locale bundle resource paths:
- Match-field strategy for selecting surfaces:
- Legacy widget/template fallback expectation:
- Client rendering targets: browser / Flutter / other
- Static UI ownership under `src/main/resources/ui`: yes/no
- AGUI brand/theme/default-plan settings if app-owned UI is in scope:

### A2A Exposure and Consumption (Required when peer-agent interoperability is in scope)
- A2A role: caller / exposed service / both / none
- Public base URL ownership (`agent.runtime.a2a.public-base-url`):
- Exposed-agent mapping file path (`agent.runtime.a2a.exposed-agents-config`):
- Public agent ids and local plan/version mappings:
- Internal A2A agent endpoint override needed (`agent.runtime.a2a.agent-endpoint-uri`): yes/no
- Endpoint ownership for `/a2a/rpc`, `/a2a/sse/{taskId}`, `/.well-known/agent-card.json`:
- Remote peer dependency/fallback behavior:
- Task/conversation correlation expectations:

### WebRTC Flow Settings (Required when WebRTC is in scope)
- Source of truth rule: define WebRTC flow from `samples/agent-support-service/src/main/resources/frontend/webrtc-test.html` only (do not use `index.html` to define WebRTC flow).
- Reference page path (default: `samples/agent-support-service/src/main/resources/frontend/webrtc-test.html`):
- Transport selector behavior (`#transport-mode` disabled/fixed to `webrtc`):
- AGUI mode default (`#agui-transport-mode=post`):
- Duplex mode default (`#duplex-mode=half`):
- VAD pause default (`#vad-pause=normal` / 1200ms):
- Voice default (`#voice-setting=alloy`):
- Instruction debug panel behavior (shown + auto-open in WebRTC mode):
- WebRTC transcript diagnostics (`WebRTC transcript log` + clear action):
- Separation rule from relay flow (no relay finalize/commit logic in WebRTC baseline):

### SIP and Realtime Telephony (Required when telephony is in scope)
- Telephony mode: adapter contract / OpenAI-managed SIP webhook / both
- Stable conversation-id mapping strategy:
- Caller identity propagation (`callerId`, `fromNumber`, `agent.session.params.*`):
- SIP adapter endpoints if used:
  - `POST /sip/adapter/v1/session/{conversationId}/start`
  - `POST /sip/adapter/v1/session/{conversationId}/turn`
  - `POST /sip/adapter/v1/session/{conversationId}/end`
- OpenAI SIP webhook endpoint if used:
  - `POST /openai/realtime/sip/webhook`
- `agent.runtime.sip.bind-processors` decision:
- SIP processor bean names or overrides:
- Realtime session/init/transcript mapping rules:
- Provider onboarding or call-control surface needed: yes/no
- SIP metadata privacy/compliance handling:

### Tooling Design
- Blueprint `## Tools` section format rule (required): use a fenced YAML block with top-level `tools:` (do not use prose bullets for tool definitions).
- MCP tool seed example (parser-compatible):
  ```yaml
  tools:
    - name: calendar.mcp
      description: Calendar MCP service seed
      endpointUri: mcp:http://localhost:8080/mcp
      inputSchemaInline:
        type: object
        properties: {}
  ```
- Tool list and purpose:
  - 
  - 
- Route artifacts to add/update:
  - 
- Kamelets required (yes/no + why):
- MCP runtime/admin methods to expose or reuse:
  - 

### Maven Dependencies
- Dependency strategy: reuse existing / add new / mixed
- Dependency scan commands and findings:
  - `mvn -q -pl camel-agent-core -am dependency:tree`
  - `mvn -q -pl samples/agent-support-service -am dependency:tree`
  - `mvn -q -pl camel-agent-starter -am dependency:tree`

| groupId | artifactId | Scope | Version Source | Target Module (pom.xml) | Reason |
|---|---|---|---|---|---|
|  |  |  | parent-managed / explicit |  |  |
|  |  |  | parent-managed / explicit |  |  |

No-change note (if applicable):
- 

### Persistence and Audit
- Runtime backend: in-memory / jdbc / redis / redis_jdbc
- Audit granularity: none / info / debug
- Split audit store required: yes/no
- DB/auth approach:
- Async audit/archive wrapper settings needed: yes/no
- Conversation archive persistence enabled by default: true/false
- Conversation archive dedicated store required: yes/no
- Conversation archive config keys:
  - `agent.conversation.persistence.enabled`
  - `agent.conversation.persistence.*` (if dedicated backend)

Quick decision hint:

| If requirement is... | Prefer | Primary methods/config |
|---|---|---|
| execution diagnostics and tool lifecycle tracing | Audit trail (`user.message`, `tool.*`, `realtime.*`) | `runtime.audit.granularity.get/set` |
| transcript replay and user/assistant conversation feed | Conversation archive (`conversation.*`) | `runtime.conversation.persistence.get/set`, `audit.conversation.sessionData` |

### Lifecycle and Operations
- Refresh strategy (all/single conversation):
- Close conversation behavior:
- Purge preview and purge criteria:
- A2A endpoint enablement and ownership:
- AGUI auto-bind decisions:
- Realtime auto-bind and browser-session decisions:
- Runtime control methods:
  - `runtime.audit.granularity.get|set`
  - `runtime.conversation.persistence.get|set`
- Archive read method:
  - `audit.conversation.sessionData`

## 4. Implementation Phases

### Phase 1 — Blueprint and Contracts
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 
  - plan catalog and version defaults are explicit when multi-plan mode is selected
  - A2UI resource tree and catalog ids are explicit when structured UI is in scope
  - exception-policy ownership and scope coverage are explicit when fault handling is in scope

### Phase 2 — Tools and Routes
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 
  - A2A caller/service routing verified when applicable
  - OpenAI Responses schema shape reviewed when provider/api-mode requires strict validation
  - SIP route contract or webhook route ownership verified when telephony is in scope
  - business versus technical failure handling is mapped before route/tool implementation when fault policies are in scope

### Phase 3 — Persistence, Audit, Lifecycle
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 
  - MCP `get/set/get` verification for audit granularity
  - MCP `get/set/get` verification for conversation persistence

### Phase 4 — Spring/UX/Realtime Integration (if applicable)
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 
  - Runtime bootstrap binds the intended AI client and gateway path when bootstrap-owned model execution is planned
  - Spring context starts with planned beans and configuration overrides
  - Agent route or controller path successfully invokes `agent:` inside the application
  - A2A endpoints respond on the intended host/path set when enabled
  - Generate fresh AGUI/realtime turn and verify non-empty `audit.conversation.sessionData`
  - Structured UI responses include the intended `widget` and/or `a2ui` contract when applicable
  - SIP start/turn/end or OpenAI webhook flow reaches the intended realtime processors when telephony is enabled
  - For WebRTC mode, verify configured defaults match HTML controls (`transport=webrtc`, `agui=post`, `duplex=half`, `pause=normal`, `voice=alloy`)

### Phase 5 — Hardening and Release
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 
  - retry exhaustion and fallback behavior are documented and operator-visible when exception policies are in scope

## 5. Test Plan

### Unit Tests
- 
- 
- A2UI surface/resource resolution test when structured UI is in scope
- `agents.yaml` plan selection/default-version parsing test when multi-plan mode is in scope
- exception-policy parse and ordered retry-fallback test when fault handling is in scope

### Integration Tests
- 
- 
- Runtime bootstrap AI mode / gateway-class test when bootstrap-owned model execution is in scope
- Spring Boot context test or equivalent bean wiring verification when Spring is in scope
- A2A agent-card/RPC verification when interoperability is in scope
- MCP tools/list includes expected runtime/archive methods
- MCP tools/call returns expected structuredContent for runtime controls
- Real model-path test for OpenAI Responses flows when strict tool-schema behavior matters
- Widget plus A2UI contract verification when frontend rendering depends on structured responses
- SIP adapter or OpenAI SIP webhook flow verification when telephony is in scope
- plan/version-specific route and UI default behavior test when one service hosts multiple plans
- `retry` / `rethrow` / `terminate` / `resolve` behavior test when exception policies are in scope

### Environment/Smoke Tests
- Commands:
  - `mvn -q -pl camel-agent-core,samples/agent-support-service -am -DskipTests compile`
  - `bash scripts/postgres-it.sh local`
  - `curl -sS -X POST http://localhost:8082/mcp/admin -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-06-18' -d '{"jsonrpc":"2.0","id":"tools-list","method":"tools/list","params":{}}'`
  - `curl -sS -X POST http://localhost:8082/mcp/admin -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-06-18' -d '{"jsonrpc":"2.0","id":"session-read","method":"tools/call","params":{"name":"audit.conversation.sessionData","arguments":{"conversationId":"<id>","limit":20}}}'`
  - sample-service `POST /sample/agent/session` when validating live OpenAI Responses schema behavior
  - `bash scripts/sip-adapter-v1-smoke.sh` when validating SIP adapter ingress
- Expected outcomes:
  - 
  - runtime control methods update live state without restart
  - archived conversation session read returns expected `conversation.*` events after fresh interaction
  - model-path validation proves schema-compatible tool execution when OpenAI Responses is selected
  - telephony ingress preserves stable conversation correlation and reaches the intended realtime path when enabled

## 6. Deployment and Rollback

### Deployment Steps
1. 
2. 
3. 

### Rollback Steps
1. 
2. 
3. 

### Observability Checks
- 
- 
- Verify Spring application logs show the planned model client and agent bootstrap path
- Verify A2A agent-card metadata and exposed-agent mapping match the intended public surface
- Verify archive event types in persistence store (`conversation.user.message`, `conversation.assistant.message`, `conversation.realtime.observed` as applicable)
- Verify MCP response includes expected `structuredContent` payloads for control/read methods

## 7. Risks and Mitigations

| Risk | Impact | Mitigation | Owner |
|---|---|---|---|
|  |  |  |  |
|  |  |  |  |

## 8. Open Questions

- 
- 
- 

---

## Requirement Traceability

Map each requirement to implementation/testing items.

| Requirement | Design/Phase | Test Coverage |
|---|---|---|
|  |  |  |
|  |  |  |
| Spring application bootstrap | Spring Application Bootstrap / Phase 4 | Spring context start + agent invocation |
| A2A exposure/consumption | A2A Exposure and Consumption / Phases 2-4 | agent-card + RPC/SSE verification |

## Dependency Traceability

Map each added dependency to the feature and validation.

| Dependency | Feature | Validation |
|---|---|---|
|  |  |  |
|  |  |  |

## Runtime Control Traceability

Map runtime control requirements to MCP methods and verification.

| Requirement | MCP Method | Verification |
|---|---|---|
| Audit granularity runtime update | runtime.audit.granularity.set / runtime.audit.granularity.get | tools/call set then get returns updated level |
| Conversation archive toggle runtime update | runtime.conversation.persistence.set / runtime.conversation.persistence.get | tools/call set then get returns updated boolean |
| Session conversation archive read | audit.conversation.sessionData | Fresh interaction yields non-empty `count` and `conversation.*` event types |
