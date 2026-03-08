# Architecture

## Runtime

1. Runtime resolves a concrete plan selection for the exchange:
   - explicit request `planName` / `planVersion`
   - else sticky `conversation.plan.selected`
   - else catalog defaults from `agent.agents-config`
   - legacy fallback: `agent.blueprint`
2. The resolved plan version blueprint (`agent.md`) is loaded by `MarkdownBlueprintLoader`.
3. Tool declarations are converted to `ToolSpec` and registered in `DefaultToolRegistry`.
4. `DefaultAgentKernel` handles message loop:
   - append `user.message`
   - invoke model client
   - enforce tool allow-list
   - validate input/output schema
   - execute tool route through Camel
   - append `assistant.message` and `snapshot.written`
5. Events are persisted via `PersistenceFacade` implementation.

Plan-aware runtime behavior:

- kernels are cached by resolved `{planName, planVersion, blueprintUri}`
- first successful resolution appends `conversation.plan.selected`
- later explicit overrides append a new `conversation.plan.selected`
- audit and runtime refresh derive active blueprint from persisted plan-selection events instead of assuming one global blueprint

Runtime bootstrap also binds mutable operational controls and optional archive services:

- `RuntimeControlState` for live audit granularity updates
- `ConversationArchiveService` for optional transcript-focused persistence (`conversation.*`)
- optional `AsyncEventPersistenceFacade` wrapper when `agent.audit.async.enabled=true`

## Plan Catalog

Catalog source:

- `agent.agents-config`

Catalog model:

- multiple named plans
- multiple versions per plan
- exactly one default plan
- exactly one default version per plan

Each version points to a concrete markdown blueprint location. The blueprint remains the unit that defines:

- system prompt
- tools
- AGUI pre-run behavior
- realtime defaults

Internal request headers:

- `agent.planName`
- `agent.planVersion`
- `agent.resolvedPlanName`
- `agent.resolvedPlanVersion`
- `agent.resolvedBlueprint`

## Persistence Mapping

- `agent.conversation` => conversation event stream
- `agent.task` => task snapshots
- `agent.dynamicRoute` => dynamic route metadata snapshots

`camel-agent-persistence-dscope` maps these flows to `FlowStateStore` operations.

## Non-Blocking Audit Path

Runtime can move audit writes off the request thread:

- config flag: `agent.audit.async.enabled=true`
- wrapper: `AsyncEventPersistenceFacade`
- applied to:
  - primary audit/event persistence facade
  - optional conversation archive persistence facade

Async facade behavior:

- `appendEvent(...)` enqueues events into a bounded in-memory queue
- a dedicated background worker flushes events to the delegate persistence facade
- retries use fixed backoff (`agent.audit.async.retry-delay-ms`)
- shutdown waits up to `agent.audit.async.shutdown-timeout-ms`
- periodic metrics logs report enqueue/persist/drop/retry counters

Read-path behavior:

- `loadConversation(...)` merges queued-but-not-yet-flushed events with persisted history
- `listConversationIds(...)` merges pending conversation ids with persisted ids

This keeps audit projections and conversation views coherent while making audit/archive persistence non-blocking for normal request flows.

## Phase-2 Orchestration Behavior

- Async waiting path:
  - `handleUserMessage(..., "task.async <checkpoint>")` creates persisted `TaskState` with `WAITING`.
  - emits `task.waiting`.
- Resume path:
  - `resumeTask(taskId)` transitions task `RESUMED -> FINISHED`.
  - emits `task.resumed` and final `assistant.message`.
- Dynamic route lifecycle:
  - `handleUserMessage(..., "route.instantiate <templateId>")` persists `DynamicRouteState` transitions `CREATED -> STARTED`.

## Distributed Claim/Lock Strategy

For load-balanced resume safety, task ownership is persisted as lease locks:

- Lock flow type: `agent.task.lock`
- Claim event: `task.lock.claim` (`ownerId`, `leaseUntil`)
- Release event: `task.lock.release`

Algorithm:

1. Read current lock state.
2. If active lease exists for another owner, deny claim.
3. Append claim event with optimistic expected version.
4. On optimistic conflict, claim fails (another node won).

## AGUI Integration

Sample AGUI integration uses `camel-ag-ui-component` runtime routes/processors directly.

Frontend transport in `samples/agent-support-service`:

- browser UI is served from `GET /agui/ui`
- frontend sends AGUI envelope via either:
  - `POST /agui/agent` (POST+SSE bridge)
  - `WS /agui/rpc` (AGUI over WebSocket)
- backend returns AGUI events for the selected transport
- frontend renders assistant text from AGUI message content events

Correlation between agent conversations and transport identifiers is handled in core via `CorrelationRegistry`:

- source key: `agent.conversationId`
- correlation keys: `agui.sessionId`, `agui.runId`, `agui.threadId`

Debug audit trail includes available correlation metadata in payload (`payload._correlation`).

Plan-aware request behavior:

- `POST /agui/agent` accepts top-level `planName` / `planVersion`
- `POST /realtime/session/{conversationId}/init` accepts top-level `planName` / `planVersion`
- `POST /realtime/session/{conversationId}/event` accepts top-level `planName` / `planVersion`
- AGUI and realtime processors forward these as Camel headers to `agent:`

## Conversation Archive Persistence (Separate from Audit Trail)

Conversation archive persistence is an optional, transcript-focused stream designed for replay/UX use cases.

- default flag: `agent.conversation.persistence.enabled=false`
- optional dedicated store mapping prefix: `agent.conversation.persistence.*`
- event family:
  - `conversation.user.message`
  - `conversation.assistant.message`
  - `conversation.realtime.observed`

Write paths:

- AGUI pre-run turns (`AgentAgUiPreRunTextProcessor`) append user/assistant archive events
- realtime transcript flows (`RealtimeEventProcessor`) append observed and final turn archive events

Read path:

- MCP tool `audit.conversation.sessionData` returns filtered archive events for a conversation (and optional `sessionId`)

Design intent:

- use audit trail (`user.message`, `tool.*`, `realtime.*`) for diagnostics/operations
- use archive trail (`conversation.*`) for transcript playback and conversation-centric UX

### Realtime Voice Frontend Behavior (Sample)

`samples/agent-support-service` `/agui/ui` voice behavior:

- single toggle control manages start/stop state (`idle`, `live`, `busy`)
- pause profile drives VAD silence timeout for both relay and WebRTC session setup:
  - `fast` -> `800ms`
  - `normal` -> `1200ms`
  - `patient` -> `1800ms`
- UI displays current pause timeout in label and listening status text
- WebRTC transcript log captures input/output transcript events for diagnostics
- output transcript processing is de-duplicated at `response.output_audio_transcript.done` handling to prevent duplicate assistant transcript display
- collapsible `Instruction seed (debug)` panel shows the currently seeded WebRTC instruction context
- instruction debug panel auto-opens when transport switches to WebRTC and on initial load when transport is already WebRTC

### Canonical WebRTC Flow (Source of Truth)

- Define WebRTC flow from `samples/agent-support-service/src/main/resources/frontend/webrtc-test.html`.
- Do not use `index.html` to define WebRTC flow defaults.
- Default control contract from `webrtc-test.html`:
  - `#transport-mode` is disabled and fixed to `webrtc` (`Browser WebRTC (direct)`)
  - `#agui-transport-mode=post` (`POST + SSE`)
  - `#duplex-mode=half`
  - `#vad-pause=normal` (1200ms)
  - `#voice-setting=alloy`
  - `Instruction seed (debug)` panel remains available and auto-opens in WebRTC mode
  - `WebRTC transcript log` panel + clear action remains available for diagnostics

WebRTC flow contract:

1. Browser requests ephemeral token via `/realtime/session/{conversationId}/token`.
2. Browser starts direct WebRTC session with OpenAI Realtime.
3. Browser posts transcript events to Camel realtime endpoint for routing and context merge.
4. Backend (`RealtimeEventProcessor`) routes transcript to agent tools/routes and returns branch flags/assistant payloads.
5. Browser renders transcript + assistant output in the WebRTC UI path.

Separation rule:

- Relay-specific finalize/commit orchestration must not be used as the WebRTC baseline flow definition.

### Pre-Conversation Realtime Context Seed

Before first user transcript turn, `POST /realtime/session/{conversationId}/init` seeds profile context from blueprint metadata/system text into session state:

- `metadata.camelAgent.agentProfile.name`
- `metadata.camelAgent.agentProfile.version`
- `metadata.camelAgent.agentProfile.purpose`
- `metadata.camelAgent.agentProfile.tools[]`
- `metadata.camelAgent.plan.name`
- `metadata.camelAgent.plan.version`
- `metadata.camelAgent.plan.blueprint`
- `metadata.camelAgent.context.agentPurpose`
- `metadata.camelAgent.context.agentFocusHint`

This seed is consumed by WebRTC instruction construction so first-turn responses are aligned with agent purpose/tool scope before transcript history exists.

## Audit Projection

Audit stores raw events (`user.message`, `tool.*`, `realtime.*`, `conversation.plan.selected`, `agent.definition.refreshed`) and projects conversation state on read.

Conversation-level audit metadata now includes:

- `agentName`
- `agentVersion`
- `planName`
- `planVersion`
- `blueprintUri`

Per-step audit projections also include the same resolved agent block so operators can see which plan/version produced each message or tool step.

When async audit is enabled, these projections include both persisted and still-queued events because the read path merges pending async entries before rendering audit responses.

### Event Flow Scenarios

#### 1) No Voice Agent (Text-only AGUI)

```mermaid
sequenceDiagram
  autonumber
  participant U as User
  participant FE as Browser UI (/agui/ui)
  participant AG as AGUI endpoint (/agui/agent)
  participant K as DefaultAgentKernel
  participant MR as AI Model Client
  participant AR as Agent Route(s)
  participant PF as PersistenceFacade

  U->>FE: Enter text prompt
  FE->>AG: POST /agui/agent (AGUI envelope)
  AG->>K: handleUserMessage(conversationId, text)
  K->>PF: append user.message
  K->>MR: model inference request
  MR-->>K: assistant/tool decision
  alt Tool route selected
    K->>AR: execute Camel route (tool)
    AR-->>K: route result
  end
  K->>PF: append assistant.message + snapshot
  K-->>AG: AGUI events stream (SSE)
  AG-->>FE: TEXT_MESSAGE_CONTENT / completion events
  FE-->>U: Render assistant response/widget
```

#### 2) Voice Agent via Camel Relay

```mermaid
sequenceDiagram
  autonumber
  participant U as User
  participant FE as Browser Voice UI
  participant REP as RealtimeEventProcessor
  participant RR as OpenAiRealtimeRelayClient
  participant OAI as OpenAI Realtime API
  participant AR as Agent Route(s)
  participant REG as RealtimeBrowserSessionRegistry
  participant PF as PersistenceFacade

  U->>FE: Speak utterance
  FE->>REP: POST /realtime/session/{id}/event (input_audio_buffer.*)
  REP->>PF: append realtime.input_audio_buffer.*
  REP->>RR: forward relay event
  RR->>OAI: realtime WS event
  OAI-->>RR: transcript/audio events
  RR-->>REP: relay events
  FE->>REP: session.state poll
  REP-->>FE: relay events payload

  FE->>REP: transcript.final (resolved transcript)
  REP->>PF: append realtime.transcript.final
  REP->>AR: route transcript to agent endpoint
  AR-->>REP: assistant result + optional realtimeSessionUpdate
  REP->>REG: merge session context update
  alt sessionContextUpdated true
    REP->>RR: send response.create
  else sessionContextUpdated false
    REP-->>FE: realtimeVoiceBranchStarted=false
  end
  REP-->>FE: assistantMessage + aguiMessages + flags
  FE-->>U: Render transcript/assistant output
```

#### 3) Voice Agent via Browser WebRTC (Direct)

```mermaid
sequenceDiagram
  autonumber
  participant U as User
  participant FE as Browser WebRTC UI
  participant OAI as OpenAI Realtime
  participant REP as RealtimeEventProcessor
  participant AR as Agent Routes
  participant REG as RealtimeBrowserSessionRegistry
  participant PF as PersistenceFacade

  U->>FE: Speak utterance
  FE->>OAI: Send audio over WebRTC channels
  OAI-->>FE: input transcript events
  FE->>REP: POST transcript final
  REP->>PF: append realtime transcript final
  REP->>AR: route transcript
  AR-->>REP: assistant result + optional session patch
  REP->>REG: merge route + transcript + assistant context
  alt session context updated
    REP-->>FE: voice branch started true
  else
    REP-->>FE: voice branch started false
  end

  alt voice branch started true
    FE->>OAI: create response
    OAI-->>FE: audio + output transcript
    FE-->>U: Play voice response + render text
  else
    FE-->>U: No response create, wait or retry
  end
```

Operational guarantee for voice transcript routing:

1. Realtime ingress persistence (`realtime.<eventType>`) is governed by current audit granularity (`none|error|info|debug`) and can be changed live.
2. Transcript is routed through agent/Camel routes first.
3. Realtime session context is merged with route result + transcript/assistant metadata.
4. `response.create` is allowed only after step 3 succeeds.

#### Scenario Comparison Matrix

| Scenario | Primary input trigger | Agent route execution point | Realtime session context update point | `response.create` initiator | Audit event type coverage |
| --- | --- | --- | --- | --- | --- |
| No voice agent (text-only AGUI) | `POST /agui/agent` with user text | `DefaultAgentKernel` routes tool calls after model decision | Not applicable (no realtime voice session) | Not applicable | `user.message`, tool/assistant/snapshot events via `PersistenceFacade` |
| Voice via Camel Relay | `POST /realtime/session/{id}/event` (`input_audio_buffer.*`, `transcript.final`) | `RealtimeEventProcessor` routes transcript to agent endpoint | `RealtimeEventProcessor` merges route/session patch into `RealtimeBrowserSessionRegistry` before voice branch | Backend relay path (`RealtimeEventProcessor -> OpenAiRealtimeRelayClient`) when `sessionContextUpdated=true` | Ingress `realtime.<eventType>` persisted according to active audit granularity; route/assistant events persisted; optional `conversation.*` archive events when enabled |
| Voice via Browser WebRTC (direct) | Browser sends audio directly to OpenAI; frontend posts `transcript.final` to Camel | `RealtimeEventProcessor` routes transcript to agent endpoint | `RealtimeEventProcessor` merges route + transcript/assistant metadata before branch flag | Frontend WebRTC data channel when backend returns `realtimeVoiceBranchStarted=true` (backend remains gatekeeper) | Events received by Camel realtime endpoint persisted according to active audit granularity; optional `conversation.*` archive events when enabled |

Legend:

- **Initiator**: component that sends the actual `response.create` event.
- **Backend gatekeeper**: Camel backend condition (`sessionContextUpdated` / `realtimeVoiceBranchStarted`) that must succeed before response generation is allowed.

## Spring AI ChatMemory Integration

- `DscopeChatMemoryRepository` (`camel-agent-spring-ai`) implements Spring AI `ChatMemoryRepository`.
- Memory is stored in DScope persistence as snapshots:
  - `flowType=agent.chat.memory`, `flowId=<conversationId>`
  - conversation index: `flowType=agent.chat.memory.index`, `flowId=all`
- `SpringAiMessageSerde` performs message serialization/deserialization for:
  - `UserMessage`
  - `SystemMessage`
  - `AssistantMessage` (with tool calls)
  - `ToolResponseMessage`

## Spring AI Provider Gateway

`MultiProviderSpringAiChatGateway` (`camel-agent-spring-ai`) is the default runtime gateway when:

- `agent.runtime.ai.mode=spring-ai`
- no explicit gateway override is configured

Provider mapping:

- `openai` -> Spring AI `OpenAiChatModel` (Chat Completions)
- `claude`/`anthropic` -> Spring AI `AnthropicChatModel`
- `gemini` -> Spring AI `VertexAiGeminiChatModel`

Tool calls are exposed to the kernel through Spring AI `AssistantMessage.ToolCall` mapping into internal `AiToolCall`.

## Audit Trail Granularity

Persistence adapter supports `agent.audit.granularity`:

- `none`: no audit event persistence
- `info`: process step persistence
- `error`: process step persistence + error data payload for error events
- `debug`: process step persistence + full payloads and metadata

Granularity can be changed at runtime through MCP admin tools:

- `runtime.audit.granularity.get`
- `runtime.audit.granularity.set`

## MCP Admin Runtime Controls

Sample admin MCP endpoint supports Streamable HTTP and runtime operations.

Transport requirement:

- `Accept: application/json, text/event-stream`

Runtime control methods:

- `runtime.audit.granularity.get`
- `runtime.audit.granularity.set`
- `runtime.conversation.persistence.get`
- `runtime.conversation.persistence.set`

Archive read method:

- `audit.conversation.sessionData`

These methods are listed via `tools/list` and executed via `tools/call`.
