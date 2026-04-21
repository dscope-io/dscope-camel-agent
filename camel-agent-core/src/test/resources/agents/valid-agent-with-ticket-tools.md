# Agent: SupportAssistant
Version: 0.2.0

## System
You are a support assistant.

## Tools
```yaml
tools:
  - name: kb.search
    description: Search KB
    routeId: kb-search
    inputSchemaInline:
      type: object
      properties:
        query:
          type: string
  - name: support.ticket.manage
    description: Manage ticket
    routeId: support-ticket-manage
    inputSchemaInline:
      type: object
      properties:
        query:
          type: string
```

## A2UI
```yaml
a2ui:
  surfaces:
    - name: billing-ticket-card-v1
      widgetTemplate: ticket-card
      surfaceIdTemplate: billing-ticket-${ticketId}
      catalogResource: classpath:agents/a2ui/billing-v1.catalog.json
      surfaceResource: classpath:agents/a2ui/billing-v1.surface.json
      matchFields: [ticketId]
      localeResources:
        en: classpath:agents/a2ui/locales/billing-v1.en.json
        es: classpath:agents/a2ui/locales/billing-v1.es.json
        es-MX: classpath:agents/a2ui/locales/billing-v1.es.json
        default: classpath:agents/a2ui/locales/billing-v1.en.json
```
