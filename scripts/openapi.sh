#!/usr/bin/env bash
# openapi.sh — dev-mind open-api（CAP-20）HMAC 签名调用 wrapper。
# 签名规范（与服务端 OpenApiAuthFilter 严格同口径）：
#   stringToSign = METHOD\n + path（含 query） + \n + timestamp + \n + sha256hex(body || "")
#   X-Signature  = hex(HMAC-SHA256(key = sha256hex(sk), stringToSign))
#
# 用法：
#   export DEVMIND_AK=ak_xxx DEVMIND_SK=sk_xxx
#   scripts/openapi.sh GET  /open-api/v1/projects
#   scripts/openapi.sh POST /open-api/v1/projects '{"name":"x","path":"D:/repo"}'
#   DEVMIND_BASE_URL=http://host:8080 scripts/openapi.sh ...
set -euo pipefail

BASE_URL="${DEVMIND_BASE_URL:-http://localhost:8080}"
METHOD="${1:?用法: openapi.sh <METHOD> <PATH> [JSON_BODY]}"
REQ_PATH="${2:?缺少路径，如 /open-api/v1/projects}"
BODY="${3:-}"
: "${DEVMIND_AK:?请先 export DEVMIND_AK}"
: "${DEVMIND_SK:?请先 export DEVMIND_SK}"

TS=$(date +%s)
BODY_HASH=$(printf '%s' "$BODY" | sha256sum | cut -d' ' -f1)
KEY=$(printf '%s' "$DEVMIND_SK" | sha256sum | cut -d' ' -f1)
STRING_TO_SIGN=$(printf '%s\n%s\n%s\n%s' "$(printf '%s' "$METHOD" | tr '[:lower:]' '[:upper:]')" "$REQ_PATH" "$TS" "$BODY_HASH")
SIG=$(printf '%s' "$STRING_TO_SIGN" | openssl dgst -sha256 -hmac "$KEY" -hex | sed 's/^.*= *//')

CURL_ARGS=(-sS -X "$(printf '%s' "$METHOD" | tr '[:lower:]' '[:upper:]')"
  -H "X-Access-Key: $DEVMIND_AK"
  -H "X-Timestamp: $TS"
  -H "X-Signature: $SIG")
if [ -n "$BODY" ]; then
  CURL_ARGS+=(-H "Content-Type: application/json" --data-raw "$BODY")
fi

curl "${CURL_ARGS[@]}" "${BASE_URL}${REQ_PATH}"
