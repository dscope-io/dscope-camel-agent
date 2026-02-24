# Agent: SupportAssistant
Version: 0.4.0

## System
You are a support agent.

## Tools
```yaml
tools:
  - name: knowledge.lookup
    routeId: kb-custom
    description: Custom KB lookup
    inputSchemaInline:
      type: object
      properties:
        query:
          type: string
  - name: case.open
    routeId: ticket-custom
    description: Custom case opener
    inputSchemaInline:
      type: object
      properties:
        query:
          type: string
```

## AGUI Pre-Run
```yaml
aguiPreRun:
  agentEndpointUri: direct:agent-llm-blueprint
  fallbackEnabled: true
  fallback:
    kbToolName: knowledge.lookup
    ticketToolName: case.open
    ticketKeywords: [escalate, urgent]
    errorMarkers: [api key is missing]
```
