# Twilio SIP Sample Setup

## What The Sample Supports

The sample now exposes a SIP-oriented HTTP adapter contract on the default runtime port:

- `POST /sip/adapter/v1/session/{conversationId}/start`
- `POST /sip/adapter/v1/session/{conversationId}/turn`
- `POST /sip/adapter/v1/session/{conversationId}/end`

These routes reuse the same realtime session-init and transcript-routing processors as the browser/WebRTC path.

Concrete request/response sequence example:

- `docs/TWILIO_ADAPTER_FLOW_EXAMPLE.md`

Important limitation:

- the sample is **not** a SIP server, SBC, or RTP media endpoint
- it does **not** accept SIP INVITEs directly
- it expects another service to translate telephony events into the JSON contract above

## Twilio Integration Model

To make phone calls work through Twilio, insert a Twilio-facing adapter in front of the sample.

The sample now ships two Twilio-related scaffolds:

1. a thin HTTP adapter skeleton under `/twilio/adapter/v1/call/{callSid}/{start|turn|end}`
2. Camel Twilio producer routes for `direct:twilio-outbound-call` and `direct:twilio-send-message`

The first scaffold is for media/call lifecycle bridging into the existing realtime flow. The second is for Twilio REST operations only.

### Recommended Topology

1. A Twilio phone number or Twilio Elastic SIP Trunk receives the inbound call.
2. A Twilio adapter service handles signaling/media.
3. The adapter maps the Twilio call to a backend `conversationId` such as `sip:twilio:<CallSid>`.
4. The adapter calls the sample SIP endpoints for start, final transcript turns, and end-of-call.
5. The adapter converts `assistantMessage` text to audio and sends it back to the caller.

## Minimum Adapter Responsibilities

Your Twilio adapter must do all of the following:

1. Create deterministic conversation ids.
   Example: `sip:twilio:<CallSid>`

2. On call start, invoke:

```http
POST /sip/adapter/v1/session/{conversationId}/start
Content-Type: application/json
```

Example body:

```json
{
  "call": {
    "id": "CA1234567890abcdef",
    "from": "+15551230001",
    "to": "+15557650002"
  },
  "session": {
    "audio": {
      "output": {
        "voice": "alloy"
      }
    },
    "metadata": {
      "twilio": {
        "callSid": "CA1234567890abcdef"
      }
    }
  }
}
```

3. When STT produces a final utterance, invoke:

```http
POST /sip/adapter/v1/session/{conversationId}/turn
Content-Type: application/json
```

Example body:

```json
{
  "text": "I need help with my login"
}
```

4. Read the sample response and synthesize the returned `assistantMessage` back to the caller.

5. On hangup, invoke:

```http
POST /sip/adapter/v1/session/{conversationId}/end
Content-Type: application/json
```

## Twilio Options

### Option 1: Twilio SIP Trunk + External SIP Adapter

Use this when you want a telephony architecture that stays SIP-first.

- Twilio Elastic SIP Trunk or BYOC points to your SIP adapter / SBC
- your adapter handles SIP signaling, RTP, STT, and TTS
- your adapter translates into this sample's `/sip/adapter/v1/...` contract

This is the closest match to the sample's current SIP adapter design.

### Option 2: Twilio Voice Media Streams + HTTP/WebSocket Adapter

Use this when you do not need a full SIP stack in your Java sample.

- Twilio Media Streams sends audio to your adapter over WebSocket
- your adapter runs STT and TTS
- your adapter calls the sample `/start`, `/turn`, and `/end` HTTP endpoints

This is often easier to prototype than standing up a SIP SBC.

## What You Need To Configure In Twilio

At minimum:

1. Provision an inbound Twilio number.
2. Decide whether you are using SIP trunking or Media Streams.
3. Point Twilio to your adapter service, not directly to the sample.
4. Make the adapter publicly reachable over HTTPS/WSS.
5. Preserve `CallSid` or SIP `Call-ID` across all adapter callbacks so conversation continuity works.

## Security And Production Requirements

Before exposing this publicly, add:

1. request authentication between Twilio adapter and sample
2. Twilio signature validation or SIP trunk IP allow-listing at the adapter edge
3. HTTPS termination and certificate management
4. rate limiting and call concurrency limits
5. PII policy for caller number, transcript text, and call metadata

## Local Development Path

For local validation:

1. run the sample
2. verify the adapter contract with `bash scripts/sip-adapter-v1-smoke.sh`
3. expose the adapter service with a tunnel such as `ngrok` if Twilio must reach it during development
4. only after the adapter works locally, connect the Twilio number/trunk to that adapter

## Current Gaps You Must Fill

The sample still leaves these responsibilities to the adapter layer:

- SIP signaling termination
- RTP/media handling
- speech-to-text
- text-to-speech
- barge-in handling
- DTMF / IVR behavior
- call transfer / conferencing semantics

## Recommended First Increment

If the immediate goal is "support calls from a phone," the fastest path is:

1. keep this sample as the backend agent runtime
2. build a thin Twilio adapter that maps `CallSid -> conversationId`
3. send `/start`, `/turn`, and `/end` to the sample
4. use Twilio or external STT/TTS in the adapter
5. add auth in front of the sample SIP endpoints before public exposure