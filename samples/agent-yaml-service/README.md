# Agent YAML Service Sample

Runnable Camel Main sample for the `agent:` runtime with:

- Spring AI gateway mode (`agent.runtime.ai.mode=spring-ai`)
- local routes for `kb.search` and `support.ticket.open`
- audit trail persistence via JDBC-backed DScope persistence

## Prerequisites

- Java 21
- Maven
- OpenAI key (for live Spring AI runs)

## Configure Secrets

Use either environment variable:

```bash
export OPENAI_API_KEY=...
```

or local file (`.agent-secrets.properties`):

```properties
openai.api.key=...
```

## Run

From repo root:

```bash
mvn -f samples/agent-yaml-service/pom.xml \
  -DskipTests \
  -Daudit.api.enabled=false \
  clean compile exec:java
```

Or use the helper script (auto-loads `.agent-secrets.properties`):

```bash
samples/agent-yaml-service/run-sample.sh
```

## Connectivity Precheck

```bash
nc -vz api.openai.com 443
curl -I https://api.openai.com/v1/models
curl https://api.openai.com/v1/models -H "Authorization: Bearer $OPENAI_API_KEY"
```

## Routes Used by Tools

- `kb.search` -> `direct:kb-search` (`routes/kb-search.camel.yaml`)
- `support.ticket.open` -> `direct:support-ticket-open` (`routes/kb-search-json.camel.xml`)

## Test Scenarios

Run sample integration tests:

```bash
mvn -f samples/agent-yaml-service/pom.xml -Dtest=SpringAiAuditTrailIntegrationTest test
```

Covered scenarios:

1. Two-turn routing + memory (deterministic gateway):
   - prompt 1 asks knowledge base -> tool `kb.search`
   - prompt 2 asks ticket -> tool `support.ticket.open`
   - prompt 2 evaluation includes prompt 1 KB result
2. Negative case:
   - ticket as first prompt does not inject KB context
3. Live LLM route decision (when API key is available):
   - validates real tool selection and second-turn context carry-over

## Runtime Config

Primary config is in `src/main/resources/application.yaml`.

Notable keys:

- `agent.runtime.ai.mode=spring-ai`
- `agent.runtime.spring-ai.provider=openai`
- `agent.runtime.spring-ai.openai.api-mode=chat`
- `agent.runtime.spring-ai.model=gpt-4o-mini`

## Troubleshooting

- `401` from OpenAI API calls
   - token is missing/invalid for the active shell.
   - verify:
      - `echo "$OPENAI_API_KEY" | wc -c`
      - `curl https://api.openai.com/v1/models -H "Authorization: Bearer $OPENAI_API_KEY"`

- `421` with OpenAI welcome body
   - request is likely hitting the API root path instead of chat endpoint.
   - ensure OpenAI config stays on `api-mode=chat` and do not override base/path incorrectly.

- Sample still behaves like old code after fixes
   - local Maven snapshots may be stale.
   - rebuild/install from repo root:
      - `mvn -f pom.xml -pl camel-agent-core,camel-agent-spring-ai,camel-agent-persistence-dscope,camel-agent-agui,samples/agent-yaml-service -am -DskipTests clean install`

- Tests unexpectedly skipped
   - check if `maven.test.skip=true` is set in env/system properties.
   - run explicit class test:
      - `mvn -f samples/agent-yaml-service/pom.xml -Dtest=SpringAiAuditTrailIntegrationTest test`
