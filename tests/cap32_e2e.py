# -*- coding: utf-8 -*-
# CAP-32 附件模块 E2E（对运行中的 :8080 后端）：
#   登录 → 上传 png + 非图片文件 → /raw 两种鉴权（header / ?access_token=）断言 Content-Disposition
#   → 列表/元数据/切 scope/删除 → 建 chat 发带图 input → 事件流 payload 含 attachments（引用，无 base64）。
# 用法: python tests/cap32_e2e.py [baseUrl] [username] [password]
import base64
import json
import sys
import urllib.request
import urllib.error
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://localhost:8080'
USER = sys.argv[2] if len(sys.argv) > 2 else 'admin'
PWD = sys.argv[3] if len(sys.argv) > 3 else 'admin123'

FAILURES = []


def check(name, cond, detail=''):
    tag = 'PASS' if cond else 'FAIL'
    print(f'[{tag}] {name}' + (f' — {detail}' if detail and not cond else ''))
    if not cond:
        FAILURES.append(name)


def req(method, path, token=None, body=None, raw=None, ctype=None, headers=None):
    url = BASE + path
    h = dict(headers or {})
    data = None
    if body is not None:
        data = json.dumps(body).encode('utf-8')
        h['Content-Type'] = 'application/json'
    elif raw is not None:
        data = raw
        if ctype:
            h['Content-Type'] = ctype
    if token:
        h['Authorization'] = 'Bearer ' + token
    r = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def multipart(field, filename, content, ctype, extra=None):
    boundary = '----cap32' + uuid.uuid4().hex
    parts = []
    for k, v in (extra or {}).items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        f'Content-Type: {ctype}\r\n\r\n'.encode() + content + b'\r\n')
    parts.append(f'--{boundary}--\r\n'.encode())
    return b''.join(parts), f'multipart/form-data; boundary={boundary}'


# 1x1 红色 PNG
PNG = base64.b64decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')
TXT = b'hello attachment\n'

# ---------- 登录 ----------
st, _, body = req('POST', '/api/auth/login', body={'username': USER, 'password': PWD})
check('登录', st == 200, f'status={st} body={body[:200]}')
token = json.loads(body).get('token') or json.loads(body).get('accessToken') if st == 200 else None
if not token:
    print('无法取得 token，终止')
    sys.exit(1)

# ---------- 上传 ----------
raw, ct = multipart('file', 'pixel.png', PNG, 'image/png')
st, _, body = req('POST', '/api/attachments', token, raw=raw, ctype=ct)
check('上传 png（默认 PRIVATE）', st == 200, f'status={st} body={body[:300]}')
png = json.loads(body) if st == 200 else {}
png_id = png.get('attachmentId', '')
check('png 返回 32 位附件 id', len(png_id) == 32, png_id)
check('png 标记 image=true', png.get('image') is True, str(png))

raw, ct = multipart('file', 'note.txt', TXT, 'text/plain')
st, _, body = req('POST', '/api/attachments', token, raw=raw, ctype=ct)
check('上传 txt', st == 200, f'status={st} body={body[:300]}')
txt = json.loads(body) if st == 200 else {}
txt_id = txt.get('attachmentId', '')
check('txt 标记 image=false', txt.get('image') is False, str(txt))

# ---------- raw 两种鉴权 ----------
st, hd, body = req('GET', f'/api/attachments/{png_id}/raw', token)
check('raw(png, header token) 200 且内容一致', st == 200 and body == PNG, f'status={st}')
check('raw(png) inline 且原样 Content-Type',
      'inline' in hd.get('Content-Disposition', '') and 'image/png' in hd.get('Content-Type', ''),
      f"{hd.get('Content-Disposition')} {hd.get('Content-Type')}")

st, hd, body = req('GET', f'/api/attachments/{png_id}/raw?access_token={token}')
check('raw(png, ?access_token=) 200', st == 200 and body == PNG, f'status={st}')

st, hd, body = req('GET', f'/api/attachments/{txt_id}/raw', token)
check('raw(txt) 触发下载 attachment+filename',
      st == 200 and 'attachment' in hd.get('Content-Disposition', '') and 'note.txt' in hd.get('Content-Disposition', ''),
      f"status={st} {hd.get('Content-Disposition')}")

st, _, _ = req('GET', f'/api/attachments/{png_id}/raw')
check('raw 无 token 401', st == 401, f'status={st}')

st, _, _ = req('POST', f'/api/attachments/{png_id}/raw?access_token={token}')
check('query token 不放行非 GET', st in (401, 403, 405), f'status={st}')

# ---------- 列表 / 元数据 / scope ----------
st, _, body = req('GET', '/api/attachments?type=image', token)
items = json.loads(body) if st == 200 else []
check('列表 type=image 仅含图片', st == 200 and any(i['attachmentId'] == png_id for i in items)
      and all(i.get('image') for i in items), f'status={st} n={len(items)}')

st, _, body = req('GET', f'/api/attachments/{png_id}', token)
check('元数据可读', st == 200 and json.loads(body).get('attachmentId') == png_id, f'status={st}')

st, _, body = req('PUT', f'/api/attachments/{png_id}/scope', token, body={'scope': 'SHARED'})
check('切 scope=SHARED', st == 200 and json.loads(body).get('scope') == 'SHARED', f'status={st} {body[:200]}')

st, _, body = req('GET', '/api/attachments?scope=SHARED', token)
check('列表 scope=SHARED 命中', st == 200 and any(i['attachmentId'] == png_id for i in json.loads(body)),
      f'status={st}')

# ---------- chat 带图 input ----------
st, _, body = req('POST', '/api/chats', token, body={'message': '说一句 hi 即可，不要使用任何工具'})
if st != 200:
    check('创建 chat', False, f'status={st} body={body[:300]}（可能本机无 claude/节点离线，跳过 chat 断言）')
else:
    chat_id = json.loads(body).get('id')
    check('创建 chat', bool(chat_id), str(body[:200]))
    st, _, body = req('POST', f'/api/chats/{chat_id}/input', token,
                      body={'text': '这张图是什么颜色？', 'images': [
                          {'attachmentId': png_id, 'name': 'pixel.png', 'contentType': 'image/png'}]})
    check('带图 input 受理', st == 200, f'status={st} body={body[:300]}')
    # 轮询事件流找 user 事件的 attachments 引用
    import time
    found = None
    for _ in range(20):
        st, _, body = req('GET', f'/api/chats/{chat_id}/events?afterSeq=-1', token)
        if st == 200:
            for ev in json.loads(body):
                if ev.get('type') == 'user' and (ev.get('payload') or {}).get('attachments'):
                    found = ev
                    break
        if found:
            break
        time.sleep(1)
    check('事件流 user.payload.attachments 含引用', bool(found), '未等到带附件的 user 事件')
    if found:
        atts = found['payload']['attachments']
        check('附件引用无 base64（只 id/name/contentType）',
              all('base64' not in json.dumps(a) and 'data' not in a for a in atts), json.dumps(atts)[:300])
    # 非图片附件应报错不静默丢图
    st, _, body = req('POST', f'/api/chats/{chat_id}/input', token,
                      body={'text': 'x', 'images': [
                          {'attachmentId': txt_id, 'name': 'note.txt', 'contentType': 'text/plain'}]})
    check('非图片附件 input 报错不静默丢图', st in (400, 409), f'status={st} body={body[:200]}')
    # 不存在的附件 id
    st, _, body = req('POST', f'/api/chats/{chat_id}/input', token,
                      body={'text': 'x', 'images': [{'attachmentId': '0' * 32, 'name': 'x.png', 'contentType': 'image/png'}]})
    check('不存在附件 input 报错', st in (400, 404, 409), f'status={st} body={body[:200]}')
    req('POST', f'/api/chats/{chat_id}/kill', token)
    req('DELETE', f'/api/chats/{chat_id}', token)

# ---------- 删除 ----------
st, _, _ = req('DELETE', f'/api/attachments/{png_id}', token)
check('删除 png', st == 200, f'status={st}')
st, _, _ = req('GET', f'/api/attachments/{png_id}/raw', token)
check('删除后 raw 404', st == 404, f'status={st}')
st, _, _ = req('DELETE', f'/api/attachments/{txt_id}', token)
check('删除 txt', st == 200, f'status={st}')

print()
if FAILURES:
    print(f'== 失败 {len(FAILURES)} 项: {FAILURES}')
    sys.exit(1)
print('== 全部通过')
