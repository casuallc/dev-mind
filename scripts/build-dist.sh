#!/usr/bin/env bash
# Build the Dev-Mind distribution package (tar.gz with bin/config/libs/web/data).
# Usage:
#   scripts/build-dist.sh                  # frontend build + maven dist assembly
#   scripts/build-dist.sh --skip-frontend  # reuse existing frontend/dist
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP_FRONTEND=0
for arg in "$@"; do
  case "$arg" in
    --skip-frontend) SKIP_FRONTEND=1 ;;
    *) echo "unknown argument: $arg (available: --skip-frontend)"; exit 1 ;;
  esac
done

if [ "$SKIP_FRONTEND" = "0" ]; then
  echo "[dist] building frontend..."
  (cd frontend && npm run build)
fi

if [ ! -d frontend/dist ]; then
  echo "[dist] ERROR: frontend/dist not found; run without --skip-frontend first" >&2
  exit 1
fi

echo "[dist] packaging (maven, profile=dist, skip tests)..."
mvn -q -DskipTests -Pdist package

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
PKG="devmind-dist/target/devmind-${VERSION}.tar.gz"
echo "[dist] done: $PKG"
echo "[dist] deploy: tar xzf $PKG && cd devmind-${VERSION} && bin/dev-mind start   (or: sudo bin/dev-mind install)"
