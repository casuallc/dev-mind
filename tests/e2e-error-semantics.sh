#!/usr/bin/env bash
# E2E：客户端错误的状态码语义（后端需在 8080 运行，或传 baseUrl）
#
# 覆盖 GlobalExceptionHandler 对框架异常的登记：路径不存在→404、方法不对→405、
# 参数/请求体不合法→400，且都带统一 ApiError 体（code/message/path/timestamp）。
# 背景：@ExceptionHandler(Exception.class) 是兜底捕获，框架异常不逐个登记就会被它
# 接住统一报 500——"不存在的路径"曾回 DEV-500，状态码失去意义。
# 用法: tests/e2e-error-semantics.sh [baseUrl]   （默认 http://127.0.0.1:8080）
set -u
BASE=${1:-http://127.0.0.1:8080}/api
FAILED=0

TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python -c "import sys,json;print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null)
if [ -z "$TOKEN" ]; then echo "FAIL: 登录失败（admin/admin123）"; exit 1; fi
AUTH="Authorization: Bearer $TOKEN"

# check <名称> <期望HTTP> <期望code> <方法> <路径> [请求体]
check() {
  local name="$1" want_st="$2" want_code="$3" method="$4" path="$5" body="${6-}"
  local raw st payload
  if [ -n "$body" ]; then
    raw=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H "$AUTH" \
          -H 'Content-Type: application/json' -d "$body")
  else
    raw=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H "$AUTH")
  fi
  st=$(printf '%s' "$raw" | tail -n1)
  payload=$(printf '%s' "$raw" | sed '$d')
  if [ "$st" = "$want_st" ] && printf '%s' "$payload" | grep -q "\"$want_code\""; then
    echo "  PASS  $name（$st $want_code）"
  else
    FAILED=1
    echo "  FAIL  $name  期望 $want_st/$want_code 实得 $st → $(printf '%s' "$payload" | head -c 200)"
  fi
}

echo "== 框架异常状态码 =="
check "不存在的 /api 路径 → 404"        404 DEV-404 GET    /definitely-not-a-route
check "路径存在但方法不对 → 405"        405 DEV-405 DELETE /documents
check "路径变量类型不符 → 400"          400 DEV-400 GET    /documents/not-a-number
check "请求体 JSON 语法错 → 400"        400 DEV-400 POST   /documents '{"kind":'
check "缺必填查询参数 → 400"            400 DEV-400 GET    /documents/search

echo "== 业务错误与鉴权回归 =="
check "业务 404 仍是 404"               404 DEV-404 GET    /projects/99999999
check "路径对了但资源不存在 → 404"      404 DEV-404 GET    /documents/99999999
ST=$(curl -s -o /dev/null -w '%{http_code}' $BASE/documents)
if [ "$ST" = "401" ]; then echo "  PASS  未带 token → 401"; else FAILED=1; echo "  FAIL  未带 token 期望 401 实得 $ST"; fi

echo
if [ "$FAILED" = "0" ]; then echo "== 错误语义验证：全部通过 =="; else echo "== 错误语义验证：有失败 =="; fi
exit $FAILED
