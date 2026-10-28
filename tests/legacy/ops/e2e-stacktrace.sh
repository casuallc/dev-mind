#!/usr/bin/env bash
# E2E：错误响应堆栈透传验证（后端需在 8080 运行，local profile）
set -u
BASE=http://127.0.0.1:8080/api
TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null)
if [ -z "$TOKEN" ]; then echo "FAIL: 登录失败（admin/admin123）"; exit 1; fi
echo "== 触发 404 DevMindException =="
BODY=$(curl -s $BASE/projects/99999999 -H "Authorization: Bearer $TOKEN")
echo "$BODY"
echo "$BODY" | python -c "
import sys,json
b=json.load(sys.stdin)
assert b.get('code')=='DEV-404', b
st=b.get('stackTrace')
if st is None:
    print('MODE=OFF（无 stackTrace 字段）'); sys.exit(2)
assert 'DevMindException' in st and 'at com.devmind' in st, st[:200]
print('MODE=ON，stackTrace 已透传，首行:', st.splitlines()[0])
"
