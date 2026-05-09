# Agent Persistence Scenarios

This guide explains what persistence exists in the agent runtime today, what gets rehydrated, and which scenario to choose for a given product need.

## Why this matters

The platform has more than one persistence path:

- audit/event persistence
- conversation archive persistence
- task and dynamic-route operational state
- optional persisted context hinting
- optional Spring AI chat memory persistence

These are related, but not identical.

## Quick answer

- Audit trail is persisted and reloaded through the `PersistenceFacade`.
- Conversation archive is also persisted and reloaded (it is not audit-only).
- Task, task-lock, and dynamic-route state are persisted and rehydrated as snapshots/events.
- Persisted context hinting (AGUI/realtime) stores and rehydrates context through conversation events.
- Spring AI chat memory can persist and rehydrate via Camel Persistence snapshots.
- The core kernel turn history used during an active request is still read from an in-memory store in `DefaultAgentKernel`.

## Persistence building blocks

## 1) Persistence facade and backend selection

`DscopePersistenceFactory` constructs stores through Camel Persistence `FlowStateStoreFactory` and wires `DscopePersistenceFacade`.

What this gives you:

- storage-agnostic backend selection from Camel Persistence
- optional dedicated audit store via `agent.audit.*` overrides
- one facade API used by kernel/runtime services

## 2) Event streams vs snapshots

The facade uses both:

- Event append/read (`appendEvents`, `readEvents`) for conversation and lock event streams.
- Snapshot write/rehydrate (`writeSnapshot`, `rehydrate`) for task and dynamic-route state.

## Scenario matrix

| Scenario | Data persisted | Rehydrated from persistence? | Typical consumer |
| --- | --- | --- | --- |
| Audit trail | `user.message`, `tool.*`, `agent.message`, `realtime.*`, model usage, selection events | Yes, via `loadConversation` | audit APIs, diagnostics, session usage summaries |
| Conversation archive | `conversation.user.message`, `conversation.assistant.message`, `conversation.realtime.observed` | Yes, filtered from persisted conversation events | transcript/replay UX |
| Task lifecycle | Task snapshots (`WAITING`, `RESUMED`, `FINISHED`, etc.) | Yes, via `loadTask` + snapshot rehydrate | async task resume/ownership logic |
| Task claims | Lock claim/release events | Yes, via lock stream rehydrate | distributed task ownership |
| Dynamic routes | Dynamic route snapshots | Yes, via `loadDynamicRoute` | route lifecycle and status checks |
| Persisted context hinting | Custom context events (for example `agent.conversation.context`) | Yes, merged from persisted conversation events | AGUI/realtime prompt enrichment |
| Spring AI chat memory (optional) | Serialized message snapshots + id index | Yes, via chat-memory snapshots | Spring AI `ChatMemory` integration |

## Scenario details

## A) Audit trail (default operational history)

Use this when you need full execution diagnostics.

Stored shape:

- append-only events per conversation id
- includes tool lifecycle and runtime internals
- includes conversation index stream for listing recent conversations

Read path:

- `PersistenceFacade.loadConversation(conversationId, limit)`

Important:

- this stream can be high volume
- audit granularity controls what is recorded

## B) Conversation archive (human transcript focus)

Use this when you need replayable user/assistant conversation history.

Stored shape:

- `conversation.*` event types appended through `ConversationArchiveService`
- can include AGUI and realtime observed transcript events

Read path:

- `ConversationArchiveService.loadConversationEvents(...)` filters persisted conversation events to `conversation.*`

Important:

- archive is persisted through the same facade, but semantically different from operator audit
- keep separate retention expectations from debug-focused audit

## C) Task and lock persistence (operational durability)

Use this for async workflows and multi-node safety.

Stored shape:

- task snapshots (`agent.task`)
- task-lock claim/release events (`agent.task.lock`)

Read/rehydrate path:

- `loadTask(...)` reads snapshot state
- claim checks and lock ownership use lock-stream rehydrate

Important:

- lock semantics prevent double-resume across nodes
- lease seconds should match expected processing windows

## D) Dynamic route state (runtime route durability)

Use this when route instantiation state must survive process restarts.

Stored shape:

- dynamic-route snapshots (`agent.dynamicRoute`)

Read path:

- `loadDynamicRoute(routeInstanceId)`

Important:

- current facade does not provide list-by-flow-type; callers track known ids externally

## E) Persisted context hinting for AGUI/realtime

Use this when you want compact, reusable context extracted from prior turns and injected into future user input/transcripts.

Stored shape:

- context events in conversation stream (default event type `agent.conversation.context`)

Read path:

- `PersistedConversationContextStore.load(...)` loads conversation events and merges matching context events

Injection points:

- AGUI processor enriches `params.text`
- realtime processor enriches transcript payload before agent handling

Important:

- this is targeted context persistence, not full model-memory replay

## F) Spring AI chat memory persistence (optional)

Use this when your Spring application relies on Spring AI `ChatMemory` abstraction and you want durable memory storage.

Stored shape:

- snapshots in `agent.chat.memory`
- conversation id index snapshot in `agent.chat.memory.index`

Read path:

- `findByConversationId` rehydrates message list snapshots

Important:

- enabled through starter auto-configuration when chat memory classes are present and `agent.chat-memory-enabled=true`
- backend is created through Camel Persistence factory path

## Current caveat: kernel turn history during request

`DefaultAgentKernel` currently keeps an in-memory conversation store for the history it sends to the model in-process.

Implication:

- persisted audit/archive/context improve durability and cross-request visibility
- but the immediate model-history source in the kernel is not yet fully rehydrated from persistent conversation events

This is acceptable for many single-process paths but should be considered if strict restart continuity of model-facing history is required.

## Recommended deployment patterns

## Pattern 1: Diagnostics-first production

Choose when operating complex tools and distributed runtime.

- enable durable audit persistence
- keep archive enabled for user-facing replay if required
- define retention separately for audit vs archive semantics

## Pattern 2: Transcript-first product UX

Choose when conversation replay and compliance transcript are primary.

- enable conversation archive
- keep audit at lower granularity unless debugging is needed
- add persisted context hinting for better follow-up turns

## Pattern 3: Spring AI memory-centric app

Choose when Spring AI chat memory is central to application behavior.

- enable starter chat memory
- use DScope chat memory repository (Camel Persistence-backed)
- validate memory window and retention against workload

## Pattern 4: Async task-heavy orchestration

Choose when delayed/resumable operations are common.

- use task snapshot persistence and lock claims
- tune claim lease duration and retry policies
- monitor lock conflicts as a health signal

## Configuration checklist

- choose persistence backend through `agent.persistence-mode` (starter) or `camel.persistence.backend`
- set `agent.audit-granularity` (`none`, `error`, `info`, `debug`) intentionally
- decide whether audit needs a dedicated backend via `agent.audit.*`
- decide whether conversation archive is enabled for your runtime path
- decide whether persisted context hinting processors are in your AGUI/realtime routes
- decide whether Spring AI chat memory should be enabled (`agent.chat-memory-enabled=true`)

## Decision guide

Use this quick rule:

- Need operator debugging and tool-level traceability: prioritize audit trail.
- Need user transcript replay and product conversation history: prioritize conversation archive.
- Need compact reusable hints in AGUI/realtime: use persisted context hinting.
- Need Spring AI-native memory repository semantics: use chat memory persistence.
- Need resumable and safe distributed execution: use task snapshots + task-lock claims.

In mature deployments, use multiple scenarios together with clear retention, ownership, and observability boundaries.
