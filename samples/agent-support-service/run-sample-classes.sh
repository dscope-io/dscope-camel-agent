#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="${AGENT_SECRETS_FILE:-$SCRIPT_DIR/.agent-secrets.properties}"
DEFAULT_PORT="${AGENT_PORT:-8080}"
MAX_PORT_SCAN="${AGENT_PORT_SCAN_MAX:-20}"
CLASSPATH_FILE="$SCRIPT_DIR/target/runtime.classpath"
MAIN_CLASS="io.dscope.camel.agent.samples.Main"
ROUTES_INCLUDE_DEFAULT="classpath:routes/kb-search.camel.yaml,classpath:routes/kb-search-json.camel.xml,classpath:routes/ag-ui-platform.camel.yaml,classpath:routes/admin-platform.camel.yaml"
ROUTES_INCLUDE="${AGENT_ROUTES_INCLUDE_PATTERN:-$ROUTES_INCLUDE_DEFAULT}"

JAVA_BIN="${JAVA_BIN:-java}"
if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  echo "Java runtime not found. Set JAVA_BIN or install Java."
  exit 1
fi

MAVEN_BIN=""
if [[ -x "/Users/roman/.sdkman/candidates/maven/current/bin/mvn" ]]; then
  MAVEN_BIN="/Users/roman/.sdkman/candidates/maven/current/bin/mvn"
elif command -v mvn >/dev/null 2>&1; then
  MAVEN_BIN="$(command -v mvn)"
fi

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

SELECTED_PORT="$(pick_port "$DEFAULT_PORT" "$MAX_PORT_SCAN")" || {
  echo "No free port found in range ${DEFAULT_PORT}..$((DEFAULT_PORT + MAX_PORT_SCAN))."
  echo "Set AGENT_PORT or stop existing listeners and retry."
  exit 1
}

if [[ "$SELECTED_PORT" != "$DEFAULT_PORT" ]]; then
  echo "Port ${DEFAULT_PORT} is busy; using ${SELECTED_PORT} for AGUI and health endpoints."
fi

if [[ ! -d "$SCRIPT_DIR/target/classes" ]]; then
  if [[ -z "$MAVEN_BIN" ]]; then
    echo "target/classes not found and Maven is unavailable to compile classes."
    exit 1
  fi
  echo "Compiling classes (one-time): $MAVEN_BIN -DskipTests compile"
  "$MAVEN_BIN" -f "$SCRIPT_DIR/pom.xml" -DskipTests compile
fi

if [[ ! -f "$CLASSPATH_FILE" ]]; then
  if [[ -z "$MAVEN_BIN" ]]; then
    echo "Missing $CLASSPATH_FILE and Maven is unavailable to generate dependency classpath."
    exit 1
  fi
  mkdir -p "$SCRIPT_DIR/target"
  echo "Generating dependency classpath: $CLASSPATH_FILE"
  "$MAVEN_BIN" -f "$SCRIPT_DIR/pom.xml" -DskipTests dependency:build-classpath -Dmdep.outputFile="$CLASSPATH_FILE"
fi

if [[ ! -s "$CLASSPATH_FILE" ]]; then
  echo "Dependency classpath file is empty: $CLASSPATH_FILE"
  exit 1
fi

OPENAI_TOKEN=""
if [[ -f "$SECRETS_FILE" ]]; then
  OPENAI_TOKEN="$(grep -E '^openai\.api\.key=' "$SECRETS_FILE" | sed -E 's/^openai\.api\.key=//' | tail -n 1 || true)"
fi

if [[ -z "$OPENAI_TOKEN" ]]; then
  echo "Missing openai.api.key in $SECRETS_FILE"
  exit 1
fi

RUNTIME_CP="$SCRIPT_DIR/target/classes:$(cat "$CLASSPATH_FILE")"

echo "Starting agent-support-service from compiled classes"
echo "UI:     http://localhost:${SELECTED_PORT}/agui/ui"
echo "Health: http://localhost:${SELECTED_PORT}/health"
echo "Hot edit path: $SCRIPT_DIR/src/main/resources/frontend"

exec "$JAVA_BIN" \
  -cp "$RUNTIME_CP" \
  -Dopenai.api.key="$OPENAI_TOKEN" \
  -Dagui.rpc.port="$SELECTED_PORT" \
  -Dagui.health.port="$SELECTED_PORT" \
  -Dagui.ui.static-root="$SCRIPT_DIR/src/main/resources/frontend" \
  -Daudit.api.enabled=false \
  -Dagent.runtime.routes-include-pattern="$ROUTES_INCLUDE" \
  "$MAIN_CLASS"
