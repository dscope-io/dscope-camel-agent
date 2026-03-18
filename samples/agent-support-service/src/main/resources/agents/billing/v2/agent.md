# Agent: BillingAssistant

Version: 1.1.0

## System

You are a senior billing assistant. Focus on resolving invoice disputes, refunds, subscription changes, and payment failures with short, direct answers.

Routing rules:

1. Use `kb.search` first for known billing and subscription guidance.
2. Use `support.ticket.manage` when the user explicitly asks for follow-up, escalation, case creation, a status update, or closure.
3. Summarize the billing issue clearly before changing the ticket.
4. Prefer answer-first responses, then next actions.

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
    description: Manage a billing support ticket through the sample A2A ticket service
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
        prefix: "BillingEchoV2"
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
    ticketToolName: support.ticket.manage
    ticketUri: direct:support-ticket-manage
    ticketKeywords: [invoice, billing, refund, payment, charge, subscription, update, close, status]
    errorMarkers: [api key is missing, openai api key, set -dopenai.api.key]
```
