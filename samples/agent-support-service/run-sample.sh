#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="${AGENT_SECRETS_FILE:-$SCRIPT_DIR/.agent-secrets.properties}"
DEFAULT_PORT="${AGENT_PORT:-8080}"
MAX_PORT_SCAN="${AGENT_PORT_SCAN_MAX:-20}"
API_MODE="${AGENT_OPENAI_API_MODE:-}"

print_help() {
  cat <<'EOF'
Usage: samples/agent-support-service/run-sample.sh [options] [--] [maven/exec args]

Convenience options:
  --chat                 Use OpenAI chat mode
  --responses-ws         Use OpenAI responses-ws mode
  --api-mode=<mode>      Explicit API mode override
  -h, --help             Show this help

Common JVM property overrides (pass as -D...):
  -Dcamel.persistence.backend=redis_jdbc|jdbc
  -Dcamel.persistence.jdbc.url=jdbc:postgresql://localhost:55432/agent_runtime?user=agent&password=agent
  -Dcamel.persistence.jdbc.driver-class-name=org.postgresql.Driver
  -Dcamel.persistence.jdbc.schema.ddl-resource=classpath:db/persistence/postgres-flow-state.sql
  -Dagent.runtime.spring-ai.provider=openai|ollama|gemini|claude

Environment:
  AGENT_PORT              Preferred HTTP port (default: 8080)
  AGENT_PORT_SCAN_MAX     Scan range if preferred port is busy (default: 20)
  AGENT_SECRETS_FILE      Secrets properties file path (default: samples/agent-support-service/.agent-secrets.properties)
EOF
}

FORWARDED_ARGS=()
for arg in "$@"; do
  if [[ "$arg" == "--help" || "$arg" == "-h" ]]; then
    print_help
    exit 0
  fi
  if [[ "$arg" == "--responses-ws" ]]; then
    API_MODE="responses-ws"
    continue
  fi
  if [[ "$arg" == "--chat" ]]; then
    API_MODE="chat"
    continue
  fi
  if [[ "$arg" == --api-mode=* ]]; then
    API_MODE="${arg#--api-mode=}"
    continue
  fi
  FORWARDED_ARGS+=("$arg")
done

is_port_busy() {
  local port="$1"
  lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
}

pick_port() {
  local preferred="$1"
  local max_scan="$2"

  if ! is_port_busy "$preferred"; then
    printf '%s' "$preferred"
    return 0
  fi

  local candidate
  for ((candidate=preferred+1; candidate<=preferred+max_scan; candidate++)); do
    if ! is_port_busy "$candidate"; then
      printf '%s' "$candidate"
      return 0
    fi
  done

  return 1
}

JAVA_PROPS=()
if [[ -f "$SECRETS_FILE" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="$(printf '%s' "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -z "$line" || "${line:0:1}" == "#" ]] && continue
    [[ "$line" != *"="* ]] && continue
    key="${line%%=*}"
    value="${line#*=}"
    key="$(printf '%s' "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    value="$(printf '%s' "$value" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -z "$key" ]] && continue
    JAVA_PROPS+=("-D${key}=${value}")
  done < "$SECRETS_FILE"
fi

SELECTED_PORT="$(pick_port "$DEFAULT_PORT" "$MAX_PORT_SCAN")" || {
  echo "No free port found in range ${DEFAULT_PORT}..$((DEFAULT_PORT + MAX_PORT_SCAN))."
  echo "Set AGENT_PORT or stop existing listeners and retry."
  exit 1
}

if [[ "$SELECTED_PORT" != "$DEFAULT_PORT" ]]; then
  echo "Port ${DEFAULT_PORT} is busy; using ${SELECTED_PORT} for AGUI and health endpoints."
fi

JAVA_PROPS+=("-Dagui.rpc.port=${SELECTED_PORT}" "-Dagui.health.port=${SELECTED_PORT}")

if [[ -n "$API_MODE" ]]; then
  JAVA_PROPS+=("-DAGENT_OPENAI_API_MODE=${API_MODE}" "-Dagent.runtime.spring-ai.openai.api-mode=${API_MODE}")
fi

echo "Starting agent-support-service on port ${SELECTED_PORT}"
echo "UI:     http://localhost:${SELECTED_PORT}/agui/ui"
echo "Health: http://localhost:${SELECTED_PORT}/health"
echo "Tip:    override JDBC schema DDL with -Dcamel.persistence.jdbc.schema.ddl-resource=classpath:db/persistence/postgres-flow-state.sql"
if [[ -n "$API_MODE" ]]; then
  echo "OpenAI api-mode override: ${API_MODE}"
fi

mvn -f "$SCRIPT_DIR/pom.xml" \
  "${JAVA_PROPS[@]}" \
  -DskipTests \
  -Daudit.api.enabled=false \
  clean compile exec:java ${FORWARDED_ARGS+"${FORWARDED_ARGS[@]}"}
