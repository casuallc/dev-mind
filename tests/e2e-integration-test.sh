#!/usr/bin/env bash
# E2E：集成连通性测试（POST /api/integrations/test 未保存试连 + /{id}/test）
set -e
BASE=http://localhost:8080/api

TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
AUTH="Authorization: Bearer $TOKEN"
CT="Content-Type: application/json"
echo "== login ok =="

echo "--- 1. 列表 ---"
curl -s "$BASE/integrations" -H "$AUTH" | python -m json.tool

echo "--- 2. draft：缺 token 应 400 ---"
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/integrations/test" -H "$AUTH" -H "$CT" \
  -d '{"type":"JIRA","name":"t","baseUrl":"https://jira.example.com","authType":"PAT"}'

echo "--- 3. draft：非法协议应 400 ---"
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/integrations/test" -H "$AUTH" -H "$CT" \
  -d '{"type":"JIRA","name":"t","baseUrl":"ftp://x","token":"abc"}'

echo "--- 4. draft：不可达主机应 ok=false（不 500） ---"
curl -s -X POST "$BASE/integrations/test" -H "$AUTH" -H "$CT" \
  -d '{"type":"JIRA","name":"t","baseUrl":"http://192.0.2.1:1","authType":"PAT","token":"abc"}' | python -m json.tool

echo "--- 5. draft：github.com 假 token 应 ok=false（401 凭据无效，证明真发出请求） ---"
curl -s -X POST "$BASE/integrations/test" -H "$AUTH" -H "$CT" \
  -d '{"type":"GITHUB","name":"t","baseUrl":"https://github.com","token":"ghp_invalid_token_xxxx"}' | python -m json.tool

echo "--- 6. draft：BASIC 缺用户名应 400 ---"
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/integrations/test" -H "$AUTH" -H "$CT" \
  -d '{"type":"JIRA","name":"t","baseUrl":"https://jira.example.com","authType":"BASIC","token":"pw"}'

echo "--- 7. 已保存实例逐条 /{id}/test（列表实时探测同源端点） ---"
for ID in $(curl -s "$BASE/integrations" -H "$AUTH" | python -c "import sys,json;[print(i['id']) for i in json.load(sys.stdin)]" | tr -d '\r'); do
  echo "id=$ID:"
  curl -s -X POST "$BASE/integrations/$ID/test" -H "$AUTH" | python -m json.tool
done

echo "--- 8. 未登录应 401/403 ---"
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/integrations/test" -H "$CT" \
  -d '{"type":"JIRA","name":"t","baseUrl":"https://x.com","token":"a"}'
echo "== DONE =="
