#!/usr/bin/env bash
# 一键更新 172.20.140.224:/apusic/dev-mind 环境。
#
# 流程：本地构建 dist 包 → scp 上传 → 远端 停服/备份/换包（保留 application-local.yml 与 data/）
#       → 可选更新配置、执行 SQL → 重启并健康检查；启动失败自动回滚备份并再次拉起。
#
# 用法：
#   scripts/deploy-224.sh                       # 全量：前端+后端构建、上传、重启
#   scripts/deploy-224.sh --skip-frontend       # 复用已有 frontend/dist（只重打后端包）
#   scripts/deploy-224.sh --skip-build          # 复用 devmind-dist/target 下最新 tar.gz，不重新构建
#   scripts/deploy-224.sh --config <file>       # 上传为远端 config/application-local.yml（先备份原文件）
#   scripts/deploy-224.sh --sql <file.sql>      # 应用停止期间对 PG 执行（java 单文件源码 + libs 里的 JDBC 驱动，
#                                               # 连接信息读远端 application-local.yml；整文件一个事务，
#                                               # 语句以「分号+换行」分隔，仅适合简单 DDL/DML）
#   scripts/deploy-224.sh --no-restart          # 只换包不重启（人工起）
#
# 环境变量（可复用到 140.143 等同布局环境）：
#   DEPLOY_HOST=root@172.20.140.224   DEPLOY_DIR=/apusic/dev-mind
set -euo pipefail
cd "$(dirname "$0")/.."

DEPLOY_HOST="${DEPLOY_HOST:-root@172.20.140.224}"
DEPLOY_DIR="${DEPLOY_DIR:-/apusic/dev-mind}"

SKIP_FRONTEND=0
SKIP_BUILD=0
DO_RESTART=1
CONFIG_FILE=""
SQL_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-frontend) SKIP_FRONTEND=1 ;;
    --skip-build)    SKIP_BUILD=1 ;;
    --no-restart)    DO_RESTART=0 ;;
    --config)        CONFIG_FILE="${2:?ERROR: --config 需要紧跟文件路径参数}"; shift ;;
    --sql)           SQL_FILE="${2:?ERROR: --sql 需要紧跟文件路径参数}"; shift ;;
    *) echo "unknown argument: $1 (see header usage)"; exit 1 ;;
  esac
  shift
done
[ -z "$CONFIG_FILE" ] || [ -f "$CONFIG_FILE" ] || { echo "ERROR: config file not found: $CONFIG_FILE"; exit 1; }
[ -z "$SQL_FILE" ]    || [ -f "$SQL_FILE" ]    || { echo "ERROR: sql file not found: $SQL_FILE"; exit 1; }

# 本机默认 java 是 17，mvn 必须显式 JDK 21（见 docs/core/开发注意事项.md）
if [ -z "${JAVA_HOME:-}" ] && [ -d "/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot" ]; then
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
fi

# ---- 1. 本地构建 ----
if [ "$SKIP_BUILD" = "0" ]; then
  if [ "$SKIP_FRONTEND" = "1" ]; then
    scripts/build-dist.sh --skip-frontend
  else
    scripts/build-dist.sh
  fi
fi
PKG_LOCAL="$(ls -t devmind-dist/target/devmind-*.tar.gz 2>/dev/null | head -1)"
[ -n "$PKG_LOCAL" ] || { echo "ERROR: devmind-dist/target 下没有 devmind-*.tar.gz，去掉 --skip-build 重新构建"; exit 1; }
PKG="$(basename "$PKG_LOCAL")"
echo "[deploy] package: $PKG_LOCAL"
echo "[deploy] target : $DEPLOY_HOST:$DEPLOY_DIR  restart=$DO_RESTART config=${CONFIG_FILE:-none} sql=${SQL_FILE:-none}"

# ---- 2. 上传包 / 配置 / SQL 到远端待应用目录 ----
ssh "$DEPLOY_HOST" "mkdir -p '$DEPLOY_DIR/.deploy-incoming'"
scp -q "$PKG_LOCAL" "$DEPLOY_HOST:$DEPLOY_DIR/.deploy-incoming/$PKG"
DO_CONFIG=0
DO_SQL=0
if [ -n "$CONFIG_FILE" ]; then
  scp -q "$CONFIG_FILE" "$DEPLOY_HOST:$DEPLOY_DIR/.deploy-incoming/application-local.yml"
  DO_CONFIG=1
fi
if [ -n "$SQL_FILE" ]; then
  scp -q "$SQL_FILE" "$DEPLOY_HOST:$DEPLOY_DIR/.deploy-incoming/update.sql"
  DO_SQL=1
fi
echo "[deploy] uploaded; starting remote update..."

# ---- 3. 远端更新（停服→备份→换包→配置/SQL→重启→失败回滚） ----
ssh "$DEPLOY_HOST" "DEPLOY_DIR='$DEPLOY_DIR' PKG='$PKG' DO_CONFIG=$DO_CONFIG DO_SQL=$DO_SQL DO_RESTART=$DO_RESTART bash -s" <<'REMOTE'
set -euo pipefail
cd "$DEPLOY_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
BK="backup/$TS"
IN=".deploy-incoming"
NEW=".deploy-new-$TS"
mkdir -p "$BK/config" "$NEW"

echo "[remote] stop dev-mind..."
bin/dev-mind stop || true

tar xzf "$IN/$PKG" -C "$NEW" --strip-components=1

rollback() {
  echo "[remote] !! start failed, rolling back to $BK"
  for d in bin libs ui runner; do
    [ -d "$BK/$d" ] || continue
    rm -rf "$d"; mv "$BK/$d" "$d"
  done
  for f in "$BK"/config/*; do
    [ -f "$f" ] && cp "$f" "config/$(basename "$f")"
  done
  bin/dev-mind start || { echo "[remote] !! rollback start also failed, see logs/console.out"; exit 1; }
  echo "[remote] rolled back and restarted old version"
  exit 1
}

# 换包：bin/libs/ui/runner 整体替换（libs 不合并，防旧 jar 残留）
for d in bin libs ui runner; do
  [ -d "$NEW/$d" ] || continue
  [ ! -d "$d" ] || mv "$d" "$BK/"
  mv "$NEW/$d" "$d"
done
chmod +x bin/dev-mind bin/dev-mind-agent 2>/dev/null || true

# config：只更新随包默认配置，application-local.yml 是机器专属覆盖，永远保留
for f in "$NEW"/config/*; do
  base="$(basename "$f")"
  [ "$base" = "application-local.yml" ] && continue
  [ ! -f "config/$base" ] || cp "config/$base" "$BK/config/"
  cp "$f" "config/$base"
done
rm -rf "$NEW"

# 上传的 application-local.yml（--config）
if [ "$DO_CONFIG" = "1" ]; then
  [ ! -f config/application-local.yml ] || cp config/application-local.yml "$BK/config/"
  cp "$IN/application-local.yml" config/application-local.yml
  echo "[remote] application-local.yml updated (old backed up)"
fi

# SQL（--sql）：JDK 单文件源码模式 + libs 里的 PG 驱动，连接信息读 application-local.yml
if [ "$DO_SQL" = "1" ]; then
  yval() {
    grep -E "^[[:space:]]*$2:" "$1" | head -1 \
      | sed -E "s/^[[:space:]]*$2:[[:space:]]*//; s/[[:space:]]+$//; s/^'(.*)'\$/\1/; s/^\"(.*)\"\$/\1/"
  }
  export JDBC_URL="$(yval config/application-local.yml url || true)"
  export DB_USER="$(yval config/application-local.yml username || true)"
  export DB_PASS="$(yval config/application-local.yml password || true)"
  [ -n "$JDBC_URL" ] || { echo "[remote] ERROR: 无法从 application-local.yml 解析 datasource.url"; rollback; }
  PGJAR="$(ls libs/postgresql-*.jar 2>/dev/null | head -1)"
  [ -n "$PGJAR" ] || { echo "[remote] ERROR: libs 下找不到 postgresql JDBC 驱动"; rollback; }
  # java 解析与 bin/dev-mind 同序：APUSIC_JAVA_HOME > JAVA_HOME > PATH > 常见安装目录
  JAVA_BIN=""
  for c in "${APUSIC_JAVA_HOME:-}/bin/java" "${JAVA_HOME:-}/bin/java" "$(command -v java || true)" /home/jdk-21*/bin/java /apusic/jdk-21*/bin/java /usr/lib/jvm/*21*/bin/java; do
    [ -n "$c" ] && [ -x "$c" ] && { JAVA_BIN="$c"; break; }
  done
  [ -n "$JAVA_BIN" ] || { echo "[remote] ERROR: 找不到 JDK 21"; rollback; }
  cat > "$IN/RunSql.java" <<'JAVA'
import java.sql.*;
import java.nio.file.*;

public class RunSql {
    public static void main(String[] args) throws Exception {
        String sql = Files.readString(Path.of(args[0]));
        try (Connection c = DriverManager.getConnection(
                System.getenv("JDBC_URL"), System.getenv("DB_USER"), System.getenv("DB_PASS"))) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (String s : sql.split(";\\s*\\R")) {
                    String q = s.replaceAll("(?m)^\\s*--.*$", "").trim();
                    if (q.isEmpty()) continue;
                    System.out.println("[sql] >> " + q.lines().findFirst().orElse(""));
                    st.execute(q);
                }
            }
            c.commit();
            System.out.println("[sql] committed OK");
        }
    }
}
JAVA
  echo "[remote] applying SQL ($JDBC_URL)..."
  if ! "$JAVA_BIN" -cp "$PGJAR" "$IN/RunSql.java" "$IN/update.sql"; then
    echo "[remote] !! SQL failed (transaction rolled back)"
    rollback
  fi
fi

if [ "$DO_RESTART" = "1" ]; then
  echo "[remote] start dev-mind..."
  bin/dev-mind start || rollback
  bin/dev-mind status || true
else
  echo "[remote] --no-restart: 换包完成，未启动"
fi

# 归档包 + 清理（包留最近 2 个，备份留最近 5 份）
mv "$IN/$PKG" "$PKG"
rm -rf "$IN"
ls -t devmind-*.tar.gz 2>/dev/null | tail -n +3 | xargs -r rm -f
ls -dt backup/*/ 2>/dev/null | tail -n +6 | xargs -r rm -rf
echo "[remote] done: $PKG @ $TS"
REMOTE

echo "[deploy] OK — http://172.20.140.224:8088/"
