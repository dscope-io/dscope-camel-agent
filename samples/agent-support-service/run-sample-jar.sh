#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="${AGENT_SECRETS_FILE:-$SCRIPT_DIR/.agent-secrets.properties}"
PORT="${AGENT_PORT:-8080}"
JAVA_BIN="${JAVA_BIN:-java}"

ROUTES_INCLUDE_DEFAULT="classpath:routes/kb-search.camel.yaml,classpath:routes/kb-search-json.camel.xml,classpath:routes/ag-ui-platform.camel.yaml,classpath:routes/admin-platform.camel.yaml"
ROUTES_INCLUDE="${AGENT_ROUTES_INCLUDE_PATTERN:-$ROUTES_INCLUDE_DEFAULT}"

TARGET_JAR="$SCRIPT_DIR/target/agent-support-service-0.5.0.jar"
M2_JAR="$HOME/.m2/repository/io/dscope/camel/agent-support-service/0.5.0/agent-support-service-0.5.0.jar"

if [[ -f "$TARGET_JAR" ]]; then
  JAR_PATH="$TARGET_JAR"
elif [[ -f "$M2_JAR" ]]; then
  JAR_PATH="$M2_JAR"
else
  echo "No sample jar found. Expected one of:"
  echo "  - $TARGET_JAR"
  echo "  - $M2_JAR"
  echo "Build once with: mvn -DskipTests package"
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

echo "Starting agent-support-service from jar: $JAR_PATH"
echo "UI:     http://localhost:${PORT}/agui/ui"
echo "Health: http://localhost:${PORT}/health"

exec "$JAVA_BIN" \
  -Dopenai.api.key="$OPENAI_TOKEN" \
  -Dagui.rpc.port="$PORT" \
  -Dagui.health.port="$PORT" \
  -Daudit.api.enabled=false \
  -Dagent.runtime.routes-include-pattern="$ROUTES_INCLUDE" \
  -jar "$JAR_PATH"
