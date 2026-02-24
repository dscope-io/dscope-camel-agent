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
