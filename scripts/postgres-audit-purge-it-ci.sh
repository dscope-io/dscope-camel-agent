#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEEP_STACK="${POSTGRES_IT_KEEP_DOCKER:-false}"

if ! command -v docker >/dev/null 2>&1; then
  echo "SKIP: docker is not installed on this runner."
  exit 0
fi

if ! docker info >/dev/null 2>&1; then
  echo "SKIP: docker daemon is not available on this runner."
  exit 0
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "SKIP: docker compose is not available on this runner."
  exit 0
fi

cleanup() {
  if [[ "$KEEP_STACK" == "true" ]]; then
    echo "Keeping local Postgres stack running because POSTGRES_IT_KEEP_DOCKER=true"
    return
  fi
  bash "$ROOT_DIR/scripts/postgres-audit-purge-it-down.sh" || true
}
trap cleanup EXIT

echo "Docker detected. Running Postgres persistence/audit/purge integration workflow..."
bash "$ROOT_DIR/scripts/postgres-audit-purge-it.sh"
