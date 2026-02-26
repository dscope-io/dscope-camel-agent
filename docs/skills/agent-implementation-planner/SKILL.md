# Agent Implementation Planner Skill

## Purpose

Use this skill to produce a complete implementation plan for a new agent that is built on top of CamelAIAgentComponent.

The output of this skill is a practical, execution-ready plan that covers:

- architecture choices
- resource layout (agent blueprint, routes, kamelets)
- persistence and audit strategy
- AGUI and realtime behavior (if required)
- testing strategy (unit, integration, smoke)
- rollout, observability, and rollback
- required Maven dependencies (runtime/test/optional)

## When to Use

Use this skill when any of the following is requested:

- create a new agent using this project as a base
- implement a new business domain agent in support-service or a new sample
- add tool routes/kamelets for a new agent
- define delivery plan before coding
- estimate scope and phases for agent development

Do not use this skill for single-file quick fixes.

## Inputs Required

Collect these inputs before writing the plan:

1. Business goal
- what user problem the agent solves
- expected success criteria

2. Interaction channels
- AGUI chat only
- AGUI + realtime voice
- backend-only orchestration

3. Tooling scope
- required tools (internal/external)
- whether kamelets are needed
- expected tool call volume and latency sensitivity

4. Persistence and audit requirements
- runtime store mode: in-memory, jdbc, redis, redis_jdbc
- audit granularity: none, info, debug
- split audit store needed or not
- retention/purge policy requirements

5. Environment and delivery constraints
- local-only or CI/CD deployment
- required databases and auth mode
- non-functional constraints (security, latency, cost)

6. Dependency constraints
- allowed external libraries
- version alignment requirements (BOM/parent-managed vs explicit version)
- licensing/security constraints for new dependencies

## Project-Specific Architecture Defaults

When requirements are ambiguous, use these defaults:

- agent blueprint: markdown blueprint loaded via agent.blueprint
- route loading: classpath routes include pattern
- tool implementation: Camel routes + optional kamelets
- persistence: jdbc for non-trivial implementations
- audit granularity: info by default, debug in test/staging
- AGUI enabled for human-facing workflows
- runtime admin endpoints enabled for refresh/close/purge preview

## Planning Workflow

### Phase 1: Scope and Boundaries

Define:

- in-scope outcomes
- out-of-scope items
- acceptance criteria

Output:

- concise problem statement
- measurable done conditions

### Phase 2: Agent Contract Design

Define:

- agent name/title
- user intents and expected responses
- tool contract per intent

Output:

- intent-to-tool mapping
- fallback behavior when tools fail

### Phase 3: Resource Design

Plan resource artifacts:

- blueprint file path
- route files (yaml/xml)
- kamelets if reusable endpoints are needed

Output:

- file tree for new agent resources
- naming conventions for route ids and tool names

### Phase 4: Persistence, Audit, and Lifecycle

Plan:

- persistence backend and DB config
- audit strategy and required events
- conversation lifecycle operations
  - refresh all/single
  - close conversation instance
  - purge preview and purge criteria

Output:

- configuration matrix by environment
- retention/purge policy mapping

### Phase 5: Integration and UX

If AGUI or realtime is needed, define:

- AGUI routes/endpoints and expected UI controls
- realtime session init/token/event behavior
- session metadata/context update rules

Output:

- endpoint list
- UX behavior notes tied to endpoints

### Phase 6: Test Strategy

Plan tests at three levels:

1. Unit tests
- blueprint parsing, processors, tool selection logic

2. Integration tests
- route invocation
- persistence/audit verification
- close/refresh behavior

3. Environment smoke tests
- script-driven local checks
- optional Docker-backed Postgres flow

Output:

- explicit test cases with expected assertions
- commands to run each test group

### Phase 6.5: Maven Dependency Resolution

For the planned agent, derive dependency changes before implementation starts.

Identify:

- existing dependencies already available via parent/module POMs
- new runtime dependencies required for the agent features
- new test-only dependencies required for integration/smoke tests
- module target for each dependency change (which `pom.xml`)

Output:

- a dependency table with: groupId, artifactId, scope, version source, reason, target module
- a "no-change" note when existing dependencies are sufficient
- conflict notes for any potential version overlaps

### Phase 7: Rollout and Operations

Plan:

- migration and deployment order
- observability checks
- rollback steps

Output:

- release checklist
- rollback checklist

## Output Format Required

When using this skill, always produce the plan with this exact section order:

1. Goal and Scope
2. Assumptions
3. Architecture Decisions
  - Must include subsection: `Maven Dependencies`
4. Implementation Phases
5. Test Plan
6. Deployment and Rollback
7. Risks and Mitigations
8. Open Questions

Companion fill-in template:

- `docs/skills/agent-implementation-planner/PLAN_TEMPLATE.md`

## Plan Quality Rules

A valid plan must:

- map each user requirement to at least one implementation task
- include at least one verification step per phase
- include at least one rollback or fallback mechanism
- identify unknowns explicitly in Open Questions
- avoid broad placeholders such as "implement feature" without file/component targets
- include an explicit Maven dependency decision for each major feature area (reuse existing vs add new)
- include target module `pom.xml` for every new dependency

## Suggested Commands Reference

Use these as planning anchors (adapt per scope):

- Build/compile:
  mvn -q -pl camel-agent-core,samples/agent-support-service -am -DskipTests compile

- Dependency inspection:
  mvn -q -pl samples/agent-support-service -am dependency:tree
  mvn -q -pl camel-agent-core -am dependency:tree

- Sample run:
  samples/agent-support-service/run-sample.sh

- Postgres integration flow:
  bash scripts/postgres-it.sh local
  bash scripts/postgres-it.sh ci
  bash scripts/postgres-it.sh down

## Definition of Done for Planning

The planning task is complete when:

- all required sections are present
- all major components are mapped to concrete files/modules
- tests and rollout are explicit
- known risks and unknowns are documented
- next implementation action can start without additional clarification
- Maven dependency plan is complete with module targets and scopes
