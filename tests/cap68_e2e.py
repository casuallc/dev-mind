#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-68 会话附件注入与附件生命周期 E2E。

前置：隔离实例（:8090，见 tests/README.md）已用新代码启动，且额外带两个覆盖：
  --devmind.attachment.max-size-mb=1            （大小超限 400 用 1.5MB 探针即可触发）
  清理 cron 用环境变量宽松绑定（含空格塞不进 run.arguments，见开发注意事项）：
  DEVMIND_ATTACHMENT_CLEANUPCRON="*/10 * * * * *"
runner jar 已重建（mvn -q install -DskipTests）；PATH 上有 node（fake 执行体依赖）。

覆盖（对应 CAP-68 文档 §8）：
  A. 生命周期：上传带标签+保留天数 → PUT /meta 改/清（空白=清除）→ 列表 tag= 过滤
     → 批量删除权限矩阵（bob 删自己的 OK、删 admin 的逐项失败；admin 删别人的 OK）
     → 过期硬删（PUT meta 把 expiresAt 拨到过去 + 短 cron，行 404 + 盘上无残留），
     未过期/永久不受影响 → 上传 >1MB 400。
  B. 会话注入（fake runner）：创建会话即带附件 → 工作区 .devmind/input/attachments/
     <id>-<净化名> 字节一致 + CLAUDE.local.md 含「## 会话附件」节（中文名剥掉补
     attachment.txt，路径穿越名剥到 basename）；会话中发文件 → runner 落盘
     .devmind/incoming/<id>-<basename> + fake 回复体现 Read 提示；发图片 → user 事件
     payload.attachments 含引用；单条 6 个文件 → 400。
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error, urllib.parse
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap68-ws"
ORIGIN = TMP / "cap68-origin"
PROPS = TMP / "cap68-runner.properties"
MARK = f"e2e-cap68-{int(time.time())}"
# runner 必须 JDK 21（PATH 上的 java 可能是 17）
_JAVA21 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe")
JAVA = str(_JAVA21) if _JAVA21.exists() else "java"
# 1x1 红色 PNG
PNG_BYTES = bytes.fromhex(
    "89504e470d0a1a0a0000000d494844520000000100000001080600000"
    "01f15c4890000000d49444154789c626001000000ffff030000060005"
    "57bfabd40000000049454e44ae426082")
BOB = "cap68bob"
BOB_PW = "cap68bob123"


def req(method, path, body=None, token=None, expect=200):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        if e.code == expect:
            return json.loads(e.read().decode() or "null")
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:400]}")


def upload(token, filename, content, content_type, tags=None, expire_days=None, expect=200):
    qs = urllib.parse.urlencode({k: v for k, v in
                                 (("tags", tags), ("expireDays", expire_days)) if v is not None})
    boundary = "----cap68boundary"
    body = b"".join([
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
        f"filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n".encode(),
        content, f"\r\n--{boundary}--\r\n".encode()])
    r = urllib.request.Request(BASE + "/attachments" + (f"?{qs}" if qs else ""),
                               data=body, method="POST")
    r.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == expect, f"上传 -> {resp.status}, expect {expect}"
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == expect:
            return json.loads(e.read().decode() or "null")
        raise AssertionError(f"上传 {filename} -> {e.code}: {e.read().decode()[:400]}")


def wait(cond, what, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = cond()
        if v:
            return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}")


def kill_tree(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)


def login(u, p):
    r = req("POST", "/auth/login", {"username": u, "password": p})
    return r.get("token") or r.get("accessToken")


def gone(aid, token):
    """附件已硬删（GET 404）。"""
    try:
        req("GET", f"/attachments/{aid}", token=token)
        return None
    except AssertionError as e:
        return True if "-> 404" in str(e) else None


def main():
    tok = login("admin", "admin123")
    print("[0] 登录 OK")

    # ================= A. 附件生命周期 =================
    a1 = upload(tok, "cap68-design.txt", b"design doc v1", "text/plain",
                tags="设计， 前端 ,设计", expire_days=30)
    aid1 = a1["attachmentId"]
    assert a1["tags"] == "设计,前端", f"标签归一（去重保序）不符: {a1['tags']}"
    assert a1["expiresAt"], "expireDays=30 应有 expiresAt"
    print(f"[A1] 上传带标签+保留天数 OK（tags={a1['tags']} expiresAt={a1['expiresAt']}）")

    v = req("PUT", f"/attachments/{aid1}/meta",
            {"description": "CAP-68 E2E", "tags": "架构", "expiresAt": "2099-01-01 00:00:00"}, tok)
    assert v["tags"] == "架构" and v["description"] == "CAP-68 E2E", f"meta 未生效: {v}"
    assert v["expiresAt"].startswith("2099-01-01"), f"expiresAt 未改: {v['expiresAt']}"
    v = req("PUT", f"/attachments/{aid1}/meta", {"tags": " ", "expiresAt": " "}, tok)
    assert not v["tags"] and not v["expiresAt"], f"空白=清除语义不符: {v}"
    assert v["description"] == "CAP-68 E2E", "未传字段不该被清（null=不变）"
    print("[A2] PUT /meta 改/清 OK")

    # tag 过滤（meta 里 tags 已清，重新打一个再过滤）
    req("PUT", f"/attachments/{aid1}/meta", {"tags": "cap68-filter"}, tok)
    hits = req("GET", "/attachments?tag=cap68-filter", token=tok)
    assert any(a["attachmentId"] == aid1 for a in hits), "tag 过滤未命中"
    assert not any(a["attachmentId"] == aid1
                   for a in req("GET", "/attachments?tag=" + urllib.parse.quote("不存在的标签"),
                                token=tok)), "tag 过滤误中"
    print("[A3] 列表 tag= 精确过滤 OK")

    # 批量删除权限矩阵：bob（DEVELOPER）删自己的 OK、删 admin 的逐项失败；admin 删 bob 剩下的 OK
    users = req("GET", "/auth/users", token=tok)
    bob_user = next((u for u in users if u.get("username") == BOB), None)
    if bob_user is None:
        req("POST", "/auth/users",
            {"username": BOB, "displayName": "CAP-68 E2E", "password": BOB_PW, "role": "DEVELOPER"}, tok)
    else:
        req("POST", f"/auth/users/{bob_user['id']}/reset-password", {"password": BOB_PW}, tok)
    bob = login(BOB, BOB_PW)
    att_bob = upload(bob, "cap68-bob.txt", b"bob file", "text/plain")["attachmentId"]
    att_admin = upload(tok, "cap68-admin.txt", b"admin file", "text/plain")["attachmentId"]
    results = req("POST", "/attachments/batch-delete", {"ids": [att_bob, att_admin]}, bob)
    by_id = {r["attachmentId"]: r for r in results}
    assert by_id[att_bob]["ok"], f"bob 删自己的应成功: {results}"
    assert not by_id[att_admin]["ok"], f"bob 删 admin 的应逐项失败: {results}"
    req("GET", f"/attachments/{att_admin}", token=tok)  # 部分失败不回滚：admin 的还在
    results = req("POST", "/attachments/batch-delete", {"ids": [att_admin]}, tok)
    assert results[0]["ok"], f"ADMIN 删别人的应成功: {results}"
    assert gone(att_bob, tok) and gone(att_admin, tok), "删除后 GET 应 404"
    print("[A4] 批量删除权限矩阵（owner 成功/他人逐项失败/ADMIN 兜底，部分失败不回滚）OK")

    # 过期硬删：expiresAt 拨到过去，等短 cron 触发（隔离实例须配 DEVMIND_ATTACHMENT_CLEANUPCRON）
    att_exp = upload(tok, "cap68-expire.txt", b"expired", "text/plain")["attachmentId"]
    att_keep = upload(tok, "cap68-keep.txt", b"keep", "text/plain")["attachmentId"]
    req("PUT", f"/attachments/{att_exp}/meta", {"expiresAt": "2020-01-01 00:00:00"}, tok)
    wait(lambda: gone(att_exp, tok), "过期附件硬删（需实例配短 cleanup cron）", 120)
    att_root = ROOT / "tmp" / "e2e-data" / "attachments"
    if att_root.exists():  # 隔离实例的盘上残留检查（默认布局才找得到）
        assert not list(att_root.glob(f"**/{att_exp}.*")), "过期附件盘上残留"
    req("GET", f"/attachments/{att_keep}", token=tok)  # 永久附件不受影响
    print("[A5] 过期硬删（行 404 + 盘无残留），永久附件不受影响 OK")

    # 大小超限 400（隔离实例 --devmind.attachment.max-size-mb=1）
    upload(tok, "cap68-big.bin", b"x" * (1536 * 1024), "application/octet-stream", expect=400)
    print("[A6] 上传 >1MB 400 OK")

    # ================= B. 会话注入 =================
    def _force_remove(func, path, _exc):
        os.chmod(path, 0o666)
        func(path)
    if ORIGIN.exists():
        shutil.rmtree(ORIGIN, onexc=_force_remove)
    ORIGIN.mkdir(parents=True)
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
    subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
    (ORIGIN / "README.md").write_text("# cap68 e2e\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True, capture_output=True, env=env)
    proj = req("POST", "/projects", {
        "name": MARK, "sourceType": "CLONE",
        "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
    }, tok)
    pid = proj["id"]
    wait(lambda: req("GET", f"/projects/{pid}", token=tok).get("cloneStatus") == "READY" or None,
         "项目克隆 READY", 90)
    print(f"[B1] 项目就绪 {pid}")

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl={BASE.replace('http://', 'ws://').removesuffix('/api')}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap68-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=2\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        [JAVA, "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap68-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    keep = [aid1, att_keep]
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print("[B2] runner 上线")

        # 创建会话即带附件：ASCII 名（空格剥掉）+ 纯中文名（剥掉补 attachment.txt）+ 路径穿越名
        att_img = upload(tok, "cap68-pixel.png", PNG_BYTES, "image/png")["attachmentId"]
        att_doc = upload(tok, "design draft.txt", b"draft-content-cap68", "text/plain")["attachmentId"]
        att_cn = upload(tok, "说明文档.txt", "中文内容 cap68".encode(), "text/plain")["attachmentId"]
        att_evil = upload(tok, "../../evil-cap68.txt", b"traversal", "text/plain")["attachmentId"]
        keep += [att_img, att_doc, att_cn, att_evil]

        s = req("POST", "/sessions", {
            "taskSpec": f"{MARK}：起手 Read .devmind/input/attachments/ 下的附件并总结",
            "projectId": pid, "attachmentIds": [att_doc, att_cn, att_evil],
        }, tok)
        sid = s["id"]

        def materialized(aid):
            hit = next(iter(WS.glob(f"**/.devmind/input/attachments/{aid}-*")), None)
            return hit if hit and hit.exists() else None
        f_doc = wait(lambda: materialized(att_doc), "会话附件物化 design draft", 90)
        f_cn, f_evil = materialized(att_cn), materialized(att_evil)
        assert f_cn and f_evil, f"中文名/穿越名附件未物化: {list(WS.glob('**/.devmind/input/*'))}"
        assert f_doc.name == f"{att_doc}-designdraft.txt", f"ASCII 净化名不符: {f_doc.name}"
        assert f_doc.read_bytes() == b"draft-content-cap68", "物化字节不一致"
        assert f_cn.name == f"{att_cn}-attachment.txt", f"中文名应剥掉补占位名: {f_cn.name}"
        assert f_evil.name == f"{att_evil}-evil-cap68.txt", f"穿越名应剥到 basename: {f_evil.name}"
        assert ".." not in str(f_evil.relative_to(f_evil.parents[2])), "物化路径越界"
        sdir = f_doc.parents[3]  # <sdir>/.devmind/input/attachments/<file> → 会话目录
        claude_md = (sdir / "CLAUDE.local.md").read_text(encoding="utf-8")
        assert "## 会话附件" in claude_md, f"注入块缺会话附件节:\n{claude_md[:600]}"
        assert f".devmind/input/attachments/{att_doc}-" in claude_md, "清单缺物化路径"
        print(f"[B3] 创建即带附件物化 OK（{f_doc.name} / {f_cn.name} / {f_evil.name} + 注入块清单）")

        # fake 起会话后停在 permission_request：先授权再发输入
        def _alive():
            v = req("GET", f"/sessions/{sid}", token=tok)
            return v if v.get("state") in ("WAITING_AUTH", "RUNNING") else None
        st = wait(_alive, "会话起活", 60)
        if st.get("state") == "WAITING_AUTH":
            req("POST", f"/sessions/{sid}/authorize",
                {"accepted": True, "scope": "once", "requestId": "perm-fake"}, tok)

        # 会话中发文件：runner 落盘 .devmind/incoming/<id>-<basename>，回复体现 Read 提示
        incoming_name = f"{att_img}-cap68-pixel.png"  # 图片也能走 files 通道（resolveAny）
        req("POST", f"/sessions/{sid}/input", {
            "text": "看下我附的文件", "files": [
                {"attachmentId": att_img, "name": "cap68-pixel.png", "contentType": "image/png"},
                {"attachmentId": att_evil, "name": "../../evil-cap68.txt",
                 "contentType": "text/plain"}]}, tok)
        wait(lambda: (sdir / ".devmind" / "incoming" / incoming_name).exists() or None,
             "incoming 落盘", 30)
        assert (sdir / ".devmind" / "incoming" / incoming_name).read_bytes() == PNG_BYTES, \
            "incoming 字节不一致"
        evil_incoming = sdir / ".devmind" / "incoming" / f"{att_evil}-evil-cap68.txt"
        assert evil_incoming.exists(), f"穿越名 incoming 应剥到 basename: {list((sdir / '.devmind' / 'incoming').iterdir())}"
        def _reply_with_incoming():
            es = req("GET", f"/sessions/{sid}/events?afterSeq=-1", token=tok)
            ok = any(e.get("type") == "assistant" and incoming_name in (e.get("content") or "")
                     for e in es)
            return es if ok else None
        evs = wait(_reply_with_incoming, "fake 回复体现 incoming 路径", 30)
        user_ev = next(e for e in evs if e.get("type") == "user"
                       and any(a.get("attachmentId") == att_img
                               for a in (e.get("payload") or {}).get("attachments") or []))
        assert any(a.get("attachmentId") == att_evil
                   for a in user_ev["payload"]["attachments"]), "user 事件缺文件引用"
        print("[B4] 文件随 input 帧落盘 .devmind/incoming（含穿越名净化）+ 回复带 Read 提示 OK")

        # 会话中发图片：images 帧（image block 直读），user 事件 payload.attachments 记引用
        req("POST", f"/sessions/{sid}/input", {
            "text": "这张图什么颜色？",
            "images": [{"attachmentId": att_img, "name": "cap68-pixel.png",
                        "contentType": "image/png"}]}, tok)
        wait(lambda: any(e.get("type") == "user" and "颜色" in (e.get("content") or "")
                         and any(a.get("attachmentId") == att_img
                                 for a in (e.get("payload") or {}).get("attachments") or [])
                         for e in req("GET", f"/sessions/{sid}/events?afterSeq=-1", token=tok))
             or None, "带图 user 事件", 30)
        print("[B5] 图片走 images 帧 + user 事件 payload.attachments 引用 OK")

        # 单条消息文件 >5 → 400
        req("POST", f"/sessions/{sid}/input", {
            "text": "x", "files": [{"attachmentId": att_doc, "name": "d.txt",
                                    "contentType": "text/plain"}] * 6}, tok, expect=400)
        print("[B6] 单条 6 个文件 400 OK")

        req("POST", f"/sessions/{sid}/finish", token=tok)
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "DONE" or None,
             "会话 DONE", 60)
        print("[B7] 会话正常收尾 DONE OK")
    finally:
        kill_tree(runner.pid)
        try:
            req("POST", f"/agent-nodes/{node['id']}/unset-default", token=tok)
            req("DELETE", f"/agent-nodes/{node['id']}", token=tok)
            req("DELETE", f"/projects/{pid}", token=tok)
            for a in keep:
                req("DELETE", f"/attachments/{a}", token=tok)
        except Exception as ex:
            print(f"[cleanup] {ex}")
    print("全部通过")


if __name__ == "__main__":
    main()
