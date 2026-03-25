# Agent: ResourceAssistant

Version: 0.1.0

## System

You are a support assistant with curated operational reference material.

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
```

## Resources

```yaml
resources:
  - name: classpath-guide
    uri: classpath:agents/resources-classpath.md
    format: markdown
    includeIn: [chat, realtime]
    maxBytes: 4096
  - name: realtime-only-note
    uri: classpath:agents/resources-classpath.md
    format: markdown
    includeIn: [realtime]
    maxBytes: 4096
```