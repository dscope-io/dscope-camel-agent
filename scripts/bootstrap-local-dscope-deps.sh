#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
AGUI_DIR="${AGUI_DIR:-/Users/roman/Projects/DScope/CamelAGUIComponent}"
PERSISTENCE_DIR="${PERSISTENCE_DIR:-/Users/roman/Projects/DScope/CamelPersistence}"

printf '\n==> Installing local dscope-camel-persistence from %s\n' "$PERSISTENCE_DIR"
mvn -q -f "$PERSISTENCE_DIR/pom.xml" clean install

printf '\n==> Installing local dscope-camel-agui from %s\n' "$AGUI_DIR"
mvn -q -f "$AGUI_DIR/camel-ag-ui-component/pom.xml" clean install

printf '\n==> Verifying camel-agent build with local integration profile\n'
mvn -q -f "$ROOT_DIR/pom.xml" -Pdscope-local test

printf '\nBootstrap complete.\n'
