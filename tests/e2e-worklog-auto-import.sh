#!/usr/bin/env bash
# E2E：CAP-28 FR-09/FR-10 定时从 Git 导入工作条目（设置开关 + 个人执行时间 + 主 tick）
# 覆盖：开关打开 → 当日提交被定时导入为 GIT 条目（hours=0）；下一 tick 不重复（幂等）；
#       开关关掉 → 新提交不导入；重新打开 → 期间漏掉的提交下一 tick 补导入；
#       设了个人执行时间 → 全局 cron 不再对本人生效，到个人时间的那分钟才导入。
# 前置：FR-10 起调度是每分钟主 tick 逐用户判到期；个人未设时间时跟随全局 git-import-cron。
# 用环境变量宽松绑定把全局 cron 覆盖成 */15s（分钟粒度下收敛为每分钟一跳，等待足够）。
# 注意 JDBC url 里的路径必须 cygpath 转 Windows 形态——MSYS 形态 /d/... 传给 JVM 会被
# 按盘符相对路径解析成 D:\d\apusic\...（库写到仓库外，清库重跑删错目录）：
#   mvn -q install -DskipTests
#   DEVMIND_WORKLOG_GITIMPORTCRON='*/15 * * * * *' \
#   mvn -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e \
#     "-Dspring-boot.run.arguments=--server.port=18091 \
#      --spring.datasource.url=jdbc:h2:file:$(cygpath -m "$PWD")/tmp/e2e-auto-import-data/db;AUTO_SERVER=TRUE"
# （cron 含空格塞不进 spring-boot.run.arguments——按空格拆参 →
#   "Cron expression must consist of 6 fields (found 1 in "*/15")"）
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

# 幂等：再等 ≥1 个 tick（主 tick 每分钟一跳，75s 保证跨过分钟边界），数量不变
sleep 75
[ "$(count)" = "2" ] && echo "幂等 ok（第二 tick 不重复）" || { echo "FAIL: 重复导入"; exit 1; }

# 关掉开关 → 新提交不导入（130s 保证 ≥2 个 tick 窗口）
curl -s -X PUT $BASE/api/worklog/settings -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"autoGitImport":false}' -o /dev/null
commit "$PREFIX-C"
sleep 130
[ "$(count)" = "2" ] && echo "关闭后不导入 ok" || { echo "FAIL: 开关关闭仍导入"; exit 1; }

# 重新打开 → 漏掉的 C 下一 tick 补导入
curl -s -X PUT $BASE/api/worklog/settings -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"autoGitImport":true}' -o /dev/null
wait_for 3 90

# FR-10 个人执行时间：设成 3 分钟后（+2 分钟在 :59s 边界时个人分钟会落进 75s 观察窗）→
# 全局 */15s 对本人失效（75s 内 D 不导入），到个人时间那分钟主 tick 才导入
USER_TIME=$(date -d '+3 min' +%H:%M)
curl -s -X PUT $BASE/api/worklog/settings -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"gitImportTime\":\"$USER_TIME\"}" -o /dev/null
commit "$PREFIX-D"
echo "个人执行时间=$USER_TIME，断言 75s 内不按全局 cron 导入..."
sleep 75
[ "$(count)" = "3" ] && echo "个人时间覆盖全局 ok（全局 cron 对本人失效）" \
  || { echo "FAIL: 设了个人时间仍按全局 cron 导入"; exit 1; }
wait_for 4 180

echo "E2E PASS"
