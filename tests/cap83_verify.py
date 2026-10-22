# -*- coding: utf-8 -*-
"""CAP-83 二进制会话产出 E2E（自起隔离 app + 真实 runner，两路回传全链）。

运行前提：
  1. `mvn -q install -DskipTests`（runner jar 已重建——附件通道在 runner 侧，旧 jar 没有）；
  2. JDK 21：JAVA_HOME 未设时回落 Eclipse Adoptium jdk-21.0.12.101 本机路径。

脚本自起：隔离 app（:18101，--spring.profiles.active=e2e 独立 H2 落 tmp/cap83-e2e/）
+ 真实 runner（executor=fake）。跑完杀双进程，不留残留。

验收覆盖（CAP-83 §10）：
  [1] 文本产出仍走 JSON 通道落 session_outputs（attachmentId 为空，内容可读）——无回归
  [2] 二进制产出（非法 UTF-8 字节，xlsx 形态）走附件通道：outputs 列表带 attachmentId/contentType
  [3] 附件原文可下载（/attachments/{id}/raw 字节级一致，Content-Disposition=attachment）
  [4] 附件归属 = 会话创建者 + PRIVATE + tags=session-output（附件管理页口径）
  [5] 二进制行文本内容端点 400（不支持文本预览）
  [6] 二进制产出推送为文档 400（兜底闸，前端已隐藏入口）
  [7] 同名覆盖：直调附件通道重传 → 引用指向新附件，旧附件 404（管理页不留孤儿）
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PORT = int(os.environ.get("E2E_PORT", "18101"))
BASE = f"http://localhost:{PORT}/api"
TMP = ROOT / "tmp" / "cap83-e2e"
APP_LOG = TMP / "app.log"
RUNNER_LOG = TMP / "runner.log"
PROPS = TMP / "agent.properties"
RUNNER_JAR = ROOT / "devmind-agent-runner" / "target" / "devmind-agent-runner.jar"
WS = TMP / "ws"
ORIGIN = TMP / "origin"
MARK = f"cap83-{int(time.time())}"

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
JAVA = str(Path(JAVA_HOME) / "bin" / "java.exe")

# 非法 UTF-8 字节序列（0xFF 起头）= 二进制产出；形态仿 xlsx 但判定只看解码
BIN_BYTES = bytes([0x50, 0x4B, 0x03, 0x04, 0xFF, 0xD8, 0x00, 0xFE, 0x42, 0x99])
TEXT_BODY = "# 核对基准\n中文文本产出（CAP-83 无回归断言）\n"

passed, failed = [], []


def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    print(f"  {'PASS' if cond else 'FAIL'}  {name}" + ("" if cond else f"  {detail}"))


def req(method, path, body=None, token=None, expect=200, raw_body=None, ctype="application/json"):
    data = raw_body if raw_body is not None else (
        json.dumps(body, ensure_ascii=False).encode() if body is not None else None)
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": ctype})
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        payload = e.read().decode("utf-8", "replace")
        if e.code != expect:
            raise AssertionError(f"{method} {path} -> {e.code} (expect {expect}): {payload[:400]}")
        try:
            return json.loads(payload or "null")
        except json.JSONDecodeError:
            return payload


def fetch_raw(path):
    """GET 原文 + 响应头（附件 raw 下载断言用）。"""
    with urllib.request.urlopen(BASE + path, timeout=30) as resp:
        return resp.status, dict(resp.headers), resp.read()


def wait(cond, what, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = cond()
        if v:
            return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}")


def wait_health(timeout=150):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"http://localhost:{PORT}/api/health", timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(2)
    return False


def wait_port_free(timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(f"http://localhost:{PORT}/api/health", timeout=2)
        except Exception:
            return True
        time.sleep(1)
    return False


def kill_tree(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)


def kill_port_strays():
    out = subprocess.run(["netstat", "-ano"], capture_output=True).stdout.decode("utf-8", "replace")
    for line in out.splitlines():
        if f":{PORT}" in line and "LISTENING" in line:
            subprocess.run(["taskkill", "/F", "/PID", line.split()[-1]], capture_output=True)


def start_app():
    log = open(APP_LOG, "w", encoding="utf-8")
    args = (
        f"--server.port={PORT} "
        f"--spring.datasource.url=jdbc:h2:file:{(TMP / 'db/devmind').as_posix()};AUTO_SERVER=TRUE "
        # 克隆工作区根指进 TMP（默认 data/repositories 相对 app cwd=仓库根，跨次运行残留撞名 → clone exit=128）
        f"--devmind.project.workspace-root={(TMP / 'repositories').as_posix()}"
    )
    # mvn 必须 cmd /c 起（Windows 上 mvn 是 cmd 脚本）；参数值均无空格
    cmd = (f'cmd /c mvn -q -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e '
           f'-Dspring-boot.run.arguments="{args}"')
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
    if not wait_health():
        log.close()
        raise AssertionError(f"app 150s 内未起来（看 {APP_LOG}）")
    return proc, log


def start_runner(node_token):
    PROPS.write_text(
        f"serverUrl=ws://localhost:{PORT}/ws/agent\n"
        f"token={node_token}\n"
        f"executor=fake\n"
        # 本机常驻 runner 服务已占默认 gitProxyPort（18377）→ 本用例不需要 git 代理，显式关闭
        f"gitProxyEnabled=false\n"
        f"workDir={(TMP / 'runner-work').as_posix()}\n"
        f"workspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=2\n",
        encoding="utf-8")
    log = open(RUNNER_LOG, "w", encoding="utf-8")
    proc = subprocess.Popen([JAVA, "-jar", str(RUNNER_JAR), str(PROPS)],
                            stdout=log, stderr=subprocess.STDOUT)
    return proc, log


def main():
    if not RUNNER_JAR.exists():
        raise AssertionError("runner jar 缺失，先 mvn -q install -DskipTests")
    if TMP.exists():
        def _force_remove(func, path, _exc):
            os.chmod(path, 0o666)
            func(path)
        shutil.rmtree(TMP, onexc=_force_remove)
    TMP.mkdir(parents=True, exist_ok=True)

    app, app_log = start_app()
    runner, runner_log = None, None
    try:
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]

        # 源仓库：本地 git 仓库作 file:// 远端（CLONE 项目 → runner 托管工作区 → 产出可回传）
        ORIGIN.mkdir(parents=True)
        env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
                   GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
        subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
        (ORIGIN / "README.md").write_text("# cap83 e2e\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
        subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True,
                       capture_output=True, env=env)

        proj = req("POST", "/projects", {
            "name": MARK, "sourceType": "CLONE",
            "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
        }, token)
        pid = proj["id"]
        wait(lambda: req("GET", f"/projects/{pid}", token=token).get("cloneStatus") == "READY" or None,
             "项目克隆 READY", 90)
        reqm = req("POST", f"/projects/{pid}/requirements",
                   {"title": f"{MARK} 需求", "description": "二进制产出回传验证"}, token)
        rid = reqm["id"]
        print(f"[0] 项目 {pid} + 需求 {reqm['code']} 就绪")

        issued = req("POST", "/agent-nodes", {"name": MARK}, token)
        node, node_token = issued["node"], issued["token"]
        runner, runner_log = start_runner(node_token)
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=token)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print(f"[0] app + runner 上线（节点 {node['id']}）")

        # 起会话（显式指定节点，不依赖平台默认）
        s = req("POST", "/sessions", {
            "projectId": pid, "taskSpec": f"{MARK} 产出会话",
            "agentNodeId": str(node["id"]),
        }, token)
        sid = s["id"]

        # 等 runner 物化工作区（CAP-42 keyed 布局 <pid>/<owner>/worktrees/sid-<sid>），预写文本 + 二进制产出
        work = wait(lambda: (WS / pid / "admin" / "worktrees" / f"sid-{sid}")
                    if (WS / pid / "admin" / "worktrees" / f"sid-{sid}").is_dir() else None,
                    "会话工作区物化", 60)
        out = work / ".devmind" / "output"
        out.mkdir(parents=True, exist_ok=True)
        # 字节写防 text mode 换行转换（Windows 下 write_text 会把 \n 翻成 \r\n，回读比对必挂）
        (out / "notes.md").write_bytes(TEXT_BODY.encode("utf-8"))
        (out / "监控指标清单.xlsx").write_bytes(BIN_BYTES)

        req("POST", f"/sessions/{sid}/finish", token=token)
        wait(lambda: req("GET", f"/sessions/{sid}", token=token).get("status") == "DONE" or None,
             "会话 DONE", 60)
        print(f"[1] 会话 {sid} DONE，产出已回传")

        # [1][2] 列表：文本行 attachmentId 空；二进制行带 attachmentId + contentType
        files = {f["fileName"]: f for f in req("GET", f"/sessions/{sid}/outputs", token=token)}
        check("1 文本产出落 session_outputs（无 attachmentId）",
              "notes.md" in files and not files["notes.md"].get("attachmentId"),
              f"files={list(files)}")
        bin_row = files.get("监控指标清单.xlsx", {})
        att_id = bin_row.get("attachmentId")
        check("2 二进制产出走附件通道（attachmentId 就位）", bool(att_id), f"row={bin_row}")
        check("2 二进制行 sizeBytes = 原始字节数",
              bin_row.get("sizeBytes") == len(BIN_BYTES), f"sizeBytes={bin_row.get('sizeBytes')}")

        # [1] 文本内容端点无回归
        text_content = req("GET", f"/sessions/{sid}/outputs/notes.md", token=token)
        check("1 文本产出内容可读", text_content.get("content") == TEXT_BODY,
              f"content={text_content.get('content')!r}")

        if att_id:
            # [3] 附件原文下载：字节级一致 + attachment disposition（xlsx 非图片）
            st, headers, body = fetch_raw(f"/attachments/{att_id}/raw?access_token={token}")
            check("3 附件 raw 下载字节级一致", st == 200 and body == BIN_BYTES,
                  f"status={st} len={len(body)}")
            check("3 非图片附件 Content-Disposition=attachment",
                  "attachment" in (headers.get("Content-Disposition") or headers.get("content-disposition") or ""),
                  f"headers={headers}")

            # [4] 归属/标签口径（附件管理页列表断言）
            mine = req("GET", "/attachments", token=token)
            row = next((a for a in mine if a["attachmentId"] == att_id), None)
            check("4 附件归属会话创建者 + PRIVATE + session-output 标签",
                  row is not None and row.get("scope") == "PRIVATE"
                  and "session-output" in (row.get("tags") or ""),
                  f"row={row}")

            # [7] 同名覆盖：直调附件通道重传 → 引用换新，旧附件 404
            new_bytes = BIN_BYTES + b"\x01\x02"
            up = req("POST", f"/agent/output/{sid}/attachment"
                             f"?token={node_token}&name=%E7%9B%91%E6%8E%A7%E6%8C%87%E6%A0%87%E6%B8%85%E5%8D%95.xlsx"
                             f"&contentType=application%2Foctet-stream",
                     raw_body=new_bytes, ctype="application/octet-stream")
            att_id2 = up.get("attachmentId")
            files2 = {f["fileName"]: f for f in req("GET", f"/sessions/{sid}/outputs", token=token)}
            check("7 同名覆盖引用指向新附件",
                  bool(att_id2) and files2["监控指标清单.xlsx"].get("attachmentId") == att_id2,
                  f"row={files2.get('监控指标清单.xlsx')}")
            try:
                fetch_raw(f"/attachments/{att_id}/raw?access_token={token}")
                check("7 旧附件已删除（raw 404）", False, "旧附件仍可下载")
            except urllib.error.HTTPError as e:
                check("7 旧附件已删除（raw 404）", e.code == 404, f"status={e.code}")

        # [5] 二进制行文本内容端点 400
        e5 = req("GET", f"/sessions/{sid}/outputs/%E7%9B%91%E6%8E%A7%E6%8C%87%E6%A0%87%E6%B8%85%E5%8D%95.xlsx",
                 token=token, expect=400)
        check("5 二进制行文本内容端点 400", "二进制产出" in json.dumps(e5, ensure_ascii=False),
              f"resp={e5}")

        # [6] 推送为文档兜底闸 400
        e6 = req("POST", f"/sessions/{sid}/outputs/publish", {
            "fileName": "监控指标清单.xlsx", "kind": "analysis", "requirementId": rid,
            "mode": "create", "title": "x",
        }, token, expect=400)
        check("6 二进制产出推送为文档 400", "二进制产出" in json.dumps(e6, ensure_ascii=False),
              f"resp={e6}")
    finally:
        if runner:
            kill_tree(runner.pid)
        if runner_log:
            runner_log.close()
        try:
            req("DELETE", f"/agent-nodes/{node['id']}", token=token)
            req("DELETE", f"/projects/{pid}", token=token)
        except Exception as ex:
            print(f"[cleanup] {ex}")
        kill_tree(app.pid)
        time.sleep(2)
        kill_port_strays()
        app_log.close()
        wait_port_free()

    print(f"\n== 结果：{len(passed)} PASS / {len(failed)} FAIL")
    if failed:
        print("失败项：")
        for f in failed:
            print(f"  - {f}")
        sys.exit(1)


if __name__ == "__main__":
    main()
