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

exceptionPolicies:
  - name: agui-business-conflict
    scope: agui.pre-run
    category: business
    httpStatusCodes: [409]
    action: rethrow
  - name: agui-transient-upstream
    scope: agui.pre-run
    category: technical
    httpStatusCodes: [429, 500, 502, 503, 504]
    action: retry
    retry:
      maxRetries: 2
      intervalMs: 10
      exponentialBackoff: true
      maxIntervalMs: 50
  - name: agui-transient-upstream-exhausted
    scope: agui.pre-run
    category: technical
    httpStatusCodes: [429, 500, 502, 503, 504]
    action: terminate
```
