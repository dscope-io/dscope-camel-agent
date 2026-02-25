#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
GOOD_VOICE="${2:-cedar}"
BAD_VOICE="${3:-nova}"

sid_ok="voice-sync-$RANDOM"
curl -s -X POST "$BASE_URL/realtime/session/$sid_ok/init" \
  -H 'Content-Type: application/json' \
  -d "{\"session\":{\"audio\":{\"output\":{\"voice\":\"$GOOD_VOICE\"}}}}" \
  >/dev/null
ok_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/realtime/session/$sid_ok/token" -H 'Content-Type: application/json' -d '{}')

sid_bad="voice-sync-$RANDOM"
curl -s -X POST "$BASE_URL/realtime/session/$sid_bad/init" \
  -H 'Content-Type: application/json' \
  -d "{\"session\":{\"audio\":{\"output\":{\"voice\":\"$BAD_VOICE\"}}}}" \
  >/dev/null
bad_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/realtime/session/$sid_bad/token" -H 'Content-Type: application/json' -d '{}')

if [[ "$ok_code" != "200" ]]; then
  echo "❌ expected 200 for supported voice '$GOOD_VOICE', got $ok_code"
  exit 1
fi

if [[ "$bad_code" != "400" ]]; then
  echo "❌ expected 400 for unsupported voice '$BAD_VOICE', got $bad_code"
  exit 1
fi

echo "✅ realtime voice token checks passed (supported=$GOOD_VOICE => 200, unsupported=$BAD_VOICE => 400)"
