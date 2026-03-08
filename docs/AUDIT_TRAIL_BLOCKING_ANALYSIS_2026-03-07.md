# Audit Trail Blocking Analysis

Date: 2026-03-07

Status update: historical analysis. As of 2026-03-08 the runtime also supports optional non-blocking audit persistence through `AsyncEventPersistenceFacade` when `agent.audit.async.enabled=true`.

## Conclusion

Audit trail writes are blocking in the baseline synchronous design.

Current runtime status:

- if `agent.audit.async.enabled=false` or unset, the analysis below still applies
- if `agent.audit.async.enabled=true`, audit and conversation-archive writes are moved off the request thread behind a bounded async queue and background writer

The realtime voice path waits for audit persistence because the browser calls the realtime endpoint synchronously, the Camel route invokes the realtime processor directly, the realtime processor calls the agent route synchronously, and the kernel persists events inline through the persistence facade.

## Request Path

1. The browser waits on `POST /realtime/session/{conversationId}/event`.
2. Undertow routes that request directly to the realtime processor.
3. The realtime processor calls the agent route with `template.request(...)`.
4. The agent producer calls `agentKernel.handleUserMessage(...)` synchronously.
5. The kernel persists emitted events inline through `persistenceFacade.appendEvent(...)`.
6. The realtime processor and conversation archive service also append additional audit events inline.

## Evidence

### App layer

- `src/main/resources/ui/agui-contract.html`
  - `postRealtimeEvent(...)` uses `await fetch(...)`.
  - `routeVoiceTranscriptToAgent(...)` awaits the server response before continuing.
- `src/main/resources/routes/ag-ui-platform.camel.yaml`
  - `realtime-session-event` invokes the realtime processor directly with no async queue.
- `src/main/resources/application.yaml`
  - persistence mode is `jdbc`.

### Core runtime

- `camel-agent-core/.../RealtimeEventProcessor.java`
  - routes the transcript with `template.request(...)`.
  - persists fallback realtime turn events inline when needed.
- `camel-agent-core/.../AgentProducer.java`
  - calls `agentKernel.handleUserMessage(...)` directly.
- `camel-agent-core/.../DefaultAgentKernel.java`
  - persists each emitted event inline via `persistenceFacade.appendEvent(...)`.
- `camel-agent-core/.../ConversationArchiveService.java`
  - appends conversation archive events inline.
- `camel-agent-persistence-dscope/.../DscopePersistenceFacade.java`
  - `appendEvent(...)` is synchronous and also updates the conversation index inline.
- `camel-agent-persistence-dscope/.../ScriptedJdbcFlowStateStore.java`
  - uses direct JDBC connections, transactions, inserts, and commit in the calling thread.

## Scaling Concerns

The main concern is not only synchronous writes. Each append currently performs extra read-before-write work:

1. Resolve conversation version.
2. Write the conversation event.
3. Resolve shared conversation index version.
4. Write the conversation index event.

For realtime turns this cost is multiplied by multiple emitted event types such as:

- `realtime.transcript.final`
- `user.message`
- `tool.start`
- `tool.result`
- `agent.message`
- `snapshot.written`
- `conversation.user.message`
- `conversation.assistant.message`

This creates write amplification and contention on the shared conversation index stream.

## Reliability Note

The kernel generally continues if persistence fails, so audit storage problems do not always fail the user conversation. However, persistence latency still blocks the request thread while writes and retries are happening.

## Recommendation

This recommendation has now been implemented as an optional runtime mode.

Implemented direction:

1. `AsyncEventPersistenceFacade` decorates `PersistenceFacade`.
2. `AgentRuntimeBootstrap` enables it with `agent.audit.async.enabled=true`.
3. The same async wrapper is applied to optional conversation archive persistence.
4. Reads merge queued events with persisted history so audit projections remain coherent.
5. Queue saturation is bounded and reported through metrics/warnings.

## Next Work

Remaining work to improve the async mode further:

- batch JDBC flush where practical
- more explicit degraded-mode controls when audit storage is slow or unavailable
- broader end-to-end load tests for async audit behavior in realtime traffic
