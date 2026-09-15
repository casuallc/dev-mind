#!/usr/bin/env bash
# 验证 /api/skills/import：缺 name 的报错信息 + 带 BOM 的 SKILL.md 可导入
set -euo pipefail
cd "$(dirname "$0")/.."
BASE=http://localhost:8080

# 造两个 zip（python 输出走文件，避免 Windows stdin 编码坑）
python - <<'PY'
import zipfile, os
os.makedirs('tmp/e2e-skill-import', exist_ok=True)

# A: frontmatter 缺 name（只有 description）
with zipfile.ZipFile('tmp/e2e-skill-import/no-name.zip', 'w') as z:
    z.writestr('no-name-skill/SKILL.md', '---\ndescription: 只有描述没有名字\n---\n正文\n')

# B: 带 UTF-8 BOM + 合法 frontmatter
with zipfile.ZipFile('tmp/e2e-skill-import/with-bom.zip', 'w') as z:
    z.writestr('SKILL.md', '﻿---\nname: bom-skill\ndescription: 带 BOM 的 skill\n---\n正文\n')
    z.writestr('refs/a.md', '# 附件\n')
PY

TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary '{"username":"admin","password":"admin123"}' | python -c 'import json,sys; print(json.load(sys.stdin.buffer)["accessToken"])')
echo "token ok"

echo '--- 场景A：缺 name，应报新错误信息 ---'
curl -s -X POST "$BASE/api/skills/import?scope=GLOBAL&overwrite=false" \
  -H "Authorization: Bearer $TOKEN" -F 'file=@tmp/e2e-skill-import/no-name.zip;type=application/zip'
echo

echo '--- 场景B：带 BOM，应导入成功 ---'
curl -s -X POST "$BASE/api/skills/import?scope=GLOBAL&overwrite=true" \
  -H "Authorization: Bearer $TOKEN" -F 'file=@tmp/e2e-skill-import/with-bom.zip;type=application/zip'
echo

# 清理：删掉导入的 skill
SID=$(curl -s "$BASE/api/skills?keyword=bom-skill" -H "Authorization: Bearer $TOKEN" \
  | python -c 'import json,sys; d=json.load(sys.stdin.buffer); items=d.get("items", d) if isinstance(d, dict) else d; print(items[0]["id"] if items else "")')
if [ -n "$SID" ]; then
  curl -s -X DELETE "$BASE/api/skills/$SID" -H "Authorization: Bearer $TOKEN" >/dev/null
  echo "cleaned skill $SID"
fi
