#!/usr/bin/env bash
# CAP-32 附件描述 + 关键字搜索 E2E（需后端已在 :8080 运行）。
# 验证：带 description 上传 → 响应透出 description → 关键字可按描述搜索 → 清理删除。
# 注意：Git Bash 调原生 curl.exe 时 argv 中文会转 GBK，multipart 字段必须走 <文件 方式保证 UTF-8 字节。
set -euo pipefail
BASE=http://localhost:8080/api
DIR="$(dirname "$0")"

TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
echo "[1] login ok"

printf 'hello cap32' > "$DIR/e2e-att.txt"
printf 'E2E描述-需求封面底稿' > "$DIR/e2e-att-desc.txt"

echo "[2] upload with description"
RESP=$(curl -s -X POST "$BASE/attachments" -H "Authorization: Bearer $TOKEN" \
  -F "file=@$DIR/e2e-att.txt" -F "description=<$DIR/e2e-att-desc.txt")
echo "$RESP" | python -c "
import sys, json, io
# 响应体是 UTF-8；Windows 控制台 stdin 默认 GBK 会把多字节序列拆出 surrogateescape 假象
v = json.load(io.TextIOWrapper(sys.stdin.buffer, encoding='utf-8'))
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
assert v['description'] == 'E2E描述-需求封面底稿', repr(v['description'])
assert v['attachmentId'] and v['scope'] == 'PRIVATE', v
print('    ok:', v['attachmentId'], v['originalName'], repr(v['description']))
open(r'$DIR/e2e-att-id', 'w').write(v['attachmentId'])
"

AID=$(cat "$DIR/e2e-att-id")

echo "[3] keyword search hits description"
# 关键字同样避免 argv 中文：Python 从 UTF-8 文件读关键字再 URL 编码
printf '封面底稿' > "$DIR/e2e-att-kw.txt"
python - "$TOKEN" "$AID" "$DIR/e2e-att-kw.txt" <<'EOF'
import json, sys, urllib.request, urllib.parse
token, aid, kwfile = sys.argv[1], sys.argv[2], sys.argv[3]
kw = open(kwfile, encoding='utf-8').read()
url = 'http://localhost:8080/api/attachments?keyword=' + urllib.parse.quote(kw)
req = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token})
rows = json.load(urllib.request.urlopen(req))
assert any(r['attachmentId'] == aid for r in rows), rows
print('    ok: description keyword matched')
EOF

echo "[4] keyword search by name still works"
curl -s "$BASE/attachments?keyword=e2e-att" -H "Authorization: Bearer $TOKEN" | python -c "
import sys, json
rows = json.load(sys.stdin)
assert any(r['attachmentId'] == '$AID' for r in rows), rows
print('    ok')
"

echo "[5] cleanup delete"
curl -s -X DELETE "$BASE/attachments/$AID" -H "Authorization: Bearer $TOKEN" -o /dev/null -w "    delete status=%{http_code}\n"
rm -f "$DIR/e2e-att.txt" "$DIR/e2e-att-id" "$DIR/e2e-att-desc.txt" "$DIR/e2e-att-kw.txt"
echo "E2E PASS"
