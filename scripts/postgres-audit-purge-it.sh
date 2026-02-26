#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker/postgres/local-compose.yaml"
DDL_FILE="$ROOT_DIR/docs/db/postgres/01_camel_persistence_schema.sql"
POSTGRES_CONTAINER="camel-agent-pg-local"

RUNTIME_DB="${IT_POSTGRES_RUNTIME_DB:-agent_runtime}"
AUDIT_DB="${IT_POSTGRES_AUDIT_DB:-agent_audit}"
RUNTIME_IT_DB="${IT_POSTGRES_RUNTIME_IT_DB:-agent_runtime_it}"
AUDIT_IT_DB="${IT_POSTGRES_AUDIT_IT_DB:-agent_audit_it}"
PG_USER="${IT_POSTGRES_USER:-agent}"
PG_PASSWORD="${IT_POSTGRES_PASSWORD:-agent}"
PG_PORT="${IT_POSTGRES_PORT:-55432}"

echo "[1/5] Starting local Postgres container..."
docker compose -f "$COMPOSE_FILE" up -d

echo "[2/5] Waiting for Postgres health..."
for _ in {1..40}; do
  if docker exec "$POSTGRES_CONTAINER" pg_isready -U "$PG_USER" -d postgres >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker exec "$POSTGRES_CONTAINER" pg_isready -U "$PG_USER" -d postgres >/dev/null

echo "[3/5] Creating runtime/audit databases and applying DDL..."
docker exec -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" \
  psql -U "$PG_USER" -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $RUNTIME_DB;" || true
docker exec -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" \
  psql -U "$PG_USER" -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $AUDIT_DB;" || true
docker exec -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" \
  psql -U "$PG_USER" -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $RUNTIME_IT_DB;" || true
docker exec -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" \
  psql -U "$PG_USER" -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $AUDIT_IT_DB;" || true

cat "$DDL_FILE" | docker exec -i -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" \
  psql -U "$PG_USER" -d "$RUNTIME_DB" -v ON_ERROR_STOP=1
cat "$DDL_FILE" | docker exec -i -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" \
  psql -U "$PG_USER" -d "$AUDIT_DB" -v ON_ERROR_STOP=1

# Compatibility DBs for current JDBC persistence module (expects CLOB type).
cat <<'SQL' | docker exec -i -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" psql -U "$PG_USER" -d "$RUNTIME_IT_DB" -v ON_ERROR_STOP=1
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'clob') THEN
    CREATE DOMAIN clob AS text;
  END IF;
END;
$$;
SQL

cat <<'SQL' | docker exec -i -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" psql -U "$PG_USER" -d "$AUDIT_IT_DB" -v ON_ERROR_STOP=1
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'clob') THEN
    CREATE DOMAIN clob AS text;
  END IF;
END;
$$;
SQL

RUNTIME_URL="jdbc:postgresql://localhost:${PG_PORT}/${RUNTIME_IT_DB}?user=${PG_USER}&password=${PG_PASSWORD}"
AUDIT_URL="jdbc:postgresql://localhost:${PG_PORT}/${AUDIT_IT_DB}?user=${PG_USER}&password=${PG_PASSWORD}"

echo "[4/5] Running Postgres persistence/audit/purge integration test..."
cd "$ROOT_DIR"
mvn -q -f samples/agent-support-service/pom.xml \
  -Ppostgres-it \
  -Dtest=PostgresPersistenceAuditPurgeIntegrationTest \
  -Dit.postgres.runtime.url="$RUNTIME_URL" \
  -Dit.postgres.audit.url="$AUDIT_URL" \
  -Dit.postgres.user="$PG_USER" \
  -Dit.postgres.password="$PG_PASSWORD" \
  test

echo "[5/5] Showing DB verification counts (should be near-zero after purge test)..."
docker exec -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" \
  psql -U "$PG_USER" -d "$RUNTIME_IT_DB" -v ON_ERROR_STOP=1 -c "SELECT flow_type, COUNT(*) AS cnt FROM camel_flow_snapshot GROUP BY flow_type ORDER BY flow_type;"
docker exec -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" \
  psql -U "$PG_USER" -d "$AUDIT_IT_DB" -v ON_ERROR_STOP=1 -c "SELECT flow_type, COUNT(*) AS cnt FROM camel_flow_event GROUP BY flow_type ORDER BY flow_type;"

echo "DDL deployment verification (runtime/audit DBs):"
docker exec -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" \
  psql -U "$PG_USER" -d "$RUNTIME_DB" -v ON_ERROR_STOP=1 -c "SELECT COUNT(*) AS runtime_snapshot_tables FROM information_schema.tables WHERE table_name = 'camel_flow_snapshot';"
docker exec -e PGPASSWORD="$PG_PASSWORD" "$POSTGRES_CONTAINER" \
  psql -U "$PG_USER" -d "$AUDIT_DB" -v ON_ERROR_STOP=1 -c "SELECT COUNT(*) AS audit_event_tables FROM information_schema.tables WHERE table_name = 'camel_flow_event';"

echo "Completed. Postgres remains running at localhost:${PG_PORT}."