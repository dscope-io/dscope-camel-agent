# Camel Agent Product Guide

![Camel Agent Architecture](../CamelArchitecture.png)

`camel-agent` is a blueprint-driven Apache Camel component for agent orchestration. It lets you define an agent in Markdown, expose tools backed by Camel routes, Kamelets, MCP services, or A2A peers, persist conversation and task state, and run the same agent from Camel Main or inside a Spring application.

The recent session, resource, SIP, and outbound-call features are additive. They extend the same blueprint, routing, persistence, A2A, AGUI, and Spring AI runtime model that already existed in the core platform.

This guide covers:

- product architecture and module responsibilities
- common deployment and runtime scenarios
- all user-facing settings and options exposed by the component, starter, and runtime bootstrap
- how to author `agent.md` instructions and plan catalogs
- how to bootstrap the product into a Spring application

## Product At A Glance

### Modules

| Module | Purpose |
| --- | --- |
| `camel-agent-core` | `agent:` Camel component, blueprint loader, kernel, tool registry, schema validation, runtime helpers |
| `camel-agent-persistence-dscope` | Persistence facade backed by DScope camel persistence with `redis`, `jdbc`, and `redis_jdbc` modes |
| `camel-agent-spring-ai` | Spring AI model gateway, multi-provider routing, OpenAI/Gemini/Claude support, chat memory serialization |
| `camel-agent-twilio` | Twilio telephony adapter built on provider-neutral outbound SIP and call-correlation contracts |
| `camel-agent-starter` | Spring Boot auto-configuration for `AgentKernel`, persistence, blueprint loader, and optional chat memory |
| `samples/agent-support-service` | Runnable sample showing support flows, AGUI, realtime voice, MCP discovery, A2A exposure, and local routes |

### Supported Runtime Patterns

| Pattern | What It Looks Like |
| --- | --- |
| Camel endpoint invocation | `to("agent:support?... ")` from a route |
| Blueprint-driven tool routing | Markdown blueprint with `## System` plus YAML blocks for tools and runtime metadata |
| Plan catalog routing | `agent.agents-config` points to `agents.yaml` with multiple plans and versions |
| A2A protocol bridge | Runtime publishes agent cards and RPC/SSE endpoints while blueprints can call peer agents through `a2a:` tools |
| Persistence-backed conversations | `PersistenceFacade` stores conversation events, task state, dynamic routes, and optionally archive streams |
| Spring AI model execution | `SpringAiModelClient` backed by `MultiProviderSpringAiChatGateway` |
| AGUI and realtime voice | Optional runtime bootstrap binds processors and relay clients for browser and voice flows |
| Blueprint static resources | Markdown/PDF/local/remote reference material is resolved into chat and realtime agent context |
| Route-driven session invocation | Ordinary Camel routes can use a structured start-or-continue session contract instead of raw text-only `agent:` exchange bodies |
| Provider-neutral telephony | Outbound support calls and SIP-style ingress reuse the same conversation and realtime orchestration seams |

## Runtime Architecture

At a high level, one request flows like this:

1. A Camel route, AGUI processor, or realtime processor sends a prompt to the `agent:` endpoint.
2. The runtime resolves the active blueprint from either `agent.agents-config` or `agent.blueprint`.
3. `MarkdownBlueprintLoader` parses the Markdown file, extracts the `## System` block, and merges all fenced YAML blocks.
4. The runtime registers declared tools, expands JSON route templates into callable virtual tools, optionally discovers MCP tools, and can expose or invoke A2A agents.
5. `DefaultAgentKernel` runs the message loop, validates schemas, invokes the model client, executes tools, and appends audit events.
6. Persistence stores conversation events, task state, dynamic route metadata, and optionally separate conversation archive events.

For lower-level event flow details, see `docs/architecture.md`.

## Main Scenarios

### 1. Blueprint-Driven Camel Tool Routing

Use this when you want deterministic business tools behind an LLM-controlled front door.

- The blueprint declares tools such as `kb.search` and `support.ticket.manage`.
- Each tool maps to a Camel route via `routeId` or `endpointUri`.
- The model chooses tools, and the kernel validates input/output payloads before and after route execution.

This is the baseline pattern shown by `samples/agent-support-service` and the foundation for all higher-level runtime flows.

### 2. Plan Catalogs And Versioning

Use this when you want multiple agent families such as `support` and `billing`, with versioned blueprints.

- `agent.agents-config` points to a catalog file such as `samples/agent-support-service/src/main/resources/agents/agents.yaml`.
- Each plan can have multiple versions.
- One plan is marked as default, and one version per plan is marked as default.
- Incoming requests can override plan selection with `agent.planName` and `agent.planVersion` headers.

This is useful for staged rollouts, tenant-specific plans, or A/B testing across agent versions.

### 3. A2A Integration

Use this when you want one or more plans to be reachable as public A2A agents while still running inside the same Camel runtime.

- `agent.runtime.a2a.enabled=true` turns on the protocol bridge.
- `agent.runtime.a2a.exposed-agents-config` maps public `agentId` values to local plans and versions.
- Blueprints can call peer services through `a2a:<agentId>?remoteUrl=...` tools.
- The runtime publishes RPC, SSE, and agent-card endpoints and keeps remote task correlation in persistence and audit state.

The support sample uses this pattern to expose `support-ticket-service` as an A2A-facing ticket lifecycle service backed by the local `ticketing:v1` plan.

### 4. AGUI And Browser Realtime

Use this when the agent must participate in browser UI, realtime session init, transcript handling, or AGUI fallback flows.

- AGUI pre-run can call the agent first.
- If the response is empty or looks like an auth/key failure, fallback can route directly to deterministic tools.
- Fallback can derive target URIs from blueprint tool metadata or use explicit override URIs.
- Realtime processors can seed browser-side context and patch future session state.

This is how the sample keeps `/agui/ui` usable without a valid OpenAI key while still exercising the normal conversation and plan-selection model.

### 5. MCP Discovery

Use this when your agent should discover tools from an MCP server instead of hard-coding all tool definitions.

- Declare a seed tool whose `endpointUri` starts with `mcp:`.
- On blueprint load, the runtime calls MCP `tools/list`.
- Discovered tools are merged into the runtime tool registry.
- Calls execute via MCP `tools/call`.

This is appropriate for external support backends, CRM systems, or shared tool platforms.

### 6. JSON Route Templates

Use this when you need parameterized route creation without letting the model author arbitrary Camel DSL.

- Define `jsonRouteTemplates` in the blueprint.
- The runtime exposes each template as a callable tool.
- The model returns only template parameters.
- The runtime expands and validates the generated route, then executes it.

This gives you controlled dynamic routing without granting raw route authoring to the model.

### 7. Route-Driven Agent Sessions

Use this when a normal Camel route, REST endpoint, or integration flow needs to start or continue an agent session and receive structured metadata back.

- `AgentSessionService` accepts `prompt`, `conversationId`, `sessionId`, `threadId`, `planName`, `planVersion`, and arbitrary `params`.
- Missing identifiers create a new durable conversation id.
- Existing identifiers continue the same conversation.
- `AgentSessionInvokeProcessor` turns JSON or map request bodies into a structured JSON response with resolved plan and task metadata.

This is the preferred façade when a route needs more than the plain assistant message returned by the raw `agent:` producer.

### 8. Blueprint Static Resources

Use this when an agent needs durable reference material injected into chat instructions or realtime session context.

- Blueprints declare `resources[]` alongside tools and other metadata.
- Runtime resolves classpath, file, plain-path, and `http(s)` resources.
- Resource text is bounded separately for chat and realtime surfaces.
- Runtime refresh can re-resolve resources for active conversations.

This extends normal blueprint authoring without introducing a separate attachment model.

### 9. SIP Ingress And Outbound Support Calls

Use this when the agent must participate in telephony flows.

- Inbound adapters normalize provider events into `/sip/adapter/v1/session/{conversationId}/{start|turn|end}`.
- Realtime processors map final transcript turns into the same `agent:` flow used by web chat.
- Outbound support calls use `OutboundSipCallRequest`, `OutboundSipCallResult`, and `SipProviderClient` from core.
- Provider-specific placement logic lives in adapter modules such as `camel-agent-twilio`.

This keeps call control provider-specific while the conversation, audit, and route orchestration model stays uniform.

## Build And Run

### Repository Build

```bash
mvn -q test
mvn clean install
```

### Sample Build And Run

```bash
mvn -f samples/agent-support-service/pom.xml -DskipTests compile exec:java
```

Or use the sample launcher:

```bash
samples/agent-support-service/run-sample.sh
```

### Local DScope Bootstrap

If you are developing against local DScope artifacts:

```bash
./scripts/bootstrap-local-dscope-deps.sh
```

## Configuration Reference

This section consolidates the settings surfaced by the component API, Spring starter, runtime bootstrap, persistence integration, AGUI, and realtime support.

### 1. Camel `agent:` Endpoint Options

These are the URI options accepted by the Camel component endpoint.

Example:

```text
agent:support?blueprint=classpath:agents/support/agent.md&plansConfig=classpath:agents/agents.yaml&persistenceMode=redis_jdbc&strictSchema=true&timeoutMs=30000&streaming=true
```

| Option | Default | Meaning |
| --- | --- | --- |
| `blueprint` | `classpath:agents/support/agent.md` | Blueprint location. Supports `classpath:`, `file:`, plain file path, or `http(s)` location. |
| `plansConfig` | unset | Optional agent catalog file. When set, plan selection can resolve the active blueprint dynamically. |
| `persistenceMode` | `redis_jdbc` | Persistence backend mode. Supported values documented in this repo are `redis`, `jdbc`, `redis_jdbc`. |
| `strictSchema` | `true` | Enables strict schema validation for tool input and output. |
| `timeoutMs` | `30000` | Tool execution timeout in milliseconds. |
| `streaming` | `true` | Enables streaming token or event delivery semantics where supported. |

### 2. Spring Starter Properties

These are the `agent.*` properties bound by `camel-agent-starter`.

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.blueprint` | `classpath:agents/support/agent.md` | Default blueprint location. |
| `agent.agents-config` | unset | Optional plan catalog file. |
| `agent.persistence-mode` | `redis_jdbc` | Persistence mode passed to DScope persistence factory. |
| `agent.strict-schema` | `true` | Strict tool schema validation. |
| `agent.timeout-ms` | `30000` | Tool timeout in milliseconds. |
| `agent.streaming` | `true` | Streaming enablement for endpoint behavior. |
| `agent.audit-granularity` | `info` | Audit verbosity: `none`, `info`, `error`, `debug`. |
| `agent.audit-persistence-backend` | unset | Optional dedicated audit backend override. |
| `agent.audit-jdbc-url` | unset | Optional dedicated audit JDBC URL. |
| `agent.audit-jdbc-username` | unset | Optional dedicated audit JDBC username. |
| `agent.audit-jdbc-password` | unset | Optional dedicated audit JDBC password. |
| `agent.audit-jdbc-driver-class-name` | unset | Optional dedicated audit JDBC driver class. |
| `agent.chat-memory-enabled` | `true` | Enables Spring AI chat memory repository auto-configuration when Spring AI chat memory classes are present. |
| `agent.chat-memory-window` | `100` | Maximum message window for `MessageWindowChatMemory`. |
| `agent.task-claim-owner-id` | generated node id | Optional node identifier for distributed task lease claims. |
| `agent.task-claim-lease-seconds` | `120` | Lease duration for distributed task ownership. |

Important integration note:

- The starter auto-configures `AgentKernel`, `PersistenceFacade`, `BlueprintLoader`, and optional chat memory.
- The starter's default `AiModelClient` uses `NoopSpringAiChatGateway`.
- For live LLM calls in a Spring app, provide your own `AiModelClient` bean or a replacement bean strategy that uses a real gateway such as `MultiProviderSpringAiChatGateway`.

### 3. Runtime Bootstrap Properties

These settings are used by the Camel Main runtime bootstrap and sample-style application runtime.

#### Core Runtime

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.runtime.routes-include-pattern` | unset | Route include pattern passed to Camel Main. Used heavily by the sample to load selected route files. |
| `agent.runtime.kamelets-include-pattern` | unset | Kamelet include pattern for runtime resource bootstrap. Camel-case alias also supported. |
| `agent.runtime.agent-routes-enabled` | `true` | Enables the built-in sample route builder that invokes the agent once on startup and can expose audit API endpoints. |
| `agent.sample.prompt` | `Find docs about persistence` | Initial prompt used by the runtime sample route builder. |
| `agent.audit.trail.limit` | `200` | Limit used by the sample runtime audit loader. |
| `agent.audit.api.enabled` | `false` | Enables the sample audit HTTP endpoint. |
| `agent.audit.api.host` | `0.0.0.0` | Host for the sample audit HTTP endpoint. |
| `agent.audit.api.port` | `8080` | Port for the sample audit HTTP endpoint. |

#### A2A Runtime

These settings are resolved by `A2ARuntimeProperties` and drive the built-in A2A protocol bridge.

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.runtime.a2a.enabled` | `false` | Enables inbound A2A RPC/SSE endpoints and agent-card publication. |
| `agent.runtime.a2a.host` | `0.0.0.0` | Bind host for A2A HTTP routes. Alias `agent.runtime.a2a.bind-host` is also supported. |
| `agent.runtime.a2a.port` | `8080` | Bind port for A2A HTTP routes. If unset, runtime falls back to `agent.audit.api.port` and then `agui.rpc.port`. |
| `agent.runtime.a2a.public-base-url` | `http://localhost:<port>` | Public base URL used to construct RPC, SSE, and agent-card URLs. |
| `agent.runtime.a2a.rpc-path` | `/a2a/rpc` | HTTP path for JSON-RPC A2A requests. |
| `agent.runtime.a2a.sse-path` | `/a2a/sse` | Base HTTP path for A2A SSE streams. |
| `agent.runtime.a2a.agent-card-path` | `/.well-known/agent-card.json` | Agent-card discovery path. |
| `agent.runtime.a2a.agent-endpoint-uri` | derived from `agent.agents-config` and `agent.blueprint` | Internal `agent:` endpoint invoked by the A2A bridge when no explicit override is supplied. |
| `agent.runtime.a2a.exposed-agents-config` | unset | YAML file that maps public A2A agent ids to local plans and versions. |

Sample baseline:

```yaml
agent:
  runtime:
    a2a:
      enabled: true
      public-base-url: http://localhost:{{agui.rpc.port:8080}}
      exposed-agents-config: classpath:agents/a2a-exposed-agents.yaml
```

The sample publishes these endpoints on the AGUI/admin port:

- `POST /a2a/rpc`
- `GET /a2a/sse/{taskId}`
- `GET /.well-known/agent-card.json`

#### AI Client Bootstrap

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.runtime.ai.mode` | unset | Auto-bind mode for runtime bootstrap. Supported in this repo: `spring-ai`, `realtime`, or unset. |
| `agent.runtime.spring-ai.gateway-class` | unset | Optional fully qualified gateway class name instantiated by runtime bootstrap. |

`agent.runtime.ai.mode=spring-ai` tells runtime bootstrap to bind a `SpringAiModelClient` if the Spring AI classes are available.

`agent.runtime.ai.mode=realtime` binds a `StaticAiModelClient` intended for realtime relay-oriented setups where model behavior is handled elsewhere.

#### Spring AI Provider Properties

These are the provider-facing properties demonstrated by the sample runtime and consumed by `MultiProviderSpringAiChatGateway`.

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.runtime.spring-ai.provider` | `openai` | Active provider. Supported values in code are `openai`, `gemini`, `claude` or `anthropic`. |
| `agent.runtime.spring-ai.model` | `gpt-5.4` in the sample | Global default model if a provider-specific model is not set. |
| `agent.runtime.spring-ai.temperature` | `0.2` | Default sampling temperature. |
| `agent.runtime.spring-ai.max-tokens` | `800` | Default max output tokens. |
| `agent.runtime.spring-ai.openai.api-mode` | `chat` | OpenAI mode. Supported values in code: `chat`, `responses-http` or `responses-ws`. `responses-http` currently returns terminal guidance instead of executing. |
| `agent.runtime.spring-ai.openai.base-url` | `https://api.openai.com` | OpenAI base URL. |
| `agent.runtime.spring-ai.openai.api-key` | unset | Direct OpenAI API key property. |
| `agent.runtime.spring-ai.openai.api-key-system-property` | `openai.api.key` | System property name checked for the key. |
| `agent.runtime.spring-ai.openai.organization-system-property` | `openai.organization` | Optional OpenAI organization system property name. |
| `agent.runtime.spring-ai.openai.project-system-property` | `openai.project` | Optional OpenAI project system property name. |
| `agent.runtime.spring-ai.openai.model` | `gpt-5.4` in the sample | Provider-specific OpenAI model override. |
| `agent.runtime.spring-ai.openai.responses-ws.endpoint-uri` | `wss://api.openai.com/v1/responses` in the sample | WebSocket endpoint for the responses gateway path. |
| `agent.runtime.spring-ai.openai.responses-ws.model` | `gpt-5.4` in the sample | Model name for responses WebSocket mode. |
| `agent.runtime.spring-ai.openai.responses-ws.request-timeout-ms` | `30000` | Request timeout for responses WebSocket mode. |
| `agent.runtime.spring-ai.openai.responses-ws.poll-interval-ms` | `50` | Poll interval for responses WebSocket mode. |
| `agent.runtime.spring-ai.openai.responses-ws.max-send-retries` | `3` | Send retry limit for responses WebSocket mode. |
| `agent.runtime.spring-ai.openai.responses-ws.max-reconnects` | `8` | Reconnect limit for responses WebSocket mode. |
| `agent.runtime.spring-ai.openai.responses-ws.initial-backoff-ms` | `150` | Initial reconnect backoff. |
| `agent.runtime.spring-ai.openai.responses-ws.max-backoff-ms` | `2000` | Maximum reconnect backoff. |
| `agent.runtime.spring-ai.gemini.model` | `gemini-2.5-flash` | Gemini model override. |
| `agent.runtime.spring-ai.gemini.vertex.project-id` | unset | Required Vertex project id for Gemini. |
| `agent.runtime.spring-ai.gemini.vertex.location` | `us-central1` | Vertex location for Gemini. |
| `agent.runtime.spring-ai.claude.base-url` | `https://api.anthropic.com/v1/messages` in the sample | Claude base URL. |
| `agent.runtime.spring-ai.claude.model` | `claude-3-5-sonnet-20241022` | Claude model override. |
| `agent.runtime.spring-ai.claude.api-key-system-property` | `anthropic.api.key` | System property name checked for Anthropic key. |
| `agent.runtime.spring-ai.claude.anthropic-version` | `2023-06-01` | Anthropic API version header value. |

#### Audit Persistence And Archive

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.audit.granularity` | `debug` in runtime bootstrap, `info` in starter | Audit payload verbosity. Runtime bootstrap seeds `RuntimeControlState` from this value. |
| `agent.audit.backend` | unset | Optional dedicated audit persistence backend. |
| `agent.audit.jdbc.url` | unset | Optional dedicated audit JDBC URL. |
| `agent.audit.jdbc.username` | unset | Optional dedicated audit JDBC username. |
| `agent.audit.jdbc.password` | unset | Optional dedicated audit JDBC password. |
| `agent.audit.jdbc.driver-class-name` | unset | Optional dedicated audit JDBC driver class. |
| `agent.audit.async.enabled` | `false` | Enables async audit and archive persistence wrapper. |
| `agent.audit.async.queue-capacity` | `4096` | Async audit queue size. |
| `agent.audit.async.retry-delay-ms` | `250` | Retry delay for async audit persistence. |
| `agent.audit.async.shutdown-timeout-ms` | `5000` | Shutdown wait for async audit flush. |
| `agent.audit.async.metrics-log-interval-ms` | `30000` | Metrics log interval for async audit wrapper. |
| `agent.conversation.persistence.enabled` | `false` | Enables separate transcript-focused conversation archive persistence. |
| `agent.conversation.persistence.*` | none | Any key under this prefix is remapped to `camel.persistence.*` for the archive store, except `enabled`. |

Examples for archive persistence:

```yaml
agent:
  conversation:
    persistence:
      enabled: true
      backend: jdbc
      jdbc:
        url: jdbc:postgresql://localhost:5432/agent_archive
        username: archive_user
        password: ${AGENT_ARCHIVE_DB_PASSWORD}
        driver-class-name: org.postgresql.Driver
```

#### AGUI Binding And Pre-Run

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.runtime.agui.bind-default-beans` | `true` | Auto-binds AGUI support beans if AGUI classes are on the classpath. Camel-case alias supported. |
| `agent.runtime.agui.bind-pre-run-processor` | `true` | Auto-binds the AGUI pre-run processor if missing. Camel-case alias supported. |
| `agent.runtime.agui.pre-run.agent-endpoint-uri` | `agent:default?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}` or runtime override | Agent endpoint called before fallback logic. Camel-case alias supported. |
| `agent.runtime.agui.pre-run.fallback-enabled` | implementation default | Enables deterministic fallback routing. Camel-case alias supported. |
| `agent.runtime.agui.pre-run.fallback.kb-tool-name` | unset | Tool name used for KB fallback resolution. Camel-case alias supported. |
| `agent.runtime.agui.pre-run.fallback.ticket-tool-name` | unset | Tool name used for ticket fallback resolution. Camel-case alias supported. |
| `agent.runtime.agui.pre-run.fallback.kb-uri` | unset | Explicit KB URI override. Camel-case alias supported. |
| `agent.runtime.agui.pre-run.fallback.ticket-uri` | unset | Explicit ticket URI override. Camel-case alias supported. |
| `agent.runtime.agui.pre-run.fallback.ticket-keywords` | unset | Comma-separated or array list of ticket-routing keywords. Camel-case alias supported. |
| `agent.runtime.agui.pre-run.fallback.error-markers` | unset | Markers used to detect auth or API failures and trigger fallback. Camel-case alias supported. |

AGUI pre-run precedence is:

1. Blueprint `aguiPreRun`
2. Runtime properties under `agent.runtime.agui.pre-run.*`
3. Alias properties under `agent.agui.pre-run.*`
4. Processor defaults

#### Realtime Properties

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.runtime.realtime.bind-relay` | `true` | Auto-binds `openAiRealtimeRelayClient` if missing. Camel-case alias supported. |
| `agent.runtime.realtime.bind-processor` | `true` | Auto-binds the realtime event processor. Camel-case alias supported. |
| `agent.runtime.realtime.processor-bean-name` | `supportRealtimeEventProcessor` | Bean name for the realtime event processor. Camel-case alias supported. |
| `agent.runtime.realtime.agent-endpoint-uri` | `agent:default?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}` | Agent endpoint used by realtime processors. Alias under `agent.realtime.*` is also supported. |
| `agent.runtime.realtime.bind-token-processor` | `true` | Auto-binds browser token and init processor support. Camel-case alias supported. |
| `agent.runtime.realtime.token-processor-bean-name` | `supportRealtimeTokenProcessor` | Bean name for the browser token processor. Camel-case alias supported. |
| `agent.runtime.realtime.init-processor-bean-name` | `supportRealtimeSessionInitProcessor` | Bean name for the browser session init processor. Camel-case alias supported. |
| `agent.runtime.realtime.browser-session-ttl-ms` | `600000` | In-memory TTL for browser session state. Camel-case alias supported. |
| `agent.runtime.realtime.prefer-core-token-processor` | `false` | Prefers the core token processor path. If true and `require-init-session` is unset, runtime bootstrap forces it to `true`. Camel-case alias supported. |
| `agent.runtime.realtime.require-init-session` | unset unless forced | If true, token minting expects a prior browser session init call. Camel-case alias supported. |
| `agent.runtime.realtime.provider` | `openai` | Realtime provider name. Alias `agent.realtime.provider` supported by config resolver. |
| `agent.runtime.realtime.model` | unset | Realtime model name. Alias `agent.realtime.model` supported. |
| `agent.runtime.realtime.language` | unset | Language hint for realtime session init or token responses. |
| `agent.runtime.realtime.voice` | unset | Voice id for realtime generation. |
| `agent.runtime.realtime.transport` | `server-relay` | Transport mode for realtime config resolution. |
| `agent.runtime.realtime.endpoint-uri` | unset | Realtime endpoint URI. Camel-case alias supported. |
| `agent.runtime.realtime.input-audio-format` | unset | Realtime input audio format. Camel-case alias supported. |
| `agent.runtime.realtime.output-audio-format` | unset | Realtime output audio format. Camel-case alias supported. |
| `agent.runtime.realtime.retention-policy` | `metadata_transcript` | Retention policy string. Camel-case alias supported. |
| `agent.runtime.realtime.reconnect.max-send-retries` | unset or blueprint value | Send retry limit. Camel-case alias supported. |
| `agent.runtime.realtime.reconnect.max-reconnects` | unset or blueprint value | Reconnect limit. Camel-case alias supported. |
| `agent.runtime.realtime.reconnect.initial-backoff-ms` | unset or blueprint value | Initial reconnect backoff. Camel-case alias supported. |
| `agent.runtime.realtime.reconnect.max-backoff-ms` | unset or blueprint value | Maximum reconnect backoff. Camel-case alias supported. |
| `agent.runtime.realtime.agent-profile-purpose-max-chars` | bounded in core, sample uses `0` | Max chars retained when seeding browser instruction context from blueprint purpose text. Camel-case alias and `agent.realtime.*` aliases are supported in the codebase. |

#### SIP Processor Binding

| Property | Default | Meaning |
| --- | --- | --- |
| `agent.runtime.sip.bind-processors` | `false` | Enables SIP processor auto-binding. Camel-case alias supported. |
| `agent.runtime.sip.init-envelope-processor-bean-name` | `supportSipSessionInitEnvelopeProcessor` | Bean name for SIP init processor. Camel-case alias supported. |
| `agent.runtime.sip.transcript-final-processor-bean-name` | `supportSipTranscriptFinalProcessor` | Bean name for transcript-final SIP processor. Camel-case alias supported. |
| `agent.runtime.sip.call-end-processor-bean-name` | `supportSipCallEndProcessor` | Bean name for SIP call-end processor. Camel-case alias supported. |

### 4. Plan Catalog Options

Plan catalogs are YAML files referenced by `agent.agents-config` or `plansConfig`.

Example:

```yaml
plans:
  - name: support
    default: true
    versions:
      - version: v1
        default: true
        blueprint: classpath:agents/support/v1/agent.md
      - version: v2
        blueprint: classpath:agents/support/v2/agent.md
```

| Field | Required | Meaning |
| --- | --- | --- |
| `plans[].name` | yes | Logical plan name such as `support` or `billing`. |
| `plans[].default` | no | Marks the default plan. Exactly one plan should be the default in a production catalog. |
| `plans[].versions[].version` | yes | Version identifier such as `v1` or `2026-03`. |
| `plans[].versions[].default` | no | Marks the default version for that plan. |
| `plans[].versions[].blueprint` | yes | Concrete blueprint path for the plan version. |

### 5. Request Headers And Exchange Contract

The `agent:` endpoint uses headers for conversation and plan selection.

| Header | Direction | Meaning |
| --- | --- | --- |
| `agent.conversationId` | request and response | Stable conversation id. If omitted, the producer generates one. |
| `agent.planName` | request | Requested plan name. |
| `agent.planVersion` | request | Requested plan version. |
| `agent.resolvedPlanName` | response | Plan resolved by runtime for non-legacy plan mode. |
| `agent.resolvedPlanVersion` | response | Version resolved by runtime for non-legacy plan mode. |
| `agent.resolvedBlueprint` | response | Blueprint URI that actually handled the request. |

AGUI and realtime flows additionally bind correlation keys such as session, run, and thread identifiers to the conversation id for audit visibility.

## Blueprint Authoring And Tool Backends

Blueprints are Markdown files. The loader uses a hybrid contract:

1. `# Agent:` provides the agent name.
2. `Version:` provides the version string.
3. `## System` provides the system instruction until the next `##` heading.
4. Every fenced YAML block marked as `yaml` is parsed and merged into one configuration object.

That means the Markdown prose is useful for humans, but the executable configuration comes from fenced YAML blocks.

### Minimal Blueprint Skeleton

````md
# Agent: SupportAssistant

Version: 0.1.0

## System

You are a support assistant that searches internal knowledge and opens tickets when needed.

## Tools

```yaml
tools:
  - name: kb.search
    description: Search support knowledge
    routeId: kb-search
    inputSchemaInline:
      type: object
      required: [query]
      properties:
        query:
          type: string
```
````

### Blueprint Fields

The current `AgentBlueprint` model supports these top-level concepts:

| Section | Meaning |
| --- | --- |
| `name` | Parsed from `# Agent:` |
| `version` | Parsed from `Version:` |
| `systemInstruction` | Parsed from the `## System` section |
| `tools` | Parsed from YAML blocks |
| `jsonRouteTemplates` | Parsed from YAML blocks |
| `realtime` | Parsed from YAML blocks |
| `aguiPreRun` | Parsed from YAML blocks |
| `resources` | Parsed from YAML blocks and resolved into static context payloads |

### Tool Definitions

Each `tools[]` entry supports the following fields.

| Field | Required | Meaning |
| --- | --- | --- |
| `name` | yes | Unique tool name exposed to the model. |
| `description` | recommended | Natural-language description used by the model. |
| `routeId` | no | Camel route id used to derive a `direct:` target when needed. |
| `endpointUri` or `endpoint-uri` | no | Explicit target endpoint URI. This is the most direct execution path. |
| `kameletUri` or `kamelet-uri` | no | Direct Kamelet URI. If it does not start with `kamelet:`, the loader adds the prefix. |
| `kamelet` | no | Structured Kamelet definition with `templateId`, `action`, and `parameters`. |
| `inputSchemaInline` | no | JSON Schema describing tool input. |
| `outputSchemaInline` | no | JSON Schema describing tool output. |
| `policy.pii.redact` | no | Redaction hint for sensitive input handling. |
| `policy.rateLimit.perMinute` | no | Tool rate limit hint. |
| `policy.timeoutMs` | no | Tool timeout hint. Default is `30000`. |

Authoring rules:

- A blueprint must declare at least one tool. The loader rejects blueprints with no tools.
- Prefer `routeId` when the target is a local Camel route and you want clean, readable tool declarations.
- Prefer `endpointUri` when the tool is not a local `direct:` route.
- Use `endpointUri: mcp:...` for MCP service discovery.
- Use `kamelet` when you want the runtime to assemble a Kamelet URI for you.

### Kamelet Tool Example

```yaml
tools:
  - name: support.echo
    description: Prefixes user text for diagnostics
    kamelet:
      templateId: support-echo-sink
      action: sink
      parameters:
        prefix: SupportEcho
    inputSchemaInline:
      type: object
      required: [query]
      properties:
        query:
          type: string
```

### MCP Tool Example

```yaml
tools:
  - name: support.mcp
    description: CRM MCP service seed
    endpointUri: mcp:http://localhost:3001/mcp
    inputSchemaInline:
      type: object
      properties: {}
```

### JSON Route Templates

Each `jsonRouteTemplates[]` entry supports:

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Template identifier. |
| `toolName` | no | Name of the generated callable tool. Defaults to `route.template.<id>`. |
| `description` | no | Tool description shown to the model. |
| `invokeUriParam` | no | Parameter name that identifies the route entrypoint for execution. |
| `parametersSchema` | no | JSON Schema for allowed template parameters. |
| `routeTemplate` | yes | Camel JSON DSL route template body. |

Template-generated tools are automatically added to the tool registry even if they are not listed under `tools`.

### Realtime Blueprint Section

Blueprints may define a top-level `realtime` YAML section. Current fields are:

| Field | Meaning |
| --- | --- |
| `provider` | Realtime provider name. Default is `openai`. |
| `model` | Realtime model. |
| `voice` | Voice identifier. |
| `transport` | Realtime transport. Default is `server-relay`. |
| `endpointUri` | Realtime endpoint URI. |
| `inputAudioFormat` | Input audio format. |
| `outputAudioFormat` | Output audio format. |
| `retentionPolicy` | Retention policy. Default is `metadata_transcript`. |
| `reconnect.maxSendRetries` | Send retry limit. |
| `reconnect.maxReconnects` | Reconnect limit. |
| `reconnect.initialBackoffMs` | Initial backoff. |
| `reconnect.maxBackoffMs` | Maximum backoff. |

If the blueprint omits realtime fields, runtime properties can supply them.

### AGUI Pre-Run Blueprint Section

Blueprints may define `aguiPreRun` or nested `agui.preRun`.

| Field | Meaning |
| --- | --- |
| `agentEndpointUri` | Agent endpoint called before fallback routing. |
| `fallbackEnabled` | Enables fallback behavior. |
| `fallback.kbToolName` | KB tool name used for fallback routing. |
| `fallback.ticketToolName` | Ticket tool name used for fallback routing. |
| `fallback.kbUri` | Explicit KB URI override. |
| `fallback.ticketUri` | Explicit ticket URI override. |
| `fallback.ticketKeywords` | Keywords that indicate ticket intent. |
| `fallback.errorMarkers` | Markers that indicate auth or provider failure. |

### Blueprint Resources Section

Blueprints may define a top-level `resources` YAML section.

| Field | Meaning |
| --- | --- |
| `name` | Logical resource name used for diagnostics and rendering labels. |
| `uri` | Resource location. Supports `classpath:`, `file:`, plain paths, and `http(s)` URLs. |
| `kind` | Resource kind such as `document`. |
| `format` | Format hint such as `markdown`, `text`, or `pdf`. |
| `includeIn` | Target surfaces such as `chat`, `realtime`, or both. |
| `loadPolicy` | Current startup-oriented loading policy used by runtime resolution. |
| `optional` | Whether missing resource resolution is tolerated. |
| `maxBytes` | Maximum raw payload size accepted before truncation or rejection. |

Behavior:

- Markdown/text resources are injected directly as text.
- PDF resources are resolved through Camel PDF extraction.
- Chat instruction rendering uses `agent.runtime.chat.resource-context-max-chars`.
- Realtime session seed rendering uses `agent.runtime.realtime.resource-context-max-chars`.
- Runtime refresh re-resolves resources and can append plan-aware refresh events to active conversations.

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

### Runtime Placeholder Syntax

Execution-facing blueprint fields may use runtime placeholders so environment-specific values and secrets stay out of the literal blueprint text.

Supported forms:

- Camel property placeholders: `{{key}}`
- Camel property placeholders with defaults: `{{key:defaultValue}}`
- environment or JVM system placeholders: `${NAME}`
- environment or JVM system placeholders with defaults: `${NAME:defaultValue}`

Examples:

```yaml
tools:
  - name: crm.lookup
    endpointUri: mcp:https://{{agent.crm.host}}/mcp?token=${CRM_TOKEN}

aguiPreRun:
  agentEndpointUri: {{agent.runtime.agui.agent-endpoint-uri}}

realtime:
  endpointUri: ${REALTIME_WS_URL:wss://api.openai.com/v1/realtime}
```

Resolution behavior:

- resolution happens at runtime, not during static blueprint authoring
- this keeps concrete execution targets and secret values out of the model-facing instruction surface
- unresolved placeholders are preserved for ordinary string resolution helpers but execution-target fields now fail fast

Fail-fast execution-target fields:

- `tools[].routeId`
- `tools[].endpointUri`
- `aguiPreRun.agentEndpointUri`
- `aguiPreRun.fallback.kbUri`
- `aguiPreRun.fallback.ticketUri`
- `realtime.endpointUri`
- structured session endpoint overrides such as `agent.session.endpointUri`

If one of those fields still contains `{{...}}` or `${...}` after runtime resolution, execution stops with an `IllegalArgumentException` naming the unresolved field.

### Route-Driven Session Invocation

Raw `agent:` endpoint usage remains valid, but it returns assistant text only.

For structured route integration, core now provides:

- `AgentSessionRequest`
- `AgentSessionResponse`
- `AgentSessionService`
- `AgentSessionInvokeProcessor`

Canonical request payload:

```json
{
  "prompt": "Please call the customer back",
  "conversationId": "optional-existing-conversation",
  "planName": "support",
  "planVersion": "v2",
  "params": {
    "customerId": "123",
    "channel": "sip"
  }
}
```

Canonical response payload:

```json
{
  "conversationId": "effective-session-id",
  "sessionId": "effective-session-id",
  "created": true,
  "message": "assistant reply",
  "resolvedPlanName": "support",
  "resolvedPlanVersion": "v2",
  "resolvedBlueprint": "classpath:agents/support/v2/agent.md",
  "events": [],
  "taskState": null,
  "params": {
    "customerId": "123",
    "channel": "sip"
  }
}
```

Normalization rules:

- `conversationId` is the durable backend key.
- `sessionId` and `threadId` are accepted as alternate ingress identifiers and normalized to the same conversation when needed.
- request params are preserved as exchange properties under `agent.session.params` for route-side consumers.

### Blueprint Authoring Recommendations

1. Keep `## System` focused on role, routing rules, safety constraints, and escalation behavior.
2. Make tool descriptions operational, not marketing-oriented. The model needs concrete tool-selection clues.
3. Put strong JSON schemas on tool inputs whenever possible. This materially improves tool reliability.
4. Use plan catalogs for versioning instead of editing a single production blueprint in place.
5. Keep realtime and AGUI metadata in the blueprint only when it is truly agent-specific. Put environment-specific details in application config.

## Bootstrapping Into A Spring Application

This product can be embedded in a Spring application, but there is one important distinction:

- `camel-agent-starter` auto-configures the kernel, persistence, blueprint loader, and optional chat memory.
- It does not auto-configure a live provider gateway for you.
- If you want real model execution, you must provide an `AiModelClient` bean or equivalent override.

### Step 1. Add Dependencies

At minimum, add the Camel Agent starter and your normal Spring Boot and Camel dependencies.

```xml
<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
  </dependency>

  <dependency>
    <groupId>org.apache.camel.springboot</groupId>
    <artifactId>camel-spring-boot-starter</artifactId>
    <version>4.15.0</version>
  </dependency>

  <dependency>
    <groupId>io.dscope.camel</groupId>
    <artifactId>camel-agent-starter</artifactId>
    <version>0.5.0</version>
  </dependency>
</dependencies>
```

If you are using snapshot development from this repository, install the repo artifacts first with `mvn clean install` and use the repo version you built.

### Step 2. Add A Blueprint

Create a blueprint in your Spring app resources, for example:

`src/main/resources/agents/support/agent.md`

Use the blueprint pattern from the previous section.

### Step 3. Configure Starter Properties

Minimal example:

```yaml
agent:
  blueprint: classpath:agents/support/agent.md
  persistence-mode: redis_jdbc
  strict-schema: true
  timeout-ms: 30000
  streaming: true
  audit-granularity: info
  chat-memory-enabled: true
  chat-memory-window: 100
```

Optional plan catalog:

```yaml
agent:
  agents-config: classpath:agents/agents.yaml
  blueprint: classpath:agents/support/v1/agent.md
```

### Step 4. Provide A Real `AiModelClient`

The simplest production path is to define an `AiModelClient` bean using `SpringAiModelClient` and `MultiProviderSpringAiChatGateway`.

```java
package com.example.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dscope.camel.agent.api.AiModelClient;
import io.dscope.camel.agent.springai.MultiProviderSpringAiChatGateway;
import io.dscope.camel.agent.springai.SpringAiModelClient;
import java.util.Properties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class AgentModelConfiguration {

    @Bean
    AiModelClient aiModelClient(ObjectMapper objectMapper, Environment environment) {
        Properties properties = new Properties();
        copy(environment, properties, "agent.runtime.spring-ai.provider", "openai");
        copy(environment, properties, "agent.runtime.spring-ai.model", "gpt-5.4");
        copy(environment, properties, "agent.runtime.spring-ai.temperature", "0.2");
        copy(environment, properties, "agent.runtime.spring-ai.max-tokens", "800");
        copy(environment, properties, "agent.runtime.spring-ai.openai.api-mode", "chat");
        copy(environment, properties, "agent.runtime.spring-ai.openai.base-url", "https://api.openai.com");
        copy(environment, properties, "agent.runtime.spring-ai.openai.api-key-system-property", "openai.api.key");
        copy(environment, properties, "agent.runtime.spring-ai.openai.organization-system-property", "openai.organization");
        copy(environment, properties, "agent.runtime.spring-ai.openai.project-system-property", "openai.project");
        copy(environment, properties, "agent.runtime.spring-ai.openai.responses-ws.endpoint-uri", "wss://api.openai.com/v1/responses");
        copy(environment, properties, "agent.runtime.spring-ai.openai.responses-ws.model", "gpt-5.4");
        copy(environment, properties, "agent.runtime.spring-ai.gemini.vertex.project-id", null);
        copy(environment, properties, "agent.runtime.spring-ai.gemini.vertex.location", "us-central1");
        copy(environment, properties, "agent.runtime.spring-ai.claude.base-url", "https://api.anthropic.com/v1/messages");
        copy(environment, properties, "agent.runtime.spring-ai.claude.api-key-system-property", "anthropic.api.key");
        copy(environment, properties, "agent.runtime.spring-ai.claude.anthropic-version", "2023-06-01");
        return new SpringAiModelClient(new MultiProviderSpringAiChatGateway(properties), objectMapper);
    }

    private static void copy(Environment environment, Properties properties, String key, String defaultValue) {
        String value = defaultValue == null ? environment.getProperty(key) : environment.getProperty(key, defaultValue);
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value);
        }
    }
}
```

Credential handling:

- For OpenAI, set `-Dopenai.api.key=...` or `OPENAI_API_KEY=...`.
- For Claude, set `-Danthropic.api.key=...` or `ANTHROPIC_API_KEY=...`.
- For Gemini, set `agent.runtime.spring-ai.gemini.vertex.project-id` and ensure Google credentials are available.

If the Spring application also needs to publish A2A endpoints, externalize the A2A bridge settings alongside the starter properties:

```yaml
agent:
  runtime:
    a2a:
      enabled: true
      public-base-url: https://support.example.com
      exposed-agents-config: classpath:agents/a2a-exposed-agents.yaml
```

`a2a-exposed-agents.yaml` stays separate from `agents.yaml` so you can keep internal plan catalog structure independent from the public A2A surface.

### Step 5. Call The Agent From Camel Routes

```java
package com.example.demo;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class SupportAgentRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("direct:support-request")
            .setHeader("agent.conversationId", simple("support-${header.customerId}"))
            .to("agent:support?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}")
            .log("Agent response: ${body}");
    }
}
```

If you do not use plan catalogs, you can omit `plansConfig` from the endpoint URI.

### Step 6. Expose A REST Endpoint If Needed

```java
package com.example.demo;

import org.apache.camel.ProducerTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupportController {

    private final ProducerTemplate producerTemplate;

    public SupportController(ProducerTemplate producerTemplate) {
        this.producerTemplate = producerTemplate;
    }

    @PostMapping("/api/support")
    public String ask(@RequestHeader("X-Conversation-Id") String conversationId,
                      @RequestBody String prompt) {
        return producerTemplate.requestBodyAndHeader(
            "direct:support-request",
            prompt,
            "agent.conversationId",
            conversationId,
            String.class
        );
    }
}
```

### Spring Bootstrap Checklist

1. Add `camel-agent-starter` plus your normal Spring Boot and Camel dependencies.
2. Add a blueprint under `src/main/resources`.
3. Configure `agent.*` starter properties.
4. Provide a real `AiModelClient` bean.
5. Invoke the `agent:` endpoint from a Camel route.
6. Pass `agent.conversationId` on every request that should share memory and audit history.
7. If you use plans, pass `agent.planName` and `agent.planVersion` when you need explicit version selection.

## Sample Configuration Profiles

### Minimal Local Development

Use this when you want a simple single-agent app with local persistence and OpenAI chat mode.

```yaml
camel:
  persistence:
    enabled: true
    backend: jdbc
    jdbc:
      url: jdbc:derby:memory:agent;create=true

agent:
  blueprint: classpath:agents/support/agent.md
  persistence-mode: jdbc
  audit-granularity: debug

spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
```

### Multi-Plan Routing

Use this when you have multiple plans and want default selection with optional per-request overrides.

```yaml
agent:
  agents-config: classpath:agents/agents.yaml
  blueprint: classpath:agents/support/v1/agent.md
```

Then set request headers when needed:

```text
agent.planName: billing
agent.planVersion: v2
```

### Dedicated Audit Database

Use this when operational audit data should be separated from agent context or chat state.

```yaml
agent:
  audit-granularity: debug
  audit-persistence-backend: jdbc
  audit-jdbc-url: jdbc:postgresql://localhost:5432/agent_audit
  audit-jdbc-username: agent_audit_user
  audit-jdbc-password: ${AGENT_AUDIT_DB_PASSWORD}
  audit-jdbc-driver-class-name: org.postgresql.Driver
```

### AGUI Fallback Setup

Use this when your UI should keep working even when model credentials are absent.

```yaml
agent:
  runtime:
    agui:
      pre-run:
        agent-endpoint-uri: agent:support?blueprint={{agent.blueprint}}
        fallback-enabled: true
        fallback:
          kb-tool-name: kb.search
          ticket-tool-name: support.ticket.manage
          ticket-uri: direct:support-ticket-manage
          ticket-keywords: ticket,open,create,update,close,status,submit,escalate
          error-markers: api key is missing,openai api key,set -dopenai.api.key
```

### Realtime Browser Session Setup

Use this when voice or WebRTC-style browser sessions need seeded context and token support.

```yaml
agent:
  runtime:
    realtime:
      processor-bean-name: supportRealtimeEventProcessorCore
      token-processor-bean-name: supportRealtimeTokenProcessor
      init-processor-bean-name: supportRealtimeSessionInitProcessor
      agent-endpoint-uri: agent:support?blueprint={{agent.blueprint}}
      browser-session-ttl-ms: 600000
      prefer-core-token-processor: true
      require-init-session: true
      provider: openai
      model: gpt-4o-realtime-preview
      language: en
      voice: alloy
      transport: server-relay
      endpoint-uri: wss://api.openai.com/v1/realtime
      input-audio-format: pcm16
      output-audio-format: pcm16
      retention-policy: metadata_transcript
      reconnect:
        max-send-retries: 3
        max-reconnects: 8
        initial-backoff-ms: 150
        max-backoff-ms: 2000
```

## Validation And Troubleshooting

### Quick Validation

```bash
mvn -q test
mvn clean install
mvn -f samples/agent-support-service/pom.xml -DskipTests compile exec:java
```

### Connectivity Checks

```bash
nc -vz api.openai.com 443
curl -I https://api.openai.com/v1/models
curl https://api.openai.com/v1/models -H "Authorization: Bearer $OPENAI_API_KEY"
```

### Common Failure Modes

| Symptom | Likely Cause | Fix |
| --- | --- | --- |
| Agent returns guidance instead of model output | Spring starter is using the default noop gateway | Provide a real `AiModelClient` bean. |
| `401` from OpenAI | Missing or invalid key | Set `OPENAI_API_KEY` or `-Dopenai.api.key`. |
| `421` or OpenAI welcome body | Endpoint mismatch | Check base URL and completions path configuration. |
| Blueprint fails to load | Missing classpath resource or malformed fenced YAML | Verify the blueprint path and YAML blocks. |
| No tools available | Blueprint did not declare tools | Add at least one valid `tools[]` entry. |
| Realtime token flow fails unexpectedly | `prefer-core-token-processor=true` but init was skipped | Call the init endpoint first or disable strict init requirement. |
| AGUI fallback does not trigger | Error markers or tool metadata do not match your environment | Align `error-markers`, tool names, and fallback URIs. |

## Recommended Adoption Path

1. Start with a single blueprint and one or two local tools.
2. Add schemas to every tool.
3. Move to a plan catalog once you need versioning.
4. Split audit persistence if operators and runtime data should be separated.
5. Add AGUI or realtime only after the text flow is stable.
6. Introduce MCP or JSON route templates only when local static routes are no longer sufficient.

## Related Repository Docs

- `README.md`
- `docs/architecture.md`
- `samples/agent-support-service/README.md`
- `docs/TEST_PLAN.md`
- `docs/CHANGELOG.md`