# Agent: SupportAssistant
Version: 0.2.0

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

## JSON Route Templates
```yaml
jsonRouteTemplates:
  - id: http.request
    toolName: route.template.http.request
    description: Create and execute a dynamic HTTP route from a safe template
    invokeUriParam: fromUri
    parametersSchema:
      type: object
      required: [fromUri, method, url]
      properties:
        fromUri:
          type: string
        method:
          type: string
        url:
          type: string
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
