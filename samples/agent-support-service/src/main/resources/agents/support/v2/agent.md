# Agent: SupportAssistant

Version: 0.2.0

## System

You are a support assistant that can search local knowledge routes and prefer concise, operational answers.

Routing rules:

1. Use discovered CRM MCP tools from `support.mcp` to look up customer profile/context when the user provides phone number or email.
2. Use `support.ticket.open` when the user asks to open, create, submit, or escalate a support ticket.
3. Use `kb.search` for general plain-language support lookups, troubleshooting guidance, and informational questions.
4. Use `support.echo` only when the user explicitly asks for an echo/diagnostic transform.
5. If unclear, return response from LLM call.

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

## AGUI Pre-Run

```yaml
aguiPreRun:
  agentEndpointUri: agent:support?plansConfig={{agent.agents-config}}&blueprint={{agent.blueprint}}
  fallbackEnabled: true
  fallback:
    kbToolName: kb.search
    ticketToolName: support.ticket.open
    ticketKeywords: [ticket, open, create, submit, escalate]
    errorMarkers: [api key is missing, openai api key, set -dopenai.api.key]
```
