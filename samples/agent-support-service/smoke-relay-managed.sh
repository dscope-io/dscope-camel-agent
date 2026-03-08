#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY_SMOKE="$SCRIPT_DIR/smoke-relay-managed.py"
START_MODE="existing"
PORT="${AGENT_PORT:-}"
START_TIMEOUT_SEC="${AGENT_SMOKE_START_TIMEOUT_SEC:-45}"

ROUTES_INCLUDE_DEFAULT="classpath:routes/kb-search.camel.yaml,classpath:routes/kb-search-json.camel.xml,classpath:routes/ag-ui-platform.camel.yaml,classpath:routes/admin-platform.camel.yaml"
ROUTES_INCLUDE="${AGENT_ROUTES_INCLUDE_PATTERN:-$ROUTES_INCLUDE_DEFAULT}"

START_CMD=("$SCRIPT_DIR/run-sample.sh" "-Dagent.runtime.routes-include-pattern=${ROUTES_INCLUDE}")
if [[ "${AGENT_SMOKE_START_USE_CLASSES:-false}" == "true" ]]; then
  START_CMD=("$SCRIPT_DIR/run-sample-classes.sh")
fi

started_pid=""

usage() {
  cat <<'EOF'
Usage: samples/agent-support-service/smoke-relay-managed.sh [--existing|--start] [--port <n>]

Modes:
  --existing     Use currently running service only (default)
  --start        Start sample service if no healthy instance is found

Options:
  --port <n>     Preferred port to probe/start (otherwise probes 8080 then 8087)

Environment:
  AGENT_BASE_URL                     Explicit base URL for smoke script
  AGENT_PORT                         Preferred port for startup/probing
  AGENT_SMOKE_START_TIMEOUT_SEC      Wait timeout for started service (default: 45)
  AGENT_ROUTES_INCLUDE_PATTERN       Routes include pattern for run-sample.sh
  AGENT_SMOKE_START_USE_CLASSES      true -> use run-sample-classes.sh for startup
EOF
}

cleanup() {
  if [[ -n "$started_pid" ]]; then
    kill -15 "$started_pid" >/dev/null 2>&1 || true
    sleep 1
    kill -9 "$started_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

health_ok() {
  local url="$1"
  curl -sS -m 3 -o /dev/null "$url/health"
}

discover_base_url() {
  if [[ -n "${AGENT_BASE_URL:-}" ]]; then
    if health_ok "$AGENT_BASE_URL"; then
      printf '%s' "$AGENT_BASE_URL"
      return 0
    fi
    return 1
  fi

  local probes=()
  if [[ -n "$PORT" ]]; then
    probes+=("$PORT")
  else
    probes+=("8080" "8087")
  fi

  local p
  for p in "${probes[@]}"; do
    local candidate="http://localhost:${p}"
    if health_ok "$candidate"; then
      printf '%s' "$candidate"
      return 0
    fi
  done

  return 1
}

wait_for_health() {
  local url="$1"
  local timeout="$2"
  local start_ts
  start_ts="$(date +%s)"
  while true; do
    if health_ok "$url"; then
      return 0
    fi
    if (( "$(date +%s)" - start_ts >= timeout )); then
      return 1
    fi
    sleep 1
  done
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --existing)
      START_MODE="existing"
      shift
      ;;
    --start)
      START_MODE="start"
      shift
      ;;
    --port)
      PORT="${2:-}"
      if [[ -z "$PORT" ]]; then
        echo "Missing value for --port" >&2
        exit 2
      fi
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! -x "$PY_SMOKE" ]]; then
  echo "Smoke script not executable: $PY_SMOKE" >&2
  exit 1
fi

if base_url="$(discover_base_url)"; then
  export AGENT_BASE_URL="$base_url"
  echo "Using existing service: $AGENT_BASE_URL"
else
  if [[ "$START_MODE" != "start" ]]; then
    echo "No healthy service found. Rerun with --start or set AGENT_BASE_URL." >&2
    exit 1
  fi

  if [[ -n "$PORT" ]]; then
    export AGENT_PORT="$PORT"
  else
    export AGENT_PORT="8087"
  fi
  export AGENT_PORT_SCAN_MAX="0"

  echo "Starting sample service on port ${AGENT_PORT} using: ${START_CMD[*]}"
  "${START_CMD[@]}" >/dev/null 2>&1 &
  started_pid="$!"

  export AGENT_BASE_URL="http://localhost:${AGENT_PORT}"
  if ! wait_for_health "$AGENT_BASE_URL" "$START_TIMEOUT_SEC"; then
    echo "Service did not become healthy within ${START_TIMEOUT_SEC}s at $AGENT_BASE_URL" >&2
    exit 1
  fi
fi

exec "$PY_SMOKE"
