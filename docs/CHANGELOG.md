# Changelog

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
