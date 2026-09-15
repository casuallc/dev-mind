# -*- coding: utf-8 -*-
# E2E：skill zip 导入端点验证（root 结构 / 单层目录包裹 / 同名 409 / overwrite 覆盖 / 缺 SKILL.md 报错）
import io, json, os, sys, zipfile, urllib.request, urllib.error

BASE = 'http://localhost:8081/api'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tmp', 'skill-import-e2e')
os.makedirs(OUT, exist_ok=True)

def make_zip(path, files):
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(name, data)

SKILL_ROOT = ('---\nname: e2e-import-root\ndescription: E2E 导入测试（根目录结构）\n'
              'allowed-tools: [Bash, Read]\nversion: 1.0\n---\n\n# Root Skill\n\n根目录导入的正文。\n')
SKILL_WRAP = '---\nname: e2e-import-wrapped\ndescription: E2E 导入测试（单层目录包裹）\n---\n\n# Wrapped\n'

make_zip(os.path.join(OUT, 'root.zip'), {
    'SKILL.md': SKILL_ROOT,
    'notes.md': '# 附件说明\n',
    'scripts/run.sh': '#!/bin/sh\necho hello\n',
    'logo.png': b'\x89PNG\r\n\x1a\nfakedata',
})
make_zip(os.path.join(OUT, 'wrapped.zip'), {
    'my-wrapped/SKILL.md': SKILL_WRAP,
    'my-wrapped/scripts/run.sh': '#!/bin/sh\necho wrapped\n',
    'my-wrapped/.DS_Store': 'junk',
    '__MACOSX/._junk': 'junk',
})
make_zip(os.path.join(OUT, 'noskill.zip'), {'readme.txt': 'no skill md here'})

def req(method, url, token=None, data=None, headers=None):
    r = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    if token: r.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, resp.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8')

def login():
    body = json.dumps({'username': 'admin', 'password': 'admin123'}).encode()
    st, txt = req('POST', BASE + '/auth/login', data=body,
                  headers={'Content-Type': 'application/json'})
    assert st == 200, 'login failed: %s %s' % (st, txt)
    return json.loads(txt)['accessToken']

def upload(token, zip_path, scope='GLOBAL', overwrite='false'):
    boundary = '----e2eboundary'
    with open(zip_path, 'rb') as f:
        payload = f.read()
    body = (('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\n'
             'Content-Type: application/zip\r\n\r\n') % (boundary, os.path.basename(zip_path))
            ).encode() + payload + ('\r\n--%s--\r\n' % boundary).encode()
    url = '%s/skills/import?scope=%s&overwrite=%s' % (BASE, scope, overwrite)
    return req('POST', url, token=token, data=body,
               headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})

token = login()
print('login ok')
results = []

def check(name, cond, detail=''):
    results.append((name, cond, detail))
    print(('PASS' if cond else 'FAIL'), name, detail)

# 1. 根目录结构导入
st, txt = upload(token, os.path.join(OUT, 'root.zip'))
check('root.zip 导入', st == 200, 'status=%s' % st)
root_id = json.loads(txt)['id'] if st == 200 else None
if root_id:
    d = json.loads(txt)
    check('root fileCount=3', d.get('fileCount') == 3, str(d.get('fileCount')))
    st2, txt2 = req('GET', BASE + '/skills/' + root_id, token=token)
    det = json.loads(txt2)
    check('root extraFrontmatter 保留', det.get('extraFrontmatter', {}).get('version') == '1.0'
          and 'allowed-tools' in det.get('extraFrontmatter', {}), txt2[:200])
    check('root contentMd 正文', '根目录导入的正文' in (det.get('contentMd') or ''))
    paths = [f['path'] for f in det.get('files', [])]
    check('root 附件路径', sorted(paths) == ['logo.png', 'notes.md', 'scripts/run.sh'], str(paths))
    binmap = {f['path']: f['binary'] for f in det.get('files', [])}
    check('root 二进制判定', binmap.get('logo.png') is True and binmap.get('notes.md') is False, str(binmap))

# 2. 同名 409
st, txt = upload(token, os.path.join(OUT, 'root.zip'))
check('同名未勾选覆盖 → 409', st == 409, 'status=%s %s' % (st, txt[:120]))

# 3. overwrite 覆盖（改附件再导）
make_zip(os.path.join(OUT, 'root2.zip'), {
    'SKILL.md': SKILL_ROOT.replace('根目录导入的正文', '覆盖后的正文'),
    'only.txt': 'replaced\n',
})
st, txt = upload(token, os.path.join(OUT, 'root2.zip'), overwrite='true')
check('overwrite 覆盖导入', st == 200, 'status=%s' % st)
if st == 200:
    d = json.loads(txt)
    check('覆盖后 id 不变', d['id'] == root_id)
    st2, txt2 = req('GET', BASE + '/skills/' + root_id, token=token)
    det = json.loads(txt2)
    check('覆盖后正文更新', '覆盖后的正文' in (det.get('contentMd') or ''))
    check('覆盖后附件替换', [f['path'] for f in det.get('files', [])] == ['only.txt'],
          str([f['path'] for f in det.get('files', [])]))

# 4. 单层目录包裹导入（跳过 __MACOSX/.DS_Store）
st, txt = upload(token, os.path.join(OUT, 'wrapped.zip'))
check('wrapped.zip 导入', st == 200, 'status=%s %s' % (st, txt[:120]))
if st == 200:
    wid = json.loads(txt)['id']
    st2, txt2 = req('GET', BASE + '/skills/' + wid, token=token)
    det = json.loads(txt2)
    check('wrapped 附件剥前缀', [f['path'] for f in det.get('files', [])] == ['scripts/run.sh'],
          str([f['path'] for f in det.get('files', [])]))

# 5. 缺 SKILL.md 报错
st, txt = upload(token, os.path.join(OUT, 'noskill.zip'))
check('缺 SKILL.md → 400', st == 400, 'status=%s %s' % (st, txt[:120]))

# 清理
for name in ('e2e-import-root', 'e2e-import-wrapped'):
    st, txt = req('GET', BASE + '/skills?keyword=' + name, token=token)
    for it in json.loads(txt).get('items', []):
        req('DELETE', BASE + '/skills/' + it['id'], token=token)
print('cleanup done')

fails = [r for r in results if not r[1]]
print('=== %d/%d passed ===' % (len(results) - len(fails), len(results)))
sys.exit(1 if fails else 0)
