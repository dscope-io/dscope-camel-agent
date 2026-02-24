# Migration Notes

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
