#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-54 会话工作区实时视图 E2E（前置：后端已用新代码启动，runner jar 已重建）。

覆盖（协议 v11）：
1. 项目会话工作区 REST：status（git 快照，改动文件出现后变更列表反映）/ tree（一层、跳过 .git、
   目录优先）/ file（内容、相对路径）/ diff（已跟踪文件有 diff、未跟踪文件 untracked=true）；
2. 安全边界：../ 逃逸、绝对路径、file 缺 path → 4xx（runner ack error → 服务端 409/400）；
3. 推送旁路：fake runner 起会话后 launch 触发首轮采集，workspace/status 缓存可读（有 ts）；
4. 问答（chat）沙箱：status gitAvailable=false（或首 push 后同值），tree/file 可浏览，
   diff 无端点（404/405 皆可，只断「不是 200」）；
5. 终态会话：runner recentDirs 兜底，finish 后 tree/file 仍可读。

跑法见 tests/README.md「起独立实例跑 E2E」；E2E_BASE 默认打 :8080。
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap54-ws"
ORIGIN = TMP / "cap54-origin"
PROPS = TMP / "cap54-runner.properties"
MARK = f"e2e-cap54-{int(time.time())}"


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


def session_dir(sid, rid, timeout=60):
    """同 cap52：工作区根到工作树隔 <项目>/<用户> 两层，glob 必须带 **。"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        for pattern in (f"**/worktrees/req-{rid}", f"**/worktrees/sid-{sid}", f"**/sessions/{sid}"):
            hit = next(iter(WS.glob(pattern)), None)
            if hit:
                return hit
        if (WS / "_chat" / sid).exists():
            return WS / "_chat" / sid
        time.sleep(1)
    raise AssertionError(f"未找到会话工作区: session={sid} req={rid}")


def ws_get(tok, sid, action, **params):
    q = "&".join(f"{k}={urllib.parse.quote(str(v))}" for k, v in params.items())
    return req("GET", f"/sessions/{sid}/workspace/{action}" + (f"?{q}" if q else ""), token=tok)


def main():
    import urllib.parse  # noqa: 供 ws_get 用
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
    (ORIGIN / "README.md").write_text("# cap54 e2e\n", encoding="utf-8")
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
        "title": f"{MARK} 工作区视图", "description": "CAP-54 E2E",
    }, tok)
    rid = reqm["id"]
    print(f"[1] 项目 {pid} + 需求 {reqm['code']} 就绪")

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl={BASE.replace('http://', 'ws://').removesuffix('/api')}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap54-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=3\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap54-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    sid = None
    chat_id = None
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print("[2] runner 上线")

        # ---- 项目会话工作区 ----
        sess = req("POST", f"/projects/{pid}/requirements/{rid}/flow/plan", None, tok)
        sid = sess["id"]
        wt = session_dir(sid, rid)
        print(f"[3] 会话 {sid} 工作树 {wt.name}")

        # launch 后 watcher 首轮采集 → status 可读（缓存或实时查询兜底）
        st = wait(lambda: ws_get(tok, sid, "status") if True else None, "status 可读", 30)
        assert st.get("gitAvailable") is True, f"工作树应是 git 库: {st}"
        repos = st.get("repos") or []
        assert repos and repos[0].get("branch"), f"应有分支: {st}"
        print(f"[4] status OK：gitAvailable=true branch={repos[0].get('branch')} 初始变更 {st.get('total', {}).get('files', 0)} 个")

        # 模拟 agent 改文件（fake executor 不产生事件，watcher 15s 兜底轮询会推到；REST 实时查）
        (wt / "new-file.txt").write_text("cap54 新增\n", encoding="utf-8")
        (wt / "README.md").write_text("# cap54 e2e\n改动一行\n", encoding="utf-8")

        st2 = ws_get(tok, sid, "status")
        paths = {c["path"] for r in st2["repos"] for c in r["changes"]}
        assert "new-file.txt" in paths and "README.md" in paths, f"变更应含两文件: {paths}"
        print(f"[5] status 反映改动 OK: {sorted(paths)}")

        tree = ws_get(tok, sid, "tree")
        names = [e["name"] for e in tree["entries"]]
        assert "README.md" in names and "new-file.txt" in names, f"tree 缺文件: {names}"
        assert ".git" not in names, f".git 不应入列: {names}"
        assert tree["entries"][0]["dir"] or not any(e["dir"] for e in tree["entries"]), \
            "目录应排前"
        f = ws_get(tok, sid, "file", path="new-file.txt")
        assert f["content"].replace("\r\n", "\n") == "cap54 新增\n", f"file 内容不符: {f}"
        print(f"[6] tree/file OK（{len(names)} 项，.git 已跳过）")

        d = ws_get(tok, sid, "diff", path="README.md")
        assert d.get("untracked") is False and "改动一行" in d.get("diff", ""), f"diff 不符: {d}"
        d2 = ws_get(tok, sid, "diff", path="new-file.txt")
        assert d2.get("untracked") is True, f"未跟踪文件应 untracked=true: {d2}"
        print("[7] diff OK（已跟踪有 diff / 未跟踪 untracked）")

        # 安全边界：逃逸与缺参一律非 200
        e = req("GET", f"/sessions/{sid}/workspace/file?path=../escape.txt", token=tok, expect=409)
        assert "越界" in json.dumps(e, ensure_ascii=False) or "相对路径" in json.dumps(e, ensure_ascii=False), e
        req("GET", f"/sessions/{sid}/workspace/tree?path=/etc", token=tok, expect=409)
        req("GET", f"/sessions/{sid}/workspace/file", token=tok, expect=400)
        print("[8] 路径逃逸 409 / 缺 path 400 OK")

        # ---- 问答沙箱 ----
        chat = req("POST", "/chats", {"message": f"{MARK} 你好"}, tok)
        chat_id = chat["id"]
        wait(lambda: req("GET", f"/chats/{chat_id}", token=tok).get("state")
             in ("RUNNING", "WAITING_INPUT", "DONE") or None, "问答启动", 60)
        cst = req("GET", f"/chats/{chat_id}/workspace/status", token=tok)
        assert cst.get("gitAvailable") is False, f"问答沙箱非 git: {cst}"
        ctree = req("GET", f"/chats/{chat_id}/workspace/tree", token=tok)
        assert "entries" in ctree, f"chat tree 不符: {ctree}"
        r = urllib.request.Request(f"{BASE}/chats/{chat_id}/workspace/diff?path=x")
        r.add_header("Authorization", "Bearer " + tok)
        try:
            urllib.request.urlopen(r)
            raise AssertionError("chat diff 端点不应存在（200）")
        except urllib.error.HTTPError as ex:
            assert ex.code in (404, 405), f"chat diff 应 404/405, got {ex.code}"
        print(f"[9] 问答沙箱 OK：gitAvailable=false，tree 可读（{len(ctree['entries'])} 项），diff 无端点")

        # ---- 终态兜底（runner recentDirs）----
        req("POST", f"/sessions/{sid}/finish", token=tok)
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "DONE" or None,
             "会话 DONE", 90)
        f2 = ws_get(tok, sid, "file", path="new-file.txt")
        assert f2["content"].replace("\r\n", "\n") == "cap54 新增\n", f"终态 file 不符: {f2}"
        print("[10] 终态会话经 recentDirs 仍可读 OK")

        print("\nCAP-54 E2E 全部通过")
    finally:
        kill_tree(runner.pid)
        try:
            if sid:
                req("DELETE", f"/sessions/{sid}", token=tok)
        except Exception:
            pass
        try:
            if chat_id:
                req("DELETE", f"/chats/{chat_id}", token=tok)
        except Exception:
            pass
        try:
            req("DELETE", f"/projects/{pid}", token=tok)
            req("DELETE", f"/agent-nodes/{node['id']}", token=tok)
        except Exception:
            pass


if __name__ == "__main__":
    main()
