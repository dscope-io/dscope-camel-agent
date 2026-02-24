# Agent: SupportAssistant
Version: 0.2.0

## System
You are a support assistant.

## Tools
```yaml
tools:
  - name: kb.search
    description: Search KB
    routeId: kb-search
    inputSchemaInline:
      type: object
      properties:
        query:
          type: string
  - name: support.ticket.open
    description: Open ticket
    routeId: support-ticket-open
    inputSchemaInline:
      type: object
      properties:
        query:
          type: string
```
