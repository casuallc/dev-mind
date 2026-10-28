#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""需求附件（描述内联引用口径）E2E。

背景：需求描述为 Markdown 富文本，附件以引用形式写进描述（![图](/api/attachments/{id}/raw)、
[文件](/api/attachments/{id}/raw)）——描述文本是附件绑定的唯一事实源，与 CAP-40
RequirementAttachmentProvider 会话投送同一扫描口径（agent 可读），不再走 relations 边。
零后端改动：上传复用 CAP-32 附件底座（scope=SHARED），绑定即描述里的引用行。

前置：隔离实例（:8090，姿势见 tests/README.md「起独立实例跑 E2E」）已启动
（零后端改动，任意新构建均可；无需 runner）。E2E_BASE 可覆盖。

覆盖：
  1. 建项目（本地 git origin）→ 建需求；
  2. 上传 PNG + TXT 各一（scope=SHARED，回读 view.scope/image 断言）；
  3. PUT 需求把两条引用行追加进 description → GET 回读描述含 /api/attachments/{id}/raw
     （CAP-40 本地分支的正则锚点，断言即证明会话投送可命中）；
  4. GET /attachments/{id} 元数据 + /raw 字节对账（PNG/TXT 各自一致）；
  5. 移除其一：PUT 描述剔除 TXT 引用行 + DELETE /attachments/{id} → GET 404、
     描述中 PNG 引用仍在；
  6. 删需求 → 清理 PNG 文件（附件库不受需求删除级联，测试收尾手工删）。
"""
import json, os, re, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8090/api")
ROOT = Path(__file__).resolve().parents[3]
TMP = ROOT / "tmp"
ORIGIN = TMP / "req-attach-origin"
MARK = f"e2e-reqattach-{int(time.time())}"

# 1x1 红色 PNG
PNG_BYTES = bytes.fromhex(
    "89504e470d0a1a0a0000000d494844520000000100000001080600000"
    "01f15c4890000000d49444154789c626001000000ffff030000060005"
    "57bfabd40000000049454e44ae426082")
TXT_BYTES = "接口报文样例：{\"code\":0}\n第二行".encode("utf-8")

# 与前端 attachmentRefs.LOCAL_REF_RE 同口径（粗校验引用行形态即可）
LOCAL_REF_RE = re.compile(r"!?\[[^\]\n]*\]\(/api/attachments/([0-9a-f]{32})/raw[^)\n]*\)")


def req(method, path, body=None, token=None, expect=200, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            payload = resp.read()
            return payload if raw else json.loads(payload.decode() or "null")
    except urllib.error.HTTPError as e:
        if e.code == expect:
            return None if raw else json.loads(e.read().decode() or "null")
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:400]}")


def upload(token, filename, content, content_type, scope="SHARED"):
    boundary = "----reqattachboundary"
    body = b"".join([
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
        f"filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n".encode(),
        content,
        f"\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"scope\"\r\n\r\n{scope}"
        f"\r\n--{boundary}--\r\n".encode(),
    ])
    r = urllib.request.Request(BASE + "/attachments", data=body, method="POST")
    r.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == 200, f"上传 -> {resp.status}"
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raise AssertionError(f"上传 {filename} -> {e.code}: {e.read().decode()[:400]}")


def wait(cond, what, timeout=90):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = cond()
        if v:
            return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}")


def requirement_input(reqm, description):
    """用需求当前值构造 PUT 请求体（只换 description，其余字段原样回带）。"""
    return {k: reqm.get(k) for k in (
        "title", "ownerId", "docId", "type", "priority", "assignee",
        "reporter", "labels", "fixVersions", "dueDate")} | {"description": description}


def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    def _force_remove(func, path, _exc):
        os.chmod(path, 0o666)
        func(path)

    if ORIGIN.exists():
        shutil.rmtree(ORIGIN, onexc=_force_remove)
    ORIGIN.mkdir(parents=True)
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
    subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
    (ORIGIN / "README.md").write_text("# req-attach e2e\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True,
                   capture_output=True, env=env)

    pid = None
    png_id = None
    try:
        proj = req("POST", "/projects", {
            "name": MARK, "sourceType": "CLONE",
            "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
        }, tok)
        pid = proj["id"]
        wait(lambda: req("GET", f"/projects/{pid}", token=tok).get("cloneStatus") == "READY" or None,
             "项目克隆 READY")
        print(f"[1] 项目就绪 {pid}")

        reqm = req("POST", f"/projects/{pid}/requirements",
                   {"title": f"{MARK} 附件需求", "description": "带截图与报文"}, tok)
        rid = reqm["id"]
        print(f"[2] 需求 {reqm['code']} {rid}")

        png = upload(tok, "截图.png", PNG_BYTES, "image/png")
        txt = upload(tok, "报文.txt", TXT_BYTES, "text/plain")
        png_id = png["attachmentId"]
        assert png["scope"] == "SHARED" and txt["scope"] == "SHARED", "scope 应为 SHARED"
        assert png["image"] is True and txt["image"] is False, "image 判定不符"
        print(f"[3] 上传 OK（png={png_id} txt={txt['attachmentId']}，均 SHARED）")

        # 绑定 = 引用行写进描述（前端 appendRefLines + buildRefLine 同款形态）
        lines = [
            f"![截图.png](/api/attachments/{png['attachmentId']}/raw)",
            f"[报文.txt](/api/attachments/{txt['attachmentId']}/raw)",
        ]
        desc = reqm.get("description") or ""
        new_desc = (desc.rstrip() + "\n\n" if desc.strip() else "") + "\n".join(lines)
        req("PUT", f"/projects/{pid}/requirements/{rid}", requirement_input(reqm, new_desc), tok)
        got = req("GET", f"/projects/{pid}/requirements/{rid}", token=tok)
        refs = LOCAL_REF_RE.findall(got["description"] or "")
        assert set(refs) == {png["attachmentId"], txt["attachmentId"]}, \
            f"描述应含两条附件引用: {refs}"
        print("[4] 引用写入描述 OK（CAP-40 锚点 /api/attachments/{id}/raw 命中）")

        for a, content in ((png, PNG_BYTES), (txt, TXT_BYTES)):
            meta = req("GET", f"/attachments/{a['attachmentId']}", token=tok)
            assert meta["originalName"] == a["originalName"]
            body = req("GET", f"/attachments/{a['attachmentId']}/raw", token=tok, raw=True)
            assert body == content, f"{a['originalName']} /raw 字节不一致"
        print("[5] 元数据 + /raw 字节对账 OK")

        # 移除 TXT：描述剔除引用行（前端 stripAttachmentRef 同款语义）+ 删文件本体
        cur = req("GET", f"/projects/{pid}/requirements/{rid}", token=tok)
        kept = [l for l in (cur["description"] or "").split("\n")
                if txt["attachmentId"] not in l]
        req("PUT", f"/projects/{pid}/requirements/{rid}",
            requirement_input(cur, "\n".join(kept)), tok)
        req("DELETE", f"/attachments/{txt['attachmentId']}", token=tok)
        req("GET", f"/attachments/{txt['attachmentId']}", token=tok, expect=404)
        final = req("GET", f"/projects/{pid}/requirements/{rid}", token=tok)
        refs = LOCAL_REF_RE.findall(final["description"] or "")
        assert refs == [png["attachmentId"]], f"应只剩 PNG 引用: {refs}"
        print("[6] 移除附件 OK（剔引用+删文件，PNG 引用保留）")

        # 删需求：需求本体删除；附件文件不受级联（测试收尾手工清理）
        req("DELETE", f"/projects/{pid}/requirements/{rid}", token=tok)
        req("GET", f"/attachments/{png['attachmentId']}", token=tok)  # 文件本体仍在
        req("DELETE", f"/attachments/{png['attachmentId']}", token=tok)
        png_id = None
        print("[7] 删需求 OK（附件不随删，收尾已清理）")

        print("PASS: requirement attachments e2e")
    finally:
        if pid:
            try:
                req("DELETE", f"/projects/{pid}", token=tok)
            except Exception:
                pass
        if png_id:
            try:
                req("DELETE", f"/attachments/{png_id}", token=tok)
            except Exception:
                pass
        shutil.rmtree(ORIGIN, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
