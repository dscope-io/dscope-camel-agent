# Agent Support Service Sample

Runnable Camel Main sample for the `agent:` runtime with:

- Spring AI gateway mode (`agent.runtime.ai.mode=spring-ai`)
- local routes for `kb.search` and `support.ticket.open`
- audit trail persistence via JDBC-backed DScope persistence
- AGUI component infrastructure from `io.dscope.camel:camel-ag-ui-component`
- simple Copilot-style web UI (`/agui/ui`) that calls `POST /agui/agent` (AGUI POST+SSE stream response)

## Prerequisites

- Java 21
- Maven
- OpenAI key (for live Spring AI runs)

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

Then open:

- `http://localhost:8080/agui/ui` for the frontend
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
- Expect assistant response and ticket JSON/widget behavior.

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
- Ticket-like prompts route to `support.ticket.open`; non-ticket prompts route to `kb.search`.

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

If OpenAI credentials are missing, AGUI requests automatically fall back to deterministic local routing via the AGUI pre-run processor:

- ticket-like prompts -> `direct:support-ticket-open`
- other prompts -> `direct:kb-search`

This keeps `/agui/ui` demo flows working offline.

## AGUI Frontend Flow

Current UI transport model in this sample:

1. Browser text chat can use either transport via UI selector:
   - `POST /agui/agent` (POST+SSE bridge)
   - WebSocket `/agui/rpc` (AGUI event stream over WS)
2. Backend returns AGUI events for the selected transport.
3. Frontend parses AGUI events (especially `TEXT_MESSAGE_CONTENT`) and renders assistant text.
4. If assistant text contains ticket JSON, frontend renders a `ticket-card` widget.

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
- `support.ticket.open` -> `direct:support-ticket-open` (`routes/kb-search-json.camel.xml`)
- `support.mcp` -> `mcp:http://localhost:3001/mcp` (optional MCP seed; runtime discovers concrete MCP tools via `tools/list`)

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
- If response indicates key/auth failure (or is empty), fallback selects ticket route based on blueprint `aguiPreRun.fallback.ticketKeywords` and tool metadata (`support.ticket.open` -> `direct:support-ticket-open`).
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
    id: support-ticket-open
    from:
      uri: direct:support-ticket-open
      steps:
        - setHeader:
            name: realtimeSessionUpdate
            constant: '{"metadata":{"lastTool":"support.ticket.open"},"audio":{"output":{"voice":"alloy"}}}'
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

## Test Scenarios

Run sample integration tests:

```bash
mvn -f samples/agent-support-service/pom.xml -Dtest=SpringAiAuditTrailIntegrationTest test
```

Covered scenarios:

1. Two-turn routing + memory (deterministic gateway):
   - prompt 1 asks knowledge base -> tool `kb.search`
   - prompt 2 asks ticket -> tool `support.ticket.open`
   - prompt 2 evaluation includes prompt 1 KB result
2. Negative case:
   - ticket as first prompt does not inject KB context
3. Live LLM route decision (when API key is available):
   - validates real tool selection and second-turn context carry-over

4. Copilot page UI ↔ audit parity (Playwright + JUnit):
   - opens `/agui/ui` in headless Chromium
   - submits a support-ticket prompt via the page form
   - uses a deterministic mocked `springAiChatGateway` in test bootstrap (no external OpenAI dependency)
   - asserts UI assistant output is rendered
   - verifies the same input/output appears in `/audit/search` for the same conversation

Run Playwright UI/audit test only:

```bash
mvn -f samples/agent-support-service/pom.xml -Dtest=AgUiPlaywrightAuditTrailIntegrationTest test
```

## Runtime Config

Primary config is in `src/main/resources/application.yaml`.

Notable keys:

- `agent.runtime.ai.mode=spring-ai`
- `agent.runtime.spring-ai.provider=openai`
- `agent.runtime.spring-ai.provider=ollama` is supported for local/remote Ollama chat backends
- `agent.runtime.spring-ai.ollama.base-url=http://localhost:11434`
- `agent.runtime.spring-ai.ollama.model=llama3.1`
- `agent.runtime.spring-ai.openai.api-mode=chat`
- set `agent.runtime.spring-ai.openai.api-mode=responses-ws` to route LLM calls through OpenAI Responses over WebSocket
- `agent.runtime.spring-ai.openai.responses-ws.*` configures endpoint/model/timeout/reconnect (`endpoint-uri`, `model`, `request-timeout-ms`, `poll-interval-ms`, `max-send-retries`, `max-reconnects`, `initial-backoff-ms`, `max-backoff-ms`)
- `agent.runtime.spring-ai.model=gpt-4o-mini`
- `agent.runtime.ai.mode=realtime` is now recognized for realtime relay foundation bootstrap (you can override `aiModelClient` bean for custom realtime adapter)
- shared bootstrap now binds AGUI defaults, AGUI pre-run processor, realtime relay client, and realtime event processor (if missing)
- `agent.runtime.realtime.processor-bean-name=supportRealtimeEventProcessorCore`
- `agent.runtime.realtime.agent-endpoint-uri=agent:support?blueprint={{agent.blueprint}}`
- `agent.runtime.realtime.agent-profile-purpose-max-chars=0` in this sample preserves full seeded purpose text from blueprint `## System` (default core behavior is bounded; `0` disables truncation)
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

### Ollama Quick Switch

Run the sample with Ollama provider (no OpenAI key required):

```bash
mvn -f samples/agent-support-service/pom.xml \
   -Dagent.runtime.spring-ai.provider=ollama \
   -Dagent.runtime.spring-ai.ollama.base-url=http://localhost:11434 \
   -Dagent.runtime.spring-ai.ollama.model=llama3.1 \
   -DskipTests clean compile exec:java
```

Equivalent run script form:

```bash
samples/agent-support-service/run-sample.sh \
   -Dagent.runtime.spring-ai.provider=ollama \
   -Dagent.runtime.spring-ai.ollama.base-url=http://localhost:11434 \
   -Dagent.runtime.spring-ai.ollama.model=llama3.1
```

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

### Optional: Override JDBC Schema DDL Resource

The JDBC scripted store picks a default DDL resource by JDBC URL vendor:

- PostgreSQL -> `classpath:db/persistence/postgres-flow-state.sql`
- Snowflake -> `classpath:db/persistence/snowflake-flow-state.sql`

To force a specific DDL file while running the sample, pass:

```bash
samples/agent-support-service/run-sample.sh \
   -Dcamel.persistence.jdbc.schema.ddl-resource=classpath:db/persistence/postgres-flow-state.sql
```

You can combine it with other persistence args (for example custom JDBC URL/backend).

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
