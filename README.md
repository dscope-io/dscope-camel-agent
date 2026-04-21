# Camel Agent

`camel-agent` is a blueprint-driven Apache Camel component for agent orchestration.

![Camel Agent Architecture](CamelArchitecture.png)

## Modules

- `camel-agent-core`: `agent:` Camel component, kernel, blueprint parser, tool registry, schema checks.
- `camel-agent-persistence-dscope`: persistence adapter using `dscope-camel-persistence` (`redis`, `jdbc`, `redis_jdbc`).
- `camel-agent-spring-ai`: Spring AI multi-provider gateway (`openai`, `anthropic`, `vertex gemini`).
- `camel-agent-twilio`: Twilio-facing telephony adapter built on provider-neutral SIP/outbound-call contracts from core.
- `camel-agent-starter`: Spring Boot auto-configuration.
- `samples/agent-support-service`: runnable Camel Main support sample.

## Feature Highlights

Core platform capabilities:

- Blueprint-driven agents defined in Markdown with tool schemas, realtime metadata, AGUI pre-run configuration, and JSON route templates.
- Camel-native tool execution through local routes, Kamelets, MCP-discovered tools, and A2A peer agents.
- Multi-plan catalog routing with versioned blueprints, sticky conversation plan selection, and legacy single-blueprint fallback.
- Persistence-backed conversations, task state, audit trail projection, and optional Spring AI chat-memory integration.
- Browser AGUI and realtime voice support with session init, transcript event handling, relay integration, and runtime refresh hooks.
- Browser AGUI and realtime voice responses can now carry first-class A2UI payloads alongside the existing text/widget compatibility path.

Recent additions:

- Blueprint static resources can load Markdown, PDF, local file, classpath, and HTTP(S) content into chat and realtime instruction context.
- Route-driven agent session invocation now has a structured contract in core via `AgentSessionService` and `AgentSessionInvokeProcessor`.
- Realtime voice runtime now also supports SIP-style ingress routes, browser session seeding, and route-driven session context patches.
- AGUI pre-run and realtime transcript handling now propagate locale and resolve blueprint-declared A2UI catalogs, surfaces, and locale bundles so different agents and versions can own different UI assets without hardcoding templates in core.
- Outbound support calling uses provider-neutral telephony contracts in core with a Twilio adapter module and sample support-call flow.
- OpenAI Responses tool schemas are now normalized before request submission so strict mode works with MCP-discovered tools and JSON route template tools, including nested object parameters.

## Compatibility Matrix

- Java: `21`
- Camel: `4.15.0`
- Jackson: `2.20.0`
- JUnit: `5.10.2`
- DScope persistence: `1.1.0`
- DScope AGUI: `1.1.0`

## Release Notes

- Changelog: `docs/CHANGELOG.md`
- Product guide: `docs/PRODUCT_GUIDE.md`
- Architecture: `docs/architecture.md`
- Development guide: `docs/DEVELOPMENT_GUIDE.md`

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

## Core Capabilities

- Blueprint system instructions plus fenced YAML sections for tools, realtime settings, AGUI pre-run behavior, and resource declarations.
- Tool backends spanning direct Camel routes, Kamelets, MCP tool discovery, A2A remote agents, and generated JSON route templates.
- Multi-plan routing with `agent.agents-config`, per-request `planName` and `planVersion`, and persisted conversation plan selection.
- Stateful runtime behavior covering conversation history, task state, audit events, archive streams, and optional Spring AI chat memory.
- UI and integration surfaces including AGUI, browser realtime, HTTP/SSE A2A endpoints, admin/runtime refresh endpoints, and SIP-style voice entrypoints.
- Structured UI responses are emitted in a backward-compatible shape: plain assistant text remains available, existing widgets still work, and `a2ui` envelopes are attached from app-owned JSON assets when the agent returns matching structured data.

## Camel Endpoint Invocation

Any Camel route can invoke the runtime through either the raw `agent:` contract or the structured session façade.

Raw endpoint contract:

- `agent.conversationId` is the durable backend session key.
- omit `agent.conversationId` to create a new conversation.
- reuse the same `agent.conversationId` to continue the conversation.

Minimal route form:

```java
from("direct:agent-call")
    .setBody(simple("${body[prompt]}"))
    .setHeader("agent.conversationId", simple("${body[conversationId]}"))
    .setHeader("agent.planName", simple("${body[planName]}"))
    .setHeader("agent.planVersion", simple("${body[planVersion]}"))
    .to("agent:support?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}");
```

Structured session contract:

- `camel-agent-core` now provides `AgentSessionRequest`, `AgentSessionResponse`, `AgentSessionService`, and `AgentSessionInvokeProcessor`.
- `conversationId`, `sessionId`, and `threadId` are normalized so ordinary Camel routes can use either UI-style or backend-style identifiers.
- arbitrary `params` are preserved as exchange properties under `agent.session.params` and returned in the structured response.

For the canonical request/response payloads, see `docs/PRODUCT_GUIDE.md` and `docs/DEVELOPMENT_GUIDE.md`.

## Plan Catalogs And Versioning

Runtime can resolve agents from a catalog instead of a single blueprint:

```yaml
agent:
  agents-config: classpath:agents/agents.yaml
  blueprint: classpath:agents/support/agent.md # optional legacy fallback
```

Catalog behavior:

- multiple named plans
- multiple versions per plan
- one default plan
- one default version per plan
- sticky conversation selection persisted as `conversation.plan.selected`

Request entrypoints can pass `planName` and `planVersion`. When omitted, runtime uses sticky selection for the conversation, then catalog defaults.

## A2A Integration

Camel Agent now integrates `camel-a2a-component` as a first-class protocol bridge.

Runtime config:

```yaml
agent:
  runtime:
    a2a:
      enabled: true
      public-base-url: http://localhost:8080
      exposed-agents-config: classpath:agents/a2a-exposed-agents.yaml
```

Exposed-agent config is separate from `agents.yaml`. It maps public A2A identities to local plans:

```yaml
agents:
  - agentId: support-ticket-service
    name: Support Ticket Service
    defaultAgent: true
    planName: ticketing
    planVersion: v1
```

Inbound endpoints:

- `POST /a2a/rpc`
- `GET /a2a/sse/{taskId}`
- `GET /.well-known/agent-card.json`

Outbound behavior:

- blueprint tools can target `a2a:` endpoints
- runtime persists remote task/conversation correlation
- audit trail records outbound/inbound A2A transitions

For shared-service behavior and task/session model details, see `docs/architecture.md` and `docs/DEVELOPMENT_GUIDE.md`.

## Persistence, Audit, And Conversation State

Default persistence mode is `redis_jdbc` (Redis fast path + JDBC source-of-truth behavior inherited from `camel-persistence`).

Core persistence responsibilities include:

- conversation event history
- task snapshots
- dynamic route metadata
- persisted plan-selection state
- audit projection data
- optional conversation archive streams

Audit trail control:

- set `agent.audit-granularity` (default: `info`)
- `none`: persist no audit events
- `info`: persist process steps only
- `error`: persist process steps and include error payload data for error events
- `debug`: persist process steps with full payloads and metadata

By default, audit trail uses the same persistence store as context/state. To store audit trail in a separate JDBC backend, configure either:

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

Conversation archive note:

- archive persistence is transcript-focused and separate from the main audit stream
- use it for replay and transcript UX scenarios, not as the primary source of task or plan-selection state

## Spring AI Integration

Implemented support:

- `DscopeChatMemoryRepository` persists Spring AI chat messages to `dscope-camel-persistence`.
- `SpringAiMessageSerde` handles serialization/deserialization for `USER`, `SYSTEM`, `ASSISTANT` (including tool calls), and `TOOL` messages.
- Starter auto-config creates:
  - `ChatMemoryRepository` backed by DScope persistence
  - `MessageWindowChatMemory` with configurable window

Starter properties:

- `agent.chat-memory-enabled` (default: `true`)
- `agent.chat-memory-window` (default: `100`)

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

The support sample browser UI now also includes:

- locale selection persisted in URL/local storage
- locale-aware AGUI and realtime requests (`locale` + `Accept-Language`)
- first-class `a2ui` payload rendering mapped into the existing ticket-card widget view
- plan/version-specific A2UI catalog ids derived from the resolved runtime plan

For local no-key A2A demo runs, the sample also includes:

- `io.dscope.camel.agent.samples.DemoA2ATicketGateway`

Use it to simulate support-agent -> A2A ticket-service -> local ticket route behavior without a live model backend:

```bash
./mvnw -q -f samples/agent-support-service/pom.xml \
  -Dagent.runtime.spring-ai.gateway-class=io.dscope.camel.agent.samples.DemoA2ATicketGateway \
  -Dagent.runtime.routes-include-pattern=classpath:routes/kb-search.camel.yaml,classpath:routes/kb-search-json.camel.xml,classpath:routes/ticket-service.camel.yaml,classpath:routes/ag-ui-platform.camel.yaml,classpath:routes/admin-platform.camel.yaml \
  exec:java
```

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
      api-mode: ${AGENT_OPENAI_API_MODE:chat}
      model: gpt-5.4
      temperature: 0.2
      max-tokens: 800
      openai:
        # Supported values:
        # - chat (default): Spring AI OpenAI Chat Completions
        # - responses-http: OpenAI Responses API over HTTP with strict tool-schema normalization
        # - responses-ws: delegated to pluggable OpenAiResponsesGateway implementation
        responses-ws:
          endpoint-uri: wss://api.openai.com/v1/responses
          model: gpt-5.4
```

Notes:

- OpenAI in this gateway uses Spring AI OpenAI Chat client (`chat` mode).
- `responses-http` is implemented and is the preferred path when you need OpenAI Responses without the WebSocket transport.
- `responses-ws` is routed through a pluggable `OpenAiResponsesGateway`; if no plugin is wired, the gateway returns a terminal guidance message.
- The sample runtime also enables A2A by default through `agent.runtime.a2a.enabled=true` and exposes the ticket service through `support.ticket.manage`.
- The sample AGUI and realtime entrypoints accept top-level `locale` and also honor `Accept-Language`; core forwards the resolved value as `agent.locale`.
- For OpenAI strict tool mode, author object schemas explicitly. Every object node should resolve to `additionalProperties: false`, and object nodes with declared properties should have `required` aligned to the property keys. The runtime now normalizes common missing cases before sending requests.
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
- second prompt asks to file a ticket, route/tool selected: `support.ticket.manage`
- second-turn LLM evaluation context includes first-turn KB result
- negative case: direct ticket prompt without prior KB turn does not inject KB context

Run:

```bash
mvn -f samples/agent-support-service/pom.xml -Dtest=SpringAiAuditTrailIntegrationTest test
```

## JSON Route Templates

`agent.md` now supports a `jsonRouteTemplates` section for safe dynamic route generation.

Runtime behavior:

- templates are parsed from blueprint YAML blocks
- each template is exposed as a callable tool (via `toolName`)
- LLM returns only template parameters
- runtime expands placeholders into Camel JSON DSL, validates it, dynamically loads route, and executes it
- dynamic route metadata is persisted through existing `DynamicRouteState` persistence

See `docs/PRODUCT_GUIDE.md` for field-level template structure and the sample blueprint for a concrete example.

## MCP Discovery And Tooling

Blueprint `tools` entries with `endpointUri` starting with `mcp:` are treated as MCP service definitions.

Runtime behavior:

- on agent startup, runtime calls MCP `tools/list` for each configured `mcp:` endpoint
- discovered MCP tools are merged into the runtime tool registry used for LLM evaluation
- MCP tool execution uses MCP `tools/call` with `{ name, arguments }`
- MCP discovery payload is written to audit as `mcp.tools.discovered` (full payload available in `debug` granularity)

See `docs/PRODUCT_GUIDE.md` for the seed-tool shape and sample blueprint examples.

## Runtime Placeholder Tokens In Blueprints

Execution-facing fields in `agent.md` can be tokenized and resolved at runtime.

Supported token forms:

- `{{key}}`
- `{{key:defaultValue}}`
- `${NAME}`
- `${NAME:defaultValue}`

Typical use cases:

- environment-specific endpoint hosts
- secret tokens passed in endpoint query strings or headers-derived URIs
- runtime-selected `agent:` endpoint targets for AGUI or route/session entrypoints

Example:

```yaml
tools:
  - name: support.mcp
    endpointUri: mcp:https://{{agent.crm.host}}/mcp?token=${CRM_TOKEN}
```

Critical execution-target fields now fail fast if a placeholder remains unresolved at runtime. See `docs/PRODUCT_GUIDE.md` for the exact field list and behavior.

### Request-Scoped Process Variables

The runtime already normalizes a small set of agent-scoped identifiers onto Camel headers and exchange properties. Use these values when you want `agent.md` or a route template to react to the current request instead of static configuration.

Common values:

- `agent.conversationId` - canonical conversation key for the active agent run
- `agent.agui.sessionId` - UI/session correlation key
- `agent.agui.threadId` - optional thread correlation key
- `agent.session.params` - request params map passed to `AgentSessionService`
- `agent.session.params.<key>` - flattened request param, for example `agent.session.params.customerId`
- `callerId` / `fromNumber` - adapter-supplied telephony identity when a SIP or Twilio bridge populates it

For realtime SIP and Twilio handoffs, the runtime also copies telephony identity into `agent.session.params.callerId` and `agent.session.params.fromNumber`, and persists the same values into realtime audit events when they are present in session metadata.

Generic pattern:

1. Put the value on the Camel exchange as a header or property in the route or processor that enters the agent.
2. Read it in Camel DSL with `${header.someName}` or `${exchangeProperty.someName}`. For the standard runtime values, use the exact header names such as `${header.agent.conversationId}` and `${header.agent.agui.sessionId}`.
3. If the value should survive the agent call, copy it into one of the standard headers above or into `agent.session.params.<key>`.

Example:

```yaml
steps:
  - setHeader:
    name: agent.conversationId
    simple: ${header.conversationId}
  - setProperty:
    name: agent.session.params.callerId
    simple: ${header.callerId}
```

## Blueprint Static Resources

Blueprints can declare a `resources` section to stage static reference material into agent context.

Supported resource locations:

- `classpath:`
- `file:`
- plain file path
- `http:`
- `https:`

Supported content handling:

- Markdown and plain text are injected as text.
- PDF documents are resolved through Camel PDF extraction.
- resources can target `chat`, `realtime`, or both via `includeIn`.

Runtime behavior:

- resource text is appended to chat instructions during kernel construction.
- realtime session init seeds resource-backed instruction context for browser and SIP voice sessions.
- runtime refresh re-resolves blueprint resources and can push updated context to active conversations.

See `docs/PRODUCT_GUIDE.md` for the full `resources[]` schema and `docs/DEVELOPMENT_GUIDE.md` for implementation details.

## AGUI And Browser Realtime

`samples/agent-support-service` uses AGUI component runtime routes and a built-in UI page:

- open `http://localhost:8080/agui/ui`
- frontend supports AGUI text transport switch:
  - `POST /agui/agent` (POST+SSE)
  - `WS /agui/rpc` (AGUI over WebSocket)
- backend responds with AGUI events for the selected transport; frontend renders assistant output from AGUI message content events
- `/agui/stream/{runId}` is available for split-transport clients

For run commands and endpoint examples, see `samples/agent-support-service/README.md`.

Realtime note:

- `POST /realtime/session/{conversationId}/event` supports route-driven session-context updates after `transcript.final` routing.
- `POST /realtime/session/{conversationId}/init` seeds pre-conversation agent context from blueprint metadata before the first user turn.
- Agent/tool routes can return a patch via exchange header/property (`realtimeSessionUpdate`, aliases: `realtime.session.update`, `sessionUpdate`) or assistant JSON body (`realtimeSessionUpdate`, `realtimeSession`, `sessionUpdate`).
- Patch is deep-merged into browser session context for the same `conversationId`; next `/realtime/session/{conversationId}/token` uses merged context.

For seeded session fields, realtime configuration properties, and sample endpoint details, see `samples/agent-support-service/README.md` and `docs/PRODUCT_GUIDE.md`.

Voice UX and transcript updates (sample frontend):

- Single dynamic voice toggle button (`Start Voice`/`Stop Voice`) with idle/live/busy states.
- Runtime-selectable pause profiles for VAD (`Fast=800ms`, `Normal=1200ms`, `Patient=1800ms`) applied to relay and WebRTC session config.
- Pause milliseconds shown in both listening status text and the pause label (`Pause (<ms>ms)`).
- Mobile behavior uses icon-only voice button while preserving dynamic `title` and `aria-label` text.
- WebRTC transcript log panel includes input/output transcript lines and clear-log action.
- Voice output transcript de-duplication ensures one final assistant transcript entry per completed output (with spacing preserved).
- Collapsible `Instruction seed (debug)` panel shows the current pre-conversation instruction context; it auto-opens when transport is switched to WebRTC (and on initial load when already in WebRTC mode).

SIP and telephony note:

- `POST /sip/adapter/v1/session/{conversationId}/start`, `/turn`, and `/end` reuse the same realtime processors and route agent flow.
- outbound support calling uses provider-neutral `OutboundSipCallRequest` / `OutboundSipCallResult` / `SipProviderClient` contracts in core.
- the sample uses `support.call.outbound` plus `SupportOutboundCallProcessor` to place a provider call and return correlation data immediately.
- `POST /telephony/onboarding/openai-twilio` and `GET /telephony/onboarding/openai-twilio` generate and reload reusable onboarding plans backed by deterministic conversation ids of the form `telephony:onboarding:{tenantId}:{agentId}`.
- `GET /audit/conversation/sip` returns SIP-specific lifecycle and correlation data for both onboarding records and live call conversations.
- recommended OpenAI voice topology is `Twilio Elastic SIP Trunk -> OpenAI SIP URI -> /openai/realtime/sip/webhook`; the Java sample remains the webhook/orchestration runtime, not a SIP endpoint.

## Phase-2 Runtime Commands

The kernel supports control messages for orchestration testing:

- `task.async <checkpoint>`: creates a persisted task in `WAITING` state.
- `route.instantiate <templateId>`: creates a dynamic route lifecycle record (`CREATED -> STARTED`).
- `AgentKernel.resumeTask(taskId)`: resumes and completes waiting tasks (`RESUMED -> FINISHED`).
