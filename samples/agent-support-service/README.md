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

Or use the helper script (auto-loads `.agent-secrets.properties`):

```bash
samples/agent-support-service/run-sample.sh
```

Then open:

- `http://localhost:8080/agui/ui` for the frontend

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

1. Browser sends `POST /agui/agent` with AGUI envelope (`threadId`, `sessionId`, `messages`).
2. Backend returns an SSE event stream in the same POST response (POST+SSE bridge).
3. Frontend parses AGUI events (especially `TEXT_MESSAGE_CONTENT`) and renders assistant text.
4. If assistant text contains ticket JSON, frontend renders a `ticket-card` widget.

Notes:

- `/agui/agent` is the primary request path for the sample UI.
- `/agui/stream/{runId}` remains available for split-transport clients.
- `/agui/rpc` is not used by this sample frontend flow.
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
- `GET /agui/stream/{runId}` -> optional event stream endpoint for split transport mode
- `GET /agui/ui` -> simple Copilot-like frontend with ticket widget rendering

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

## Runtime Config

Primary config is in `src/main/resources/application.yaml`.

Notable keys:

- `agent.runtime.ai.mode=spring-ai`
- `agent.runtime.spring-ai.provider=openai`
- `agent.runtime.spring-ai.openai.api-mode=chat`
- `agent.runtime.spring-ai.model=gpt-4o-mini`

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

- Tests unexpectedly skipped
   - check if `maven.test.skip=true` is set in env/system properties.
   - run explicit class test:
      - `mvn -f samples/agent-support-service/pom.xml -Dtest=SpringAiAuditTrailIntegrationTest test`
