# Twilio Adapter Flow Example

This example shows the thinnest useful adapter that sits between Twilio voice traffic and the sample's SIP-style HTTP adapter contract.

## Goal

Map one inbound Twilio call into this backend flow:

1. call start -> `/sip/adapter/v1/session/{conversationId}/start`
2. final transcript turn -> `/sip/adapter/v1/session/{conversationId}/turn`
3. hangup -> `/sip/adapter/v1/session/{conversationId}/end`

If you want to keep the first bridge inside this sample while you are prototyping, the sample now exposes a Twilio-shaped ingress skeleton:

1. call start -> `/twilio/adapter/v1/call/{callSid}/start`
2. final transcript turn -> `/twilio/adapter/v1/call/{callSid}/turn`
3. hangup -> `/twilio/adapter/v1/call/{callSid}/end`

Those sample routes normalize to `conversationId = sip:twilio:{callSid}` and then forward into the existing SIP-style realtime processors.

## Conversation Id Rule

Use Twilio `CallSid` as the stable call identity.

Example:

```text
conversationId = sip:twilio:CA1234567890abcdef
```

That keeps all turns for the same phone call in one backend conversation.

## Minimal Adapter Sequence

### 1. Twilio Notifies Call Start

Your adapter receives a new call event from Twilio.

It should immediately initialize the backend session.

```bash
curl -sS -X POST \
  http://localhost:8080/sip/adapter/v1/session/sip:twilio:CA1234567890abcdef/start \
  -H 'Content-Type: application/json' \
  -d '{
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
          "callSid": "CA1234567890abcdef",
          "streamSid": "MZ1234567890abcdef"
        }
      }
    }
  }'
```

### 2. Adapter Produces Final Transcript

Once your adapter's STT pipeline produces a final utterance, send it to the sample.

```bash
curl -sS -X POST \
  http://localhost:8080/sip/adapter/v1/session/sip:twilio:CA1234567890abcdef/turn \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "I need help logging into my account"
  }'
```

Example response shape:

```json
{
  "assistantMessage": "I found login troubleshooting steps. Would you like me to open a support ticket?",
  "sessionContextUpdated": true,
  "realtimeVoiceBranchStarted": false
}
```

The adapter should:

1. read `assistantMessage`
2. synthesize it to speech
3. play that audio back to the caller

### 3. Second Turn With Escalation Request

```bash
curl -sS -X POST \
  http://localhost:8080/sip/adapter/v1/session/sip:twilio:CA1234567890abcdef/turn \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "Yes, please create a support ticket"
  }'
```

Because the `conversationId` is stable, the sample can carry forward the first-turn KB context into the ticket workflow.

### 4. Call End

```bash
curl -sS -X POST \
  http://localhost:8080/sip/adapter/v1/session/sip:twilio:CA1234567890abcdef/end \
  -H 'Content-Type: application/json' \
  -d '{}'
```

Example response:

```json
{
  "conversationId": "sip:twilio:CA1234567890abcdef",
  "ended": true
}
```

## Pseudocode Adapter

```text
onCallStarted(callSid, fromNumber, toNumber):
  conversationId = "sip:twilio:" + callSid
  POST /sip/adapter/v1/session/{conversationId}/start

onFinalTranscript(callSid, transcript):
  conversationId = "sip:twilio:" + callSid
  response = POST /sip/adapter/v1/session/{conversationId}/turn { text: transcript }
  speak(response.assistantMessage)

onCallEnded(callSid):
  conversationId = "sip:twilio:" + callSid
  POST /sip/adapter/v1/session/{conversationId}/end
```

## Twilio-Side Notes

You can implement the adapter using either:

1. Twilio Media Streams
2. Twilio Elastic SIP Trunk with your own SIP/media edge

The adapter is where STT/TTS, audio playback, barge-in, and any Twilio-specific authentication live.

## Recommended MVP

For the first phone-call proof of concept:

1. use Twilio Media Streams
2. run a tiny adapter service that converts final transcripts into `/turn` calls
3. use TTS on the adapter side for `assistantMessage`
4. keep the Java sample focused on conversation state, routing, tool calls, and memory