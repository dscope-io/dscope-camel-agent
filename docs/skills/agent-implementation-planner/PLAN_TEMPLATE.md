# New Agent Implementation Plan Template

## 1. Goal and Scope

### Goal
- Business objective:
- Primary users:
- Success criteria (measurable):

### In Scope
- 
- 

### Out of Scope
- 
- 

## 2. Assumptions

- 
- 
- 

## 3. Architecture Decisions

### Agent Identity
- Agent name/title:
- Blueprint path:
- Versioning approach:

### Interaction Model
- Channels: AGUI / Realtime / Backend-only
- Primary request-response pattern:
- Fallback behavior:

### Tooling Design
- Tool list and purpose:
  - 
  - 
- Route artifacts to add/update:
  - 
- Kamelets required (yes/no + why):

### Maven Dependencies
- Dependency strategy: reuse existing / add new / mixed
- Dependency scan commands and findings:
  - `mvn -q -pl camel-agent-core -am dependency:tree`
  - `mvn -q -pl samples/agent-support-service -am dependency:tree`

| groupId | artifactId | Scope | Version Source | Target Module (pom.xml) | Reason |
|---|---|---|---|---|---|
|  |  |  | parent-managed / explicit |  |  |
|  |  |  | parent-managed / explicit |  |  |

No-change note (if applicable):
- 

### Persistence and Audit
- Runtime backend: in-memory / jdbc / redis / redis_jdbc
- Audit granularity: none / info / debug
- Split audit store required: yes/no
- DB/auth approach:

### Lifecycle and Operations
- Refresh strategy (all/single conversation):
- Close conversation behavior:
- Purge preview and purge criteria:

## 4. Implementation Phases

### Phase 1 — Blueprint and Contracts
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 

### Phase 2 — Tools and Routes
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 

### Phase 3 — Persistence, Audit, Lifecycle
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 

### Phase 4 — UX/Realtime Integration (if applicable)
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 

### Phase 5 — Hardening and Release
- Tasks:
  - 
- Deliverables:
  - 
- Verification:
  - 

## 5. Test Plan

### Unit Tests
- 
- 

### Integration Tests
- 
- 

### Environment/Smoke Tests
- Commands:
  - `mvn -q -pl camel-agent-core,samples/agent-support-service -am -DskipTests compile`
  - `bash scripts/postgres-it.sh local`
- Expected outcomes:
  - 

## 6. Deployment and Rollback

### Deployment Steps
1. 
2. 
3. 

### Rollback Steps
1. 
2. 
3. 

### Observability Checks
- 
- 

## 7. Risks and Mitigations

| Risk | Impact | Mitigation | Owner |
|---|---|---|---|
|  |  |  |  |
|  |  |  |  |

## 8. Open Questions

- 
- 
- 

---

## Requirement Traceability

Map each requirement to implementation/testing items.

| Requirement | Design/Phase | Test Coverage |
|---|---|---|
|  |  |  |
|  |  |  |

## Dependency Traceability

Map each added dependency to the feature and validation.

| Dependency | Feature | Validation |
|---|---|---|
|  |  |  |
|  |  |  |
