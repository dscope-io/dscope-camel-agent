# SIP Realtime Adapter Plan

## Objective
Enable a SIP-based frontend (telephony or voice bot edge) to reuse the existing agent realtime backend and memory-sync behavior without coupling to `/agui/ui`.

## Current Reuse Points
The following backend endpoints already provide the required building blocks:

- `POST /realtime/session/{conversationId}/init`
- `POST /realtime/session/{conversationId}/event`
- `POST /realtime/session/{conversationId}/token` (optional for browser/WebRTC-style client-secret flow)

Memory/context sync already happens in agent runtime processors:

- Session seed + profile merge: `RealtimeBrowserSessionInitProcessor`
- Transcript route + context merge + branch trigger: `RealtimeEventProcessor`
- Token minting using stored session context: `RealtimeBrowserTokenProcessor`

## Target Architecture (SIP)
- **SIP Adapter Service** (new): terminates SIP signaling/media and performs STT/TTS integration.
- **Agent Realtime Backend** (existing): keeps session context, routes transcripts to agent, merges realtime context.
- **Conversation Identity**: adapter maps SIP call identity to backend `conversationId`.

### Identity Mapping
Use deterministic mapping for continuity:

- `conversationId = sip:{tenant}:{callId}`
- Optional aliases stored by adapter:
  - SIP `Call-ID`
  - SIP dialog ID / leg ID
  - user ANI/DNIS

## Event Contract (SIP Adapter -> Realtime Backend)

### 1) Call Start / Session Init
Adapter calls:

`POST /realtime/session/{conversationId}/init`

Body example:

```json
{
  "session": {
    "type": "realtime",
    "model": "gpt-realtime",
    "audio": {
      "output": { "voice": "alloy" }
    },
    "metadata": {
      "sip": {
        "callId": "abc-123",
        "from": "+15551234567",
        "to": "+15557654321"
      }
    }
  }
}
```

### 2) Final Transcript Turn
Adapter sends final recognized utterance:

`POST /realtime/session/{conversationId}/event`

```json
{
  "type": "transcript.final",
  "text": "I need help with my login"
}
```

Backend returns:

- `assistantMessage`
- `sessionContextUpdated`
- `realtimeVoiceBranchStarted`
- optional `aguiMessages`

Adapter should treat this response as authoritative turn output/state.

### 3) Optional Realtime Provider Events
If adapter relays low-level events, send to same `/event` endpoint with their `type`.

### 4) Token Flow (Optional)
Use `/token` only when adapter/client needs OpenAI client secret style flow.
For pure server-side SIP orchestration, this can be skipped.

## State Machine (Adapter)
1. `CALL_CONNECTED`
2. `SESSION_INITIALIZED` (`/init` success)
3. `LISTENING`
4. `TRANSCRIPT_FINAL_RECEIVED`
5. `AGENT_ROUTED` (`/event` success)
6. `SPEAKING_RESPONSE`
7. back to `LISTENING` until hangup
8. `CALL_ENDED`

## Memory and Context Rules
- Do not store agent memory only in SIP adapter.
- Use backend `conversationId` consistently across all turns.
- Always call `/init` once at call start (or reconnect) before sending `transcript.final`.
- Let backend own merge logic for `metadata.camelAgent.*` and `context.recentTurns`.

## Failure Handling
- `/init` non-200: fail call bootstrap or fallback to static IVR prompt.
- `/event` timeout/error: retry once with same `conversationId`, then fallback prompt.
- Missing session (`410` from `/token`): re-run `/init`, then continue.
- Duplicate transcript events: adapter de-duplicates by utterance ID + timestamp window.

## Implementation Phases

### Phase 1 (MVP)
- SIP adapter sends `/init` and `transcript.final` only.
- Uses `assistantMessage` text response.
- No direct low-level provider event relay.

### Phase 2
- Add barge-in handling and partial transcript policy.
- Add adapter-level retry/backoff and idempotency keys.
- Add conversation transfer support (same `conversationId` across legs when desired).

### Phase 3
- Add observability dashboards:
  - transcript latency
  - route latency
  - response synthesis latency
  - context-merge success rate

## Validation Checklist
- Multi-turn call keeps prior-turn context in responses.
- Ticket workflow uses prior KB context on later turns.
- Reconnect/new SIP leg can continue same conversation when `conversationId` is preserved.
- Backend reports `sessionContextUpdated=true` for routed final transcripts.

## Open Decisions
- Canonical `conversationId` strategy across transfers/conferences.
- Whether to persist SIP metadata in backend session metadata (privacy/compliance).
- TTS owner: SIP adapter vs backend realtime branch.
