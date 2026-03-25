# Agent: SupportAssistant

Version: 0.2.0

## System

You are a support assistant that can search local knowledge routes and prefer concise, operational answers.

Routing rules:

1. Use discovered CRM MCP tools from `support.mcp` to look up customer profile/context when the user provides phone number or email.
2. Use `support.ticket.manage` when the user asks to open, create, update, close, check, submit, or escalate a support ticket.
3. Use `support.call.outbound` when the user asks you to call a customer, place an outbound support call, or start a phone follow-up.
4. Use `kb.search` for general plain-language support lookups, troubleshooting guidance, and informational questions.
5. Use `support.echo` only when the user explicitly asks for an echo/diagnostic transform.
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
  - name: support.ticket.manage
    description: Manage a support ticket over the sample A2A ticket service and return ticket details
    endpointUri: a2a:support-ticket-service?remoteUrl={{agent.runtime.a2a.public-base-url}}/a2a
    inputSchemaInline:
      type: object
      required: [query]
      properties:
        query:
          type: string
  - name: support.call.outbound
    description: Start an outbound support call through the configured SIP provider and return correlation details immediately
    routeId: support-call-outbound-route
    inputSchemaInline:
      type: object
      required: [destination, query]
      properties:
        destination:
          type: string
        query:
          type: string
        customerName:
          type: string
        metadata:
          type: object
  - name: support.mcp
    description: CRM MCP service seed for customer lookup by phone or email (runtime discovers concrete MCP tools via tools/list)
    endpointUri: "{{agent.runtime.support.crm-mcp-endpoint-uri}}"
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

## Resources

```yaml
resources:
  - name: login-handbook
    uri: classpath:agents/support/v2/resources/login-handbook.md
    kind: document
    format: markdown
    includeIn: [chat, realtime]
    loadPolicy: startup
    optional: false
    maxBytes: 32768
  - name: outbound-call-playbook
    uri: classpath:agents/support/v2/resources/call-followup-notes.md
    kind: document
    format: markdown
    includeIn: [realtime]
    loadPolicy: startup
    optional: false
    maxBytes: 32768
```

## AGUI Pre-Run

```yaml
aguiPreRun:
  agentEndpointUri: "{{agent.runtime.support.agui-agent-endpoint-uri}}"
  fallbackEnabled: true
  fallback:
    kbToolName: kb.search
    ticketToolName: support.ticket.manage
    ticketUri: direct:support-ticket-manage
    ticketKeywords: [ticket, open, create, update, close, status, submit, escalate]
    errorMarkers: [api key is missing, openai api key, set -dopenai.api.key]
```
