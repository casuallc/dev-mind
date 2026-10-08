#!/usr/bin/env bash
# E2E：需求列表按更新时间排序（sortBy=updatedAt + sortDir asc/desc）——
# 默认 seq 倒序不变；updatedAt 排序后追加 seq 倒序稳定次序。
# 用法：tests/e2e-req-sort.sh [baseUrl]（默认 http://127.0.0.1:8080，隔离实例传 :18090）
# 前置：app 已起、e2e profile 种子 admin/admin123 可用。
set -euo pipefail
BASE=${1:-http://127.0.0.1:8080}
TMP=/d/apusic/dev-mind/tmp/e2e-req-sort
mkdir -p "$TMP"

json_get() { python -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))$1)" "$2"; }

TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' -o "$TMP/login.json" && json_get '["accessToken"]' "$TMP/login.json")
AUTH="Authorization: Bearer $TOKEN"
echo "login ok ($BASE)"

# 本地 git 仓库（LOCAL 项目 path 必填且须为 git 仓库）
REPO=$TMP/repo
if [ ! -d "$REPO/.git" ]; then mkdir -p "$REPO" && git -C "$REPO" init -q && git -C "$REPO" -c user.email=e@e -c user.name=e commit -q --allow-empty -m init; fi

PID=$(curl -s -X POST $BASE/api/projects -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"e2e-req-sort\",\"path\":\"$(cygpath -m "$REPO")\"}" -o "$TMP/proj.json" && json_get '["id"]' "$TMP/proj.json")
echo "project=$PID"

mkreq() { curl -s -X POST $BASE/api/projects/$PID/requirements -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"title\":\"$1\"}" -o "$TMP/$1.json" && json_get '["id"]' "$TMP/$1.json"; }
R1=$(mkreq s1); sleep 0.2; R2=$(mkreq s2); sleep 0.2; R3=$(mkreq s3)
# 把 s1 顶到最新（PUT 标题原值也会刷 updatedAt）
sleep 0.2
curl -s -X PUT $BASE/api/projects/$PID/requirements/$R1 -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"title":"s1"}' -o /dev/null
echo "seeded s1/s2/s3，随后更新 s1"

codes() { curl -s "$BASE/api/projects/$PID/requirements?$1" -H "$AUTH" -o "$TMP/list.json" \
  && python -c "import json,sys;print(','.join(i['code'] for i in json.load(open(sys.argv[1],encoding='utf-8'))['items']))" "$TMP/list.json"; }
# 编号按创建顺序 REQ-1/2/3（同一项目内 seq 递增）
D_DEF=$(codes '')
D_UPD_DESC=$(codes 'sortBy=updatedAt&sortDir=desc')
D_UPD_ASC=$(codes 'sortBy=updatedAt&sortDir=asc')
echo "default=$D_DEF (want REQ-3,REQ-2,REQ-1)"
echo "updatedAt desc=$D_UPD_DESC (want REQ-1,REQ-3,REQ-2)"
echo "updatedAt asc=$D_UPD_ASC (want REQ-2,REQ-3,REQ-1)"

[ "$D_DEF" = "REQ-3,REQ-2,REQ-1" ] && [ "$D_UPD_DESC" = "REQ-1,REQ-3,REQ-2" ] && [ "$D_UPD_ASC" = "REQ-2,REQ-3,REQ-1" ] \
  && echo "E2E PASS" || { echo "E2E FAIL"; exit 1; }
