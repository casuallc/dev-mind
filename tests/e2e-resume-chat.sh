#!/usr/bin/env bash
# E2E：问答「继续对话」——claude --resume 续接验证
# 链路：创建(问数字) → 回答 → finish(DONE) → resume(继续对话) → 追问验证上下文
#      → suspend → resume → 再追问（验证挂起路径也带历史）→ 断言两次 init session_id 一致
# 前提：app :8080 + runner（executor=claude，节点 12）在线
set -uo pipefail
BASE=http://localhost:8080/api
NODE=12
MAGIC=4271

TOKEN=$(curl -s "$BASE/auth/login" -H 'Content-Type: application/json' \
  --data-binary '{"username":"admin","password":"admin123"}' \
  | python -c "import sys,json;print(json.load(sys.stdin.buffer)['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"
echo "login ok"

# 中文请求体走 UTF-8 文件（Git Bash 内联中文按 GBK 编码）
printf '%s' "{\"message\":\"请记住数字 $MAGIC，只回复：记住了\",\"agentNodeId\":\"$NODE\"}" > tmp/resume-create.json
CHAT=$(curl -s "$BASE/chats" -X POST -H "$AUTH" -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @tmp/resume-create.json | python -c "import sys,json;print(json.load(sys.stdin.buffer)['id'])")
echo "chat=$CHAT"

events() { curl -s "$BASE/chats/$CHAT/events" -H "$AUTH"; }

# 等待条件（参数：描述 + python 判断表达式，表达式输入为 stdin 的 events JSON）
wait_ev() {
  local desc="$1" expr="$2" timeout="${3:-150}" i
  for i in $(seq 1 "$timeout"); do
    if events | python -c "
import sys, json
evs = json.load(sys.stdin.buffer)
sys.exit(0 if ($expr) else 1)
"; then echo "ok: $desc (${i}s)"; return 0; fi
    sleep 1
  done
  echo "TIMEOUT: $desc"; return 1
}

state() { curl -s "$BASE/chats/$CHAT" -H "$AUTH" | python -c "import sys,json;print(json.load(sys.stdin.buffer)['state'])"; }

# 1) 等首轮回答（assistant 文本含「记住」）
wait_ev "首轮回答" "any(e['type']=='assistant' and '记住' in (e.get('content') or '') for e in evs)" || exit 1
SID1=$(events | python -c "
import sys, json
evs = json.load(sys.stdin.buffer)
inits = [e for e in evs if e['type']=='system' and (e.get('payload') or {}).get('sessionId')]
print(inits[0]['payload']['sessionId'] if inits else '')")
echo "first cli session_id=$SID1"
[ -n "$SID1" ] || { echo "FAIL: 未捕获 init session_id"; exit 1; }

# 2) finish → DONE
curl -s "$BASE/chats/$CHAT/finish" -X POST -H "$AUTH" > /dev/null
for i in $(seq 1 60); do ST=$(state); [ "$ST" = "DONE" ] && break; sleep 1; done
echo "state after finish=$ST"
[ "$ST" = "DONE" ] || { echo "FAIL: 未 DONE"; exit 1; }

# 3) 终态「继续对话」
curl -s "$BASE/chats/$CHAT/resume" -X POST -H "$AUTH" | python -c "import sys,json;d=json.load(sys.stdin.buffer);print('resume →', d.get('state') or d)"
sleep 2
SID2=$(events | python -c "
import sys, json
evs = json.load(sys.stdin.buffer)
inits = [e for e in evs if e['type']=='system' and (e.get('payload') or {}).get('sessionId')]
print(inits[-1]['payload']['sessionId'] if inits else '')")
echo "second cli session_id=$SID2"

# 4) 追问，验证上下文在
printf '%s' '{"text":"我刚才让你记住的数字是什么？只回答数字"}' > tmp/resume-ask.json
curl -s "$BASE/chats/$CHAT/input" -X POST -H "$AUTH" -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @tmp/resume-ask.json > /dev/null
# 首轮回答只是「记住了」不含数字 → 任何含数字的 assistant 消息即证明续接上下文
wait_ev "追问回答含 $MAGIC" "any(e['type']=='assistant' and '$MAGIC' in (e.get('content') or '') for e in evs)" 180 || exit 1

# 5) suspend → resume → 再追问（挂起路径同样带历史）
curl -s "$BASE/chats/$CHAT/suspend" -X POST -H "$AUTH" > /dev/null
for i in $(seq 1 30); do ST=$(state); [ "$ST" = "SUSPENDED" ] && break; sleep 1; done
echo "state after suspend=$ST"
# SUSPENDED 状态先于 runner 侧进程真正退出（suspend=杀进程异步收口），等 slot 释放再 resume
sleep 5
curl -s "$BASE/chats/$CHAT/resume" -X POST -H "$AUTH" | python -c "import sys,json;d=json.load(sys.stdin.buffer);print('resume(挂起) →', d.get('state') or d)"
sleep 2
printf '%s' '{"text":"那个数字加一等于几？只回答数字"}' > tmp/resume-ask2.json
curl -s "$BASE/chats/$CHAT/input" -X POST -H "$AUTH" -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @tmp/resume-ask2.json > /dev/null
wait_ev "加一回答含 4272" "any(e['type']=='assistant' and '4272' in (e.get('content') or '') for e in evs)" 180 || exit 1

# 6) 汇总断言
events | python -c "
import sys, json
evs = json.load(sys.stdin.buffer)
inits = [ (e.get('payload') or {}).get('sessionId') for e in evs if e['type']=='system' and (e.get('payload') or {}).get('sessionId') ]
print('init session_ids:', inits)
ok = len(inits) >= 3 and len(set(inits)) == 1
print(('PASS' if ok else 'FAIL'), '三次拉起 init session_id 一致（--resume 命中同一会话）')
sys.exit(0 if ok else 1)
" || exit 1

# 清理
curl -s "$BASE/chats/$CHAT" -X DELETE -H "$AUTH" > /dev/null
echo "E2E PASS（chat=$CHAT 已删除）"
