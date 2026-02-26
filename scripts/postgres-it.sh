#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-local}"

usage() {
  cat <<'USAGE'
Usage:
  bash scripts/postgres-it.sh [local|ci|down]

Modes:
  local  Start Docker Postgres, deploy DDL, run Postgres integration test, verify purge (default)
  ci     Same as local, but auto-skip if Docker is unavailable and auto-teardown by default
  down   Stop and remove local Postgres Docker stack + volumes
USAGE
}

case "$MODE" in
  local)
    bash "$ROOT_DIR/scripts/postgres-audit-purge-it.sh"
    ;;
  ci)
    bash "$ROOT_DIR/scripts/postgres-audit-purge-it-ci.sh"
    ;;
  down)
    bash "$ROOT_DIR/scripts/postgres-audit-purge-it-down.sh"
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    echo "Unknown mode: $MODE" >&2
    usage
    exit 2
    ;;
esac
