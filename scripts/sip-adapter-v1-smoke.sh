#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
CONVERSATION_ID="${2:-sip:smoke:call-$RANDOM}"

tmp_start="/tmp/sip-adapter-start-$RANDOM.json"
tmp_turn="/tmp/sip-adapter-turn-$RANDOM.json"
tmp_end="/tmp/sip-adapter-end-$RANDOM.json"

cleanup() {
  rm -f "$tmp_start" "$tmp_turn" "$tmp_end"
}
trap cleanup EXIT

echo "SIP adapter smoke: base=$BASE_URL conversationId=$CONVERSATION_ID"

status_start=$(curl -sS -o "$tmp_start" -w "%{http_code}" \
  -X POST "$BASE_URL/sip/adapter/v1/session/$CONVERSATION_ID/start" \
  -H 'Content-Type: application/json' \
  -d '{"call":{"id":"smoke-call-1","from":"+15551230001","to":"+15557650002"},"session":{"audio":{"output":{"voice":"alloy"}}}}')

if [[ "$status_start" != "200" ]]; then
  echo "start failed: HTTP $status_start"
  cat "$tmp_start"
  exit 1
fi

status_turn=$(curl -sS -o "$tmp_turn" -w "%{http_code}" \
  -X POST "$BASE_URL/sip/adapter/v1/session/$CONVERSATION_ID/turn" \
  -H 'Content-Type: application/json' \
  -d '{"text":"My login is failing, please open a support ticket"}')

if [[ "$status_turn" != "200" ]]; then
  echo "turn failed: HTTP $status_turn"
  cat "$tmp_turn"
  exit 1
fi

status_end=$(curl -sS -o "$tmp_end" -w "%{http_code}" \
  -X POST "$BASE_URL/sip/adapter/v1/session/$CONVERSATION_ID/end" \
  -H 'Content-Type: application/json' \
  -d '{}')

if [[ "$status_end" != "200" ]]; then
  echo "end failed: HTTP $status_end"
  cat "$tmp_end"
  exit 1
fi

python3 - "$tmp_start" "$tmp_turn" "$tmp_end" "$CONVERSATION_ID" <<'PY'
import json
import sys

start_path, turn_path, end_path, expected_id = sys.argv[1:]

with open(start_path, 'r', encoding='utf-8') as f:
    start = json.load(f)
with open(turn_path, 'r', encoding='utf-8') as f:
    turn = json.load(f)
with open(end_path, 'r', encoding='utf-8') as f:
    end = json.load(f)

session = start.get('session', {})
if session.get('type') != 'realtime':
    raise SystemExit('start response missing realtime session type')

if not isinstance(turn, dict) or 'accepted' not in turn:
    raise SystemExit('turn response missing expected realtime acknowledgment fields')

if end.get('ended') is not True or end.get('conversationId') != expected_id:
    raise SystemExit('end response missing ended=true or conversationId mismatch')

print('✅ SIP adapter v1 smoke passed')
print('start.session.type=', session.get('type'))
print('turn.accepted=', turn.get('accepted'))
print('end.ended=', end.get('ended'))
PY
