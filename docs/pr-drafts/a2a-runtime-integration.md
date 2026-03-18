# A2A Runtime Integration PR Draft

## Title

`feat(a2a): add A2A protocol integration to Camel Agent runtime`

## Description

## Summary

This PR adds first-class A2A protocol support to Camel Agent by integrating the existing `camel-a2a-component` into the agent runtime.

The goal is to let Camel Agent:
- call remote A2A agents as part of tool-driven workflows
- expose local agents over A2A using agent-card based routing
- maintain A2A task/conversation state separately from end-user conversations
- persist A2A correlation and audit metadata across inbound and outbound flows

## What’s Included

### Runtime and configuration
- Add `agent.runtime.a2a.*` configuration
- Add exposed A2A agent mapping config separate from `agents.yaml`
- Bind A2A runtime beans and HTTP routes during `AgentRuntimeBootstrap`

### Inbound A2A support
- Expose:
  - `POST /a2a/rpc`
  - `GET /a2a/sse/{taskId}`
  - `GET /.well-known/agent-card.json`
- Route inbound A2A requests through explicit public agent-card mappings
- Resolve mapped local `{planName, planVersion}` and invoke the local `agent:` component
- Create separate linked local conversations for A2A tasks

### Outbound A2A support
- Support blueprint tools targeting `a2a:` endpoints
- Persist remote A2A conversation/task identifiers locally
- Propagate local correlation metadata for audit/debugging
- Support follow-up task operations without losing local/remote continuity

### Audit and correlation
- Add A2A-specific correlation keys
- Record:
  - inbound A2A request acceptance
  - selected public A2A agent
  - mapped local plan/version
  - outbound remote A2A call lifecycle
  - local/remote conversation and task linkage
- Extend conversation/audit views to show A2A-linked conversations

## Design choices

- `agents.yaml` remains the internal local plan catalog
- public A2A exposure uses a separate `agent.runtime.a2a.exposed-agents` mapping
- only explicitly exposed agents are published via agent cards
- inbound routing is based on public agent-card mapping, not payload-supplied plan selection
- A2A conversations are separate local conversations linked to a parent/root conversation when applicable
- v1 supports both A2A client and server behavior
- v1 supports the full current A2A core method surface exposed by `camel-a2a-component`

## Test plan

- bootstrap binds A2A runtime beans and routes when enabled
- exposed agent config validates mapped local plan/version entries
- agent-card discovery returns only explicitly exposed agents
- inbound `SendMessage` resolves correct local plan/version
- inbound A2A requests create linked local conversations
- outbound A2A tool calls persist remote task/conversation identifiers
- follow-up A2A task methods preserve local/remote correlation
- audit search/view show A2A-linked conversation metadata
- existing non-A2A AGUI/realtime/agent flows remain unchanged when A2A is disabled

## Notes

This PR intentionally reuses the current permissive A2A signer/verifier/policy defaults from `camel-a2a-component`.
Security hardening can follow in a later iteration once the base protocol bridge is stable.

## Suggested Checklist

- [ ] Added `agent.runtime.a2a.*` runtime configuration
- [ ] Added exposed-agent mapping separate from `agents.yaml`
- [ ] Bound A2A server routes in runtime bootstrap
- [ ] Added inbound agent-card to local plan/version routing
- [ ] Added outbound `a2a:` tool correlation handling
- [ ] Extended audit and conversation views with A2A metadata
- [ ] Added sample configuration and sample route coverage
- [ ] Added integration and regression tests

## Suggested Branch Name

- `feat/a2a-runtime-integration`
