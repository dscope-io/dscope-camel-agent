# Persistence + Audit DB Deployment Guide

This project persists state/audit through DScope JDBC `FlowStateStore` tables:

- `camel_flow_snapshot`
- `camel_flow_event`
- `camel_flow_idempotency`

The same schema is used for:

- runtime persistence (`camel.persistence.*`)
- optional split audit persistence (`agent.audit.jdbc.*` or `agent.audit.persistence.*`)

## 1) Deploy schema

### PostgreSQL

1. Create DB/user.
2. Run [docs/db/postgres/01_camel_persistence_schema.sql](docs/db/postgres/01_camel_persistence_schema.sql).

Example:

```bash
psql "host=<host> dbname=<db> user=<user> sslmode=require" -f docs/db/postgres/01_camel_persistence_schema.sql
```

### Snowflake

1. Create warehouse/database/role/user.
2. Run [docs/db/snowflake/01_camel_persistence_schema.sql](docs/db/snowflake/01_camel_persistence_schema.sql).

Example:

```sql
USE ROLE SYSADMIN;
USE WAREHOUSE <warehouse>;
USE DATABASE <database>;
!source docs/db/snowflake/01_camel_persistence_schema.sql
```

## 2) Configure application.yaml

Use one backend for both state and audit, or split audit into a dedicated JDBC target.

### Schema DDL resource selection

JDBC schema initialization uses vendor-based defaults:

- PostgreSQL: `classpath:db/persistence/postgres-flow-state.sql`
- Snowflake: `classpath:db/persistence/snowflake-flow-state.sql`

To force a specific DDL resource (for custom schemas or non-default locations), set:

```yaml
camel:
  persistence:
    jdbc:
      schema:
        ddl-resource: classpath:db/persistence/postgres-flow-state.sql
```

Equivalent JVM/system property:

```bash
-Dcamel.persistence.jdbc.schema.ddl-resource=classpath:db/persistence/postgres-flow-state.sql
```

### Postgres runtime + audit (single store)

```yaml
camel:
  persistence:
    enabled: true
    backend: jdbc
    jdbc:
      url: jdbc:postgresql://<host>:5432/<db>?sslmode=require
      username: ${AGENT_DB_USER}
      password: ${AGENT_DB_PASSWORD}
      driver-class-name: org.postgresql.Driver
```

### Postgres runtime + split audit store

```yaml
camel:
  persistence:
    enabled: true
    backend: jdbc
    jdbc:
      url: jdbc:postgresql://<host>:5432/agent_runtime?sslmode=require
      username: ${AGENT_DB_USER}
      password: ${AGENT_DB_PASSWORD}
      driver-class-name: org.postgresql.Driver

agent:
  audit:
    backend: jdbc
    jdbc:
      url: jdbc:postgresql://<host>:5432/agent_audit?sslmode=require
      username: ${AGENT_AUDIT_DB_USER}
      password: ${AGENT_AUDIT_DB_PASSWORD}
      driver-class-name: org.postgresql.Driver
```

### Snowflake runtime (+ optional split audit)

```yaml
camel:
  persistence:
    enabled: true
    backend: jdbc
    jdbc:
      url: jdbc:snowflake://<account>.snowflakecomputing.com/?db=<db>&schema=CAMEL_AGENT&warehouse=<wh>&role=<role>
      username: ${SNOWFLAKE_USER}
      password: ${SNOWFLAKE_PASSWORD}
      driver-class-name: net.snowflake.client.jdbc.SnowflakeDriver

agent:
  audit:
    backend: jdbc
    jdbc:
      url: jdbc:snowflake://<account>.snowflakecomputing.com/?db=<db>&schema=CAMEL_AGENT&warehouse=<wh>&role=<role>
      username: ${SNOWFLAKE_AUDIT_USER}
      password: ${SNOWFLAKE_AUDIT_PASSWORD}
      driver-class-name: net.snowflake.client.jdbc.SnowflakeDriver
```

## 3) Login authentication options

### PostgreSQL

- User/password (`username`, `password`)
- TLS + password (`sslmode=require`)
- mTLS (`sslmode=verify-full` + `sslcert`, `sslkey`, `sslrootcert` in JDBC URL)
- IAM/RDS token auth (token in `password`, short-lived)
- Kerberos/GSSAPI (driver/JVM setup required)

### Snowflake

- User/password (default)
- Key-pair auth (`privateKey` / `private_key_file` JDBC properties)
- OAuth (`authenticator=oauth`, token in `password`)
- SSO/external browser (`authenticator=externalbrowser`)
- Okta/native SSO (`authenticator=https://<okta-account>.okta.com`)

## 4) Purge scripts

Use scripts to remove persistence + audit data by criteria:

- closed conversations (`agent.instance.closed` marker)
- before date/time
- specific agent name/type (`payload.agentName`)

### PostgreSQL

Run [docs/db/postgres/02_purge_conversations.sql](docs/db/postgres/02_purge_conversations.sql).

Example:

```bash
psql "host=<host> dbname=<db> user=<user> sslmode=require" \
  -v purge_before_ts='2026-01-01T00:00:00Z' \
  -v purge_agent_name='support-agent' \
  -v purge_require_closed='true' \
  -f docs/db/postgres/02_purge_conversations.sql
```

### Snowflake

Run [docs/db/snowflake/02_purge_conversations.sql](docs/db/snowflake/02_purge_conversations.sql) after setting:

- `PURGE_BEFORE_TS`
- `PURGE_AGENT_NAME`
- `PURGE_REQUIRE_CLOSED`

## 5) Operational notes

- Close marker event is emitted as `agent.instance.closed` when `POST /runtime/conversation/{conversationId}/close` is called.
- Preview purge scope before DB deletes with:

```bash
curl -s "http://localhost:8080/runtime/purge/preview?requireClosed=true&before=2026-01-01T00:00:00Z&agentName=support-agent&limit=200&includeConversationIds=true"
```

- Preview endpoint is non-destructive and returns runtime-estimated conversation scope only.
- Purge permanently deletes matching records; run in non-prod first.
- If runtime and audit are split, execute purge in both stores.
