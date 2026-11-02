#!/usr/bin/env bash
# Build the Dev-Mind distribution package (tar.gz with bin/config/libs/ui/data).
# Usage:
#   scripts/build-dist.sh                  # frontend build + maven dist assembly
#   scripts/build-dist.sh --skip-frontend  # reuse existing frontend/dist
#   scripts/build-dist.sh --skip-changelog # 不打更新日志新条目（CAP-87 M4，默认自动生成）
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP_FRONTEND=0
SKIP_CHANGELOG=0
for arg in "$@"; do
  case "$arg" in
    --skip-frontend) SKIP_FRONTEND=1 ;;
    --skip-changelog) SKIP_CHANGELOG=1 ;;
    *) echo "unknown argument: $arg (available: --skip-frontend / --skip-changelog)"; exit 1 ;;
  esac
done

# CAP-87 M4：打包前自动生成更新日志发布段落（构建即版本锚点，条目记录的提交=打包时 HEAD，
# 与 jar 内 git.properties 一致）；python 按 python3 → python → py -3 探测
PY=""
if [ "$SKIP_CHANGELOG" = "0" ]; then
  # 必须实跑一次验证：Windows 应用商店的 python3.exe 占位 stub 能被 command -v 查到，
  # 但一执行就以 49 退出（2026-11-02 部署即退事故），会把真 python 挤掉
  for c in python3 python "py -3"; do
    if $c -c "pass" >/dev/null 2>&1; then PY=$c; break; fi
  done
  if [ -z "$PY" ]; then
    echo "[dist] ERROR: 找不到 python3/python/py，无法生成更新日志条目（可用 --skip-changelog 跳过）" >&2
    exit 1
  fi
  echo "[dist] generating changelog entry..."
  $PY scripts/changelog-gen.py
fi

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

# 打包成功后把更新日志改动自动入库（单文件提交、不 push）；失败不阻断已产出的包
if [ "$SKIP_CHANGELOG" = "0" ]; then
  $PY scripts/changelog-gen.py --commit || echo "[dist] WARN: CHANGELOG 自动提交失败，请手工提交" >&2
fi

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
PKG="devmind-dist/target/devmind-${VERSION}.tar.gz"
echo "[dist] done: $PKG"
echo "[dist] deploy: tar xzf $PKG && cd devmind-${VERSION} && bin/dev-mind start   (or: sudo bin/dev-mind install)"
