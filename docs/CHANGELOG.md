# Changelog

## 2026-02-26

### Release 0.5.0

- Promoted project and sample modules from snapshot to stable release version `0.5.0`.
- Completed clean verification build and full test run for the multi-module workspace.
- Installed `0.5.0` artifacts to local Maven repository for downstream consumption.

### Documentation updates

- Updated root and sample READMEs with `0.5.0` release status.
- Added Maven dependency import guidance for `camel-agent-starter`, `camel-agent-core`, and optional `camel-agent-persistence-dscope`.

### Build tooling updates

- Replaced MCP-based Karavan metadata generation with a local project-specific generator for `camel-agent-core`.
- Added `AgentKaravanMetadataGenerator` and wired `karavan-metadata` profile to generate `agent` component metadata under `camel-agent-core/src/main/resources/karavan/metadata`.
- Updated documented generation command to `mvn -pl camel-agent-core -Pkaravan-metadata -DskipTests compile exec:java`.

## 2026-02-23

### AGUI sample voice UX and transcript improvements

- Added a single dynamic voice toggle button in `/agui/ui` with `idle`, `live`, and `busy` states.
- Added runtime-selectable VAD pause profiles in the UI and session config:
  - `Fast` = `800ms`
  - `Normal` = `1200ms`
  - `Patient` = `1800ms`
- Updated voice status and pause label to display active pause timing (`Pause (<ms>ms)`).
- Added responsive mobile behavior for the voice toggle (icon-only on narrow screens).
- Added dynamic accessibility/tooltip labels (`title` and `aria-label`) for current toggle state.
- Added/expanded WebRTC transcript diagnostics panel with clear action.
- Fixed duplicate assistant voice output transcript rendering so only one final transcript entry is shown.
- Fixed output transcript delta spacing issues by preserving delta whitespace during assembly.

### Documentation updates

- Updated [README.md](../README.md), [samples/agent-support-service/README.md](../samples/agent-support-service/README.md), [architecture.md](architecture.md), [TEST_PLAN.md](TEST_PLAN.md), and [migration-notes.md](migration-notes.md) to reflect the new voice/transcript behavior.
