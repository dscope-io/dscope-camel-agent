# Agent Support Service Sample

Runnable Camel Main sample for the `agent:` runtime with:

- Spring AI gateway mode (`agent.runtime.ai.mode=spring-ai`)
- multi-agent plan catalog (`support`, `billing`, `ticketing`)
- blueprint static resources for chat and realtime context (`support:v2`)
- local routes for `kb.search` and ticket lifecycle state updates
- outbound support calling through provider-neutral SIP contracts and Twilio management routes
- SIP-style ingress routes that map call lifecycle and transcript turns into realtime agent flow
- inbound and outbound A2A support for ticket management
- audit trail persistence via JDBC-backed DScope persistence
- AGUI component infrastructure from `io.dscope.camel:camel-ag-ui-component`
- simple Copilot-style web UI (`/agui/ui`) that calls `POST /agui/agent` (AGUI POST+SSE stream response)

## Prerequisites

- Java 21
- Maven
- OpenAI key for live Spring AI runs

For local simulation without an API key, use the sample demo gateway:

- `io.dscope.camel.agent.samples.DemoA2ATicketGateway`

## Configure Secrets

Use either environment variable:

```bash
export OPENAI_API_KEY=...
```

or local file (`.agent-secrets.properties`):

```properties
openai.api.key=...
```

## Run

From repo root:

```bash
mvn -f samples/agent-support-service/pom.xml \
  -DskipTests \
  -Daudit.api.enabled=false \
  clean compile exec:java
```
If port `8080` is already in use, launcher auto-selects the next free port (scan window: `8080..8100` by default) and prints it.

Optional environment overrides:

- `AGENT_PORT` (default `8080`)
- `AGENT_PORT_SCAN_MAX` (default `20`)

Or use the helper script (auto-loads `.agent-secrets.properties`):

```bash
samples/agent-support-service/run-sample.sh
```

For local A2A demo mode without an API key:

```bash
./mvnw -q -f samples/agent-support-service/pom.xml \
  -Dagent.runtime.spring-ai.gateway-class=io.dscope.camel.agent.samples.DemoA2ATicketGateway \
  -Dagent.runtime.routes-include-pattern=classpath:routes/kb-search.camel.yaml,classpath:routes/kb-search-json.camel.xml,classpath:routes/ticket-service.camel.yaml,classpath:routes/ag-ui-platform.camel.yaml,classpath:routes/admin-platform.camel.yaml \
  exec:java
```

Then open:

- `http://localhost:8080/agui/ui` for the frontend
- `http://localhost:8080/audit/ui` for audit exploration
- Use the single voice toggle button in the UI to start/stop mic streaming to realtime.

## Manual Verification Checklist (Web + Realtime)

1. Start the sample:

```bash
samples/agent-support-service/run-sample.sh
```

2. Confirm service is listening:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN | cat
```

3. Open UI in browser:

- `http://localhost:8080/agui/ui`

4. Web chat check:

- Send: `My login is failing, please open a support ticket`.
- Expect the support assistant to call the A2A ticketing service and render ticket JSON/widget behavior.

5. Realtime voice check (browser):

- Keep `Pause` profile on `Normal` (or choose `Fast`/`Patient` based on preference).
- Click `Start Voice`, allow microphone access.
- Speak a short ticket request (for example: `Please open and escalate a ticket for repeated login failures`).
- Click `Stop Voice`.
- Expect transcription routed to agent flow and assistant response (plus audio playback when realtime audio deltas are produced).
- In transcript log, expect one `voice output transcript` assistant message per response (no duplicate compressed variant).

6. Optional API-level realtime sanity check:

```bash
curl -s -X POST http://localhost:8080/realtime/session/voice-check-1/event \
   -H 'Content-Type: application/json' \
   -d '{"type":"session.state"}'
```

7. Expected fallback behavior without valid OpenAI credentials:

- AGUI pre-run falls back to deterministic local routing.
- With `DemoA2ATicketGateway`, support prompts still route through the multi-agent A2A ticket flow.

Or call the same AGUI endpoint used by the frontend directly (POST+SSE stream response):

```bash
curl -N -X POST http://localhost:8080/agui/agent \
   -H 'Content-Type: application/json' \
   -d '{
      "threadId":"demo-session",
      "sessionId":"demo-session",
      "messages":[{"role":"user","content":"My login is failing, please open a support ticket"}]
   }'
```

When assistant text contains ticket JSON, the frontend renders a `ticket-card` widget template.

## AGUI Frontend Flow

Current UI transport model in this sample:

1. Browser text chat can use either transport via UI selector:
   - `POST /agui/agent` (POST+SSE bridge)
   - WebSocket `/agui/rpc` (AGUI event stream over WS)
2. Backend returns AGUI events for the selected transport.
3. Frontend parses AGUI events (especially `TEXT_MESSAGE_CONTENT`) and renders assistant text.
4. If assistant text contains ticket JSON, frontend renders a `ticket-card` widget.

In the current sample, `support.ticket.manage` is an outbound `a2a:` tool:

- support/billing agents call `a2a:support-ticket-service`
- exposed A2A agent `support-ticket-service` maps to local plan `ticketing:v1`
- ticketing agent calls local route tool `support.ticket.manage.route`
- ticket lifecycle route updates ticket state and returns JSON

Voice/transcript UX in `/agui/ui`:

- Single dynamic voice toggle button with idle/live/busy visual states.
- Pause selector controls realtime VAD silence timeout (`Fast=800ms`, `Normal=1200ms`, `Patient=1800ms`).
- Current pause milliseconds are shown in the pause label and voice listening status text.
- WebRTC transcript log panel records input and output transcript events with a clear-log button.
- Voice output transcript rendering is de-duplicated so only one final assistant transcript line is shown.
- Collapsible `Instruction seed (debug)` panel displays the current WebRTC instruction seed derived from realtime session metadata.
- Instruction debug panel auto-opens when transport switches to `WebRTC` and also on initial page load when current transport is already `WebRTC`.

Notes:

- `/agui/agent` is the primary request path for the sample UI.
- `/agui/rpc` is available for AGUI WebSocket transport and is functionally aligned with `/agui/agent`.
- `/agui/stream/{runId}` remains available for split-transport clients.
- AGUI frontend behavior in this sample is configured by runtime routes/processors (`application.yaml` + `routes/ag-ui-platform.camel.yaml`), not by a UI section in `agents/support/agent.md`.

## Connectivity Precheck

```bash
nc -vz api.openai.com 443
curl -I https://api.openai.com/v1/models
curl https://api.openai.com/v1/models -H "Authorization: Bearer $OPENAI_API_KEY"
```

## Routes Used by Tools

- `kb.search` -> `direct:kb-search` (`routes/kb-search.camel.yaml`)
- `support.ticket.manage.route` -> `direct:support-ticket-manage` (`routes/ticket-service.camel.yaml`)
- `support.ticket.manage` -> `a2a:support-ticket-service?remoteUrl={{agent.runtime.a2a.public-base-url}}/a2a`
- `support.call.outbound` -> `direct:support-call-outbound-route` (`routes/openai-sip-support.camel.yaml`)
- `support.mcp` -> `mcp:http://localhost:3001/mcp` (optional MCP seed; runtime discovers concrete MCP tools via `tools/list`)

## Blueprint Resources In The Sample

`support:v2` demonstrates blueprint-declared static resources:

- `login-handbook` is included in both chat and realtime context
- `outbound-call-playbook` is included in realtime context for voice and outbound follow-up flows

Sample budgets:

- `agent.runtime.chat.resource-context-max-chars=64000`
- `agent.runtime.realtime.resource-context-max-chars=12000`

These are applied automatically when the resolved plan uses `agents/support/v2/agent.md`.

## Plan-Level AI Overrides In agents.yaml

The sample `agents/agents.yaml` supports an optional `ai` block at both the plan and version levels.

- plan-level `ai` defines defaults for every version in that plan
- version-level `ai` overrides or extends the plan defaults for the selected version
- nested `properties` maps are flattened into runtime property keys and override `application.yaml` only for conversations using that resolved plan/version

Example:

```yaml
plans:
   - name: support
      default: true
      ai:
         provider: openai
         properties:
            agent:
               runtime:
                  spring-ai:
                     openai:
                        api-mode: responses-http
      versions:
         - version: v2
            blueprint: classpath:agents/support/v2/agent.md
            ai:
               model: gpt-5.4-mini
               temperature: 0.3
               max-tokens: 700
               properties:
                  agent:
                     runtime:
                        spring-ai:
                           openai:
                              prompt-cache:
                                 enabled: true
```

Supported fields inside `ai`:

- `provider`
- `model`
- `temperature`
- `max-tokens`
- `properties`

The resolved AI configuration is persisted with plan selection and exposed by:

- session responses under `resolvedAi`
- audit conversation view/list/search responses under `ai`
- audit metadata under `conversationMetadata.ai`

## Tokenized Execution Targets In The Sample Blueprint

`agents/support/v2/agent.md` now demonstrates runtime token substitution for execution-facing fields:

- `support.mcp.endpointUri` uses `{{agent.runtime.support.crm-mcp-endpoint-uri}}`
- `aguiPreRun.agentEndpointUri` uses `{{agent.runtime.support.agui-agent-endpoint-uri}}`

The backing runtime properties are defined in `application.yaml`:

- `agent.runtime.support.crm-mcp-endpoint-uri`
- `agent.runtime.support.agui-agent-endpoint-uri`

The sample CRM MCP property also demonstrates mixed property and env usage:

```yaml
agent:
   runtime:
      support:
         crm-mcp-endpoint-uri: mcp:https://${AGENT_SUPPORT_CRM_MCP_HOST:camel-crm-service-702748800338.europe-west1.run.app}/mcp
```

If one of these execution targets remains unresolved at runtime, the agent now fails fast with an error that identifies the failing blueprint field and, for tools, the tool name.

## MCP Quick Start (Local)

1. Start an MCP server that exposes `tools/list` and `tools/call` on `http://localhost:3001/mcp`.
2. Keep `support.mcp` in `agents/support/agent.md` enabled.
3. Run the sample and send a request (UI or curl):

```bash
curl -N -X POST http://localhost:8080/agui/agent \
   -H 'Content-Type: application/json' \
   -d '{
      "threadId":"mcp-demo-1",
      "sessionId":"mcp-demo-1",
      "messages":[{"role":"user","content":"Use MCP tools to help me diagnose login failures."}]
   }'
```

Expected debug-audit behavior:

- an `mcp.tools.discovered` event is persisted once per conversation
- event payload includes `services[]` entries with:
   - `serviceTool` (for example `support.mcp`)
   - `endpointUri` (for example `mcp:http://localhost:3001/mcp`)
   - `result.tools[]` from MCP `tools/list` (or `error` when discovery fails)

Example event payload shape:

```json
{
   "type": "mcp.tools.discovered",
   "payload": {
      "services": [
         {
            "serviceTool": "support.mcp",
            "endpointUri": "mcp:http://localhost:3001/mcp",
            "result": {
               "tools": [
                  {
                     "name": "kb.lookup",
                     "description": "Lookup support knowledge base",
                     "inputSchema": {
                        "type": "object",
                        "properties": {
                           "query": {
                              "type": "string"
                           }
                        }
                     }
                  }
               ]
            }
         }
      ]
   }
}
```

## AGUI Endpoints

- `POST /agui/agent` -> primary frontend path (single POST request, SSE event stream response)
- `WS /agui/rpc` -> AGUI WebSocket RPC path (event stream over WebSocket)
- `GET /agui/stream/{runId}` -> optional event stream endpoint for split transport mode
- `GET /agui/ui` -> simple Copilot-like frontend with ticket widget rendering

## A2A Endpoints

- `POST /a2a/rpc`
- `GET /a2a/sse/{taskId}`
- `GET /.well-known/agent-card.json`

### Direct A2A Smoke

Open ticket:

```bash
curl -sS -X POST http://localhost:8080/a2a/rpc \
   -H 'Content-Type: application/json' \
   -d '{
      "jsonrpc":"2.0",
      "id":"1",
      "method":"SendMessage",
      "params":{
         "conversationId":"demo-a2a-1",
         "message":{
            "messageId":"m1",
            "role":"user",
            "parts":[{"partId":"p1","type":"text","mimeType":"text/plain","text":"Please open a support ticket for my login issue"}]
         },
         "metadata":{"agentId":"support-ticket-service"}
      }
   }'
```

Close the same ticket by reusing returned `linkedConversationId`:

```bash
curl -sS -X POST http://localhost:8080/a2a/rpc \
   -H 'Content-Type: application/json' \
   -d '{
      "jsonrpc":"2.0",
      "id":"2",
      "method":"SendMessage",
      "params":{
         "conversationId":"demo-a2a-1",
         "message":{
            "messageId":"m2",
            "role":"user",
            "parts":[{"partId":"p2","type":"text","mimeType":"text/plain","text":"Please close the ticket now"}]
         },
         "metadata":{
            "agentId":"support-ticket-service",
            "linkedConversationId":"<linkedConversationId>"
         }
      }
   }'
```

### AGUI Pre-Run Fallback Quick Check

This sample blueprint now includes `aguiPreRun` metadata. You can verify deterministic fallback routing (without valid OpenAI credentials) by calling `POST /agui/agent` with a ticket-oriented prompt:

```bash
curl -N -X POST http://localhost:8080/agui/agent \
   -H 'Content-Type: application/json' \
   -d '{
      "threadId":"agui-prerun-demo-1",
      "sessionId":"agui-prerun-demo-1",
      "messages":[{"role":"user","content":"Please escalate and open a support ticket for repeated login failures"}]
   }'
```

Expected result:

- AGUI pre-run processor invokes the normal agent endpoint first.
- If response indicates key/auth failure (or is empty), fallback selects ticket route based on blueprint `aguiPreRun.fallback.ticketKeywords` and tool metadata (`support.ticket.manage` -> `direct:support-ticket-manage`).
- SSE output contains assistant text with ticket payload that the sample UI renders as a `ticket-card` widget.

## Realtime Relay Endpoint (Foundation)

For voice-agent foundation wiring, sample exposes:

- `POST /realtime/session/{conversationId}/event`

Current behavior:

- accepts JSON event payloads with at least `type`
- when `type = transcript.final` and text is provided (`text` or `payload.text`), forwards transcript into the same `agent:` blueprint/tool flow
- returns JSON acknowledgment with `assistantMessage` when routed
- supports OpenAI Realtime WebSocket relay session lifecycle:
   - `session.start` (connects to OpenAI Realtime endpoint and optional `session.update`)
   - relay events such as `input_audio_buffer.append`, `input_audio_buffer.commit`, `response.create`
   - `session.state` (returns connection + buffered relay events)
   - `session.close` / `session.stop` (closes relay session)
- route-to-session context update:
   - when `transcript.final` is routed to Camel agent/tool routes, route output can include a session-context patch
   - accepted patch sources (first match wins):
      - exchange header: `realtimeSessionUpdate` (or aliases `realtime.session.update`, `sessionUpdate`)
      - exchange property: `realtimeSessionUpdate` (or aliases `realtime.session.update`, `sessionUpdate`)
      - assistant JSON body fields: `realtimeSessionUpdate`, `realtimeSession`, `sessionUpdate`
   - patch is deep-merged into the in-memory realtime browser session context for the same `conversationId`
   - token endpoint (`/realtime/session/{conversationId}/token`) then uses the merged context on next mint
   - response now includes `sessionContextUpdated: true|false`
- pre-conversation session seed (`/realtime/session/{conversationId}/init`):
   - init seeds blueprint-derived profile context before first user turn under `session.metadata.camelAgent`
   - seeded profile fields: `agentProfile.name`, `agentProfile.version`, `agentProfile.purpose`, `agentProfile.tools[]`
   - seeded context fields: `context.agentPurpose`, `context.agentFocusHint`
   - WebRTC instruction builder consumes these fields to keep first-turn responses focused on agent purpose and tool scope
- Browser UI behavior:
   - voice toggle start sends `session.start`, captures mic, streams `input_audio_buffer.append` chunks
   - voice toggle stop sends `input_audio_buffer.commit`, `response.create`, and `session.close`
   - finalized transcription events are forwarded as `transcript.final` into the same blueprint/tool flow
   - realtime assistant audio deltas (for example `response.audio.delta`) are decoded as PCM16 and played in-browser
   - output transcript finalization prefers completed transcript payloads and avoids duplicate transcript message emission

Example route patch (YAML):

```yaml
- route:
    id: support-ticket-manage-route
    from:
      uri: direct:support-ticket-manage
      steps:
        - setHeader:
            name: realtimeSessionUpdate
            constant: '{"metadata":{"lastTool":"support.ticket.manage.route"},"audio":{"output":{"voice":"alloy"}}}'
        - setBody:
            simple: '{"ticketId":"TCK-${exchangeId}","status":"OPEN","summary":"${body[query]}"}'
```

Example start call:

```bash
curl -s -X POST http://localhost:8080/realtime/session/voice-1/event \
   -H 'Content-Type: application/json' \
   -d '{
      "type":"session.start",
      "session":{
         "voice":"alloy",
         "input_audio_format":"pcm16",
         "output_audio_format":"pcm16"
      }
   }'
```

Example transcript-to-agent call:

```bash
curl -s -X POST http://localhost:8080/realtime/session/voice-1/event \
   -H 'Content-Type: application/json' \
   -d '{"type":"transcript.final","text":"Please open a support ticket for repeated login failures"}'
```

### SIP Adapter Stubs (Ingress)

Sample also exposes minimal SIP-oriented ingress routes that reuse the same realtime processors:

- `POST /sip/adapter/v1/session/{conversationId}/start` -> normalizes SIP call payload into realtime `/init` session envelope
- `POST /sip/adapter/v1/session/{conversationId}/turn` -> normalizes transcript payload into realtime `transcript.final` event and routes into agent flow
- `POST /sip/adapter/v1/session/{conversationId}/end` -> returns lightweight end-of-call acknowledgment
- sample config now enables `agent.runtime.sip.bind-processors=true`, so these routes are live in the default sample runtime

Example call start:

```bash
curl -s -X POST http://localhost:8080/sip/adapter/v1/session/sip:demo:call-1/start \
   -H 'Content-Type: application/json' \
   -d '{"call":{"id":"call-1","from":"+15551230001","to":"+15557650002"},"session":{"audio":{"output":{"voice":"nova"}}}}'
```

Example final transcript turn:

```bash
curl -s -X POST http://localhost:8080/sip/adapter/v1/session/sip:demo:call-1/turn \
   -H 'Content-Type: application/json' \
   -d '{"text":"I need help with my login"}'
```

One-command smoke (start -> turn -> end):

```bash
bash scripts/sip-adapter-v1-smoke.sh
```

### Outbound Support Call Flow

The sample blueprint also exposes `support.call.outbound`, which places a provider call and returns correlation data immediately.

Flow:

1. tool `support.call.outbound` routes to `direct:support-call-outbound-route`
2. `SupportOutboundCallProcessor` builds `OutboundSipCallRequest`
3. `TwilioSipProviderClient` places the provider call through `direct:twilio-outbound-call`
4. response returns provider, request id, conversation id, destination, and normalized call status

Example agent-triggered invocation:

```bash
curl -s -X POST http://localhost:8080/realtime/session/outbound-demo-1/event \
   -H 'Content-Type: application/json' \
   -d '{"type":"transcript.final","text":"Please call the customer back at +15551230001 about repeated login failures"}'
```

Example request body shape used by the outbound-call tool route:

```json
{
   "destination": "+15551230001",
   "query": "Follow up on repeated login failures",
   "customerName": "Roman Example",
   "metadata": {
      "priority": "high"
   }
}
```

Required runtime properties for live Twilio placement:

- `TWILIO_ACCOUNT_SID`
- `TWILIO_AUTH_TOKEN`
- `TWILIO_FROM_NUMBER`
- optional `OPENAI_PROJECT_ID` for provider/call correlation metadata

### Twilio Phone Integration

The sample exposes an HTTP SIP-adapter contract. It does not terminate raw SIP or RTP by itself.

The sample now also includes a thin Twilio-oriented adapter skeleton that maps Twilio call ids into the same backend contract:

- `POST /twilio/adapter/v1/call/{callSid}/start`
- `POST /twilio/adapter/v1/call/{callSid}/turn`
- `POST /twilio/adapter/v1/call/{callSid}/end`

Those routes derive `conversationId = sip:twilio:{callSid}` and forward into the same realtime processors used by the SIP adapter.

That means Twilio cannot point a phone call directly at this Java process as a SIP endpoint. You need a small adapter layer that does two jobs:

1. terminate Twilio telephony/media
2. translate call lifecycle and transcripts into the sample's `/sip/adapter/v1/session/{conversationId}/{start|turn|end}` contract

Recommended production shape:

- Twilio phone number or Elastic SIP trunk receives the PSTN call
- Twilio forwards audio/signaling to your SIP/media adapter
- the adapter maps `CallSid` or SIP `Call-ID` to `conversationId` such as `sip:twilio:<callSid>`
- on call start, the adapter calls `POST /sip/adapter/v1/session/{conversationId}/start`
- when speech recognition produces a final utterance, the adapter calls `POST /sip/adapter/v1/session/{conversationId}/turn`
- when the call ends, the adapter calls `POST /sip/adapter/v1/session/{conversationId}/end`
- the adapter converts returned `assistantMessage` text into speech and plays it back to the caller

For a full Twilio implementation checklist, see:

- `docs/TWILIO_SIP_SAMPLE_SETUP.md`
- `docs/TWILIO_ADAPTER_FLOW_EXAMPLE.md`

For Twilio REST management scaffolding included in the sample, use these Camel direct routes:

- `direct:twilio-outbound-call`
- `direct:twilio-send-message`

These routes use the Camel Twilio component and expect Twilio credentials via `TWILIO_ACCOUNT_SID` and `TWILIO_AUTH_TOKEN`.

## Test Scenarios

Run sample integration tests:

```bash
mvn -f samples/agent-support-service/pom.xml -Dtest=SpringAiAuditTrailIntegrationTest test
```

Covered scenarios:

1. Two-turn routing + memory (deterministic gateway):
   - prompt 1 asks knowledge base -> tool `kb.search`
   - prompt 2 asks ticket -> tool `support.ticket.manage`
   - prompt 2 evaluation includes prompt 1 KB result
2. Negative case:
   - ticket as first prompt does not inject KB context
3. Live LLM route decision (when API key is available):
   - validates real tool selection and second-turn context carry-over

## Runtime Config

Primary config is in `src/main/resources/application.yaml`.

Notable keys:

- `agent.runtime.ai.mode=spring-ai`
- `agent.runtime.spring-ai.provider=openai`
- `agent.runtime.spring-ai.openai.api-mode=chat`
- set `agent.runtime.spring-ai.openai.api-mode=responses-ws` to route LLM calls through OpenAI Responses over WebSocket
- `agent.runtime.spring-ai.openai.responses-ws.*` configures endpoint/model/timeout/reconnect (`endpoint-uri`, `model`, `request-timeout-ms`, `poll-interval-ms`, `max-send-retries`, `max-reconnects`, `initial-backoff-ms`, `max-backoff-ms`)
- `agent.runtime.spring-ai.model=gpt-5.4`
- `agent.runtime.a2a.enabled=true` exposes `/a2a/rpc`, `/a2a/sse/{taskId}`, and `/.well-known/agent-card.json`
- `agent.runtime.ai.mode=realtime` is now recognized for realtime relay foundation bootstrap (you can override `aiModelClient` bean for custom realtime adapter)
- shared bootstrap now binds AGUI defaults, AGUI pre-run processor, realtime relay client, and realtime event processor (if missing)
- `agent.runtime.realtime.processor-bean-name=supportRealtimeEventProcessorCore`
- `agent.runtime.realtime.agent-endpoint-uri=agent:support?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}`
- `agent.runtime.sip.bind-processors=true` binds `supportSipSessionInitEnvelopeProcessor`, `supportSipTranscriptFinalProcessor`, and `supportSipCallEndProcessor`
- `agent.runtime.realtime.agent-profile-purpose-max-chars=0` in this sample preserves full seeded purpose text from blueprint `## System` (default core behavior is bounded; `0` disables truncation)
- `agent.runtime.realtime.resource-context-max-chars=12000` bounds blueprint resource text injected into browser realtime session instructions
- `agent.runtime.chat.resource-context-max-chars=64000` bounds blueprint resource text injected into chat/kernel instructions
- relay reconnect policy is configurable via `agent.runtime.realtime.reconnect.*`:
   - `max-send-retries` (default `3`)
   - `max-reconnects` (default `8`)
   - `initial-backoff-ms` (default `150`)
   - `max-backoff-ms` (default `2000`)
- AGUI pre-run processor is now shared from core (`AgentAgUiPreRunTextProcessor`) and can be configured via `agent.runtime.agui.pre-run.*` (or alias `agent.agui.pre-run.*`):
   - `agent-endpoint-uri`
   - `fallback-enabled`
   - `fallback.kb-tool-name`, `fallback.ticket-tool-name`
   - `fallback.kb-uri`, `fallback.ticket-uri` (optional explicit overrides)
   - `fallback.ticket-keywords`
   - `fallback.error-markers`
   - sample blueprint also demonstrates this via `aguiPreRun` in `agents/support/agent.md`

Blueprint support added (foundation phase):

- `agents/support/agent.md` supports a top-level `realtime` section.
- `agents/support/v2/agent.md` supports a top-level `resources` section for static chat/realtime context.
- If `realtime` is absent (or fields are missing) in blueprint, runtime falls back to `application.yaml` properties under:
   - `agent.runtime.realtime.*` (primary)
   - `agent.realtime.*` (alias)
- Sample currently demonstrates fallback by defining realtime defaults in `application.yaml`.
- Reconnect policy can be configured from either source:
   - blueprint `realtime.reconnect.{maxSendRetries,maxReconnects,initialBackoffMs,maxBackoffMs}`
   - or `application.yaml` as `agent.runtime.realtime.reconnect.*` / `agent.realtime.reconnect.*`
- AGUI pre-run fallback routes can be derived from blueprint tool metadata when explicit URIs are not set:
   - uses `tools[].endpointUri` first
   - else derives `direct:<tools[].routeId>`
- AGUI pre-run config precedence is: blueprint `aguiPreRun` -> `application.yaml` (`agent.runtime.agui.pre-run.*`) -> alias (`agent.agui.pre-run.*`) -> defaults.
- route-driven session invocation is available from core via `AgentSessionService` and `AgentSessionInvokeProcessor` when you need structured request/response handling instead of raw `agent:` text bodies.

### Responses-WS Quick Switch

1. In `src/main/resources/application.yaml` set:
   - `agent.runtime.spring-ai.openai.api-mode: responses-ws`
2. Keep or tune:
   - `agent.runtime.spring-ai.openai.responses-ws.endpoint-uri`
   - `agent.runtime.spring-ai.openai.responses-ws.model`
3. Start the sample and send a regular AGUI request (`POST /agui/agent` or `WS /agui/rpc`).

Optional smoke helper:

```bash
bash scripts/openai-responses-ws-smoke.sh
```

No-edit toggles are also available:

- Maven profile:
   - `mvn -f samples/agent-support-service/pom.xml -Presponses-ws -DskipTests clean compile exec:java`
   - `mvn -f samples/agent-support-service/pom.xml -Pchat -DskipTests clean compile exec:java`
- Run script:
   - `samples/agent-support-service/run-sample.sh --responses-ws`
   - `samples/agent-support-service/run-sample.sh --chat`
   - `samples/agent-support-service/run-sample.sh --api-mode=responses-ws`
- Env var:
   - `AGENT_OPENAI_API_MODE=responses-ws samples/agent-support-service/run-sample.sh`

### Optional: Separate JDBC for Audit Trail

By default, audit trail uses the same persistence store as context/state.

To split audit trail to a different JDBC database, set one of these forms:

```yaml
agent:
   audit:
      granularity: debug
      backend: jdbc
      jdbc:
         url: jdbc:postgresql://localhost:5432/agent_audit
         username: agent_audit_user
         password: ${AGENT_AUDIT_DB_PASSWORD}
         driver-class-name: org.postgresql.Driver
```

Or equivalent namespaced form:

```yaml
agent:
   audit:
      persistence:
         backend: jdbc
         jdbc:
            url: jdbc:postgresql://localhost:5432/agent_audit
```

## Troubleshooting

- `401` from OpenAI API calls
   - token is missing/invalid for the active shell.
   - verify:
      - `echo "$OPENAI_API_KEY" | wc -c`
      - `curl https://api.openai.com/v1/models -H "Authorization: Bearer $OPENAI_API_KEY"`

- `421` with OpenAI welcome body
   - request is likely hitting the API root path instead of chat endpoint.
   - ensure OpenAI config stays on `api-mode=chat` and do not override base/path incorrectly.

- Sample still behaves like old code after fixes
   - local Maven snapshots may be stale.
   - rebuild/install from repo root:
   - `mvn -f pom.xml -pl camel-agent-core,camel-agent-spring-ai,camel-agent-persistence-dscope,samples/agent-support-service -am -DskipTests clean install`

### Troubleshooting Grep Cookbook

Use these against your live file (for example `/tmp/agent-support-live.log`) to isolate each stage quickly.

- Realtime relay lifecycle (`OpenAiRealtimeRelayClient`):
   ```bash
   grep -nE "Realtime relay connected|Realtime relay event sent|Realtime relay send failed|Realtime relay reconnecting|Realtime relay reconnected|Realtime relay closed|Realtime websocket (opened|closed|error)" /tmp/agent-support-live.log
   ```

- Realtime ingress + routing (`RealtimeEventProcessor`):
   ```bash
   grep -nE "Realtime event received|Realtime session\.start connected|Realtime relay event forwarded|Realtime transcript received|Realtime transcript routed|Realtime event not routed to agent|Realtime config resolved" /tmp/agent-support-live.log
   ```

- Kernel model/tool execution (`DefaultAgentKernel`):
   ```bash
   grep -nE "Kernel handleUserMessage started|Kernel model response received|Kernel tool execution (started|completed)|Kernel tool fallback captured|Kernel assistant fallback used from tool result|Kernel final assistant message ready|Kernel handleUserMessage completed" /tmp/agent-support-live.log
   ```

- AGUI pre-run decisions (`AgentAgUiPreRunTextProcessor`):
   ```bash
   grep -nE "AGUI pre-run started|AGUI pre-run primary agent response|AGUI pre-run fallback triggered|AGUI pre-run primary agent failed|AGUI pre-run deterministic fallback route|AGUI pre-run completed" /tmp/agent-support-live.log
   ```

- Runtime bootstrap bindings (`AgentRuntimeBootstrap`):
   ```bash
   grep -nE "Agent runtime bootstrap started|Agent runtime persistence selected|Agent runtime AI model client bound|Agent runtime AGUI|Agent runtime realtime|Agent runtime bootstrap completed" /tmp/agent-support-live.log
   ```

- End-to-end voice transcript -> ticket triage view:
   ```bash
   grep -nE "Realtime transcript received|Realtime transcript routed|Kernel realtime transcript routing|Kernel tool execution started:.*support\.ticket\.open|Kernel tool execution completed:.*support\.ticket\.open|Kernel final assistant message ready" /tmp/agent-support-live.log
   ```

- Follow only new troubleshooting lines live (low-noise tail):
   ```bash
   tail -f /tmp/agent-support-live.log | grep -E "Realtime relay|Realtime transcript|Kernel |AGUI pre-run|Agent runtime bootstrap"
   ```

- Tests unexpectedly skipped
   - check if `maven.test.skip=true` is set in env/system properties.
   - run explicit class test:
      - `mvn -f samples/agent-support-service/pom.xml -Dtest=SpringAiAuditTrailIntegrationTest test`
