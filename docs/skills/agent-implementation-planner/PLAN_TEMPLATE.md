# New Agent Implementation Plan Template

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

### Interaction Model
- Channels: AGUI / Realtime / Backend-only
- Primary request-response pattern:
- Fallback behavior:
- MCP admin transport requirements (Streamable HTTP headers, protocol version):

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

### Phase 2 — Tools and Routes
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 

### Phase 3 — Persistence, Audit, Lifecycle
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 
  - MCP `get/set/get` verification for audit granularity
  - MCP `get/set/get` verification for conversation persistence

### Phase 4 — UX/Realtime Integration (if applicable)
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 
  - Generate fresh AGUI/realtime turn and verify non-empty `audit.conversation.sessionData`
  - For WebRTC mode, verify configured defaults match HTML controls (`transport=webrtc`, `agui=post`, `duplex=half`, `pause=normal`, `voice=alloy`)

### Phase 5 — Hardening and Release
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 

## 5. Test Plan

### Unit Tests
- 
- 

### Integration Tests
- 
- 
- MCP tools/list includes expected runtime/archive methods
- MCP tools/call returns expected structuredContent for runtime controls

### Environment/Smoke Tests
- Commands:
  - `mvn -q -pl camel-agent-core,samples/agent-support-service -am -DskipTests compile`
  - `bash scripts/postgres-it.sh local`
  - `curl -sS -X POST http://localhost:8082/mcp/admin -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-06-18' -d '{"jsonrpc":"2.0","id":"tools-list","method":"tools/list","params":{}}'`
  - `curl -sS -X POST http://localhost:8082/mcp/admin -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-06-18' -d '{"jsonrpc":"2.0","id":"session-read","method":"tools/call","params":{"name":"audit.conversation.sessionData","arguments":{"conversationId":"<id>","limit":20}}}'`
- Expected outcomes:
  - 
  - runtime control methods update live state without restart
  - archived conversation session read returns expected `conversation.*` events after fresh interaction

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
