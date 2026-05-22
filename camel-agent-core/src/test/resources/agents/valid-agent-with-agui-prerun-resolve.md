# Agent: SupportAssistantResolve
Version: 0.4.1

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
```

## AGUI Pre-Run
```yaml
aguiPreRun:
  agentEndpointUri: direct:agent-llm-blueprint-resolve
  fallbackEnabled: true
  fallback:
    kbToolName: knowledge.lookup
    kbUri: direct:kb-custom

exceptionPolicies:
  - name: agui-business-resolve
    scope: agui.pre-run
    category: business
    httpStatusCodes: [409]
    action: resolve
    prompt: Use existing context and propose a user-safe workaround.
```
