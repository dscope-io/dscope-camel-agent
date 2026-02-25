# Test Plan

## Unit

1. `MarkdownBlueprintLoaderTest`
   - parses valid `agent.md`
2. `SchemaValidatorTest`
   - rejects missing required fields
3. `DefaultAgentKernelTest`
   - produces assistant message + lifecycle events
   - async task waiting/resume transitions
   - dynamic route lifecycle persistence in memory
4. `DscopePersistenceFacadeTest`
   - appends/reads conversation events
   - saves/rehydrates task snapshot
   - saves/rehydrates dynamic route snapshot
   - distributed task lock claim/release/lease-expiry behavior
5. `SpringAiModelClientTest`
   - delegates to chat gateway
6. `SpringAiMessageSerdeTest`
   - serializes/deserializes Spring AI messages including tool calls/responses
7. `DscopeChatMemoryRepositoryTest`
   - save/find/delete conversation memory with DScope persistence contract
8. `AgentAutoConfigurationTest`
   - starter properties/auto-config basic loading

9. `DefaultAgentKernelTest`
   - lock conflict path when another node owns a task lease
10. `MultiProviderSpringAiChatGatewayTest`
   - OpenAI tool-name normalization for API-compatible function names
   - tool-call name remap back to original tool names for kernel dispatch
11. `RealtimeEventProcessorTest`
   - `shouldLogRealtimeContextCheckpointsInOrder`: verifies context-change logger checkpoints are emitted in strict order before backend voice branch start
   - `shouldNotLogVoiceBranchStartedWhenContextUpdateFails`: verifies `updated=false` + skip checkpoints and absence of voice-branch-start log when realtime context update is not applied
   - `shouldInitializeSessionStartPayloadWhenNoStoredContextExists`: verifies dropped/recreated realtime sessions initialize from incoming `session.start` payload + defaults when no prior context exists
   - `shouldRestoreRealtimeSessionContextOnSessionStartReconnect`: verifies dropped/recreated realtime sessions are rehydrated with full stored context plus incoming overrides on `session.start`

## Integration

1. `SpringAiAuditTrailIntegrationTest`
   - two-turn deterministic scenario:
     - prompt 1 selects `kb.search`
     - prompt 2 selects `support.ticket.open`
     - second prompt evaluation includes first-turn KB result
   - negative scenario:
     - ticket as first prompt does not inject KB result context
   - live LLM scenario (when key available):
     - validates real route selection and second-turn context carry-over
2. End-to-end route execution with `agent:` endpoint and `direct:kb-search`
3. `redis_jdbc` fallback behavior with Redis miss and JDBC success
4. Restart rehydration scenario for unfinished task
5. AGUI sample transport flow (`/agui/ui` -> `POST /agui/agent` POST+SSE)
6. Realtime voice UI behavior (`/agui/ui`):
   - single-toggle start/stop state transitions (`idle`/`live`/`busy`)
   - VAD pause profile mapping (`fast=800`, `normal=1200`, `patient=1800`) reflected in UI label/status
   - output transcript de-duplication (single final `voice output transcript` message per assistant response)
   - voice selector compatibility and persistence:
     - UI only exposes provider-supported realtime voices (`alloy`, `ash`, `ballad`, `coral`, `cedar`, `echo`, `marin`, `sage`, `shimmer`, `verse`)
     - changing voice persists via `POST /realtime/session/{id}/init` (`session.audio.output.voice`) and `POST /realtime/session/{id}/token` returns `200` for the same session
       - quick manual check (one line): `sid=voice-sync-$RANDOM; curl -s -X POST "http://localhost:8080/realtime/session/$sid/init" -H 'Content-Type: application/json' -d '{"session":{"audio":{"output":{"voice":"cedar"}}}}' >/dev/null; curl -s -o /tmp/realtime-token-$sid.json -w "%{http_code}\n" -X POST "http://localhost:8080/realtime/session/$sid/token" -H 'Content-Type: application/json' -d '{}'`
   - quick negative check (one line, expect `400`): `sid=voice-sync-$RANDOM; curl -s -X POST "http://localhost:8080/realtime/session/$sid/init" -H 'Content-Type: application/json' -d '{"session":{"audio":{"output":{"voice":"nova"}}}}' >/dev/null; curl -s -o /tmp/realtime-token-bad-$sid.json -w "%{http_code}\n" -X POST "http://localhost:8080/realtime/session/$sid/token" -H 'Content-Type: application/json' -d '{}'`
      - assert-style positive check (auto-fail unless `200`): `sid=voice-sync-$RANDOM; curl -s -X POST "http://localhost:8080/realtime/session/$sid/init" -H 'Content-Type: application/json' -d '{"session":{"audio":{"output":{"voice":"cedar"}}}}' >/dev/null; code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:8080/realtime/session/$sid/token" -H 'Content-Type: application/json' -d '{}'); [ "$code" = "200" ] || { echo "expected 200, got $code"; exit 1; }`
      - assert-style negative check (auto-fail unless `400`): `sid=voice-sync-$RANDOM; curl -s -X POST "http://localhost:8080/realtime/session/$sid/init" -H 'Content-Type: application/json' -d '{"session":{"audio":{"output":{"voice":"nova"}}}}' >/dev/null; code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:8080/realtime/session/$sid/token" -H 'Content-Type: application/json' -d '{}'); [ "$code" = "400" ] || { echo "expected 400, got $code"; exit 1; }`
      - script alias (positive+negative in one run): `bash scripts/realtime-voice-token-check.sh` (optional args: `bash scripts/realtime-voice-token-check.sh http://localhost:8080 cedar nova`)
7. Realtime event ordering and context-update validation (scenario matrix aligned):
    - text-only AGUI path:
       - verify `user.message` is persisted before tool/assistant lifecycle events
       - verify final `assistant.message` and `snapshot.written` are emitted in order
    - voice via Camel Relay:
       - verify ingress `realtime.<eventType>` persistence for incoming realtime events (`input_audio_buffer.*`, `transcript.final`, `session.*`)
       - verify processing order: `Realtime transcript received` -> transcript routed via agent route -> realtime context update applied -> backend `response.create` branch start (when context update succeeds)
       - verify negative path: if realtime context update is not applied, backend voice branch start is skipped
    - voice via Browser WebRTC:
       - verify frontend requests `response.create` only after backend returns successful routing/context signals (`realtimeVoiceBranchStarted=true`)
       - verify context update metadata (`metadata.camelAgent.lastTranscript`, `metadata.camelAgent.lastAssistantMessage`) is present in realtime session registry for routed transcript
    - cross-cutting:
       - verify agent-context and realtime-context updates are both observable: agent events in conversation trail and realtime session context in registry/session state

## Ops Quick Reference (Logs)

Use these during integration test runs (for example with logs in `/tmp/agent-support-live.log`).

1. Realtime relay lifecycle:
   - `grep -nE 'Realtime relay connected|Realtime relay event sent|Realtime relay send failed|Realtime relay reconnecting|Realtime relay reconnected|Realtime relay closed|Realtime websocket (opened|closed|error)' /tmp/agent-support-live.log`
2. Realtime ingress and transcript routing:
   - `grep -nE 'Realtime event received|Realtime ingress event persisted|Realtime session.start connected|Realtime relay event forwarded|Realtime transcript received|Realtime transcript routed|Realtime event not routed to agent' /tmp/agent-support-live.log`
3. Realtime context-change checkpoints:
   - `grep -nE 'Realtime context update composed|Realtime route session update applied|Realtime context update apply result|Realtime voice branch started from backend|Realtime voice branch skipped because session context update was not applied' /tmp/agent-support-live.log`
4. Realtime reconnect/session restoration checkpoints:
   - `grep -nE 'Realtime session.start connected|Realtime session.start payload restored|Realtime session.update forwarded|Realtime session.update forwarding failed' /tmp/agent-support-live.log`
5. Kernel tool routing and assistant fallback:
   - `grep -nE 'Kernel handleUserMessage started|Kernel model response received|Kernel tool execution (started|completed)|Kernel tool fallback captured|Kernel assistant fallback used from tool result|Kernel final assistant message ready' /tmp/agent-support-live.log`
6. AGUI pre-run fallback decisions:
   - `grep -nE 'AGUI pre-run started|AGUI pre-run primary agent response|AGUI pre-run fallback triggered|AGUI pre-run deterministic fallback route|AGUI pre-run completed' /tmp/agent-support-live.log`
7. End-to-end voice-to-ticket trace:
   - `grep -nE 'Realtime transcript received|Realtime transcript routed|Kernel realtime transcript routing|Kernel tool execution started:.*support.ticket.open|Kernel tool execution completed:.*support.ticket.open|Kernel final assistant message ready' /tmp/agent-support-live.log`
8. Single-command triage (sorted, timestamped signal lines):
   - `grep -nE 'Realtime relay|Realtime event received|Realtime transcript|Kernel |AGUI pre-run|Agent runtime bootstrap' /tmp/agent-support-live.log | sort -t: -k1,1n`
9. Live monitoring (low-noise follow):
   - `tail -f /tmp/agent-support-live.log | grep -E 'Realtime relay|Realtime transcript|Kernel |AGUI pre-run|Agent runtime bootstrap'`
10. Live monitoring with source tags:
   - `tail -f /tmp/agent-support-live.log | awk '/Realtime relay/{print "[relay] " $0; next} /Kernel /{print "[kernel] " $0; next} /AGUI pre-run/{print "[agui] " $0; next} /Agent runtime bootstrap/{print "[bootstrap] " $0; next} /Realtime (event received|transcript)/{print "[realtime] " $0; next}'`
11. Live monitoring with colorized source tags:
   - `tail -f /tmp/agent-support-live.log | awk 'BEGIN{reset="\033[0m"; cRelay="\033[36m"; cKernel="\033[35m"; cAgui="\033[33m"; cBoot="\033[32m"; cRt="\033[34m"} /Realtime relay/{print cRelay "[relay] " $0 reset; next} /Kernel /{print cKernel "[kernel] " $0 reset; next} /AGUI pre-run/{print cAgui "[agui] " $0 reset; next} /Agent runtime bootstrap/{print cBoot "[bootstrap] " $0 reset; next} /Realtime (event received|transcript)/{print cRt "[realtime] " $0 reset; next}'`
12. Non-ANSI fallback (CI/plain logs):
   - `tail -f /tmp/agent-support-live.log | awk '/Realtime relay/{print "[relay] " $0; next} /Kernel /{print "[kernel] " $0; next} /AGUI pre-run/{print "[agui] " $0; next} /Agent runtime bootstrap/{print "[bootstrap] " $0; next} /Realtime (event received|transcript)/{print "[realtime] " $0; next}'`