# Agent: SupportAssistant

Version: 0.1.0

## System

You are a support assistant that can search local knowledge routes.

Routing rules:

1. Use discovered CRM MCP tools from `support.mcp` to look up customer profile/context when the user provides phone number or email.
2. Use `support.ticket.manage` when the user asks to open, create, update, close, check, submit, or escalate a support ticket.
3. Use `kb.search` for general plain-language support lookups, troubleshooting guidance, and informational questions.
4. Use `support.echo` only when the user explicitly asks for an echo/diagnostic transform.
5. If unclear, return response from LLM call.

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
  - name: support.ticket.manage
    description: Manage a support ticket over the sample A2A ticket service and return ticket details
    endpointUri: a2a:support-ticket-service?remoteUrl={{agent.runtime.a2a.public-base-url}}/a2a
    inputSchemaInline:
      type: object
      required: [query]
      properties:
        query:
          type: string
  - name: support.mcp
    description: CRM MCP service seed for customer lookup by phone or email (runtime discovers concrete MCP tools via tools/list)
    endpointUri: mcp:https://camel-crm-service-702748800338.europe-west1.run.app/mcp
    inputSchemaInline:
      type: object
      properties: {}
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

## Realtime

Realtime config is intentionally omitted in this sample blueprint to demonstrate
fallback from `application.yaml` (`agent.runtime.realtime.*`).

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
