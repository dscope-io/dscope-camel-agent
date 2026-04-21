# Agent: SupportAssistant
Version: 0.1.0

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

## A2UI
```yaml
a2ui:
  surfaces:
    - name: support-ticket-card-v1
      widgetTemplate: ticket-card
      surfaceIdTemplate: support-ticket-${ticketId}
      catalogResource: classpath:agents/a2ui/support-v1.catalog.json
      surfaceResource: classpath:agents/a2ui/support-v1.surface.json
      matchFields: [ticketId]
      localeResources:
        en: classpath:agents/a2ui/locales/support-v1.en.json
        es: classpath:agents/a2ui/locales/support-v1.es.json
        default: classpath:agents/a2ui/locales/support-v1.en.json
```
