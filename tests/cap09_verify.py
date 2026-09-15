# -*- coding: utf-8 -*-
"""CAP-09 部署执行器端到端验证。
前置：后端 :8080 已起；tests/fixtures/TestSshServer(2222) 已起（部署目标服务器）。
覆盖：部署计划配置、创建部署单（计划可见/幂等 409/force）、执行成功逐步可见、
备份登记、WS 实时流、失败自动回滚+回滚步骤+P0 通知、手动回滚、确认门、历史筛选、日志/删除。
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
passed = 0
failed = 0

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")


def call(method, path, body=None):
    url = quote(BASE + path, safe=":/?&=%,.-")
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def call_text(path):
    with urllib.request.urlopen(quote(BASE + path, safe=":/?&=%,.-"), timeout=60) as r:
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
    """触发本地构建并等待终态，返回 build"""
    st, b = call("POST", f"/projects/{pid}/builds", {})
    assert st == 200 and b.get("id"), f"触发构建失败: {st} {b}"
    deadline = time.time() + 60
    while time.time() < deadline:
        st, b = call("GET", f"/builds/{b['id']}")
        if st == 200 and b["status"] in ("SUCCESS", "FAILED"):
            return b
        time.sleep(1)
    raise TimeoutError("构建未结束")


def wait_deploy(did, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, d = call("GET", f"/deployments/{did}")
        if st == 200 and d["status"] in ("SUCCESS", "FAILED", "ROLLED_BACK"):
            return d
        time.sleep(1)
    raise TimeoutError(f"部署 #{did} 未在 {timeout}s 内结束")


def create_execute(pid, body, expect_terminal):
    st, d = call("POST", "/deployments", body)
    check(f"创建部署单", st == 200 and d.get("id"), f"{st} {d}")
    did = d["id"]
    st, d = call("POST", f"/deployments/{did}/execute")
    check(f"执行部署 #{did}", st == 200 and d["status"] == "RUNNING", f"{st} {d}")
    d = wait_deploy(did)
    check(f"部署 #{did} 终态 {expect_terminal}", d["status"] == expect_terminal, str(d))
    return d


def tpl(pid, code, name, script, params):
    r = call("POST", "/script-templates", {
        "projectId": pid, "code": code, "name": name, "templateText": script,
        "params": params, "allowed": ["deploy"]})
    assert r[0] == 200, f"建模板 {code} 失败: {r}"


def main():
    # ---------- 0. 项目 + 清理 ----------
    st, projects = call("GET", "/projects")
    proj = next((p for p in (projects or []) if p.get("name") == "CAP07 测试"), None)
    if not proj:
        st, proj = call("POST", "/projects", {
            "name": "CAP07 测试", "path": str(ROOT).replace("/", "\\"), "defaultBranch": "master", "tags": []})
        check("创建项目", st == 200 and proj, f"{st} {proj}")
    pid = proj["id"]
    print(f"  [项目 {pid}]")

    for s in (call("GET", f"/projects/{pid}/servers")[1] or []):
        call("DELETE", f"/projects/{pid}/servers/{s['id']}")
    for t in (call("GET", f"/script-templates?projectId={pid}")[1] or []):
        call("DELETE", f"/script-templates/{t['id']}")
    for d in (call("GET", f"/deployments?projectId={pid}")[1] or []):
        call("DELETE", f"/deployments/{d['id']}")
    replace_steps(pid, [])

    # ---------- 0b. 服务器 + 部署模板 ----------
    st, ssh = call("POST", f"/projects/{pid}/servers", {
        "name": "本地SSH", "env": "test", "accessType": "ssh",
        "accessConfig": json.dumps({"host": "127.0.0.1", "port": 2222, "username": "test",
                                    "authType": "password", "password": "testpw"}, ensure_ascii=False),
        "capabilities": ["build", "deploy", "exec", "logs", "health"], "enabled": True})
    ssh_id = ssh.get("id") if ssh else None
    check("注册 SSH 服务器", st == 200 and ssh_id, f"{st} {ssh}")

    tpl(pid, "dep_artifact", "拉取产物", 'echo "pull artifact=${artifact}"',
        [{"name": "artifact", "required": False, "label": "产物"}])
    tpl(pid, "dep_backup", "备份", 'echo "backup=/opt/app/backup-$(date +%s).tar.gz"', [])
    tpl(pid, "dep_deploy", "部署", 'echo "deploy ${artifact} to env ${env}"',
        [{"name": "artifact", "required": False, "label": "产物"},
         {"name": "env", "required": False, "label": "环境"}])
    tpl(pid, "dep_start", "启动", 'echo "start app ok"', [])
    tpl(pid, "dep_start_slow", "启动(慢)", 'sleep 5; echo "ws-live-deploy-line"', [])
    tpl(pid, "dep_start_fail", "启动(失败)", 'echo "start failed: port busy"; exit 1', [])
    tpl(pid, "dep_health", "健康检查", 'echo "health check ok"', [])
    tpl(pid, "rb_restore", "恢复备份", 'echo "restore from ${backup}"',
        [{"name": "backup", "required": False, "label": "备份"}])
    tpl(pid, "rb_health", "回滚后检查", 'echo "health ok after restore"', [])

    # ---------- 1. 部署计划配置（FR-01） ----------
    st, cfg = call("GET", f"/projects/{pid}/deploy-config")
    check("默认空配置", st == 200 and cfg["steps"] == [], f"{st} {cfg}")
    plan = [
        {"name": "拉取产物", "type": "artifact", "templateCode": "dep_artifact", "params": {}},
        {"name": "备份", "type": "backup", "templateCode": "dep_backup", "params": {}},
        {"name": "部署", "type": "deploy", "templateCode": "dep_deploy", "params": {}},
        {"name": "启动", "type": "start", "templateCode": "dep_start", "params": {}},
        {"name": "健康检查", "type": "health", "templateCode": "dep_health", "params": {}},
    ]
    rb = [
        {"name": "恢复备份", "type": "deploy", "templateCode": "rb_restore", "params": {}},
        {"name": "回滚后检查", "type": "health", "templateCode": "rb_health", "params": {}},
    ]
    st, cfg = call("PUT", f"/projects/{pid}/deploy-config", {"steps": plan, "rollbackSteps": rb})
    check("保存部署配置", st == 200 and len(cfg["steps"]) == 5 and len(cfg["rollbackSteps"]) == 2, f"{st} {cfg}")
    st, bad = call("PUT", f"/projects/{pid}/deploy-config", {"steps": [], "rollbackSteps": []})
    check("空步骤 → 400", st == 400, f"{st}")
    st, bad = call("PUT", f"/projects/{pid}/deploy-config", {"steps": [{"name": "", "type": "deploy", "templateCode": "x", "params": {}}], "rollbackSteps": []})
    check("空步骤名 → 400", st == 400, f"{st}")

    # ---------- 2. 构建产物准备（artifactRef） ----------
    replace_steps(pid, [{"name": "打包", "command": 'echo "artifact=cap09-app.jar"'}])
    good_build = trigger_build(pid)
    check("构建产物 artifactRef", good_build["status"] == "SUCCESS" and good_build["artifactRef"] == "cap09-app.jar", str(good_build))
    replace_steps(pid, [{"name": "无产物", "command": "echo no-artifact-line"}])
    no_artifact_build = trigger_build(pid)
    check("无产物构建", no_artifact_build["status"] == "SUCCESS" and no_artifact_build["artifactRef"] is None, str(no_artifact_build))
    replace_steps(pid, [])

    # ---------- 3. 创建部署单（FR-01/04） ----------
    # 无配置时创建 → 400（此时已配置，改用配置不存在项目验证）
    st, no_cfg = call("POST", "/deployments", {"projectId": "no-such-project", "serverId": ssh_id, "buildId": good_build["id"]})
    check("项目不存在 → 404", st == 404, f"{st}")
    # 无产物构建 → 400
    st, bad = call("POST", "/deployments", {"projectId": pid, "serverId": ssh_id, "buildId": no_artifact_build["id"]})
    check("构建无产物 → 400", st == 400, f"{st} {bad}")
    # 正常创建
    st, d = call("POST", "/deployments", {"projectId": pid, "serverId": ssh_id, "buildId": good_build["id"], "env": "test"})
    check("创建部署单 PLANNED", st == 200 and d["status"] == "PLANNED", f"{st} {d}")
    check("计划在执行前可见(5步)", len(d["plan"]) == 5, str(d["plan"]))
    check("步骤初始 PENDING", all(s["status"] == "PENDING" for s in d["steps"]), str(d["steps"]))
    check("部署关联 build/server", d["buildId"] == good_build["id"] and d["serverId"] == ssh_id, str(d))
    # 幂等：重复创建同 build → 409
    st, dup = call("POST", "/deployments", {"projectId": pid, "serverId": ssh_id, "buildId": good_build["id"]})
    check("重复部署 → 409", st == 409, f"{st} {dup}")
    st, forced = call("POST", "/deployments", {"projectId": pid, "serverId": ssh_id, "buildId": good_build["id"], "force": True})
    check("force 可重建", st == 200 and forced["status"] == "PLANNED", f"{st} {forced}")
    # 无 buildId 允许（重新部署/手动部署）
    st, nob = call("POST", "/deployments", {"projectId": pid, "serverId": ssh_id, "env": "test"})
    check("无 build 也可创建", st == 200 and nob.get("id"), f"{st} {nob}")
    for extra in (d["id"], forced["id"], nob["id"]):
        call("DELETE", f"/deployments/{extra}")

    # ---------- 4. 执行成功（FR-02 逐步可见 + FR-04 备份登记） ----------
    st, d = call("POST", "/deployments", {"projectId": pid, "serverId": ssh_id, "buildId": good_build["id"], "env": "test"})
    did = d["id"]
    st, d = call("POST", f"/deployments/{did}/execute")
    check("执行成功部署 → RUNNING", st == 200 and d["status"] == "RUNNING", f"{st} {d}")
    d = wait_deploy(did)
    check("部署终态 SUCCESS", d["status"] == "SUCCESS", str(d))
    check("5 步全部 SUCCESS", len(d["steps"]) == 5 and all(s["status"] == "SUCCESS" for s in d["steps"]), str(d["steps"]))
    check("备份引用已登记", d["backupRef"] and d["backupRef"].startswith("/opt/app/backup-"), str(d["backupRef"]))
    st, logs = call_text(f"/deployments/{did}/logs")
    for frag in ["pull artifact=cap09-app.jar", "deploy cap09-app.jar to env test", "start app ok", "health check ok",
                 "backup=", "步骤 1/5", "步骤 5/5"]:
        check(f"日志含 {frag[:32]}", st == 200 and frag in (logs or ""), f"{st}")
    # 成功通知 P1
    st, nts = call("GET", "/notifications")
    ok_nt = next((n for n in (nts or []) if n.get("entityType") == "deployment" and str(n.get("entityId")) == str(did)), None)
    check("成功通知 P1", ok_nt and ok_nt["level"] == "P1" and "部署成功" in ok_nt["title"], str(ok_nt))

    # ---------- 5. WS 实时流（FR-02） ----------
    slow_plan = [
        {"name": "拉取", "type": "artifact", "templateCode": "dep_artifact", "params": {}},
        {"name": "启动(慢)", "type": "start", "templateCode": "dep_start_slow", "params": {}},
    ]
    st, d = call("POST", "/deployments", {"projectId": pid, "serverId": ssh_id, "buildId": good_build["id"], "env": "test", "plan": slow_plan, "force": True})
    wdid = d["id"]
    st, d = call("POST", f"/deployments/{wdid}/execute")
    r = subprocess.run(["node", str(ROOT / "tmp" / "cap09-ws-test.mjs"), str(wdid), "SUCCESS"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=40)
    out = r.stdout + r.stderr
    check("WS 收到 step/log+done", r.returncode == 0 and "WS_OK" in out, out[-300:])
    wait_deploy(wdid)

    # ---------- 6. 失败自动回滚（FR-03） + P0 通知 ----------
    fail_plan = [
        {"name": "拉取产物", "type": "artifact", "templateCode": "dep_artifact", "params": {}},
        {"name": "备份", "type": "backup", "templateCode": "dep_backup", "params": {}},
        {"name": "启动", "type": "start", "templateCode": "dep_start_fail", "params": {}},
    ]
    st, d = call("POST", "/deployments", {"projectId": pid, "serverId": ssh_id, "buildId": good_build["id"], "env": "test", "plan": fail_plan, "force": True})
    fdid = d["id"]
    st, d = call("POST", f"/deployments/{fdid}/execute")
    d = wait_deploy(fdid)
    check("失败部署终态 ROLLED_BACK", d["status"] == "ROLLED_BACK", str(d))
    check("错误摘要含失败原因", "port busy" in (d["errorSummary"] or ""), str(d["errorSummary"]))
    check("失败步骤标记 FAILED", any(s["type"] == "start" and s["status"] == "FAILED" for s in d["steps"]), str(d["steps"]))
    check("回滚步骤可见(恢复备份+检查)", any("恢复备份" in (s["name"] or "") for s in d["steps"])
          and any("回滚后检查" in (s["name"] or "") for s in d["steps"]), str(d["steps"]))
    check("回滚步骤均成功", all(s["status"] == "SUCCESS" for s in d["steps"] if "回滚" in (s["name"] or "") or "恢复" in (s["name"] or "")), str(d["steps"]))
    check("回滚前备份引用保留", d["backupRef"] and d["backupRef"].startswith("/opt/app/backup-"), str(d["backupRef"]))
    st, logs = call_text(f"/deployments/{fdid}/logs")
    check("回滚日志含 restore from backup", "restore from /opt/app/backup-" in (logs or ""), f"{st}")
    st, nts = call("GET", "/notifications")
    rb_nt = next((n for n in (nts or []) if n.get("entityType") == "deployment" and str(n.get("entityId")) == str(fdid)), None)
    check("回滚通知 P0", rb_nt and rb_nt["level"] == "P0" and "已自动回滚" in rb_nt["title"], str(rb_nt))

    # ---------- 7. 手动回滚（FR-03） ----------
    st, d = call("POST", f"/deployments/{did}/rollback")
    check("手动回滚创建新单", st == 200 and d.get("id") and d["rollbackOf"] == did, f"{st} {d}")
    rdid = d["id"]
    d = wait_deploy(rdid)
    check("回滚部署终态 ROLLED_BACK", d["status"] == "ROLLED_BACK", str(d))
    check("回滚计划=回滚步骤", len(d["plan"]) == 2, str(d["plan"]))
    st, nts = call("GET", "/notifications")
    # 手动回滚成功是 SUCCESS 语义？—— 见实现：rollback 部署成功→ROLLED_BACK，无额外通知

    # ---------- 8. 确认门（FR-07） ----------
    st, d = call("POST", "/deployments", {"projectId": pid, "serverId": ssh_id, "buildId": good_build["id"], "env": "test",
                                          "confirmRequired": True, "force": True})
    cid = d["id"]
    check("确认门创建 confirmRequired", st == 200 and d["confirmRequired"] and not d["confirmed"], f"{st} {d}")
    st, d = call("POST", f"/deployments/{cid}/execute")
    check("未确认执行 → 409", st == 409, f"{st} {d}")
    st, d = call("POST", f"/deployments/{cid}/confirm")
    check("确认成功", st == 200 and d["confirmed"], f"{st} {d}")
    st, d = call("POST", f"/deployments/{cid}/execute")
    check("确认后执行 RUNNING", st == 200 and d["status"] == "RUNNING", f"{st} {d}")
    wait_deploy(cid)

    # ---------- 9. 状态机/历史/日志/删除（FR-05） ----------
    st, done = call("POST", f"/deployments/{did}/execute")
    check("终态再执行 → 409", st == 409, f"{st}")
    st, all_d = call("GET", f"/deployments?projectId={pid}")
    check("历史列表含记录", st == 200 and len(all_d) >= 5, f"{st} n={len(all_d or [])}")
    st, ok_d = call("GET", f"/deployments?projectId={pid}&status=SUCCESS")
    check("历史按状态筛选", st == 200 and all(x["status"] == "SUCCESS" for x in ok_d), f"{st}")
    st, miss = call("GET", "/deployments/999999")
    check("不存在部署 → 404", st == 404, f"{st}")
    st, d = call("DELETE", f"/deployments/{cid}")
    check("删除部署", st == 200, f"{st}")
    st, miss = call("GET", f"/deployments/{cid}")
    check("删除后 404", st == 404, f"{st}")
    # 审计完整：部署模板执行应留审计
    st, audits = call("GET", f"/audit-logs?projectId={pid}")
    exec_audits = [a for a in (audits or []) if a.get("action") == "execute"]
    check("审计含部署模板执行", len(exec_audits) >= 3, f"n={len(exec_audits)}")

    # ---------- 清理 ----------
    for d in (call("GET", f"/deployments?projectId={pid}")[1] or []):
        call("DELETE", f"/deployments/{d['id']}")
    for t in (call("GET", f"/script-templates?projectId={pid}")[1] or []):
        call("DELETE", f"/script-templates/{t['id']}")
    for s in (call("GET", f"/projects/{pid}/servers")[1] or []):
        call("DELETE", f"/projects/{pid}/servers/{s['id']}")
    call("DELETE", f"/projects/{pid}/deploy-config")
    replace_steps(pid, [])

    print(f"\n== CAP-09 验证结果: {passed} passed, {failed} failed ==")
    return failed == 0


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
