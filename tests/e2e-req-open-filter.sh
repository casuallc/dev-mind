#!/usr/bin/env bash
# E2E：需求列表 status=OPEN 伪状态（未完结=排除 DONE/CANCELLED）——默认视图不再混入已解决条目。
# 前置：app 已用临时 H2 起在 :8080（见下方启动注释），admin/admin123 种子可用。
set -euo pipefail
BASE=http://127.0.0.1:8080
TMP=/d/apusic/dev-mind/tmp/e2e-req-open
mkdir -p "$TMP"

json_get() { python -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))$1)" "$2"; }

TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' -o "$TMP/login.json" && json_get '["accessToken"]' "$TMP/login.json")
AUTH="Authorization: Bearer $TOKEN"
echo "login ok"

# 本地 git 仓库（LOCAL 项目 path 必填且须为 git 仓库）
REPO=$TMP/repo
if [ ! -d "$REPO/.git" ]; then mkdir -p "$REPO" && git -C "$REPO" init -q && git -C "$REPO" -c user.email=e@e -c user.name=e commit -q --allow-empty -m init; fi

PID=$(curl -s -X POST $BASE/api/projects -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"e2e-req-open\",\"path\":\"$(cygpath -m "$REPO")\"}" -o "$TMP/proj.json" && json_get '["id"]' "$TMP/proj.json")
echo "project=$PID"

mkreq() { curl -s -X POST $BASE/api/projects/$PID/requirements -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"title\":\"$1\"}" -o "$TMP/$1.json" && json_get '["id"]' "$TMP/$1.json"; }
R1=$(mkreq r1-draft); R2=$(mkreq r2-done); R3=$(mkreq r3-cancelled); R4=$(mkreq r4-progress)
setst() { curl -s -X PUT $BASE/api/projects/$PID/requirements/$1/status -H "$AUTH" -H 'Content-Type: application/json' -d "{\"status\":\"$2\"}" -o /dev/null; }
setst "$R2" DONE; setst "$R3" CANCELLED; setst "$R4" IN_PROGRESS
echo "seeded 4 requirements (DRAFT/DONE/CANCELLED/IN_PROGRESS)"

total() { curl -s "$BASE/api/projects/$PID/requirements?$1" -H "$AUTH" -o "$TMP/list.json" && json_get '["total"]' "$TMP/list.json"; }
T_OPEN=$(total 'status=OPEN'); T_ALL=$(total ''); T_DONE=$(total 'status=DONE'); T_DEF=$(total 'page=0&size=20')
echo "OPEN=$T_OPEN (want 2)  ALL=$T_ALL (want 4)  DONE=$T_DONE (want 1)  no-filter=$T_DEF (want 4)"

[ "$T_OPEN" = "2" ] && [ "$T_ALL" = "4" ] && [ "$T_DONE" = "1" ] && [ "$T_DEF" = "4" ] \
  && echo "E2E PASS" || { echo "E2E FAIL"; exit 1; }
