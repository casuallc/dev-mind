#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""需求附件（relations 稀疏边绑定）E2E。

背景：创建/编辑需求支持上传图片、文本等附件。绑定零后端改动——复用 CAP-32 附件底座
（上传 scope=SHARED）+ relations 扩展边 requirement --attached--> attachment；
需求删除时 RequirementService.delete 已双向清理 relations 边。

前置：隔离实例（:8090，姿势见 tests/README.md「起独立实例跑 E2E」）已启动
（零后端改动，任意新构建均可；无需 runner）。E2E_BASE 可覆盖。

覆盖：
  1. 建项目（本地 git origin）→ 建需求；
  2. 上传 PNG + TXT 各一（scope=SHARED，回读 view.scope 断言）；
  3. POST relations 建 attached 边 ×2 → GET relations?fromType=requirement&fromId=
     过滤 attached+attachment 回读到 2 条；
  4. GET /attachments/{id} 元数据 + /raw 字节对账（PNG/TXT 各自一致）；
  5. 移除其一：删边 + DELETE /attachments/{id} → GET 404、边列表只剩 1 条；
  6. 删需求 → 该需求的 attached 边随删（级联清理），另一附件文件本体保留（不随删）。
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error, urllib.parse
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8090/api")
ROOT = Path(__file__).resolve().parent.parent
TMP = ROOT / "tmp"
ORIGIN = TMP / "req-attach-origin"
MARK = f"e2e-reqattach-{int(time.time())}"

# 1x1 红色 PNG
PNG_BYTES = bytes.fromhex(
    "89504e470d0a1a0a0000000d494844520000000100000001080600000"
    "01f15c4890000000d49444154789c626001000000ffff030000060005"
    "57bfabd40000000049454e44ae426082")
TXT_BYTES = "接口报文样例：{\"code\":0}\n第二行".encode("utf-8")


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


def attached_edges(pid, rid, tok):
    """前端 listRequirementAttachments 同口径：取边 → 过滤 attached+attachment。"""
    edges = req("GET", f"/projects/{pid}/relations?fromType=requirement&fromId={rid}", token=tok)
    return [e for e in edges if e["relationType"] == "attached" and e["toType"] == "attachment"]


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
        assert png["scope"] == "SHARED" and txt["scope"] == "SHARED", "scope 应为 SHARED"
        assert png["image"] is True and txt["image"] is False, "image 判定不符"
        print(f"[3] 上传 OK（png={png['attachmentId']} txt={txt['attachmentId']}，均 SHARED）")

        for a in (png, txt):
            req("POST", f"/projects/{pid}/relations", {
                "fromType": "requirement", "fromId": rid,
                "toType": "attachment", "toId": a["attachmentId"],
                "relationType": "attached",
            }, tok)
        edges = attached_edges(pid, rid, tok)
        assert len(edges) == 2, f"应读到 2 条 attached 边: {edges}"
        print("[4] 绑定 OK（2 条 attached 边）")

        for a, content in ((png, PNG_BYTES), (txt, TXT_BYTES)):
            meta = req("GET", f"/attachments/{a['attachmentId']}", token=tok)
            assert meta["originalName"] == a["originalName"]
            got = req("GET", f"/attachments/{a['attachmentId']}/raw", token=tok, raw=True)
            assert got == content, f"{a['originalName']} /raw 字节不一致"
        print("[5] 元数据 + /raw 字节对账 OK")

        # 移除 TXT：删边 + 删文件本体
        txt_edge = next(e for e in attached_edges(pid, rid, tok) if e["toId"] == txt["attachmentId"])
        req("DELETE", f"/projects/{pid}/relations/{txt_edge['id']}", token=tok)
        req("DELETE", f"/attachments/{txt['attachmentId']}", token=tok)
        req("GET", f"/attachments/{txt['attachmentId']}", token=tok, expect=404)
        edges = attached_edges(pid, rid, tok)
        assert len(edges) == 1 and edges[0]["toId"] == png["attachmentId"], "应只剩 PNG 边"
        print("[6] 移除附件 OK（边+文件双删，剩 1 条）")

        # 删需求：attached 边级联清理，PNG 文件本体保留
        req("DELETE", f"/projects/{pid}/requirements/{rid}", token=tok)
        edges = req("GET", f"/projects/{pid}/relations?fromType=requirement&fromId={rid}", token=tok)
        assert not [e for e in edges if e["relationType"] == "attached"], "需求删除后 attached 边应清空"
        req("GET", f"/attachments/{png['attachmentId']}", token=tok)  # 文件本体保留
        print("[7] 删需求级联清边 OK（文件本体保留）")

        print("PASS: requirement attachments e2e")
    finally:
        if pid:
            try:
                req("DELETE", f"/projects/{pid}", token=tok)
            except Exception:
                pass
        shutil.rmtree(ORIGIN, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
