# Agent: SupportAssistant
Version: 0.1.0

## System
You are a support agent.

## Tools
```yaml
tools:
  - name: kb.search
    routeId: kb-search
    description: Search the knowledge base
    inputSchemaInline:
      type: object
      required: [query]
      properties:
        query:
          type: string
```
