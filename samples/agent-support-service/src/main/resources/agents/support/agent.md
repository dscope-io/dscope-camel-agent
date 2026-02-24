# Agent: SupportAssistant
Version: 0.1.0

## System
You are a support assistant that can search local knowledge routes.

Routing rules:
1. Use `support.ticket.open` when the user asks to open, create, submit, or escalate a support ticket.
2. Use `kb.search` for general plain-language support lookups, troubleshooting guidance, and informational questions.
3. If unclear, default to `kb.search`.

AGUI note:
- In this sample, AGUI frontend transport is configured by runtime routes/processors (`application.yaml` + `routes/ag-ui-platform.camel.yaml`).
- AGUI pre-run behavior is configured in blueprint metadata (`aguiPreRun`) with runtime property fallback support.

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
  - name: support.mcp
    description: Optional MCP support service seed (runtime discovers concrete MCP tools via tools/list)
    endpointUri: mcp:http://localhost:3001/mcp
    inputSchemaInline:
      type: object
      properties: {}
```

## JSON Route Templates
```yaml
jsonRouteTemplates:
  - id: http.request
    toolName: route.template.http.request
    description: Instantiate and execute a safe JSON DSL HTTP route from template parameters
    invokeUriParam: fromUri
    parametersSchema:
      type: object
      required: [fromUri, method, url]
      properties:
        fromUri:
          type: string
          description: Route entrypoint (for example direct:dynamic-http)
        method:
          type: string
          enum: [GET, POST, PUT, DELETE]
        url:
          type: string
          description: HTTP target URL for toD
        executeBody:
          type: object
          description: Optional body sent when invoking generated route
    routeTemplate:
      route:
        id: agent.dynamic.http.request
        from:
          uri: "{{fromUri}}"
          steps:
            - setHeader:
                name: CamelHttpMethod
                constant: "{{method}}"
            - toD:
                uri: "{{url}}"
```

## Realtime

Realtime config is intentionally omitted in this sample blueprint to demonstrate
fallback from `application.yaml` (`agent.runtime.realtime.*`).

## AGUI Pre-Run

```yaml
aguiPreRun:
  agentEndpointUri: agent:support?blueprint={{agent.blueprint}}
  fallbackEnabled: true
  fallback:
    kbToolName: kb.search
    ticketToolName: support.ticket.open
    ticketKeywords: [ticket, open, create, submit, escalate]
    errorMarkers: [api key is missing, openai api key, set -dopenai.api.key]
```
