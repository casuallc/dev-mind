# -*- coding: utf-8 -*-
"""CAP-76 FR-03 工具契约快照（验收 10）。

起隔离 app（或复用 `E2E_BASE` 指向的实例）→ 拉取
`GET /api/copilot/admin/openapi.json` 与 `GET /api/copilot/admin/tools`，
自检后把契约快照写到 `docs/capabilities/tools/cap76-tools.yaml`（随代码评审；
JSON 是合法 YAML 1.2，标准 YAML 解析器可直接读）。

自检项：
  - `openapi` = 3.0.3，`info` 齐全；
  - operation 数 == 注册表工具数，operationId 集合与工具名集合一致；
  - 每个 operation 的 requestBody schema 与注册表 `parametersSchema` 解析结果逐字段相等；
  - 每个 operation 带 x-tool-risk / x-min-role / x-owner-module / x-tool-since；
  - 所有 path 形如 `/tools/<name>`，method 均为 post。

用法：
  python tests/cap76_tools_contract.py
  E2E_BASE=http://localhost:8090/api python tests/cap76_tools_contract.py
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
PORT = int(os.environ.get("E2E_PORT", "18096"))
BASE = os.environ.get("E2E_BASE") or f"http://localhost:{PORT}/api"
OUT = ROOT / "docs" / "capabilities" / "tools" / "cap76-tools.yaml"
TMP = ROOT / "tmp" / "cap76-contract"
JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

passed, failed = [], []
TOKEN = None


def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    print(f"  {'PASS' if cond else 'FAIL'}  {name}" + ("" if cond else f"  {detail}"))


def api(method, path, body=None, token=None, timeout=60):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def wait_health(timeout=180):
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


def _force_rmtree(path):
    def _onerror(func, p, _exc):
        try:
            os.chmod(p, 0o666)
        except Exception:
            pass
        try:
            func(p)
        except Exception:
            pass
    if os.path.exists(path):
        shutil.rmtree(path, onerror=_onerror)


def start_app():
    _force_rmtree(TMP)
    (TMP / "db").mkdir(parents=True, exist_ok=True)
    kill_port_strays()
    log = open(TMP / "app.log", "w", encoding="utf-8")
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    args = (
        f"--server.port={PORT} "
        f"--spring.datasource.url=jdbc:h2:file:{(TMP / 'db' / 'devmind').as_posix()};AUTO_SERVER=TRUE "
        f"--devmind.project.workspace-root={(TMP / 'repositories').as_posix()} "
        f"--devmind.worktree.root={(TMP / 'worktrees').as_posix()} "
        f"--devmind.attachment.root-dir={(TMP / 'attachments').as_posix()} "
        f"--devmind.worklog.repo-root={(TMP / 'worklog').as_posix()} "
        f"--devmind.worklog.daily-enabled=false "
        f"--devmind.worklog.weekly-enabled=false "
        f"--devmind.worklog.git-import-enabled=false"
    )
    cmd = (f'cmd /c mvn -q -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e '
           f'-Dspring-boot.run.arguments="{args}"')
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
    if not wait_health():
        log.close()
        raise AssertionError(f"app 180s 内未起来（看 {TMP / 'app.log'}）")
    return proc, log


def stop_app(proc, log):
    kill_tree(proc.pid)
    time.sleep(2)
    kill_port_strays()
    log.close()
    wait_port_free()


def main():
    global TOKEN
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    own_app = not os.environ.get("E2E_BASE")
    proc = log = None
    if own_app:
        print(f"[app] 起隔离实例（:{PORT}）")
        proc, log = start_app()

    try:
        status, body = api("POST", "/auth/login", {"username": "admin", "password": "admin123"})
        TOKEN = (body or {}).get("token") or (body or {}).get("accessToken") if status == 200 else None
        check("admin 登录", bool(TOKEN), f"{status} {body}")
        if not TOKEN:
            sys.exit(1)

        status, doc = api("GET", "/copilot/admin/openapi.json", token=TOKEN)
        check("拉取 openapi.json", status == 200 and isinstance(doc, dict), f"{status}")
        status, tools = api("GET", "/copilot/admin/tools", token=TOKEN)
        check("拉取工具目录", status == 200 and isinstance(tools, list) and len(tools) > 0,
              f"{status} {tools if not isinstance(tools, list) else len(tools)}")
        if not isinstance(doc, dict) or not isinstance(tools, list) or not tools:
            sys.exit(1)

        check("OpenAPI 版本 3.0.3", doc.get("openapi") == "3.0.3", str(doc.get("openapi")))
        info = doc.get("info") or {}
        check("info 完整", bool(info.get("title")) and bool(info.get("version")), str(info))

        paths = doc.get("paths") or {}
        tool_names = {t.get("name") for t in tools}
        check("operation 数 == 工具数", len(paths) == len(tools),
              f"{len(paths)} vs {len(tools)}")

        problems = []
        for tool in tools:
            name = tool.get("name")
            path = f"/tools/{name}"
            entry = paths.get(path)
            if not entry or "post" not in entry:
                problems.append(f"{name}: 缺 post operation")
                continue
            op = entry["post"]
            if op.get("operationId") != name:
                problems.append(f"{name}: operationId={op.get('operationId')}")
            if op.get("summary") != tool.get("title"):
                problems.append(f"{name}: summary 与 title 不一致")
            for ext, key in (("x-tool-risk", "risk"), ("x-min-role", "minRole"),
                             ("x-owner-module", "ownerModule"), ("x-tool-since", "since")):
                if op.get(ext) != tool.get(key):
                    problems.append(f"{name}: {ext}={op.get(ext)!r} 期望 {tool.get(key)!r}")
            schema = (((op.get("requestBody") or {}).get("content") or {})
                      .get("application/json") or {}).get("schema")
            try:
                expected = json.loads(tool.get("parametersSchema") or "{}")
            except Exception as e:
                problems.append(f"{name}: parametersSchema 不是 JSON: {e}")
                continue
            if schema != expected:
                problems.append(f"{name}: requestBody schema 与 parametersSchema 不一致")
        check("每个 operation 与注册表逐项一致", not problems,
              "; ".join(problems[:6]) + (" …" if len(problems) > 6 else ""))
        check("operationId 集合 == 工具名集合",
              {((paths.get(f'/tools/{n}') or {}).get("post") or {}).get("operationId")
               for n in tool_names} == tool_names)
        check("path 均为 /tools/<name>",
              all(p.startswith("/tools/") and p[len("/tools/"):] in tool_names for p in paths))

        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        TMP.mkdir(parents=True, exist_ok=True)
        (TMP / "openapi.json").write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n",
                                          encoding="utf-8")
        check("契约快照已写", OUT.exists() and OUT.stat().st_size > 0, str(OUT))
        print(f"  快照：{OUT}（{len(paths)} 个 operation）")
    finally:
        if proc is not None and log is not None:
            stop_app(proc, log)
            print("清理完成：app 已停止")

    print(f"\n通过 {len(passed)} 项，失败 {len(failed)} 项")
    if failed:
        print("失败项：")
        for name in failed:
            print("  - " + name)
        sys.exit(1)


if __name__ == "__main__":
    main()
