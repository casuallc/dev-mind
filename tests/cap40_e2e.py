#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-40 需求附件上下文投送 E2E（前置：后端 :8080 已用新代码启动，runner jar 已重建）。

链路：上传 png 附件 → 需求描述内嵌 /api/attachments/{id}/raw 链接 → flow/analyze 起会话
→ runner 拉上下文包物化 → 断言会话工作区 .devmind/input/{id}.png 字节一致、
CLAUDE.local.md 含「## 需求附件」节。
降级链路：描述引用不存在附件（32 个 f）→ 会话照常 RUNNING、注入块标注「不可用」。
（Jira 内嵌图链路需真实 Jira 实例，单测覆盖，E2E 不验。）
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = "http://localhost:8080/api"
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap40-ws"
ORIGIN = TMP / "cap40-origin"
PROPS = TMP / "cap40-runner.properties"
MARK = f"e2e-cap40-{int(time.time())}"
# runner 必须 JDK 21（PATH 上的 java 可能是 17）
_JAVA21 = Path(r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe")
JAVA = str(_JAVA21) if _JAVA21.exists() else "java"
# 1x1 红色 PNG
PNG_BYTES = bytes.fromhex(
    "89504e470d0a1a0a0000000d494844520000000100000001080600000"
    "01f15c4890000000d49444154789c626001000000ffff030000060005"
    "57bfabd40000000049454e44ae426082")
MISSING_ID = "f" * 32


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


def upload_attachment(token, filename, content, content_type):
    boundary = "----cap40boundary"
    parts = []
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                 f"filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n".encode())
    parts.append(content)
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    body = b"".join(parts)
    r = urllib.request.Request(BASE + "/attachments", data=body, method="POST")
    r.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    r.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(r) as resp:
        assert resp.status == 200, f"上传附件 -> {resp.status}"
        return json.loads(resp.read().decode())


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


def session_workdir(sid, aid):
    """定位 runner 会话工作区（CAP-42 固定布局 <ws>/<pid>/<owner>/work）：附件物化产物就绪才返回。"""
    t0 = time.time()
    while time.time() - t0 < 60:
        for cand in WS.glob("*/*/work"):
            if (cand / ".devmind" / "input" / f"{aid}.png").exists():
                return cand
        time.sleep(1)
    raise AssertionError(f"未找到已物化的会话工作区: {sid}")


def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    att = upload_attachment(tok, "arch.png", PNG_BYTES, "image/png")
    aid = att["attachmentId"]
    print(f"[1] 附件已上传 {aid}")

    def _force_remove(func, path, _exc):
        os.chmod(path, 0o666)
        func(path)
    if ORIGIN.exists():
        shutil.rmtree(ORIGIN, onexc=_force_remove)
    ORIGIN.mkdir(parents=True)
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
    subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
    (ORIGIN / "README.md").write_text("# cap40 e2e\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True, capture_output=True, env=env)

    proj = req("POST", "/projects", {
        "name": MARK, "sourceType": "CLONE",
        "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
    }, tok)
    pid = proj["id"]
    wait(lambda: req("GET", f"/projects/{pid}", token=tok).get("cloneStatus") == "READY" or None,
         "项目克隆 READY", 90)

    reqm = req("POST", f"/projects/{pid}/requirements", {
        "title": f"{MARK} 需求",
        "description": f"支持配置化告警阈值，界面见 ![架构图](/api/attachments/{aid}/raw)，"
                       f"另一张已删除的图 ![丢失](/api/attachments/{MISSING_ID}/raw)",
    }, tok)
    rid = reqm["id"]
    print(f"[2] 项目 {pid} + 需求 {reqm['code']}（描述内嵌 2 张图，一张已删）")

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl=ws://localhost:8080/ws/agent\ntoken={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap40-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=2\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        [JAVA, "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap40-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print("[3] runner 上线")

        s1 = req("POST", f"/projects/{pid}/requirements/{rid}/flow/analyze", None, tok)
        sid = s1["id"]
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "RUNNING" or None,
             "分析会话 RUNNING", 60)
        workdir = session_workdir(sid, aid)

        # 附件物化：字节一致 + 命名 {id}.png
        materialized = workdir / ".devmind" / "input" / f"{aid}.png"
        assert materialized.exists(), f"附件未物化: {materialized}（workdir={workdir}）"
        assert materialized.read_bytes() == PNG_BYTES, "物化附件字节不一致"
        # 缺失附件：不阻断启动，注入块标注不可用
        claude_md = (workdir / "CLAUDE.local.md").read_text(encoding="utf-8")
        assert "## 需求附件" in claude_md, f"注入块缺需求附件节:\n{claude_md[:600]}"
        assert f".devmind/input/{aid}.png" in claude_md, "清单缺物化路径"
        assert "不可用" in claude_md and MISSING_ID in claude_md, "缺失附件未标注不可用"
        print("[4] 附件物化 .devmind/input OK，CLAUDE.local.md 清单含路径 + 缺失标注")

        # 已注入上下文快照含 attachment 条目
        snap = req("GET", f"/sessions/{sid}/context", token=tok)
        kinds = [i["kind"] for i in snap["items"]]
        assert "attachment" in kinds, f"快照缺 attachment 条目: {snap['items']}"
        print("[5] 已注入上下文快照含 attachment 条目 OK")

        req("POST", f"/sessions/{sid}/finish", token=tok)
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "DONE" or None,
             "分析会话 DONE", 60)
        print("[6] 会话正常收尾 DONE OK")
    finally:
        kill_tree(runner.pid)
        try:
            req("POST", f"/agent-nodes/{node['id']}/unset-default", token=tok)
            req("DELETE", f"/agent-nodes/{node['id']}", token=tok)
            req("DELETE", f"/projects/{pid}", token=tok)
            req("DELETE", f"/attachments/{aid}", token=tok)
        except Exception as ex:
            print(f"[cleanup] {ex}")
    print("全部通过")


if __name__ == "__main__":
    main()
