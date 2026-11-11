#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if [ -n "${GUGUPAN_JAVA_HOME:-}" ]; then
  JAVA_HOME="$GUGUPAN_JAVA_HOME"
  PATH="$JAVA_HOME/bin:$PATH"
  export JAVA_HOME PATH
fi
(cd frontend && npm ci --no-audit --no-fund && npm run build)
mvn -B clean verify
printf '%s\n' '构建完成：target/gugupan.jar'
