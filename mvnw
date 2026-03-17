#!/usr/bin/env sh
set -eu

if [ -n "${MVN_CMD:-}" ] && [ -x "${MVN_CMD}" ]; then
  exec "${MVN_CMD}" "$@"
fi

if [ -n "${MAVEN_HOME:-}" ] && [ -x "${MAVEN_HOME}/bin/mvn" ]; then
  exec "${MAVEN_HOME}/bin/mvn" "$@"
fi

if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

if [ -x "/Users/roman/.sdkman/candidates/maven/current/bin/mvn" ]; then
  exec "/Users/roman/.sdkman/candidates/maven/current/bin/mvn" "$@"
fi

echo "Error: Maven executable not found. Set MVN_CMD, MAVEN_HOME, or install Maven on PATH." >&2
exit 1
