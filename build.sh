#!/usr/bin/env sh
set -eu
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 8.12.1 is required. Install it, then rerun ./build.sh."
  exit 1
fi
gradle build
