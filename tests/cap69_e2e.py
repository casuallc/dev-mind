#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-69 脚本测试套件 E2E（前置：mvn -q install -DskipTests 出 runner jar；app 起隔离实例）。

链路：真 runner jar（execAllowlist=bash）连隔离实例，fixture 是 tmp/ 下的 file:// git 仓库
（fake 测试脚本按 SUITE_MODE 生成 pass/fail/skip 混合 junit.xml）。exec 帧整包下发、
repo 克隆缓存、DEVMIND_JUNIT marker 回收、JUnit 解析全走真的。

验收覆盖（docs/capabilities/CAP-69-script-test-suite.md）：
  [1] 套件 CRUD：创建/列表/更新；secret env 视图层掩码 + 掩码回传=不变；
      junitPath 穿越（../x.xml）与非法 env 键（1BAD）400
  [2] 触发运行（显式节点）→ SUCCESS：results 三条（pass×2/skip×1，中文用例名
      classname#name 拼接、time 秒→毫秒），summary 聚合
  [3] env 覆盖（SUITE_MODE=fail，仅本次生效）→ FAILED：fail 结果带 error 文本；
      失败转缺陷线索端点可用
  [4] junit 缺失（SUITE_MODE=nojunit）→ 不炸：SUCCESS + results 空 +
      errorSummary「未回传 JUnit 报告」
  [5] 离线节点 → 触发 409
  [6] 共享 workspaceKey：套件 A 先跑留 .state-ran.txt，套件 B 同 key 跑
      check_state.sh 读到 → SUCCESS（工作区跨套件复用）
  [7] 套件默认节点路由：套件 B/C 不传 agentNodeId（body 空）走套件默认
  [8] GET /api/test-runs?kind=script 过滤（独立于项目的运行历史）

前置（隔离实例不起就不算数，别拿 :8080 的 dev 实例跑）：
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
  mvn -q install -DskipTests
  rm -rf tmp/cap69-data
  mvn -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e \
    "-Dspring-boot.run.arguments=--server.port=18099 --spring.datasource.url=jdbc:h2:file:$(cygpath -m $PWD/tmp/cap69-data/db)/devmind;AUTO_SERVER=TRUE"
  python tests/cap69_e2e.py        # E2E_BASE 默认 http://localhost:18099/api
"""
import json, os, shutil, subprocess, sys, time
import urllib.request, urllib.error
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:18099/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
REPO_DIR = TMP / "cap69-repo"        # fixture git 仓库（file:// 源）
WS_DIR = TMP / "cap69-ws"            # runner 工作区根
PROPS = TMP / "cap69-runner.properties"
MARK = f"e2e-cap69-{int(time.time())}"
JAVA_HOME = os.environ.get("JAVA_HOME")
JAVA = str(Path(JAVA_HOME) / "bin" / "java.exe") if JAVA_HOME else "java"

# fake 测试脚本：按 SUITE_MODE 出 junit.xml（pass 混合 skip / fail / 不产出）
RUN_TESTS_SH = """#!/usr/bin/env bash
set -u
MODE="${SUITE_MODE:-pass}"
if [ "$MODE" = "nojunit" ]; then
  echo "no junit produced (mode=nojunit)"
  exit 0
fi
mkdir -p test-results
if [ "$MODE" = "fail" ]; then
  cat > test-results/junit.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fake" tests="2" failures="1" skipped="0" time="0.46">
  <testcase classname="fake.Login" name="登录成功" time="0.12"/>
  <testcase classname="fake.Login" name="错误密码拒绝" time="0.34">
    <failure message="expected 401 got 200">stack line</failure>
  </testcase>
</testsuite>
XML
  exit 1
fi
cat > test-results/junit.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fake" tests="3" failures="0" skipped="1" time="1.76">
  <testcase classname="fake.Cluster" name="集群创建" time="0.50"/>
  <testcase classname="fake.Cluster" name="扩容到三节点" time="1.25"/>
  <testcase classname="fake.Cluster" name="缩容回单节点" time="0.01">
    <skipped message="环境不支持"/>
  </testcase>
</testsuite>
XML
echo "ran $(date +%s)" >> .state-ran.txt
exit 0
"""

# 共享工作区断言脚本：同 workspaceKey 的前序套件应已留下 .state-ran.txt
CHECK_STATE_SH = """#!/usr/bin/env bash
if [ ! -f .state-ran.txt ]; then
  echo "missing .state-ran.txt: workspace NOT shared" >&2
  exit 3
fi
echo "shared workspace state found:"
cat .state-ran.txt
"""


def req(method, path, body=None, token=None, expect=200):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            payload = resp.read()
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(payload.decode() or "null")
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


def wait_run(run_id, tok, timeout=180):
    return wait(lambda: (r if (r := req("GET", f"/test-runs/{run_id}", token=tok))["status"]
                         in ("SUCCESS", "FAILED") else None),
                f"运行 #{run_id} 终态", timeout)


def kill_tree(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)


def git(*args, cwd):
    subprocess.run(["git", "-c", "core.autocrlf=false", *args], cwd=cwd, check=True,
                   capture_output=True)


def _rm_readonly(func, path, _exc_info):
    # Windows 下 git objects 是只读文件，rmtree 默认删不掉
    os.chmod(path, 0o666)
    func(path)


def rm_rf(p):
    shutil.rmtree(p, ignore_errors=True)
    if p.exists():
        shutil.rmtree(p, onexc=_rm_readonly)


def make_fixture_repo():
    rm_rf(REPO_DIR)
    REPO_DIR.mkdir(parents=True)
    (REPO_DIR / "run_tests.sh").write_text(RUN_TESTS_SH, encoding="utf-8", newline="\n")
    (REPO_DIR / "check_state.sh").write_text(CHECK_STATE_SH, encoding="utf-8", newline="\n")
    git("init", "-b", "master", cwd=REPO_DIR)
    git("add", ".", cwd=REPO_DIR)
    git("-c", "user.email=cap69@e2e.local", "-c", "user.name=cap69",
        "commit", "-m", "cap69 fixture", cwd=REPO_DIR)
    return "file:///" + REPO_DIR.as_posix()


def main():
    if not RUNNER_JAR.exists():
        raise AssertionError("runner jar 缺失：先 mvn -q install -DskipTests")

    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    # 新鲜度护栏：防误指到在用的 dev 实例
    assert req("GET", "/script-suites", token=tok) == [], "script-suites 非空——这不是干净实例，拒跑"
    assert req("GET", "/test-runs?kind=script", token=tok) == [], "已有脚本运行记录——拒跑"

    repo_url = make_fixture_repo()
    print(f"[0] fixture 仓库 OK（{repo_url}）")

    # ---- [1] CRUD + env 掩码 ----
    suite_a = req("POST", "/script-suites", {
        "name": MARK + " A", "repoUrl": repo_url, "branch": "master",
        "command": "bash run_tests.sh", "junitPath": "test-results/junit.xml",
        "env": [{"key": "BASE_URL", "value": "http://example.local", "secret": False},
                {"key": "PASSWORD", "value": "s3cret", "secret": True}],
        "workspaceKey": "cap69-shared", "timeoutSec": 600,
    }, tok, expect=200)
    aid = suite_a["id"]
    view = next(s for s in req("GET", "/script-suites", token=tok) if s["id"] == aid)
    env = {e["key"]: e for e in view["env"]}
    assert env["BASE_URL"]["value"] == "http://example.local", view["env"]
    assert env["PASSWORD"]["value"] == "******" and env["PASSWORD"]["secret"], view["env"]
    # 掩码回传 = 该条不变（同时改名验证 PUT 生效）
    req("PUT", f"/script-suites/{aid}", {
        "name": MARK + " A2", "repoUrl": repo_url, "branch": "master",
        "command": "bash run_tests.sh", "junitPath": "test-results/junit.xml",
        "env": [{"key": "BASE_URL", "value": "http://example.local", "secret": False},
                {"key": "PASSWORD", "value": "******", "secret": True}],
        "workspaceKey": "cap69-shared", "timeoutSec": 600,
    }, tok)
    view = req("GET", f"/script-suites/{aid}", token=tok)
    assert view["name"].endswith("A2")
    env = {e["key"]: e for e in view["env"]}
    assert env["PASSWORD"]["value"] == "******", "掩码回传后视图仍应掩码"
    # 负例：junitPath 穿越 / env 非法键
    req("POST", "/script-suites", {
        "name": MARK + " bad", "repoUrl": repo_url, "branch": "master",
        "command": "bash run_tests.sh", "junitPath": "../evil.xml"}, tok, expect=400)
    req("POST", "/script-suites", {
        "name": MARK + " bad2", "repoUrl": repo_url, "branch": "master",
        "command": "bash run_tests.sh", "junitPath": "test-results/junit.xml",
        "env": [{"key": "1BAD", "value": "x", "secret": False}]}, tok, expect=400)
    print("[1] CRUD + secret 掩码/掩码回传 + 路径与 env 键校验 OK")

    # ---- 节点与 runner ----
    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    nid = node["id"]
    offline = req("POST", "/agent-nodes", {"name": MARK + "-offline"}, tok)

    rm_rf(WS_DIR)
    PROPS.write_text(
        f"serverUrl={BASE.replace('http://', 'ws://').removesuffix('/api')}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap69-runner-work').as_posix()}\nworkspaceRoot={WS_DIR.as_posix()}\n"
        f"execAllowlist=bash\nmaxConcurrent=2\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        [JAVA, "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap69-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)

    run_ids = []
    suites_extra = []
    try:
        view = wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                                  if n["id"] == nid and n["status"] == "ONLINE"), None),
                    "节点上线", 40)
        print(f"[1b] runner 上线（protocolVersion={view.get('protocolVersion')}）")

        # 套件 B/C 走套件默认节点（不随 run 传 agentNodeId）
        suite_b = req("POST", "/script-suites", {
            "name": MARK + " B", "repoUrl": repo_url, "branch": "master",
            "command": "bash check_state.sh && bash run_tests.sh",
            "junitPath": "test-results/junit.xml",
            "agentNodeId": str(nid), "workspaceKey": "cap69-shared", "timeoutSec": 600,
        }, tok)
        suites_extra.append(suite_b["id"])
        suite_c = req("POST", "/script-suites", {
            "name": MARK + " C", "repoUrl": repo_url, "branch": "master",
            "command": "bash run_tests.sh", "junitPath": "test-results/junit.xml",
            "env": [{"key": "SUITE_MODE", "value": "nojunit", "secret": False}],
            "agentNodeId": str(nid), "timeoutSec": 600,
        }, tok)
        suites_extra.append(suite_c["id"])

        # ---- [2] 触发运行（显式节点）→ SUCCESS，三条结果逐一断言 ----
        run = req("POST", f"/script-suites/{aid}/run", {"agentNodeId": str(nid)}, tok)
        run_ids.append(run["id"])
        r = wait_run(run["id"], tok)
        assert r["status"] == "SUCCESS", f"应 SUCCESS: {r['status']} {r.get('errorSummary')}"
        s = r["summary"]
        assert (s["total"], s["passed"], s["failed"], s["skipped"]) == (3, 2, 0, 1), s
        by_name = {x["name"]: x for x in r["results"]}
        assert set(by_name) == {"fake.Cluster#集群创建", "fake.Cluster#扩容到三节点",
                                "fake.Cluster#缩容回单节点"}, list(by_name)
        assert by_name["fake.Cluster#集群创建"]["status"] == "pass"
        assert by_name["fake.Cluster#集群创建"]["duration"] == 500, by_name["fake.Cluster#集群创建"]
        assert by_name["fake.Cluster#扩容到三节点"]["duration"] == 1250
        assert by_name["fake.Cluster#缩容回单节点"]["status"] == "skip"
        print(f"[2] 运行 #{run['id']} SUCCESS：3 条结果（pass×2/skip×1，中文名 + 耗时）OK")

        # ---- [3] env 覆盖 → FAILED + 缺陷线索 ----
        run = req("POST", f"/script-suites/{aid}/run",
                  {"agentNodeId": str(nid), "env": {"SUITE_MODE": "fail"}}, tok)
        run_ids.append(run["id"])
        r = wait_run(run["id"], tok)
        assert r["status"] == "FAILED", f"覆盖 SUITE_MODE=fail 应 FAILED: {r['status']}"
        assert r["summary"]["failed"] == 1, r["summary"]
        fail_row = next(x for x in r["results"] if x["status"] == "fail")
        assert fail_row["name"] == "fake.Login#错误密码拒绝"
        assert "expected 401" in (fail_row["error"] or ""), fail_row["error"]
        drafts = req("POST", f"/test-runs/{run['id']}/issues", None, tok)
        assert isinstance(drafts, list) and len(drafts) >= 1 and drafts[0]["title"], drafts
        print(f"[3] env 覆盖 → FAILED（fail 行带 error 文本）+ 缺陷线索 {len(drafts)} 条 OK")

        # ---- [4] junit 缺失不炸（suite C 自带 SUITE_MODE=nojunit） ----
        run = req("POST", f"/script-suites/{suite_c['id']}/run", None, tok)  # 套件默认节点
        run_ids.append(run["id"])
        r = wait_run(run["id"], tok)
        assert r["status"] == "SUCCESS", f"junit 缺失不应判失败: {r['status']} {r.get('errorSummary')}"
        assert r["results"] == [], r["results"]
        assert "未回传 JUnit 报告" in (r["errorSummary"] or ""), r["errorSummary"]
        print(f"[4] junit 缺失 → SUCCESS + results 空 + errorSummary 注记 OK（#{run['id']}，套件默认节点路由）")

        # ---- [5] 离线节点 → 409 ----
        resp = req("POST", f"/script-suites/{aid}/run",
                   {"agentNodeId": str(offline["node"]["id"])}, tok, expect=409)
        assert resp is not None
        print("[5] 离线节点触发 409 OK")

        # ---- [6] 共享 workspaceKey：B 读到 A 留的 .state-ran.txt ----
        run = req("POST", f"/script-suites/{suite_b['id']}/run", None, tok)
        run_ids.append(run["id"])
        r = wait_run(run["id"], tok)
        assert r["status"] == "SUCCESS", \
            f"共享工作区应读到 .state-ran.txt: {r['status']} {r.get('errorSummary')}"
        print(f"[6] 共享 workspaceKey 跨套件复用 OK（#{run['id']} SUCCESS）")

        # ---- [8] kind=script 运行历史 ----
        hist = req("GET", "/test-runs?kind=script", token=tok)
        assert len(hist) == 4, f"应有 4 条脚本运行记录: {len(hist)}"
        assert all(h["projectId"] is None for h in hist), "脚本运行应无项目归属"
        print("[8] GET /test-runs?kind=script 过滤 OK（4 条，projectId 全空）")
    finally:
        try:
            for rid in run_ids:
                req("DELETE", f"/test-runs/{rid}", token=tok)
            for sid in [aid] + suites_extra:
                req("DELETE", f"/script-suites/{sid}", token=tok)
            for n in (node, offline["node"]):
                req("DELETE", f"/agent-nodes/{n['id']}", token=tok)
        except Exception as ex:
            print(f"[cleanup] {ex}")
        kill_tree(runner.pid)
    print("全部通过")


if __name__ == "__main__":
    main()
