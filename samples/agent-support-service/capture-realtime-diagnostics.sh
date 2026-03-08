#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${AGENT_PORT:-8080}"
BASE_URL="${AGENT_BASE_URL:-http://localhost:${PORT}}"
LOG_FILE="${AGENT_CAPTURE_LOG_FILE:-$SCRIPT_DIR/diagnostics-capture.log}"
LAUNCHER="${AGENT_CAPTURE_LAUNCHER:-$SCRIPT_DIR/run-sample-classes.sh}"
START_TIMEOUT_SEC="${AGENT_CAPTURE_START_TIMEOUT_SEC:-45}"
START_MODE="${AGENT_CAPTURE_START_MODE:-existing}"

started_pid=""

cleanup() {
  if [[ -n "$started_pid" ]]; then
    kill -15 "$started_pid" >/dev/null 2>&1 || true
    sleep 1
    kill -9 "$started_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

is_listening() {
  local check_port="$1"
  lsof -nP -iTCP:"${check_port}" -sTCP:LISTEN >/dev/null 2>&1
}

wait_for_health() {
  local timeout="$1"
  local start_ts
  start_ts="$(date +%s)"
  while true; do
    if curl -sS -m 2 "${BASE_URL}/health" >/dev/null 2>&1; then
      return 0
    fi
    if (( "$(date +%s)" - start_ts >= timeout )); then
      return 1
    fi
    sleep 1
  done
}

ensure_service() {
  if is_listening "$PORT"; then
    echo "Using existing service on port ${PORT}" >&2
    return 0
  fi

  if [[ "$START_MODE" != "start" ]]; then
    echo "No service listening on ${PORT}. Start it first or rerun with --start." >&2
    return 1
  fi

  if [[ ! -x "$LAUNCHER" ]]; then
    echo "Launcher is not executable: $LAUNCHER" >&2
    return 1
  fi

  : > "$LOG_FILE"
  AGENT_PORT="$PORT" AGENT_PORT_SCAN_MAX=0 "$LAUNCHER" >"$LOG_FILE" 2>&1 &
  started_pid="$!"
  echo "Started temporary service PID ${started_pid} on port ${PORT}" >&2

  if ! wait_for_health "$START_TIMEOUT_SEC"; then
    echo "Service did not become healthy within ${START_TIMEOUT_SEC}s" >&2
    echo "--- launcher log tail ---" >&2
    tail -n 80 "$LOG_FILE" >&2 || true
    return 1
  fi

  return 0
}

post_event() {
  local cid="$1"
  local payload="$2"
  curl -sS -m 12 -H 'Content-Type: application/json' \
    -d "$payload" \
    "${BASE_URL}/realtime/session/${cid}/event" >/dev/null
}

run_capture() {
  local cid
  cid="diag-$(date +%s)"

  post_event "$cid" '{"type":"session.start","session":{"voice":"alloy","input_audio_transcription":{"model":"gpt-4o-mini-transcribe"},"turn_detection":{"type":"server_vad","create_response":false},"audio":{"input":{"transcription":{"model":"gpt-4o-mini-transcribe","language":"en"},"turn_detection":{"type":"server_vad","create_response":false}},"output":{"voice":"alloy"}}}}'
  post_event "$cid" '{"type":"transcript.observed","direction":"input","observedEventType":"conversation.item.input_audio_transcription.completed","transcript":"My login is failing, please open a support ticket","text":"My login is failing, please open a support ticket"}'
  post_event "$cid" '{"type":"session.state"}'
  post_event "$cid" '{"type":"transcript.final","text":"My login is failing, please open a support ticket"}'
  post_event "$cid" '{"type":"session.close"}'

  sleep 1

  echo "capture conversationId=${cid}"
  echo "log file=${LOG_FILE}"
  echo "--- matched diagnostics ---"
  if [[ -s "$LOG_FILE" ]]; then
    grep -E "${cid}|Realtime session.state diagnostics|Realtime transcript observed|Realtime transcript\.final received without transcript text|Realtime transcript received|Realtime transcript routed" "$LOG_FILE" || true
  else
    echo "No diagnostics found in ${LOG_FILE} (file missing or empty)."
    echo "Set AGENT_CAPTURE_LOG_FILE to your active server log file and rerun."
  fi
}

main() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --start)
        START_MODE="start"
        shift
        ;;
      --existing)
        START_MODE="existing"
        shift
        ;;
      *)
        echo "Unknown argument: $1" >&2
        echo "Usage: $0 [--existing|--start]" >&2
        exit 2
        ;;
    esac
  done

  ensure_service

  if [[ ! -f "$LOG_FILE" ]]; then
    : > "$LOG_FILE"
  fi

  run_capture
}

main "$@"
