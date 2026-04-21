# Agent: SupportAssistant
Version: 0.3.0

## System
You are a support agent.

## Tools
```yaml
tools:
  - name: kb.search
    routeId: kb-search
    description: Search the knowledge base
    inputSchemaInline:
      type: object
      required: [query]
      properties:
        query:
          type: string
```

## Realtime
```yaml
realtime:
  provider: openai
  model: gpt-4o-realtime-preview
  voice: alloy
  transport: server-relay
  endpointUri: mcp:http://localhost:3001/mcp
  inputAudioFormat: pcm16
  outputAudioFormat: pcm16
  retentionPolicy: metadata_transcript
  reconnect:
    maxSendRetries: 3
    maxReconnects: 8
    initialBackoffMs: 150
    maxBackoffMs: 2000
```

## A2UI
```yaml
a2ui:
  surfaces:
    - name: support-ticket-card-v2
      widgetTemplate: ticket-card
      surfaceIdTemplate: support-ticket-v2-${ticketId}
      catalogResource: classpath:agents/a2ui/support-v2.catalog.json
      surfaceResource: classpath:agents/a2ui/support-v2.surface.json
      matchFields: [ticketId]
      localeResources:
        en: classpath:agents/a2ui/locales/support-v2.en.json
        fr: classpath:agents/a2ui/locales/support-v2.fr.json
        fr-CA: classpath:agents/a2ui/locales/support-v2.fr.json
        default: classpath:agents/a2ui/locales/support-v2.en.json
```
