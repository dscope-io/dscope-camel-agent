#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRETS_FILE="${AGENT_SECRETS_FILE:-$SCRIPT_DIR/.agent-secrets.properties}"

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

mvn -f "$SCRIPT_DIR/pom.xml" \
  "${JAVA_PROPS[@]}" \
  -DskipTests \
  -Daudit.api.enabled=false \
  clean compile exec:java "$@"
