#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
java -version
mkdir -p lib
jar=lib/h2-2.3.232.jar
expected=8dae62d22db8982c3dcb3826edb9c727c5d302063a67eef7d63d82de401f07d3
if ! test -f "$jar"; then
  curl -fL https://repo.maven.apache.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar -o "$jar.download"
  printf '%s  %s\n' "$expected" "$jar.download" | sha256sum -c -
  mv "$jar.download" "$jar"
fi
printf '%s  %s\n' "$expected" "$jar" | sha256sum -c -
