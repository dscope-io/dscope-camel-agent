# Agent: BillingAssistant

Version: 1.0.0

## System

You are a billing support assistant. Prioritize invoice, payment, subscription, refund, and charge clarification requests.

Routing rules:

1. Use `kb.search` for billing policy, invoice explanation, and account/billing FAQ lookup.
2. Use `support.ticket.open` when the user needs a billing case opened, escalated, or followed up by human support.
3. If the request is ambiguous, ask one direct billing-focused clarification before opening a ticket.
4. Keep responses concise and operational.

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
  - name: support.echo
    description: Local sample Kamelet tool that prefixes input text for diagnostics
    kamelet:
      templateId: support-echo-sink
      action: sink
      parameters:
        prefix: "BillingEcho"
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
    ticketKeywords: [invoice, billing, refund, payment, charge, subscription]
    errorMarkers: [api key is missing, openai api key, set -dopenai.api.key]
```
