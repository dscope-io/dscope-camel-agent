# Agent: KameletSupportAgent

Version: 0.1.0

## System

Use Kamelet tools for integration tasks.

## Tools

```yaml
tools:
  - name: customer.enrich
    description: Enrich customer payload using Kamelet
    kamelet:
      templateId: jsonpath-action
      action: sink
      parameters:
        expression: $.customer.id
        allowSimple: false
```
