# Camel Agent

`camel-agent` is a blueprint-driven Apache Camel component for agent orchestration.

## Modules

- `camel-agent-core`: `agent:` Camel component, kernel, blueprint parser, tool registry, schema checks.
- `camel-agent-persistence-dscope`: persistence adapter using `dscope-camel-persistence` (`redis`, `jdbc`, `redis_jdbc`).
- `camel-agent-spring-ai`: Spring AI multi-provider gateway (`openai`, `anthropic`, `vertex gemini`).
- `camel-agent-starter`: Spring Boot auto-configuration.
- `samples/agent-support-service`: runnable Camel Main support sample.

## Compatibility Matrix

- Java: `21`
- Camel: `4.15.0`
- Jackson: `2.20.0`
- JUnit: `5.10.2`
- DScope persistence: `1.1.0`
- DScope AGUI: `1.1.0`

## Release Notes

- Changelog: `docs/CHANGELOG.md`

## Build

```bash
mvn -q test
mvn clean install
```

## Local DScope Integration Bootstrap

Install local AGUI and persistence artifacts, then run this project with local profile:

```bash
./scripts/bootstrap-local-dscope-deps.sh
```

This activates `-Pdscope-local` for local DScope dependency alignment used by runtime modules and samples.

## Core URI

```text
agent:agentId?blueprint=classpath:agents/support/agent.md&persistenceMode=redis_jdbc&strictSchema=true&timeoutMs=30000&streaming=true
```

## Persistence Defaults

Default mode is `redis_jdbc` (Redis fast path + JDBC source-of-truth behavior inherited from `camel-persistence`).

## Spring AI ChatMemory Persistence

Implemented support:

- `DscopeChatMemoryRepository` persists Spring AI chat messages to `dscope-camel-persistence`.
- `SpringAiMessageSerde` handles serialization/deserialization for `USER`, `SYSTEM`, `ASSISTANT` (including tool calls), and `TOOL` messages.
- Starter auto-config creates:
  - `ChatMemoryRepository` backed by DScope persistence
  - `MessageWindowChatMemory` with configurable window

Starter properties:

- `agent.chat-memory-enabled` (default: `true`)
- `agent.chat-memory-window` (default: `100`)

## Audit Trail Granularity

Set `agent.audit-granularity` (default: `info`) to control persistence verbosity:

- `none`: persist no audit events.
- `info`: persist process steps only.
- `error`: persist process steps and include error payload data for error events.
- `debug`: persist process steps with full payloads and metadata.

## Optional Audit JDBC Split

By default, audit trail uses the same persistence store as context/state.

To store audit trail in a separate JDBC backend, configure either:

```yaml
agent:
  audit:
    backend: jdbc
    jdbc:
      url: jdbc:postgresql://localhost:5432/agent_audit
      username: agent_audit_user
      password: ${AGENT_AUDIT_DB_PASSWORD}
      driver-class-name: org.postgresql.Driver
```

Or namespaced equivalent:

```yaml
agent:
  audit:
    persistence:
      backend: jdbc
      jdbc:
        url: jdbc:postgresql://localhost:5432/agent_audit
```

## Load-Balanced Task Ownership (redis_jdbc)

Distributed task ownership is implemented via persistence-backed lease locks (`flowType=agent.task.lock`):

- `tryClaimTask(taskId, ownerId, leaseSeconds)` uses optimistic append semantics.
- `resumeTask(taskId)` claims lease before processing and releases after completion.
- Conflicting resume attempts return a lock message instead of double-processing.

Starter properties:

- `agent.task-claim-owner-id` (optional; defaults to generated node id)
- `agent.task-claim-lease-seconds` (default: `120`)

## Sample

```bash
mvn -f samples/agent-support-service/pom.xml -DskipTests compile exec:java
```

Or run with local hidden secrets file (`samples/agent-support-service/.agent-secrets.properties`):

```bash
samples/agent-support-service/run-sample.sh
```

See sample-specific usage and test guidance in:

- `samples/agent-support-service/README.md`

## Spring AI Runtime Config (Sample)

`samples/agent-support-service/src/main/resources/application.yaml` configures runtime provider routing:

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com

agent:
  runtime:
    ai:
      mode: spring-ai
    spring-ai:
      provider: openai # openai | gemini | claude
      model: gpt-4o-mini
      temperature: 0.2
      max-tokens: 800
      openai:
        # Spring AI OpenAI chat client uses Chat Completions endpoint.
        api-mode: chat
```

Notes:

- OpenAI in this gateway uses Spring AI OpenAI Chat client (`chat` mode).
- `responses` mode is intentionally not supported in this Spring AI-only gateway.
- Gemini uses Spring AI Vertex Gemini client and requires:
  - `agent.runtime.spring-ai.gemini.vertex.project-id`
  - `agent.runtime.spring-ai.gemini.vertex.location`
- Claude uses Spring AI Anthropic client (`/v1/messages`).

## Connectivity Check

Before running the sample with external providers, verify network and credentials:

```bash
nc -vz api.openai.com 443
curl -I https://api.openai.com/v1/models
curl https://api.openai.com/v1/models -H "Authorization: Bearer $OPENAI_API_KEY"
```

## Troubleshooting (Quick)

- `401` from OpenAI endpoints usually means missing/invalid API key in the current shell.
- `421` with the OpenAI welcome body indicates endpoint/path mismatch (request hitting API root instead of chat endpoint).
- If runtime behavior looks stale after code changes, rebuild/install local snapshots from the repo root.
- If tests are unexpectedly skipped, verify `maven.test.skip` is not enabled in environment/system properties.

For detailed sample troubleshooting and commands, see:

- `samples/agent-support-service/README.md`

## Security Remediation

Spring Framework dependency remediation was applied for known `spring-core` vulnerabilities (including annotation detection issues reported against older releases).

What was changed:

- centralized Spring Framework version management via `spring.framework.version`
- pinned `org.springframework:spring-core` in root `dependencyManagement`
- updated `camel-agent-spring-ai` to consume managed Spring version

Current resolved baseline:

- `org.springframework:spring-core:6.1.21`

Verification commands:

```bash
mvn -U -f camel-agent-spring-ai/pom.xml -DskipTests dependency:tree -Dincludes=org.springframework:spring-core
mvn -U -f camel-agent-starter/pom.xml -DskipTests dependency:tree -Dincludes=org.springframework:spring-core
mvn -U -f pom.xml verify
```

## Multi-Turn Memory + Routing Tests

Sample integration tests verify route selection and context carry-over behavior:

- first prompt asks for knowledge base help, route/tool selected: `kb.search`
- second prompt asks to file a ticket, route/tool selected: `support.ticket.open`
- second-turn LLM evaluation context includes first-turn KB result
- negative case: direct ticket prompt without prior KB turn does not inject KB context

Run:

```bash
mvn -f samples/agent-support-service/pom.xml -Dtest=SpringAiAuditTrailIntegrationTest test
```

## JSON Route Templates (Agent-Generated)

`agent.md` now supports a `jsonRouteTemplates` section for safe dynamic route generation.

Runtime behavior:

- templates are parsed from blueprint YAML blocks
- each template is exposed as a callable tool (via `toolName`)
- LLM returns only template parameters
- runtime expands placeholders into Camel JSON DSL, validates it, dynamically loads route, and executes it
- dynamic route metadata is persisted through existing `DynamicRouteState` persistence

See sample blueprint template in:

- `samples/agent-support-service/src/main/resources/agents/support/agent.md`

## MCP Tools in Blueprint

Blueprint `tools` entries with `endpointUri` starting with `mcp:` are treated as MCP service definitions.

Runtime behavior:

- on agent startup, runtime calls MCP `tools/list` for each configured `mcp:` endpoint
- discovered MCP tools are merged into the runtime tool registry used for LLM evaluation
- MCP tool execution uses MCP `tools/call` with `{ name, arguments }`
- MCP discovery payload is written to audit as `mcp.tools.discovered` (full payload available in `debug` granularity)

Example seed tool in `agent.md`:

```yaml
tools:
  - name: support.mcp
    description: MCP support backend
    endpointUri: mcp:http://localhost:3001/mcp
```

## AGUI Frontend (Sample)

`samples/agent-support-service` uses AGUI component runtime routes and a built-in UI page:

- open `http://localhost:8080/agui/ui`
- frontend sends `POST /agui/agent` with AGUI request envelope
- backend responds with an SSE event stream (same POST response); frontend renders assistant output from AGUI message content events
- `/agui/stream/{runId}` is available for split-transport clients

For run commands and endpoint examples, see `samples/agent-support-service/README.md`.

Realtime note:

- `POST /realtime/session/{conversationId}/event` supports route-driven session-context updates after `transcript.final` routing.
- Agent/tool routes can return a patch via exchange header/property (`realtimeSessionUpdate`, aliases: `realtime.session.update`, `sessionUpdate`) or assistant JSON body (`realtimeSessionUpdate`, `realtimeSession`, `sessionUpdate`).
- Patch is deep-merged into browser session context for the same `conversationId`; next `/realtime/session/{conversationId}/token` uses merged context.

Voice UX and transcript updates (sample frontend):

- Single dynamic voice toggle button (`Start Voice`/`Stop Voice`) with idle/live/busy states.
- Runtime-selectable pause profiles for VAD (`Fast=800ms`, `Normal=1200ms`, `Patient=1800ms`) applied to relay and WebRTC session config.
- Pause milliseconds shown in both listening status text and the pause label (`Pause (<ms>ms)`).
- Mobile behavior uses icon-only voice button while preserving dynamic `title` and `aria-label` text.
- WebRTC transcript log panel includes input/output transcript lines and clear-log action.
- Voice output transcript de-duplication ensures one final assistant transcript entry per completed output (with spacing preserved).

## Phase-2 Runtime Commands

The kernel supports control messages for orchestration testing:

- `task.async <checkpoint>`: creates a persisted task in `WAITING` state.
- `route.instantiate <templateId>`: creates a dynamic route lifecycle record (`CREATED -> STARTED`).
- `AgentKernel.resumeTask(taskId)`: resumes and completes waiting tasks (`RESUMED -> FINISHED`).
