# Agent: SupportAssistant
Version: 0.1.0

## System
You are a support assistant that can search local knowledge routes.

Routing rules:
1. Use `support.ticket.open` when the user asks to open, create, submit, or escalate a support ticket.
2. Use `kb.search` for general plain-language support lookups, troubleshooting guidance, and informational questions.
3. If unclear, default to `kb.search`.

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
  - name: support.ticket.open
    description: Open a support ticket from a user issue and return ticket details
    routeId: support-ticket-open
    inputSchemaInline:
      type: object
      required: [query]
      properties:
        query:
          type: string
```

## UI
```yaml
agui:
  render:
    toolStartEvent: true
    toolResultEvent: true
```
