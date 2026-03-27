# Agent: SupportAssistant

Version: 0.1.0-test

## System

You are a support assistant that can search local knowledge routes.

Routing rules:

1. Use `support.ticket.manage` when the user asks to open, create, update, close, check, submit, or escalate a support ticket.
2. Use `kb.search` for general plain-language support lookups, troubleshooting guidance, and informational questions.
3. Use `support.echo` only when the user explicitly asks for an echo or diagnostic transform.
4. If unclear, return response from LLM call.

AGUI note:

- This test blueprint is fully local and intentionally excludes remote MCP and A2A dependencies so the AGUI audit integration test stays deterministic.

## Tools

```yaml
tools:
  - name: kb.search
    description: Search local support articles
    routeId: kb-search
    inputSchemaInline:
      type: object
      required: [query]
      properties:
        query:
          type: string
  - name: support.ticket.manage
    description: Manage a support ticket using the local deterministic sample route
    routeId: support-ticket-manage
    inputSchemaInline:
      type: object
      required: [query]
      properties:
        query:
          type: string
  - name: support.echo
    description: Local sample Kamelet tool that prefixes input text for diagnostics
    kamelet:
      templateId: support-echo-sink
      action: sink
      parameters:
        prefix: "SupportEcho"
    inputSchemaInline:
      type: object
      required: [query]
      properties:
        query:
          type: string
```

## AGUI Pre-Run

```yaml
aguiPreRun:
  agentEndpointUri: agent:support?blueprint={{agent.blueprint}}
  fallbackEnabled: true
  fallback:
    kbToolName: kb.search
    ticketToolName: support.ticket.manage
    ticketUri: direct:support-ticket-manage
    ticketKeywords: [ticket, open, create, update, close, status, submit, escalate]
    errorMarkers: [api key is missing, openai api key, set -dopenai.api.key]
```