#!/usr/bin/env bash
# E2E：CAP-28 FR-09 定时从 Git 导入工作条目（设置开关 + gitImportTick）
# 覆盖：开关打开 → 当日提交被定时导入为 GIT 条目（hours=0）；下一 tick 不重复（幂等）；
#       开关关掉 → 新提交不导入；重新打开 → 期间漏掉的提交下一 tick 补导入。
# 前置：隔离实例已带 cron 覆盖启动（每 15s 一跳）。cron 含空格塞不进 spring-boot.run.arguments
# （按空格拆参 → "Cron expression must consist of 6 fields (found 1 in "*/15")"），
# 走环境变量宽松绑定传入（DEVMIND_WORKLOG_GITIMPORTCRON ↔ devmind.worklog.git-import-cron）：
#   mvn -q install -DskipTests
#   DEVMIND_WORKLOG_GITIMPORTCRON='*/15 * * * * *' \
#   mvn -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e \
#     "-Dspring-boot.run.arguments=--server.port=18091 \
#      --spring.datasource.url=jdbc:h2:file:$PWD/tmp/e2e-auto-import-data/db;AUTO_SERVER=TRUE"
# 用法：tests/e2e-worklog-auto-import.sh [baseUrl]（默认 http://127.0.0.1:18091）
set -euo pipefail
BASE=${1:-http://127.0.0.1:18091}
TS=$(date +%s)
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TMP=$ROOT/tmp/e2e-worklog-auto-import/$TS
mkdir -p "$TMP"
TODAY=$(date +%F)
PREFIX="autoimp-$TS"

json_get() { python -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))$1)" "$2"; }

TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' -o "$TMP/login.json" && json_get '["accessToken"]' "$TMP/login.json")
AUTH="Authorization: Bearer $TOKEN"
echo "login ok ($BASE) today=$TODAY prefix=$PREFIX"

# 造库：本地 git 仓库 + local user.email（署名解析走仓库本地 user.email 回退链）
REPO=$TMP/repo
mkdir -p "$REPO" && git -C "$REPO" init -q
git -C "$REPO" config user.email "e2e-auto@example.com"
git -C "$REPO" config user.name "e2e-auto"
commit() { git -C "$REPO" commit -q --allow-empty -m "$1"; }
commit "$PREFIX-A"
commit "$PREFIX-B"

# 登记（ADMIN）→ 订阅 → 打开定时导入开关
RID=$(curl -s -X POST $BASE/api/repos -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$PREFIX\",\"sourceType\":\"LOCAL\",\"localPath\":\"$(cygpath -m "$REPO")\"}" \
  -o "$TMP/repo.json" && json_get '["id"]' "$TMP/repo.json")
echo "repo=$RID"
curl -s -X PUT $BASE/api/worklog/repos/$RID/subscription -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"subscribed":true}' -o /dev/null
curl -s -X PUT $BASE/api/worklog/settings -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"autoGitImport":true}' -o /dev/null
echo "订阅 + autoGitImport=true，等待定时导入（cron */15s）..."

# 轮询条目数（keyword 精确到本批次前缀，防历史数据干扰）
count() { curl -s "$BASE/api/worklog/entries?from=$TODAY&to=$TODAY&size=100&keyword=$PREFIX" -H "$AUTH" \
  -o "$TMP/entries.json" && python -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['total'])" "$TMP/entries.json"; }
wait_for() { # $1=want $2=timeout
  local deadline=$((SECONDS + $2)) n
  while [ $SECONDS -lt $deadline ]; do
    n=$(count)
    [ "$n" = "$1" ] && { echo "entries=$n (want $1)"; return 0; }
    sleep 3
  done
  echo "TIMEOUT: entries=$(count) want $1"; cat "$TMP/entries.json"; return 1
}

wait_for 2 90
# 导入形态断言：GIT 来源 + hours=0（路径经 cygpath 转 Windows 形态——heredoc 内嵌路径不过 MSYS 参数转换）
ENTRIES_WIN=$(cygpath -m "$TMP/entries.json")
python - "$ENTRIES_WIN" "$PREFIX-A" "$PREFIX-B" <<'EOF'
import json, sys
items = json.load(open(sys.argv[1], encoding="utf-8"))["items"]
assert all(i["source"] == "GIT" for i in items), items
assert all(i["hours"] == 0 for i in items), items
assert sorted(i["title"] for i in items) == sorted(sys.argv[2:4]), items
print("shape ok: source=GIT hours=0 title=commit subject")
EOF

# 幂等：再等 ≥2 个 tick，数量不变
sleep 35
[ "$(count)" = "2" ] && echo "幂等 ok（第二 tick 不重复）" || { echo "FAIL: 重复导入"; exit 1; }

# 关掉开关 → 新提交不导入
curl -s -X PUT $BASE/api/worklog/settings -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"autoGitImport":false}' -o /dev/null
commit "$PREFIX-C"
sleep 40
[ "$(count)" = "2" ] && echo "关闭后不导入 ok" || { echo "FAIL: 开关关闭仍导入"; exit 1; }

# 重新打开 → 漏掉的 C 下一 tick 补导入
curl -s -X PUT $BASE/api/worklog/settings -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"autoGitImport":true}' -o /dev/null
wait_for 3 60

echo "E2E PASS"
