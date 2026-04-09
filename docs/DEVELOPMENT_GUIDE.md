# Development Guide

## Purpose

This guide is the developer-oriented companion to the product and architecture docs. It covers the implementation seams, extension points, and validation workflow for the full platform, not only the most recent additions.

Use this alongside `docs/architecture.md` and `docs/PRODUCT_GUIDE.md`.

The development surface spans:

- blueprint-defined agents and tool contracts
- plan catalogs and versioned blueprint selection
- Camel route, Kamelet, MCP, A2A, and JSON-template tool execution
- persistence, audit trail, conversation archive, and Spring AI chat memory
- AGUI, browser realtime, and route-driven runtime processors
- blueprint static resources
- route-driven structured session invocation
- SIP ingress, outbound support calling, and provider adapters

## Core Development Rules

1. Treat `conversationId` as the canonical backend conversation and session identifier.
2. Keep plan selection plan-aware and conversation-aware.
3. Extend the existing blueprint and kernel model before inventing new parallel orchestration layers.
4. Prefer provider-neutral contracts in core and provider-specific integrations in adapter modules.
5. Reuse existing AGUI, realtime, persistence, and A2A seams instead of introducing duplicate state models.

## Module Responsibilities

- `camel-agent-core`: `agent:` Camel component, blueprint loader, kernel, tool registry, runtime processors, A2A integration layer, session façade, resource resolution, and telephony contracts.
- `camel-agent-persistence-dscope`: persistence implementation backed by DScope camel persistence for conversations, task state, dynamic routes, audit, and archive flows.
- `camel-agent-spring-ai`: Spring AI-backed model execution, provider routing, and chat-memory integration.
- `camel-agent-twilio`: Twilio-specific telephony adapter built on provider-neutral call contracts from core.
- `camel-agent-starter`: Spring Boot auto-configuration for endpoint, kernel, persistence, and chat-memory integration.
- `samples/agent-support-service`: runnable reference runtime that exercises plans, AGUI, realtime, MCP, A2A, SIP-style ingress, and outbound support calling.

## Runtime Mental Model

At development time, assume one consistent flow across all entrypoints:

1. An entrypoint sends a prompt or event into the runtime.
2. The runtime resolves a concrete plan and blueprint.
3. Blueprint metadata becomes tools, realtime defaults, AGUI behavior, and optional resource context.
4. `DefaultAgentKernel` executes the turn, tools, and model interactions.
5. Persistence records conversation events, task state, and optional archive or audit projections.

This is true whether the entrypoint is:

- a plain Camel route using `agent:`
- AGUI HTTP or WebSocket traffic
- browser realtime processors
- A2A RPC dispatch
- structured session invocation
- SIP-style transcript ingress

## Blueprint Authoring

### Core Contract

Blueprints remain the unit of agent behavior. A blueprint can define:

- `## System` instructions
- `tools`
- `jsonRouteTemplates`
- `realtime`
- `aguiPreRun`
- `resources`

Developer guidance:

- keep `## System` focused on role, routing rules, escalation behavior, and operational constraints
- keep tool schemas explicit and stable
- use blueprint metadata to describe capabilities, not runtime-specific conversation state
- keep blueprint versions immutable once promoted into a plan catalog

### Tool Backends

Tools can execute through multiple backends:

- direct Camel routes via `routeId`
- explicit Camel endpoints via `endpointUri`
- Kamelets
- MCP seed tools and discovered MCP tools
- A2A remote agents
- JSON route template expansion

Choose the narrowest backend that fits the use case:

- local deterministic business logic: direct Camel routes
- shared external tool platform: MCP
- remote agent-to-agent workflow: A2A
- controlled parameterized route generation: JSON route templates

## Plan Catalogs And Versioning

Plan catalogs are the preferred runtime selection model.

Developer guidance:

1. Use `agent.agents-config` for multi-agent or versioned environments.
2. Treat `agent.blueprint` as legacy fallback or local single-agent convenience.
3. Expect plan selection to become sticky through persisted `conversation.plan.selected` events.
4. When changing plan selection behavior, verify runtime refresh, AGUI, realtime, and A2A paths still honor the same resolution order.

Selection order:

1. explicit request `planName` and `planVersion`
2. sticky persisted selection for the conversation
3. catalog defaults
4. legacy fallback blueprint

### Plan-Scoped AI Overrides

`agents.yaml` can define an optional `ai` block at the plan level and at each version entry.

Guidance:

1. Use plan-level `ai` for provider defaults shared by all versions of a plan.
2. Use version-level `ai` for model, temperature, max token, prompt cache, or provider-specific property differences.
3. Put provider-specific runtime tuning in `ai.properties` when it should vary by plan/version instead of globally in `application.yaml`.
4. Expect plan-level `ai` to merge first and version-level `ai` to win on conflicts.

Supported fields:

- `provider`
- `model`
- `temperature`
- `max-tokens`
- `properties` as a nested map that is flattened into runtime property keys

Resolved AI settings are persisted in `conversation.plan.selected` and surfaced through session and audit APIs so diagnostics can identify the exact provider and override set used by a conversation.

## Camel Endpoint Invocation

### Raw `agent:` Contract

Use raw `agent:` when the route only needs assistant text and the returned conversation id header.

```java
from("direct:agent-call")
    .setBody(simple("${body[prompt]}"))
    .setHeader("agent.conversationId", simple("${body[conversationId]}"))
    .to("agent:support?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}");
```

Behavior:

- no `agent.conversationId` means create a new conversation
- existing `agent.conversationId` means continue the session
- response body is assistant text only
- plan resolution, tool execution, and persistence still run through the same kernel path as any other entrypoint

### Structured Session Contract

Use the core session façade when a route needs plan metadata, task state, events, or arbitrary request params.

Core classes:

- `io.dscope.camel.agent.session.AgentSessionRequest`
- `io.dscope.camel.agent.session.AgentSessionResponse`
- `io.dscope.camel.agent.session.AgentSessionService`
- `io.dscope.camel.agent.session.AgentSessionInvokeProcessor`

Request shape:

```json
{
  "prompt": "Please call the customer back",
  "conversationId": "optional-existing-conversation",
  "sessionId": "optional-ui-session-id",
  "threadId": "optional-ui-thread-id",
  "planName": "support",
  "planVersion": "v2",
  "params": {
    "customerId": "123",
    "channel": "sip"
  }
}
```

Implementation notes:

- `conversationId`, `sessionId`, and `threadId` are normalized to a single durable conversation key
- request params are stored as exchange properties under `agent.session.params`
- resolved plan headers are written back as standard agent headers
- `AgentSessionInvokeProcessor` accepts JSON strings, maps, or strongly typed request objects

Recommended route usage:

```java
from("direct:agent-session.invoke")
    .process(new AgentSessionInvokeProcessor());
```

## Persistence, Audit, And Conversation State

### Core Persistence Responsibilities

The persistence layer is responsible for:

- conversation event history
- task snapshots
- dynamic route metadata
- plan-selection events
- optional archive events
- audit projection data

Developer guidance:

- preserve event compatibility when changing runtime processors
- avoid writing route-specific state outside the established persistence abstractions
- keep correlation metadata attached to the same conversation whenever possible

### Audit Trail

Audit is part of the runtime contract, not an afterthought.

Important developer concerns:

- `agent.audit-granularity` changes payload verbosity and should be respected by new flows
- async audit mode should not change business behavior, only request-thread timing
- audit splitting to a dedicated JDBC backend should not break conversation-state persistence

Core audit services and processors:

- `AuditTrailService`: basic service-style loader for raw event arrays and usage summaries
- `AuditTrailSearchProcessor`: filtered raw event search for one conversation
- `AuditConversationListProcessor`: searchable/sortable conversation summary list
- `AuditConversationViewProcessor`: normalized message-oriented conversation projection for console UIs
- `AuditConversationSipProcessor`: SIP-focused lifecycle projection for onboarding records and live call conversations
- `AuditConversationUsageProcessor`: token and cost summary from `model.usage` events
- `AuditConversationSessionDataProcessor`: transcript/archive reads backed by `ConversationArchiveService`
- `AuditConversationAgentMessageProcessor`: manual assistant-message append for operator tooling
- `AuditAgentCatalogProcessor`: plan/version catalog metadata for console sidebars or picker UIs
- `AuditAgentBlueprintProcessor`: resolved blueprint content and metadata for the selected conversation
- `AuditMetadataSupport`: shared projection logic for agent metadata, AI config, SIP correlation, A2A correlation, and blueprint-derived labels
- `AuditUsageSupport`: parser/aggregator for `model.usage` events

Runtime bootstrap bindings:

- `AgentRuntimeBootstrap` binds `auditTrailService`, `auditTrailSearchProcessor`, `auditConversationListProcessor`, `auditConversationViewProcessor`, `auditConversationSipProcessor`, `auditConversationUsageProcessor`, `auditConversationSessionDataProcessor`, `auditConversationAgentMessageProcessor`, `auditAgentCatalogProcessor`, and `auditAgentBlueprintProcessor`
- the sample admin routes expose these bindings over both HTTP endpoints and MCP tools

Primary event families for audit trail work:

- `user.message`
- `assistant.message`
- `assistant.manual.message`
- `sip.*`
- `tool.requested`, `tool.started`, `tool.completed`, `tool.failed`
- `realtime.*`
- `model.usage`
- `conversation.plan.selected`
- `agent.definition.refreshed`
- `conversation.a2a.*`

When designing new flows, decide explicitly whether the data belongs in:

- audit trail for diagnostics, execution tracing, model usage, and operator metadata
- conversation archive for transcript playback and session-scoped user/assistant feeds

#### Processor Contracts

`AuditTrailSearchProcessor` input headers:

- `conversationId` required
- optional `type`
- optional `q`
- optional `from`
- optional `to`
- optional `limit`

`AuditTrailSearchProcessor` response fields:

- `conversationId`
- `ai`
- `sip`
- `conversationMetadata`
- `modelUsage`
- `filters`
- `count`
- `events[]`

Each projected event row includes:

- `conversationId`
- `taskId`
- `type`
- `payload`
- `sip`
- `timestamp`
- `agent`

`AuditConversationListProcessor` input headers:

- optional `q`
- optional `topic`
- optional `sortBy`
- optional `order`
- optional `limit`

`AuditConversationListProcessor` response fields:

- `query`
- `topic`
- `sortBy`
- `order`
- `limit`
- `count`
- `blueprintUri`
- `items[]`

Each `items[]` row includes:

- `conversationId`
- `ai`
- `metadata`

`metadata` is the main console summary block and can include:

- `title`
- `description`
- `topics[]`
- `channel`
- `agentTitle`
- `agentName`
- `agentVersion`
- `planName`
- `planVersion`
- `blueprintUri`
- `conversationKind`
- `firstEventAt`
- `lastEventAt`
- `eventCount`
- `modelUsageTotals`
- optional `sip` metadata including provider, call ids, direction, status, and onboarding correlation
- optional A2A fields such as `a2aAgentId`, `a2aRemoteConversationId`, and `a2aRemoteTaskId`

`AuditConversationViewProcessor` input headers:

- `conversationId` required
- optional `limit`

`AuditConversationViewProcessor` response fields:

- `conversationId`
- `eventCount`
- `copilotKitAvailable`
- `agui`
- `agentPerspective`
- `modelUsage`
- `agent`
- `sip`
- `a2a`

`agentPerspective.messages[]` is the preferred UI timeline feed. Each row includes:

- `role`
- `type`
- `text`
- `timestamp`
- `manual`
- `payload`
- `agent`

`AuditConversationUsageProcessor` input headers:

- `conversationId` required
- optional `limit`

`AuditConversationUsageProcessor` response fields:

- `conversationId`
- `eventCount`
- `modelUsage`

`modelUsage` comes from `AuditUsageSupport` and includes:

- `callCount`
- `totals`
- `byModel[]`
- `calls[]`

`AuditConversationSessionDataProcessor` input headers:

- `conversationId` required
- optional `sessionId`
- optional `type`
- optional `q`
- optional `from`
- optional `to`
- optional `limit`

`AuditConversationSessionDataProcessor` response fields:

- `conversationId`
- `sessionId`
- `archivePersistenceEnabled`
- `filters`
- `count`
- `events[]`

Each archive row includes:

- `conversationId`
- `taskId`
- `type`
- `role`
- `direction`
- `source`
- `sessionId`
- `runId`
- `text`
- `payload`
- `timestamp`

`AuditConversationAgentMessageProcessor` accepts:

- `conversationId` required
- `message` or `text` required
- optional `sessionId`
- optional `runId`
- optional `threadId`

and appends `assistant.manual.message` with correlation metadata suitable for AGUI-linked consoles.

`AuditConversationSipProcessor` input headers:

- `conversationId` required
- optional `limit`

`AuditConversationSipProcessor` response fields:

- `conversationId`
- `eventCount`
- `sip`
- `events[]`

Each SIP event row includes:

- `type`
- `timestamp`
- `payload`
- `sip`

#### MCP Audit Surface

`AuditMcpToolsListProcessor` and `AuditMcpToolsCallProcessor` expose the audit console surface through MCP.

Primary tool names:

- `audit.events.search`
- `audit.conversations.list`
- `audit.conversation.view`
- `audit.conversation.sessionData`
- `audit.conversation.agentMessage`
- `audit.agent.catalog`
- `audit.agent.blueprint`

Operational companion tools:

- `runtime.audit.granularity.get`
- `runtime.audit.granularity.set`
- `runtime.conversation.persistence.get`
- `runtime.conversation.persistence.set`
- `runtime.refresh`
- `runtime.conversation.close`
- `runtime.purge.preview`

This MCP surface is the easiest way to build a console that wants one transport contract for reads, writes, and runtime controls.

#### Custom Console Design Guidance

For a custom audit console, prefer this data flow:

1. use `audit.conversations.list` for the searchable left-hand conversation index
2. use `audit.conversation.view` for the main timeline because it already normalizes visible user/assistant messages
3. use `audit.events.search` for raw event inspection, export, and advanced filters
4. use `GET /audit/conversation/usage` for cost/token charts and provider/model breakdowns
5. use `audit.conversation.sessionData` for transcript replay or session-oriented subviews
6. use `audit.agent.catalog` and `audit.agent.blueprint` for plan/version context and blueprint inspection

Recommended UI mapping:

- list pane -> `items[]` from conversation list
- conversation header -> `metadata` or `conversationMetadata`
- readable timeline -> `agentPerspective.messages[]`
- developer/raw panel -> `events[]`
- usage/cost widgets -> `modelUsage`
- transcript replay panel -> archive `events[]`

Recommended rendering rules:

- show `ai` at both conversation level and event/message level when debugging per-plan model overrides
- show `a2a` only when populated to avoid clutter for standard conversations
- keep `payload` collapsible rather than fully expanded by default
- treat `model.usage` data as append-only accounting events and aggregate with `AuditUsageSupport` semantics rather than recomputing from message text

For a concrete example, use `samples/agent-support-service/src/main/resources/frontend/audit.html` as the reference console implementation. It already demonstrates:

- conversation listing
- metadata and topic rendering
- normalized agent-perspective timelines
- raw JSON export
- MCP tool invocation for audit and runtime controls

### Conversation Archive

Conversation archive persistence is transcript-focused and distinct from the main audit trail.

Use it for:

- replay and transcript UX scenarios
- assistant and user turn inspection
- observed realtime transcript history

Do not treat it as the primary source for plan selection or runtime task state.

## Spring AI Integration

`camel-agent-spring-ai` provides model execution and chat-memory integration.

Developer guidance:

- keep provider routing isolated in the Spring AI gateway layer
- keep agent behavior model-agnostic above the gateway
- validate chat-memory serialization when changing message formats, tool-call payloads, or assistant turn structures
- treat Spring AI chat memory as complementary to conversation event persistence, not a replacement for it

When changing Spring AI integration, validate:

- provider selection
- tool-call serialization
- chat-memory replay
- fallback behavior when credentials are missing or invalid

## AGUI And Browser Realtime

### AGUI Runtime

AGUI integration uses the same conversation and plan-selection model as other entrypoints.

Developer concerns:

- preserve correlation keys between `conversationId`, `sessionId`, `threadId`, and `runId`
- keep fallback behavior aligned with blueprint tool metadata or explicit fallback route config
- ensure plan-aware request paths continue forwarding `planName` and `planVersion`

### Browser Realtime

Browser realtime flows depend on runtime-bound processors for:

- session init
- transcript and control events
- relay integration
- token minting
- session context patching

Developer guidance:

- treat realtime session instructions as a derived view of blueprint and runtime metadata
- avoid creating browser-only instruction models that diverge from the core blueprint
- validate session refresh behavior after blueprint or resource changes

## MCP Discovery And JSON Route Templates

### MCP Discovery

Use MCP seed tools when you want external tool inventories discovered at runtime.

Developer guidance:

- keep seed tool definitions stable
- treat discovered MCP tools as runtime-expanded tool registry entries
- validate `tools/list` and `tools/call` compatibility when changing discovery or request shaping

### JSON Route Templates

Use JSON route templates when the model should choose parameters but not author raw Camel DSL.

Developer guidance:

- keep template parameters narrow and validated
- prefer schema-driven inputs over free-form dynamic route authoring
- test both route generation and route execution behavior

## A2A Integration

Camel Agent uses `camel-a2a-component` for shared protocol/runtime behavior and adds agent-specific mapping on top.

Developer responsibilities in this layer:

- map public A2A agents to local plans and versions
- keep linked conversation correlation stable
- preserve outbound and inbound audit metadata
- reuse shared A2A task services when they already exist in the registry

Validate both sides when changing A2A behavior:

- inbound RPC and SSE flow
- exposed-agent catalog mapping
- outbound `a2a:` tool calls
- linked conversation and remote task correlation

## Blueprint Static Resources

### Authoring

Blueprints can declare `resources[]`.

Example:

```yaml
resources:
  - name: login-handbook
    uri: classpath:agents/support/v2/resources/login-handbook.md
    kind: document
    format: markdown
    includeIn: [chat, realtime]
    loadPolicy: startup
    optional: false
```

Supported locations:

- `classpath:`
- `file:`
- plain file path
- `http:`
- `https:`

Supported formats:

- markdown / plain text
- PDF via Camel PDF extraction

### Runtime Flow

1. `MarkdownBlueprintLoader` parses `resources[]`
2. `BlueprintResourceResolver` loads and normalizes them
3. `BlueprintInstructionRenderer` injects bounded text into chat instructions
4. `RealtimeBrowserSessionInitProcessor` injects bounded text into realtime session seed context
5. `RuntimeResourceRefreshProcessor` re-resolves them during runtime refresh

Relevant budgets:

- `agent.runtime.chat.resource-context-max-chars`
- `agent.runtime.realtime.resource-context-max-chars`

Guidelines:

- keep resources operational and compact
- prefer blueprint resources for durable reference material, not per-turn data
- use runtime refresh when resource content changes underneath a long-lived runtime

## Runtime Placeholder Resolution

Blueprint execution metadata can use runtime placeholders for environment-specific URLs, route ids, and secrets.

Supported syntax:

- `{{key}}`
- `{{key:defaultValue}}`
- `${NAME}`
- `${NAME:defaultValue}`

Resolution order:

1. bootstrap-time application properties are resolved through `RuntimePropertyPlaceholderResolver`, which supports both `{{...}}` and `${...}` placeholders and can resolve references to other loaded properties
2. execution-target fields are resolved through the runtime placeholder utilities before route or endpoint invocation
3. `${...}` fallback values come from environment variables, then JVM system properties, then inline defaults

Implementation seam:

- `RuntimePropertyPlaceholderResolver` handles bootstrap-time application property resolution
- `RuntimePlaceholderResolver` handles execution-facing substitution for blueprint and route/session execution targets

Fail-fast execution targets:

- `tools[].routeId`
- `tools[].endpointUri`
- `aguiPreRun.agentEndpointUri`
- AGUI fallback URIs
- `realtime.endpointUri`
- route/session endpoint override headers and properties such as `agent.session.endpointUri`

Design rule:

- use permissive `resolveString(...)` only for non-critical metadata
- use strict execution-target resolution for anything that directly controls route or endpoint invocation

If an execution target still contains `{{...}}` or `${...}` after resolution, runtime should fail immediately with a field-specific `IllegalArgumentException` rather than invoking the wrong route or leaking a partial URI into downstream behavior.

## SIP And Telephony

### SIP And Realtime Ingress

Canonical sample ingress routes:

- `POST /sip/adapter/v1/session/{conversationId}/start`
- `POST /sip/adapter/v1/session/{conversationId}/turn`
- `POST /sip/adapter/v1/session/{conversationId}/end`

Guidelines:

1. Map provider call identity to one stable `conversationId`.
2. Normalize call start into realtime `/init`.
3. Normalize final transcript turns into realtime `transcript.final`.
4. Let `RealtimeEventProcessor` route transcript turns into the same `agent:` flow as chat.
5. Use route session patches when a tool needs to update future voice-session context.

Do not create a separate call-local conversation model unless the adapter truly spans multiple independent agent conversations.

When using OpenAI Realtime SIP with Twilio Elastic SIP Trunk, the preferred path is different: Twilio points at the OpenAI SIP URI, OpenAI sends verified webhook events to `/openai/realtime/sip/webhook`, and the backend keeps using the same conversation/audit model.

### Reusable OpenAI/Twilio Onboarding

Core onboarding support lives in `camel-agent-core` so other agent projects can bind their own HTTP or MCP surface without copying business logic:

- `OpenAiTwilioSipOnboardingService` builds the onboarding plan, SIP URI, checklist, warnings, and runtime property guidance
- `TelephonyOnboardingPersistenceService` stores sanitized onboarding records as audit/persistence events
- onboarding records use deterministic conversation ids shaped like `telephony:onboarding:{tenantId}:{agentId}`

The sample runtime wires those services through:

- `POST /telephony/onboarding/openai-twilio`
- `GET /telephony/onboarding/openai-twilio`
- `GET /audit/conversation/sip`

For new agent projects, keep the reusable core services and replace only the edge-specific processor that resolves tenant defaults, secrets, and route naming.

### Outbound Support Calling

Core telephony contracts live in `camel-agent-core`:

- `OutboundSipCallRequest`
- `OutboundSipCallResult`
- `SipProviderClient`
- call correlation records and realtime call-session support

Provider split:

- core owns normalized call request/response and conversation correlation
- provider modules own placement details
- `camel-agent-twilio` is the current provider adapter

Sample flow:

1. blueprint tool `support.call.outbound`
2. local route `direct:support-call-outbound-route`
3. `SupportOutboundCallProcessor`
4. `TwilioSipProviderClient`
5. `direct:twilio-outbound-call`

Guidelines:

- always preserve or return the backend `conversationId`
- persist provider request ids and references for later webhook correlation
- keep call placement provider-specific and agent/orchestration state provider-neutral

## Validation Workflow

Focused validation is usually better than a full reactor run during active development.

### Common Build Patterns

Repository build:

```bash
mvn -q test
mvn clean install
```

Focused core session tests:

```bash
mvn -pl camel-agent-core compiler:testCompile surefire:test -Dtest=AgentSessionServiceTest
```

Focused blueprint and realtime resource tests:

```bash
mvn -pl camel-agent-core resources:testResources compiler:compile compiler:testCompile surefire:test -Dtest=MarkdownBlueprintLoaderTest,RealtimeBrowserSessionInitProcessorTest
```

Sample-focused compile and tests:

```bash
mvn -pl samples/agent-support-service -am resources:resources resources:testResources compiler:compile compiler:testCompile surefire:test -DskipTests=false
```

Sample-focused local install when downstream module artifacts may be stale:

```bash
mvn install -DskipTests
```

### What To Validate By Area

- blueprint or tool changes: schema validation, route execution, and agent turn behavior
- plan catalog changes: default selection, explicit overrides, and sticky conversation selection
- persistence or audit changes: conversation replay, task state, and audit output compatibility
- Spring AI changes: provider routing, chat memory, and tool-call serialization
- AGUI or realtime changes: init flow, correlation metadata, transcript routing, and fallback behavior
- A2A changes: agent card exposure, RPC/SSE handling, and linked conversation correlation
- session façade changes: JSON request parsing, identifier normalization, and structured response serialization
- telephony changes: provider correlation, ingress normalization, outbound placement, and webhook handling

Repo-specific note:

- when full lifecycle plugin phases are noisy or unstable, prefer explicit `resources`, `compile`, `testCompile`, and `surefire:test` goals for the slice you are changing
- when shared modules such as `camel-agent-core` or `camel-agent-spring-ai` changed, prefer reactor-slice sample validation (`-pl samples/agent-support-service -am`) over direct `-f samples/...` runs so the sample uses the current workspace outputs instead of stale local Maven artifacts

## Documentation Expectations For Future Changes

When changing any user-facing or runtime-significant area, update the relevant docs together:

- `README.md` for overview and quick-start implications
- `docs/architecture.md` for runtime flow changes
- `docs/PRODUCT_GUIDE.md` for user-facing contracts and configuration
- `samples/agent-support-service/README.md` when sample routes, endpoints, or run instructions change
- this guide when implementation seams, extension points, or validation workflow change