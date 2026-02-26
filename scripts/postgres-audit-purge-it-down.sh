#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker/postgres/local-compose.yaml"

echo "Stopping local Postgres test stack and removing volumes..."
docker compose -f "$COMPOSE_FILE" down -v --remove-orphans
echo "Local Postgres test stack removed."
