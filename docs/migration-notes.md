# Migration Notes

## 0.7.0

Release promotion highlights:

- Project modules, sample service, and standalone AGUI adapter now build as `0.7.0`.
- Runtime dependencies align with Camel `4.20.0`, Spring Boot `4.0.5`, Spring Framework `7.0.7`, and Spring AI `1.1.4`.
- Clean rebuild and full test run completed before local Maven install.
- Artifacts installed to local Maven for downstream projects.

Dependency import baseline for consumers:

- `io.dscope.camel:camel-agent-starter:0.7.0` (recommended)
- `io.dscope.camel:camel-agent-core:0.7.0` (runtime/core)
- `io.dscope.camel:camel-agent-persistence-dscope:0.7.0` (optional persistence adapter)
- `io.dscope.camel:camel-agent-agui:0.7.0` (optional standalone AGUI adapter)

DScope component baselines:

- `io.dscope.camel:camel-persistence-core:1.3.0`
- `io.dscope.camel:camel-persistence-redis:1.3.0`
- `io.dscope.camel:camel-persistence-jdbc:1.3.0`
- `io.dscope.camel:camel-persistence-ic4j:1.3.0`
- `io.dscope.camel:camel-a2a-component:1.2.0`
- `io.dscope.camel:camel-ag-ui-component:1.3.0`

Behavioral notes:

- JSON route template invoke URI validation now runs before dynamic route registration.
- Blueprint HTTP resource resolution continues to reject private, loopback, link-local, wildcard, and multicast targets.

Persistence adapter notes:

- `DscopePersistenceFactory` now delegates flow-state store creation to Camel Persistence `FlowStateStoreFactory` for both primary and optional audit stores.
- Dedicated audit override settings are normalized into `camel.persistence.*` keys before store creation.
- The legacy agent-side `ScriptedJdbcFlowStateStore` helper was removed as unused after store selection moved to Camel Persistence.

## 0.5.0

Release promotion highlights:

- Project and sample modules promoted from `0.1.0-SNAPSHOT` to `0.5.0`.
- Clean build + full test run completed before release install.
- Artifacts installed to local Maven for downstream projects.

Dependency import baseline for consumers:

- `io.dscope.camel:camel-agent-starter:0.5.0` (recommended)
- `io.dscope.camel:camel-agent-core:0.5.0` (runtime/core)
- `io.dscope.camel:camel-agent-persistence-dscope:0.5.0` (optional persistence adapter)

Karavan metadata generation update:

- Replaced MCP-based metadata generation with a local project-specific generator for `camel-agent-core`.
- Use `mvn -pl camel-agent-core -Pkaravan-metadata -DskipTests compile exec:java` to generate agent metadata.
- Generated files now live under `camel-agent-core/src/main/resources/karavan/metadata`.

## 0.1.0-SNAPSHOT

Initial bootstrap release:

- Multi-module scaffold aligned with DScope Camel projects.
- `agent:` Camel component skeleton.
- Blueprint parser for `agent.md` with YAML tool block support.
- DScope persistence adapter (`redis_jdbc` default).
- Spring AI adapter module abstraction.
- AGUI-enabled sample frontend using `camel-ag-ui-component` runtime routes/processors.

Recent updates:

- Runtime bootstrap now defaults to `MultiProviderSpringAiChatGateway` when `agent.runtime.ai.mode=spring-ai`.
- Provider execution moved to Spring AI clients for OpenAI, Anthropic, and Vertex Gemini.
- Manual/custom HTTP provider gateway code removed from runtime gateway implementation.
- Sample now uses Camel XML DSL route loading plus YAML route loading via `agent.runtime.routes-include-pattern`.
- Sample secrets loading moved from Java code to shell runner (`samples/agent-support-service/run-sample.sh`).
- Sample frontend flow uses `POST /agui/agent` (single POST request with SSE event stream response) as the primary AGUI transport path.
- Sample voice UI now uses a single dynamic start/stop toggle with responsive mobile icon-only behavior.
- Sample realtime VAD pause profile selector added (`Fast=800ms`, `Normal=1200ms`, `Patient=1800ms`) and applied to relay + WebRTC session config.
- Sample UI now surfaces active pause milliseconds in both pause label and voice status text.
- WebRTC transcript diagnostics panel added with clear-log action.
- Voice output transcript rendering now emits a single final assistant transcript entry (duplicate/delta double-rendering removed, spacing preserved).
