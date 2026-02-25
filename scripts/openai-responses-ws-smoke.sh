#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_MODE="${AGENT_OPENAI_API_MODE:-responses-ws}"
SECRETS_FILE="${AGENT_SECRETS_FILE:-$ROOT_DIR/samples/agent-support-service/.agent-secrets.properties}"

if [[ -z "${OPENAI_API_KEY:-}" && -f "$SECRETS_FILE" ]]; then
  extracted_key=$(grep -E '^\s*(openai\.api\.key|OPENAI_API_KEY)\s*=' "$SECRETS_FILE" | tail -n 1 | sed 's/^[^=]*=//;s/^\s*//;s/\s*$//')
  if [[ -n "$extracted_key" ]]; then
    export OPENAI_API_KEY="$extracted_key"
  fi
fi

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  echo "OPENAI_API_KEY is not set"
  echo "Set OPENAI_API_KEY or provide $SECRETS_FILE with openai.api.key=..."
  exit 1
fi

pushd "$ROOT_DIR" >/dev/null

if lsof -ti tcp:8080 >/dev/null 2>&1; then
  lsof -ti tcp:8080 | xargs -r kill -9
  sleep 1
fi

LOG_FILE="/tmp/openai-responses-ws-smoke.log"
: > "$LOG_FILE"

mvn -q -f samples/agent-support-service/pom.xml -DskipTests -Daudit.api.enabled=false -Dagent.openai.api.mode="$API_MODE" -Dopenai.api.key="$OPENAI_API_KEY" clean compile exec:java >"$LOG_FILE" 2>&1 &
APP_PID=$!

cleanup() {
  kill "$APP_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for _ in $(seq 1 120); do
  if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
    echo "service process exited before becoming healthy"
    tail -n 180 "$LOG_FILE" | cat
    exit 1
  fi
  if curl -s "http://localhost:8080/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -s "http://localhost:8080/health" >/dev/null 2>&1; then
  echo "service did not become healthy"
  tail -n 120 "$LOG_FILE" | cat
  exit 1
fi

RESPONSE_FILE="/tmp/openai-responses-ws-smoke-response.txt"
curl -sN -X POST "http://localhost:8080/agui/agent" \
  -H 'Content-Type: application/json' \
  -d '{"threadId":"responses-ws-smoke-1","sessionId":"responses-ws-smoke-1","messages":[{"role":"user","content":"My login is failing, please open a support ticket"}]}' \
  > "$RESPONSE_FILE"

if ! grep -qE 'RUN_FINISHED|support.ticket.open|ticket' "$RESPONSE_FILE"; then
  echo "smoke response did not contain expected signals"
  echo "--- response ---"
  head -n 120 "$RESPONSE_FILE" | cat
  echo "--- app log ---"
  tail -n 180 "$LOG_FILE" | cat
  exit 1
fi

if grep -qiE 'invalid_api_key|incorrect api key|OPENAI invocation error|responses-ws invocation error' "$RESPONSE_FILE"; then
  echo "smoke response contains provider/auth errors"
  echo "--- response ---"
  head -n 120 "$RESPONSE_FILE" | cat
  echo "--- app log ---"
  tail -n 180 "$LOG_FILE" | cat
  exit 1
fi

echo "responses-ws smoke passed"
echo "api-mode used: $API_MODE"
head -n 40 "$RESPONSE_FILE" | cat
