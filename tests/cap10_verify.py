# -*- coding: utf-8 -*-
"""CAP-10 测试执行器端到端验证。
前置：后端 :8080 已起；tests/fixtures/TestSshServer(2222) 与 tests/fixtures/api-mock.js(9300) 已起（或本脚本拉起）。
覆盖：apiDocSource + OpenAPI 生成套件（正常流/鉴权边界/参数替换）、用例人工编辑、
冒烟套件（health 用例）、API 执行 pass/fail + 汇总、报告文档（kind=report）、WS 实时流、
失败转缺陷线索（FR-06）、套件沉淀 api-suite 文档（FR-03 版本化）、部署成功自动回归（FR-05）。
"""
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://localhost:8080/api"
ROOT = pathlib.Path(r"D:/apusic/dev-mind")
TMP = ROOT / "tmp"
FIX = ROOT / "tests" / "fixtures"
REPO = TMP / "cap10-repo"
passed = 0
failed = 0
PROCS = []

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")


def call(method, path, body=None):
    url = quote(BASE + path, safe=":/?&=%,.-")
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def call_text(path):
    with urllib.request.urlopen(quote(BASE + path, safe=":/?&=%,.-"), timeout=90) as r:
        return r.status, r.read().decode("utf-8", "replace")


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def git(args):
    out = subprocess.run(["git", "-C", str(ROOT)] + args, capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else None


def start_harnesses():
    need_ssh, need_mock = True, True
    try:
        with urllib.request.urlopen("http://127.0.0.1:9300/api/health", timeout=2):
            need_mock = False
    except Exception:
        pass
    try:
        import socket
        s = socket.create_connection(("127.0.0.1", 2222), timeout=2)
        s.close()
        need_ssh = False
    except Exception:
        pass
    if need_ssh:
        m2 = str(pathlib.Path.home() / ".m2" / "repository")
        cp = (";".join([
            str(FIX),
            f"{m2}/org/apache/sshd/sshd-core/2.16.0/sshd-core-2.16.0.jar",
            f"{m2}/org/apache/sshd/sshd-common/2.16.0/sshd-common-2.16.0.jar",
            f"{m2}/org/apache/sshd/sshd-sftp/2.16.0/sshd-sftp-2.16.0.jar",
            f"{m2}/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
            f"{m2}/org/slf4j/slf4j-nop/2.0.17/slf4j-nop-2.0.17.jar",
        ]))
        p = subprocess.Popen(["java", "-cp", cp, "TestSshServer", "2222", "test", "testpw"],
                             cwd=FIX, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        PROCS.append(p)
        print("  [拉起 TestSshServer:2222]")
    if need_mock:
        p = subprocess.Popen(["node", str(FIX / "api-mock.js"), "9300"],
                             cwd=FIX, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        PROCS.append(p)
        print("  [拉起 api-mock:9300]")
    time.sleep(4)


def tpl(pid, code, name, script, params, allowed):
    r = call("POST", "/script-templates", {
        "projectId": pid, "code": code, "name": name, "templateText": script,
        "params": params, "allowed": allowed})
    assert r[0] == 200, f"建模板 {code} 失败: {r}"


def replace_steps(pid, steps):
    for s in (call("GET", f"/projects/{pid}/build-steps")[1] or []):
        call("DELETE", f"/projects/{pid}/build-steps/{s['id']}")
    for i, st in enumerate(steps):
        st = dict(st)
        st["sortOrder"] = i
        st["workingDir"] = st.get("workingDir", "")
        st["location"] = st.get("location", "LOCAL")
        r = call("POST", f"/projects/{pid}/build-steps", st)
        assert r[0] == 200, f"建步骤失败: {r}"


def trigger_build(pid):
    st, b = call("POST", f"/projects/{pid}/builds", {})
    assert st == 200 and b.get("id"), f"触发构建失败: {st} {b}"
    deadline = time.time() + 60
    while time.time() < deadline:
        st, b = call("GET", f"/builds/{b['id']}")
        if st == 200 and b["status"] in ("SUCCESS", "FAILED"):
            return b
        time.sleep(1)
    raise TimeoutError("构建未结束")


def wait_run(rid, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, r = call("GET", f"/test-runs/{rid}")
        if st == 200 and r["status"] in ("SUCCESS", "FAILED"):
            return r
        time.sleep(1)
    raise TimeoutError(f"测试 #{rid} 未在 {timeout}s 内结束")


def find_case(cases, name):
    for c in cases:
        if c["name"] == name:
            return c
    return None


def main():
    start_harnesses()

    # ---------- 0. 项目（独立临时仓库，避免污染主仓库） ----------
    st, projects = call("GET", "/projects")
    proj = next((p for p in (projects or []) if p.get("name") == "CAP10 测试"), None)
    if not proj:
        st, proj = call("POST", "/projects", {
            "name": "CAP10 测试", "path": str(REPO).replace("/", "\\"),
            "defaultBranch": "master", "tags": [], "apiDocSource": "docs/openapi.yaml"})
        check("创建项目", st == 200 and proj and proj.get("id"), f"{st} {proj}")
    else:
        # 复用项目时把 apiDocSource 刷新（可能被上次验证改过）
        call("PUT", f"/projects/{proj['id']}", {
            "name": "CAP10 测试", "path": str(REPO).replace("/", "\\"),
            "defaultBranch": "master", "tags": [], "apiDocSource": "docs/openapi.yaml",
            "autoRegressionOnDeploy": False})
        print("  [复用项目]")
    pid = proj["id"]
    print(f"  [项目 {pid}]")

    # ---------- 0b. 清理遗留数据 ----------
    for s in (call("GET", f"/projects/{pid}/servers")[1] or []):
        call("DELETE", f"/projects/{pid}/servers/{s['id']}")
    for t in (call("GET", f"/script-templates?projectId={pid}")[1] or []):
        call("DELETE", f"/script-templates/{t['id']}")
    for r in (call("GET", f"/test-runs?projectId={pid}")[1] or []):
        call("DELETE", f"/test-runs/{r['id']}")
    for s in (call("GET", f"/projects/{pid}/test-suites")[1] or []):
        call("DELETE", f"/test-suites/{s['id']}")
    for d in (call("GET", f"/deployments?projectId={pid}")[1] or []):
        call("DELETE", f"/deployments/{d['id']}")
    for b in (call("GET", f"/builds?projectId={pid}")[1] or []):
        call("DELETE", f"/builds/{b['id']}")

    # ---------- 1. 注册服务器 + 模板 ----------
    st, ssh = call("POST", f"/projects/{pid}/servers", {
        "name": "本地SSH", "env": "test", "accessType": "ssh",
        "accessConfig": json.dumps({"host": "127.0.0.1", "port": 2222, "username": "test",
                                    "authType": "password", "password": "testpw"}),
        "capabilities": ["test", "deploy", "build", "health"], "enabled": True})
    ssh_id = ssh.get("id")
    check("注册 SSH 服务器", st == 200 and ssh_id, f"{st} {ssh}")

    tpl(pid, "health-smoke", "冒烟健康检查", "echo health-ok", [], ["test"])
    tpl(pid, "deploy-apply", "部署应用", "echo deploy-ok artifact=${artifact}", [{"name": "artifact", "required": False}], ["deploy"])

    # ---------- 2. OpenAPI 生成 API 套件（FR-02） ----------
    st, suite = call("POST", f"/projects/{pid}/test-suites/generate")
    check("从 OpenAPI 生成套件", st == 200 and suite.get("id") and suite["kind"] == "api"
          and suite["source"] == "openapi", f"{st} {suite}")
    api_id = suite["id"]
    cases = suite["cases"]
    names = [c["name"] for c in cases]
    check("生成用例数=7", len(cases) == 7, f"实际 {len(cases)}: {names}")

    c_health = find_case(cases, "healthCheck")
    check("health 用例", c_health is not None and c_health["method"] == "GET" and c_health["path"] == "/api/health"
          and c_health["expected"].get("status") == 200, str(c_health))
    c_users = find_case(cases, "listUsers")
    check("listUsers 用例", c_users is not None and c_users["expected"].get("status") == 200, str(c_users))
    c_create = find_case(cases, "createUser")
    check("createUser 用例(body 示例)", c_create is not None and c_create["method"] == "POST"
          and c_create["expected"].get("status") == 201 and "carol" in (c_create["body"] or ""), str(c_create))
    c_get = find_case(cases, "getUser")
    check("getUser 路径参数替换", c_get is not None and c_get["path"] == "/api/users/1", str(c_get))
    c_pets = find_case(cases, "listPets")
    check("listPets query 参数", c_pets is not None and c_pets["params"].get("name") in ("test", ""), str(c_pets))
    c_priv = find_case(cases, "getPrivate")
    c_priv_auth = find_case(cases, "getPrivate（未鉴权）")
    check("鉴权边界用例（未鉴权期望401）", c_priv_auth is not None and c_priv_auth["expected"].get("status") == 401, str(c_priv_auth))

    # 未鉴权用例实际请求确实 401 → pass；但正常 getPrivate 用例无 token 期望 200 会 fail，
    # 首跑前把它禁用（同时演示人工编辑增删用例 + 追加 echo 用例）
    edited = []
    for c in cases:
        if c["name"] == "getPrivate":
            continue  # 删除
        edited.append({"id": c["id"], "name": c["name"], "kind": c["kind"], "method": c["method"],
                       "path": c["path"], "params": c["params"], "headers": c["headers"],
                       "body": c["body"], "expected": c["expected"], "enabled": True})
    edited.append({"name": "echo-ok", "kind": "http", "method": "GET", "path": "/api/echo?msg=hi",
                   "params": {}, "headers": {}, "body": None,
                   "expected": {"status": 200, "contains": "hi"}, "enabled": True})
    st, suite2 = call("PUT", f"/test-suites/{api_id}/cases", edited)
    check("人工编辑用例（删 getPrivate/增 echo-ok）", st == 200 and len(suite2["cases"]) == 7
          and find_case(suite2["cases"], "echo-ok") is not None, f"{st} {suite2 and suite2['caseCount']}")

    # ---------- 3. 冒烟套件（health 用例，FR-01） ----------
    st, smoke = call("POST", f"/projects/{pid}/test-suites",
                     {"name": "冒烟套件", "kind": "smoke"})
    check("创建冒烟套件", st == 200 and smoke.get("id") and smoke["kind"] == "smoke", f"{st} {smoke}")
    smoke_id = smoke["id"]
    st, smoke2 = call("PUT", f"/test-suites/{smoke_id}/cases", [{
        "name": "ssh-health", "kind": "health", "method": "GET", "path": "/",
        "params": {}, "headers": {}, "body": None,
        "expected": {"type": "command", "command": "echo health-ok"}, "enabled": True}])
    check("冒烟套件加 health 用例", st == 200 and len(smoke2["cases"]) == 1, f"{st} {smoke2}")

    # ---------- 4. 执行：API + 冒烟（FR-04） ----------
    st, run = call("POST", "/tests/runs", {"projectId": pid, "suiteIds": [api_id, smoke_id],
                                           "serverId": ssh_id, "baseUrl": "http://127.0.0.1:9300"})
    check("创建测试运行", st == 200 and run.get("id") and run["status"] == "RUNNING", f"{st} {run}")
    r1 = wait_run(run["id"])
    check("运行#1 终态 SUCCESS", r1["status"] == "SUCCESS", f"{r1['status']} {r1['errorSummary']}")
    s1 = r1["summary"]
    check("汇总 total=8 全通过", s1["total"] == 8 and s1["failed"] == 0 and s1["passed"] == 8,
          f"{s1}")
    rs = {x["name"]: x["status"] for x in r1["results"]}
    check("用例级结果（echo-ok pass）", rs.get("echo-ok") == "pass", f"{rs}")
    check("health 用例 pass（SSH 命令健康检查）", rs.get("ssh-health") == "pass", f"{rs}")
    check("未鉴权用例 pass（401 匹配）", rs.get("getPrivate（未鉴权）") == "pass", f"{rs}")

    # ---------- 5. 报告（FR-04） ----------
    st, rpt = call_text(f"/test-runs/{r1['id']}/report")
    check("报告文本含用例结果", st == 200 and "用例结果" in rpt and "✅" in rpt, f"{st}")
    check("报告文档已沉淀", r1["reportDocId"] is not None, f"{r1['reportDocId']}")
    if r1["reportDocId"]:
        st, doc = call("GET", f"/documents/{r1['reportDocId']}")
        check("报告文档 kind=report", st == 200 and doc["kind"] == "report", f"{st} {doc and doc.get('kind')}")

    # ---------- 6. WS 实时流 ----------
    # 追加慢用例让 run 足够久，WS 连接后仍能收到实时 result 帧
    slow_cases = [{"id": c["id"], "name": c["name"], "kind": c["kind"], "method": c["method"],
                   "path": c["path"], "params": c["params"], "headers": c["headers"],
                   "body": c["body"], "expected": c["expected"], "enabled": True}
                  for c in suite2["cases"]]
    slow_cases.append({"name": "slow-echo", "kind": "http", "method": "GET", "path": "/api/slow",
                       "params": {}, "headers": {}, "body": None,
                       "expected": {"status": 200, "contains": "slow"}, "enabled": True})
    call("PUT", f"/test-suites/{api_id}/cases", slow_cases)
    st, rw = call("POST", "/tests/runs", {"projectId": pid, "suiteIds": [api_id],
                                          "serverId": ssh_id, "baseUrl": "http://127.0.0.1:9300"})
    check("WS 前创建 run", st == 200 and rw.get("id"), f"{st} {rw}")
    p = subprocess.run(["node", str(TMP / "cap10-ws-test.mjs"), str(rw["id"]), "SUCCESS"],
                       capture_output=True, text=True, timeout=60)
    out = (p.stdout or "") + (p.stderr or "")
    check("WS 实时流（result+done）", p.returncode == 0 and "WS_OK" in out, f"rc={p.returncode}\n{out}")
    rw2 = wait_run(rw["id"])
    check("WS run 终态 SUCCESS", rw2["status"] == "SUCCESS", f"{rw2['status']}")

    # ---------- 7. 失败 → 缺陷线索（FR-06） ----------
    fail_cases = []
    for c in suite2["cases"]:
        e = dict(c["expected"])
        if c["name"] == "listUsers":
            e["status"] = 500  # 实际 200，故意制造失败
        fail_cases.append({"id": c["id"], "name": c["name"], "kind": c["kind"], "method": c["method"],
                           "path": c["path"], "params": c["params"], "headers": c["headers"],
                           "body": c["body"], "expected": e, "enabled": True})
    call("PUT", f"/test-suites/{api_id}/cases", fail_cases)
    st, rf = call("POST", "/tests/runs", {"projectId": pid, "suiteIds": [api_id],
                                          "serverId": ssh_id, "baseUrl": "http://127.0.0.1:9300"})
    rf = wait_run(rf["id"])
    check("运行#失败 终态 FAILED", rf["status"] == "FAILED", f"{rf['status']} {rf['errorSummary']}")
    check("失败汇总 failed>=1", rf["summary"]["failed"] >= 1, f"{rf['summary']}")
    st, issues = call("POST", f"/test-runs/{rf['id']}/issues")
    check("失败转缺陷线索", st == 200 and issues and issues[0]["title"] == "测试失败: listUsers"
          and "500" in issues[0]["expected"] and issues[0]["runId"] == rf["id"],
          f"{st} {issues}")

    # 恢复 listUsers 期望（后续自动回归用干净套件）
    ok_cases = [{"id": c["id"], "name": c["name"], "kind": c["kind"], "method": c["method"],
                 "path": c["path"], "params": c["params"], "headers": c["headers"],
                 "body": c["body"], "expected": {"status": 200}, "enabled": True}
                for c in suite2["cases"] if c["name"] != "slow-echo"]
    call("PUT", f"/test-suites/{api_id}/cases", ok_cases)

    # ---------- 8. 套件沉淀到 docs-repo（FR-03，版本化） ----------
    st, pub = call("POST", f"/test-suites/{api_id}/publish")
    check("套件沉淀 api-suite 文档", st == 200 and pub["docId"] is not None, f"{st} {pub}")
    st, doc = call("GET", f"/documents/{pub['docId']}")
    check("沉淀文档 kind=api-suite", st == 200 and doc["kind"] == "api-suite"
          and "用例数" in doc["contentMd"], f"{st}")
    v1 = doc["versionNo"]
    st, pub2 = call("POST", f"/test-suites/{api_id}/publish")
    st, doc2 = call("GET", f"/documents/{pub2['docId']}")
    check("二次沉淀生成新版本", doc2["versionNo"] == v1 + 1, f"v{doc2['versionNo']} vs v{v1}")

    # ---------- 9. 自动回归（FR-05） ----------
    st, _ = call("PUT", f"/projects/{pid}", {
        "name": "CAP10 测试", "path": str(REPO).replace("/", "\\"), "defaultBranch": "master",
        "tags": [], "apiDocSource": "docs/openapi.yaml", "autoRegressionOnDeploy": True})
    check("开启 autoRegressionOnDeploy", st == 200, f"{st}")
    st, pv = call("GET", f"/projects/{pid}")
    check("项目视图带回归开关", pv["autoRegressionOnDeploy"] is True, f"{pv.get('autoRegressionOnDeploy')}")

    # 跑一次成功部署：本地构建产出 artifact → 部署单（deploy-apply 模板）→ SUCCESS → 自动回归
    replace_steps(pid, [{"name": "produce", "command": "echo artifact=v1.0", "workingDir": "", "location": "LOCAL"}])
    b = trigger_build(pid)
    check("构建成功并登记产物", b["status"] == "SUCCESS" and b["artifactRef"] == "v1.0",
          f"{b['status']} {b.get('artifactRef')}")
    st, cfg = call("PUT", f"/projects/{pid}/deploy-config", {
        "steps": [{"name": "apply", "type": "deploy", "templateCode": "deploy-apply",
                   "params": {"artifact": "v1.0"}}],
        "rollbackSteps": []})
    check("配置部署计划", st == 200, f"{st} {cfg}")
    st, dep = call("POST", "/deployments", {"projectId": pid, "serverId": ssh_id,
                                            "buildId": b["id"], "env": "test"})
    check("创建部署单", st == 200 and dep.get("id"), f"{st} {dep}")
    st, dep = call("POST", f"/deployments/{dep['id']}/execute")
    check("执行部署", st == 200 and dep["status"] == "RUNNING", f"{st} {dep}")
    deadline = time.time() + 90
    dep_final = None
    while time.time() < deadline:
        st, dep_final = call("GET", f"/deployments/{dep['id']}")
        if st == 200 and dep_final["status"] in ("SUCCESS", "FAILED", "ROLLED_BACK"):
            break
        time.sleep(1)
    check("部署成功", dep_final and dep_final["status"] == "SUCCESS", f"{dep_final and dep_final['status']}")

    auto_run = None
    deadline = time.time() + 60
    while time.time() < deadline:
        st, runs = call("GET", f"/test-runs?projectId={pid}")
        auto_run = next((r for r in runs if r["triggeredBy"] == "deploy"), None)
        if auto_run and auto_run["status"] in ("SUCCESS", "FAILED"):
            break
        time.sleep(1)
    check("部署成功自动触发回归", auto_run is not None, "未出现 triggeredBy=deploy 的测试运行")
    if auto_run:
        check("自动回归关联部署 + 全套件", auto_run["deploymentId"] == dep["id"]
              and set(auto_run["suiteIds"]) == {api_id, smoke_id}, f"{auto_run['deploymentId']} {auto_run['suiteIds']}")
        check("自动回归执行完成", auto_run["status"] in ("SUCCESS", "FAILED"), f"{auto_run['status']}")
        # 目标来自部署的 SSH 服务器（非 http）→ http 用例跳过，health 用例照跑
        st, runs = call("GET", f"/test-runs?projectId={pid}")
        ar = next(r for r in runs if r["id"] == auto_run["id"])
        skip_cnt = ar["summary"]["skipped"]
        check("http 用例因无 baseUrl 跳过、health 用例通过", skip_cnt >= 1 and ar["summary"]["passed"] >= 1,
              f"summary={ar['summary']}")

    # ---------- 10. 收尾清理 + 删除保护 ----------
    st, _ = call("PUT", f"/projects/{pid}", {
        "name": "CAP10 测试", "path": str(REPO).replace("/", "\\"), "defaultBranch": "master",
        "tags": [], "apiDocSource": "docs/openapi.yaml", "autoRegressionOnDeploy": False})
    for r in (call("GET", f"/test-runs?projectId={pid}")[1] or []):
        call("DELETE", f"/test-runs/{r['id']}")
    st, del_suite = call("DELETE", f"/test-suites/{smoke_id}")
    check("删除套件", st == 200, f"{st}")
    st, del_bad = call("DELETE", f"/test-suites/999999")
    check("删除不存在套件 404", st == 404, f"{st}")
    call("DELETE", f"/test-suites/{api_id}")

    print(f"\n===== CAP-10 结果: {passed} passed, {failed} failed =====")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    try:
        main()
    finally:
        for p in PROCS:
            try:
                p.terminate()
            except Exception:
                pass
