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

- In this sample, AGUI frontend transport is configured by runtime routes and processors.
- This test blueprint intentionally excludes remote MCP service discovery so the integration test remains deterministic.

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
    description: Manage a support ticket over the sample A2A ticket service and return ticket details
    endpointUri: a2a:support-ticket-service?remoteUrl={{agent.runtime.a2a.public-base-url}}/a2a
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

## AGUI Pre-Run

```yaml
aguiPreRun:
  agentEndpointUri: agent:support?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}
  fallbackEnabled: true
  fallback:
    kbToolName: kb.search
    ticketToolName: support.ticket.manage
    ticketUri: direct:support-ticket-manage
    ticketKeywords: [ticket, open, create, update, close, status, submit, escalate]
    errorMarkers: [api key is missing, openai api key, set -dopenai.api.key]
```