#!/usr/bin/env bash
# Build the Dev-Mind distribution package (tar.gz with bin/config/libs/ui/data).
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

echo "[dist] packaging (maven clean package, profile=dist, skip tests)..."
# 安装包内置固定默认主密钥 data/auth.key（入库的公开初始值，等价默认口令 admin/admin123；
# 安装后务必 bin/dev-mind rotate-key 轮换为本机专属）。缺失说明仓库不完整，直接失败，禁静默打出无密钥包。
DIST_DATA=devmind-dist/src/main/dist/data
if [ ! -s "$DIST_DATA/auth.key" ]; then
  echo "[dist] ERROR: $DIST_DATA/auth.key missing (tracked default key)" >&2
  exit 1
fi
# server-crypto.key 是已下线 server-adapter 的遗物（无任何代码引用），落在这会被一起打进包
rm -f "$DIST_DATA/server-crypto.key"
# 必须 clean：增量构建不会清理 target/classes 里已删源码的残留资源
# （2026-09-10 事故：残留 spring.factories 注册已删类 → 部署启动 ClassNotFoundException）
mvn -q -DskipTests -Pdist clean package

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
PKG="devmind-dist/target/devmind-${VERSION}.tar.gz"
echo "[dist] done: $PKG"
echo "[dist] deploy: tar xzf $PKG && cd devmind-${VERSION} && bin/dev-mind start   (or: sudo bin/dev-mind install)"
