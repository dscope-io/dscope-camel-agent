# Twilio SIP Sample Setup

## What The Sample Supports

The sample now supports two distinct telephony integration shapes.

Shared runtime APIs:

- `POST /telephony/onboarding/openai-twilio`
- `GET /telephony/onboarding/openai-twilio`
- `GET /audit/conversation/sip`
- `POST /openai/realtime/sip/webhook`

Adapter-style SIP ingress APIs:

- `POST /sip/adapter/v1/session/{conversationId}/start`
- `POST /sip/adapter/v1/session/{conversationId}/turn`
- `POST /sip/adapter/v1/session/{conversationId}/end`

These routes reuse the same realtime session-init and transcript-routing processors as the browser/WebRTC path.

Important distinction:

- the sample is still **not** a SIP server, SBC, or RTP media endpoint
- it still does **not** accept SIP INVITEs directly
- for OpenAI Realtime SIP, Twilio targets the OpenAI SIP URI, not this Java process
- for custom media flows, another service still translates telephony events into the JSON adapter contract above

## Preferred Topology For OpenAI Realtime SIP

Use this when Twilio Elastic SIP Trunk should hand the call to OpenAI Realtime SIP and the sample should remain the orchestration, webhook, and audit runtime.

1. Call the onboarding API:

```http
POST /telephony/onboarding/openai-twilio
Content-Type: application/json
```

Example body:

```json
{
  "tenantId": "acme",
  "agentId": "support",
  "openAi": {
    "projectId": "proj_123"
  },
  "webhook": {
    "baseUrl": "https://agent.example.com"
  },
  "twilio": {
    "trunkDomain": "acme.pstn.twilio.com"
  }
}
```

2. Read the response and configure Twilio Elastic SIP Trunk to target the generated OpenAI SIP URI:

```text
sip:{projectId}@sip.api.openai.com;transport=tls
```

Example onboarding response excerpt:

```json
{
  "conversationId": "telephony:onboarding:acme:support",
  "status": "accepted",
  "sip": {
    "provider": "twilio",
    "uri": "sip:proj_123@sip.api.openai.com;transport=tls",
    "trunkDomain": "acme.pstn.twilio.com",
    "webhookUrl": "https://agent.example.com/openai/realtime/sip/webhook"
  },
  "runtime": {
    "properties": {
      "openai.project.id": "proj_123"
    }
  }
}
```

3. Configure the OpenAI Realtime SIP webhook to point at:

```text
https://agent.example.com/openai/realtime/sip/webhook
```

4. When a call arrives, OpenAI sends the webhook to the sample.
5. The sample verifies the webhook, accepts the call through OpenAI call control, correlates the conversation, and records SIP lifecycle audit events.
6. Use `GET /audit/conversation/sip?conversationId=<conversationId>` to inspect provider ids, direction, call status, and onboarding correlation.

Example SIP audit response excerpt:

```json
{
  "conversationId": "sip:openai:call_01HXYZ",
  "eventCount": 3,
  "sip": {
    "provider": "twilio",
    "direction": "inbound",
    "status": "accepted",
    "callId": "CA1234567890abcdef",
    "openAiCallId": "call_01HXYZ",
    "onboardingConversationId": "telephony:onboarding:acme:support"
  },
  "events": [
    {
      "type": "sip.openai.incoming",
      "timestamp": "2026-04-08T10:00:00Z"
    },
    {
      "type": "sip.openai.accepted",
      "timestamp": "2026-04-08T10:00:01Z"
    },
    {
      "type": "sip.openai.monitor",
      "timestamp": "2026-04-08T10:00:02Z"
    }
  ]
}
```

The onboarding record is also persisted under a deterministic conversation id:

```text
telephony:onboarding:{tenantId}:{agentId}
```

That lets a tenant-specific agent project re-run lookup and audit queries without introducing a second config store.

## Adapter-Backed Telephony Option

Use this when you want to keep media handling outside OpenAI Realtime SIP or when you need a provider-specific adapter.

The sample ships two Twilio-related scaffolds:

1. a thin HTTP adapter skeleton under `/twilio/adapter/v1/call/{callSid}/{start|turn|end}`
2. Camel Twilio producer routes for `direct:twilio-outbound-call` and `direct:twilio-send-message`

The first scaffold is for media and call-lifecycle bridging into the existing realtime flow. The second is for Twilio REST operations only.

### Recommended Adapter Topology

1. A Twilio phone number or Twilio Elastic SIP Trunk receives the inbound call.
2. A Twilio adapter service handles signaling and media.
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

## Twilio Deployment Options

### Option 1: Twilio Elastic SIP Trunk To OpenAI SIP

Use this when OpenAI Realtime SIP should own the SIP leg.

- Twilio Elastic SIP Trunk points to the OpenAI SIP URI returned by onboarding
- OpenAI sends webhook events to this sample
- this sample handles verification, call acceptance, conversation correlation, and SIP audit projection

This is the preferred topology for the OpenAI realtime voice path in this repo.

### Option 2: Twilio SIP Trunk Or Media Streams To Your Adapter

Use this when you need a custom SIP/media layer.

- Twilio points to your adapter or SBC
- your adapter handles SIP signaling, RTP, STT, and TTS
- your adapter translates into this sample's `/sip/adapter/v1/...` contract

This stays closest to the generic SIP adapter design.

## What You Need To Configure In Twilio

At minimum:

1. Provision an inbound Twilio number or trunk.
2. Decide whether you are using OpenAI-managed SIP or an adapter-managed path.
3. For OpenAI-managed SIP, point the Twilio trunk to the generated OpenAI SIP URI.
4. For adapter-managed flows, point Twilio to your adapter service, not directly to the sample.
5. Make the webhook or adapter edge publicly reachable over HTTPS or WSS.
6. Preserve `CallSid` or SIP `Call-ID` across all callbacks so conversation continuity works.

## Security And Production Requirements

Before exposing this publicly, add:

1. request authentication and signature verification on the webhook or adapter edge
2. SIP trunk IP allow-listing where applicable
3. HTTPS termination and certificate management
4. rate limiting and call concurrency limits
5. PII policy for caller number, transcript text, and call metadata

## Local Development Path

For local validation:

1. run the sample
2. call `POST /telephony/onboarding/openai-twilio` and verify the generated OpenAI SIP URI and webhook URL
3. verify the adapter contract with `bash scripts/sip-adapter-v1-smoke.sh` if you are building the adapter-backed path
4. expose the webhook or adapter edge with a tunnel such as `ngrok` when Twilio or OpenAI must reach it during development
5. use `GET /audit/conversation/sip` to confirm the expected call ids and lifecycle events are being recorded

## Current Gaps You Must Fill

The sample still leaves these responsibilities outside the Java runtime:

- SIP signaling termination when you choose the adapter-managed path
- RTP and media handling
- speech-to-text and text-to-speech outside the OpenAI-managed path
- barge-in handling
- DTMF and IVR behavior
- call transfer and conferencing semantics

## Recommended First Increment

If the immediate goal is to support Twilio phone calls through OpenAI Realtime SIP, the fastest path is:

1. keep this sample as the backend agent runtime
2. use `POST /telephony/onboarding/openai-twilio` to generate the OpenAI SIP URI and webhook target
3. point Twilio Elastic SIP Trunk at that SIP URI
4. expose `/openai/realtime/sip/webhook` publicly with authentication and HTTPS
5. inspect `GET /audit/conversation/sip` during the first call to confirm provider correlation and lifecycle state