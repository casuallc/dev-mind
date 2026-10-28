#!/usr/bin/env bash
# E2E：fake 会话验证 CliEventParser 结构化解析（对话视图数据源）
# 前提：app 以 --devmind.session.executor=fake 起在 :8081
set -euo pipefail
BASE=http://localhost:8081/api

jqr() { python -c "import sys,json;d=json.load(sys.stdin.buffer);print(eval(sys.argv[1]))" "$1"; }

TOKEN=$(curl -s "$BASE/auth/login" -H 'Content-Type: application/json' \
  --data-binary '{"username":"admin","password":"admin123"}' | jqr "d['accessToken']")
echo "login ok"
AUTH="Authorization: Bearer $TOKEN"

# curl 中文请求体必须走 UTF-8 文件（Git Bash 内联中文会按 GBK 编码）
printf '%s' '{"taskSpec":"E2E 对话视图验证：跑一遍结构化事件"}' > tmp/e2e-create.json
printf '%s' '{"text":"你好，fake"}' > tmp/e2e-input.json

SID=$(curl -s "$BASE/sessions" -X POST -H "$AUTH" -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @tmp/e2e-create.json | jqr "d['id']")
echo "session=$SID"

# 等 permission_request 出现（fake 流程末尾）
for i in $(seq 1 20); do
  sleep 1
  EVS=$(curl -s "$BASE/sessions/$SID/events" -H "$AUTH")
  echo "$EVS" | grep -q permission_request && break
done

# 授权 → 注入输入 → 结束
curl -s "$BASE/sessions/$SID/authorize" -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  --data-binary '{"accepted":true,"scope":"once","requestId":"perm-fake"}' > /dev/null
sleep 1
curl -s "$BASE/sessions/$SID/input" -X POST -H "$AUTH" -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @tmp/e2e-input.json > /dev/null
sleep 1
# fake-agent 收到 __exit__ 会发 result 再退出（/finish 只关 stdin，fake 不发 result）
printf '%s' '{"text":"__exit__"}' > tmp/e2e-exit.json
curl -s "$BASE/sessions/$SID/input" -X POST -H "$AUTH" -H 'Content-Type: application/json' \
  --data-binary @tmp/e2e-exit.json > /dev/null
for i in $(seq 1 15); do
  sleep 1
  STATE=$(curl -s "$BASE/sessions/$SID" -H "$AUTH" | jqr "d['state']")
  [ "$STATE" = "DONE" ] || [ "$STATE" = "FAILED" ] && break
done
echo "final state=$STATE"

# 汇总事件结构，验证解析结果
curl -s "$BASE/sessions/$SID/events" -H "$AUTH" | python -c "
import sys, json
evs = json.load(sys.stdin.buffer)
print('total events:', len(evs))
from collections import Counter
print(Counter(e['type'] for e in evs))
asst = [e for e in evs if e['type']=='assistant']
tu   = [e for e in evs if e['type']=='tool_use']
tr   = [e for e in evs if e['type']=='tool_result']
res  = [e for e in evs if e['type']=='result']
usr  = [e for e in evs if e['type']=='user']
ok = True
def check(name, cond):
    global ok
    print(('PASS' if cond else 'FAIL'), name)
    ok = ok and cond
check('assistant 带 model=fake-opus', all((e.get('payload') or {}).get('model')=='fake-opus' for e in asst) and len(asst)>0)
check('assistant 文本无 [Bash] 标记', all('[Bash]' not in (e.get('content') or '') for e in asst))
check('tool_use 带 toolUseId', all((e.get('payload') or {}).get('toolUseId') for e in tu) and len(tu)>=3)
check('tool_result 存在且带 toolUseId', len(tr)>=3 and all((e.get('payload') or {}).get('toolUseId') for e in tr))
check('tool_use/tool_result id 可配对', {e['payload']['toolUseId'] for e in tu} == {e['payload']['toolUseId'] for e in tr})
check('result 带 durationMs', len(res)>0 and 'durationMs' in (res[-1].get('payload') or {}))
check('注入输入产生 user 事件', any('你好' in (e.get('content') or '') for e in usr))
check('init 事件仅 1 条（非 init 系统事件已降级）', sum(1 for e in evs if e['type']=='system')==1)
sys.exit(0 if ok else 1)
"
