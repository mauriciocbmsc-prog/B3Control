#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
exec java --add-modules jdk.httpserver -cp 'lib/*' src/Main.java "$@"
