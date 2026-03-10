# Spring Boot Embedded Agent Example Plan

## 1. Goal and Scope

### Goal
- Business objective: deliver a Spring Boot embedded support agent that exposes a REST API for support triage and ticket creation while reusing CamelAIAgentComponent blueprints, persistence, and audit infrastructure.
- Primary users: internal support applications, backend service integrations, and operators validating Spring Boot deployment patterns.
- Success criteria (measurable):
  - a Spring Boot sample application starts locally with `mvn -q -pl samples/agent-support-spring-app -am spring-boot:run`
  - `POST /api/support` invokes the `agent:` endpoint and returns an agent response with a stable `agent.conversationId`
  - local support tools (`kb.search`, `support.ticket.open`) execute through Camel routes declared in the sample application
  - the application uses `camel-agent-starter` plus an explicit live `AiModelClient` bean instead of the starter's noop default
  - integration tests prove Spring context wiring, route invocation, and persistence/audit behavior

### In Scope
- new Spring Boot sample module `samples/agent-support-spring-app`
- blueprint, local tool routes, and application configuration for Spring Boot embedding
- explicit `AiModelClient` bean using `SpringAiModelClient` and `MultiProviderSpringAiChatGateway`
- REST controller plus Camel route bridge to `agent:`
- JDBC-backed local persistence and audit settings suitable for local development
- test coverage for context startup, agent invocation, and tool-routing integration

### Out of Scope
- AGUI browser UI in the first iteration
- realtime voice or WebRTC transport
- MCP-backed external tools in the first iteration
- production deployment manifests for Kubernetes or cloud runtime

## 2. Assumptions

- the sample should demonstrate the canonical Spring Boot embedding path documented in `docs/PRODUCT_GUIDE.md`
- Java 21, Camel 4.15.0, Spring Boot 3.5.11, and Spring AI 1.0.3 remain the current aligned baseline
- provider defaults follow the current sample baseline unless overridden: `agent.runtime.spring-ai.provider=openai`, `agent.runtime.spring-ai.model=gpt-5.4`, `agent.runtime.spring-ai.openai.api-mode=chat`
- local development can use Derby or H2-style in-memory JDBC persistence, while CI can override to Postgres if needed
- operators still want audit visibility even for a backend-only Spring deployment, so audit trail remains enabled

## 3. Architecture Decisions

### Agent Identity
- Agent name/title: `SupportSpringAssistant`
- Blueprint path: `classpath:agents/support/agent.md`
- Versioning approach: single blueprint version `v1` in the first sample, with a follow-up path to `agents.yaml` once multiple plans are needed

### Spring Application Bootstrap
- Deployment style: Spring Boot only for the sample module
- Starter usage: yes, use `camel-agent-starter` for `AgentKernel`, `PersistenceFacade`, `BlueprintLoader`, and optional chat memory wiring
- Spring Boot module or sample target: `samples/agent-support-spring-app/pom.xml`
- Blueprint resource path inside the application: `samples/agent-support-spring-app/src/main/resources/agents/support/agent.md`
- Externalized configuration keys:
  - `agent.blueprint=classpath:agents/support/agent.md`
  - `agent.persistence-mode=jdbc`
  - `agent.audit-granularity=info` locally, `debug` in integration testing
  - `agent.chat-memory-enabled=true`
  - `agent.chat-memory-window=100`
  - `agent.runtime.spring-ai.provider=openai`
  - `agent.runtime.spring-ai.model=gpt-5.4`
  - `agent.runtime.spring-ai.openai.api-mode=chat`
- Live model bean strategy:
  - starter limitation: default `AiModelClient` uses `NoopSpringAiChatGateway`
  - planned override: add `AgentModelConfiguration` that exposes `AiModelClient aiModelClient(ObjectMapper, Environment)` returning `new SpringAiModelClient(new MultiProviderSpringAiChatGateway(properties), objectMapper)`
- Route invocation pattern inside the application:
  - REST controller receives support prompts
  - controller sends requests to `direct:support-request`
  - Camel route sets `agent.conversationId` and forwards to `agent:support?blueprint={{agent.blueprint}}`
- Credentials strategy:
  - OpenAI: `OPENAI_API_KEY` or `-Dopenai.api.key=...`
  - optional future provider support: `ANTHROPIC_API_KEY` and Gemini Vertex properties kept available through the same bean configuration path
- Reference docs:
  - `docs/PRODUCT_GUIDE.md`
  - `docs/skills/agent-implementation-planner/SKILL.md`

### Interaction Model
- Channels: backend-only orchestration with REST entrypoint in v1
- Primary request-response pattern: synchronous HTTP request -> Spring MVC controller -> Camel `direct:` route -> `agent:` endpoint -> response body returned to caller
- Fallback behavior:
  - if live provider credentials are missing, return a clear terminal guidance response in development mode
  - optional v1.1 enhancement: deterministic local fallback route selection similar to AGUI pre-run behavior
- MCP admin transport requirements (Streamable HTTP headers, protocol version): not exposed by the v1 Spring sample itself, but planning and smoke coverage should remain compatible with the existing runtime admin MCP conventions if the sample later adds admin endpoints

### Tooling Design
- Blueprint `## Tools` section format rule (required): use a fenced YAML block with top-level `tools:`
- Tool list and purpose:
  - `kb.search`: local support knowledge lookup backed by a Camel YAML route
  - `support.ticket.open`: deterministic ticket creation stub backed by Camel XML or YAML route
- Route artifacts to add/update:
  - `samples/agent-support-spring-app/src/main/resources/routes/kb-search.camel.yaml`
  - `samples/agent-support-spring-app/src/main/resources/routes/support-ticket.camel.yaml`
  - `samples/agent-support-spring-app/src/main/java/.../SupportAgentRoute.java`
  - `samples/agent-support-spring-app/src/main/java/.../SupportController.java`
- Kamelets required (yes/no + why): no for v1, because the purpose is to demonstrate Spring embedding with simple local routes first
- MCP runtime/admin methods to expose or reuse:
  - no direct MCP tool exposure in v1 sample
  - reuse existing admin MCP method set later if an operator/admin surface is added

### Maven Dependencies
- Dependency strategy: mixed, reuse Camel Agent modules and add only the Spring Boot sample-level app dependencies required for embedding
- Dependency scan commands and findings:
  - `mvn -q -pl camel-agent-core -am dependency:tree`
  - `mvn -q -pl samples/agent-support-service -am dependency:tree`
  - `mvn -q -pl camel-agent-starter -am dependency:tree`
  - finding: `camel-agent-starter` already brings in `camel-agent-core`, `camel-agent-persistence-dscope`, `camel-agent-spring-ai`, Spring Boot auto-configure, and Spring AI model abstractions
  - finding: the sample module still needs Spring Boot runtime app dependencies such as web and Camel Spring Boot starter

| groupId | artifactId | Scope | Version Source | Target Module (pom.xml) | Reason |
|---|---|---|---|---|---|
| io.dscope.camel | camel-agent-starter | compile | parent/module version | samples/agent-support-spring-app/pom.xml | primary Spring Boot integration path for Camel Agent |
| org.apache.camel.springboot | camel-spring-boot-starter | compile | explicit Camel-aligned version | samples/agent-support-spring-app/pom.xml | run Camel routes inside Spring Boot |
| org.springframework.boot | spring-boot-starter-web | compile | explicit Spring Boot version or parent-managed if parent is adopted | samples/agent-support-spring-app/pom.xml | REST controller entrypoint |
| org.springframework.boot | spring-boot-starter-test | test | explicit Spring Boot version or parent-managed if parent is adopted | samples/agent-support-spring-app/pom.xml | Spring context and controller integration testing |

No-change note (if applicable):
- no additional runtime dependency is needed for `camel-agent-core` or `camel-agent-spring-ai` because `camel-agent-starter` already pulls them in transitively

### Persistence and Audit
- Runtime backend: `jdbc`
- Audit granularity: `info` by default, `debug` in integration testing profile
- Split audit store required: no for v1 sample
- DB/auth approach:
  - local default: in-memory Derby or equivalent JDBC URL in `application.yaml`
  - CI override: Postgres through environment-specific properties if needed
- Conversation archive persistence enabled by default: false
- Conversation archive dedicated store required: no in v1
- Conversation archive config keys:
  - `agent.conversation.persistence.enabled=false`
  - keep `agent.conversation.persistence.*` undocumented in the v1 sample config, but reserve the section in comments for later expansion

### Lifecycle and Operations
- Refresh strategy (all/single conversation): not exposed in v1 application API, but design should keep `agent.conversationId` stable so future refresh/inspection endpoints can be added cleanly
- Close conversation behavior: no explicit close endpoint in v1; lifecycle ends naturally per caller-managed conversation id
- Purge preview and purge criteria: not in v1 scope
- Runtime control methods:
  - not exposed by the sample API in v1
  - keep compatibility with existing runtime control semantics if a future admin surface is added
- Archive read method:
  - not exposed in v1

## 4. Implementation Phases

### Phase 1 — Blueprint and Contracts
- Tasks:
  - create `samples/agent-support-spring-app` module skeleton and resource layout
  - add `agents/support/agent.md` with `## System` and fenced YAML `tools:` block
  - define request/response contract for `POST /api/support`
- Deliverables:
  - Spring sample module structure
  - initial blueprint
  - controller request model or raw text contract decision
- Verification:
  - blueprint parses successfully in a unit test or startup test
  - sample module compiles with starter dependency set

### Phase 2 — Tools and Routes
- Tasks:
  - add `kb.search` route
  - add `support.ticket.open` route
  - create Camel route bridging `direct:support-request` to `agent:`
  - ensure route sets or propagates `agent.conversationId`
- Deliverables:
  - route resource files
  - `SupportAgentRoute.java`
  - deterministic tool execution behavior for the sample
- Verification:
  - route integration test proves prompt reaches `agent:` and returns a response
  - tool invocation is reflected in audit trail or logs during integration testing

### Phase 3 — Persistence, Audit, Lifecycle
- Tasks:
  - configure JDBC persistence in `application.yaml`
  - configure audit granularity and local development defaults
  - keep conversation archive disabled but document enabling path in comments
- Deliverables:
  - Spring sample `application.yaml`
  - environment override notes for local and CI
- Verification:
  - integration test proves multiple requests with the same conversation id preserve history
  - debug profile proves richer audit payloads are available when enabled

### Phase 4 — Spring/UX/Realtime Integration (if applicable)
- Tasks:
  - add `@SpringBootApplication` main class
  - add `AgentModelConfiguration` with explicit live `AiModelClient` bean
  - add `SupportController` that delegates to Camel
  - expose a simple health or readiness path through Spring Boot actuator or controller if needed
- Deliverables:
  - `Application.java`
  - `AgentModelConfiguration.java`
  - `SupportController.java`
- Verification:
  - Spring context starts with the overridden `AiModelClient`
  - `POST /api/support` successfully invokes `agent:` inside the application
  - missing-key behavior is clear and actionable in development mode

### Phase 5 — Hardening and Release
- Tasks:
  - add README for the new Spring sample module
  - add run commands and credential guidance
  - add focused tests for bean wiring and route behavior
  - verify dependency tree remains clean and aligned with parent versions
- Deliverables:
  - sample README
  - final integration tests
  - release notes snippet or product guide cross-link
- Verification:
  - `mvn -q -pl samples/agent-support-spring-app -am test` passes
  - startup and sample request flow succeed from a clean checkout

## 5. Test Plan

### Unit Tests
- blueprint structure test for `agents/support/agent.md`
- model configuration test proving `AiModelClient` bean is the explicit Spring AI implementation instead of the starter default

### Integration Tests
- Spring Boot context test for the sample application
- controller-to-route-to-agent invocation test using a mock or deterministic gateway
- conversation continuity test with repeated `agent.conversationId`
- audit behavior test showing expected events or persistence writes for a handled request

### Environment/Smoke Tests
- Commands:
  - `mvn -q -pl samples/agent-support-spring-app -am -DskipTests compile`
  - `mvn -q -pl samples/agent-support-spring-app -am test`
  - `mvn -q -pl samples/agent-support-spring-app -am spring-boot:run`
  - `curl -sS -X POST http://localhost:8080/api/support -H 'Content-Type: text/plain' -H 'X-Conversation-Id: spring-demo-1' --data 'My login is failing, please open a support ticket'`
- Expected outcomes:
  - Spring Boot app starts without missing-bean errors
  - `POST /api/support` returns an agent response body and preserves the supplied conversation id
  - local tool routes are invoked for support prompts
  - with valid credentials, live provider calls succeed; without them, the application returns actionable provider guidance

## 6. Deployment and Rollback

### Deployment Steps
1. Add the new sample module to the root Maven reactor.
2. Build and run the Spring sample locally and in CI.
3. Publish documentation links from the main product docs once the sample is stable.

### Rollback Steps
1. Remove the new sample module from the Maven reactor if it destabilizes builds.
2. Revert README and product-guide links to the prior state.
3. Keep the existing Camel Main sample as the supported bootstrap path until the Spring sample is fixed.

### Observability Checks
- verify startup logs show the explicit `AiModelClient` bean path rather than the noop default
- verify request logs include stable conversation ids for repeated calls
- verify persistence/audit data appears for successful requests when debug logging or test assertions inspect the store

## 7. Risks and Mitigations

| Risk | Impact | Mitigation | Owner |
|---|---|---|---|
| sample accidentally uses starter noop gateway | requests appear to work structurally but never hit a live provider | add explicit bean wiring test and document the override in code and README | platform maintainer |
| dependency drift between Spring Boot, Camel, and Spring AI | startup or runtime incompatibility | keep versions aligned to the repository parent and verify with dependency tree checks | platform maintainer |
| sample grows into a second copy of the existing support-service sample | maintenance burden and docs confusion | keep scope narrow: Spring embedding only, no AGUI/realtime in v1 | docs and platform maintainer |

## 8. Open Questions

- should the Spring sample stay backend-only in v1, or should it also expose AGUI routes as a second phase?
- should the sample adopt a Spring Boot parent POM or inherit directly from the repo parent and set starter versions explicitly?
- do we want a deterministic fallback path in the Spring sample when provider keys are missing, or is explicit terminal guidance sufficient?

---

## Requirement Traceability

| Requirement | Design/Phase | Test Coverage |
|---|---|---|
| show how Camel Agent is bootstrapped into a Spring application | Spring Application Bootstrap / Phase 4 | Spring context startup + `/api/support` invocation |
| demonstrate explicit live model wiring instead of noop starter default | Spring Application Bootstrap / Phase 4 | bean wiring test |
| preserve blueprint-driven tool routing inside Spring Boot | Tooling Design / Phase 2 | route integration test |
| keep sample easy to run locally | Goal and Scope / Phase 5 | compile, test, spring-boot run smoke |

## Dependency Traceability

| Dependency | Feature | Validation |
|---|---|---|
| `io.dscope.camel:camel-agent-starter` | Spring Boot Camel Agent integration | Spring context startup |
| `org.apache.camel.springboot:camel-spring-boot-starter` | Camel route execution inside Spring Boot | route invocation integration test |
| `org.springframework.boot:spring-boot-starter-web` | REST controller entrypoint | `/api/support` smoke test |

## Runtime Control Traceability

| Requirement | MCP Method | Verification |
|---|---|---|
| v1 Spring sample does not expose runtime control endpoints | not in scope | documented explicitly in Goal and Scope / Lifecycle |
| future compatibility with runtime control model | `runtime.audit.granularity.*`, `runtime.conversation.persistence.*` | follow-on phase if admin surface is added |