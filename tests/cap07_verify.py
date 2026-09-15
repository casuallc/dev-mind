# -*- coding: utf-8 -*-
"""CAP-07 服务器适配器端到端验证。
前置：后端 :8080 已起；tests/fixtures/TestSshServer(2222) 与 tests/fixtures/agent-mock.js(9100) 已起（或本脚本拉起）。
覆盖：SSH/HTTP 连通测试、模板执行+白名单(404/403)、必填参数、上传下载、健康检查、日志、
审计留痕、凭证密文落库(FR-07)与读取解密。"""
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

# Windows 控制台 GBK 下打印中文会崩；统一 UTF-8
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080/api"
ROOT = pathlib.Path(r"D:/apusic/dev-mind")
TMP = ROOT / "tmp"
FIX = ROOT / "tests" / "fixtures"
passed = 0
failed = 0

# 拉起的子进程（结束时清理）
PROCS = []


def call(method, path, body=None):
    url = quote(BASE + path, safe=":/?&=%,.-")
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def raw_get(path):
    req = urllib.request.Request(BASE + path)
    with urllib.request.urlopen(req, timeout=40) as r:
        return r.status, r.read().decode("utf-8", "replace")


def upload(server_id, local_path, remote_path):
    boundary = "----CAP07Boundary"
    fname = pathlib.Path(local_path).name
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{fname}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode(),
        pathlib.Path(local_path).read_bytes(),
        b"\r\n",
        f'--{boundary}\r\nContent-Disposition: form-data; name="remotePath"\r\n\r\n{remote_path}\r\n'.encode(),
        f"--{boundary}--\r\n".encode(),
    ]
    body = b"".join(parts)
    req = urllib.request.Request(BASE + f"/servers/{server_id}/upload", data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def start_harnesses():
    """拉起嵌入式 SSH 服务器(2222)与 HTTP mock agent(9100)；返回 True 表示已由本脚本拉起。"""
    need = True
    try:
        urllib.request.urlopen("http://127.0.0.1:9100/api/agent/ping", timeout=2)
        need = False  # 200 pong（无鉴权模式）
    except urllib.error.HTTPError:
        need = False  # 401 说明 agent 已起但需要 token
    except Exception:
        pass
    if need:
        m2 = str(pathlib.Path.home() / ".m2" / "repository")
        cp = (";".join([
            str(FIX),  # tests/fixtures 含 TestSshServer.class
            f"{m2}/org/apache/sshd/sshd-core/2.16.0/sshd-core-2.16.0.jar",
            f"{m2}/org/apache/sshd/sshd-common/2.16.0/sshd-common-2.16.0.jar",
            f"{m2}/org/apache/sshd/sshd-sftp/2.16.0/sshd-sftp-2.16.0.jar",
            f"{m2}/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
            f"{m2}/org/slf4j/slf4j-nop/2.0.17/slf4j-nop-2.0.17.jar",
        ]))
        p1 = subprocess.Popen(["java", "-cp", cp, "TestSshServer", "2222", "test", "testpw"],
                              cwd=FIX, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        PROCS.append(p1)
        p2 = subprocess.Popen(["node", "agent-mock.js", "9100", "tok123"],
                              cwd=FIX, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        PROCS.append(p2)
        time.sleep(5)
        print("  [拉起测试双端: SSH:2222 + Agent:9100]")


def stop_harnesses():
    for p in PROCS:
        try:
            p.terminate()
        except Exception:
            pass


def main():
    start_harnesses()

    # ---------- 0. 准备项目（id 由服务端生成，按名称复用） ----------
    st, projects = call("GET", "/projects")
    proj = next((p for p in (projects or []) if p.get("name") == "CAP07 测试"), None)
    if not proj:
        st, proj = call("POST", "/projects", {
            "name": "CAP07 测试", "path": str(ROOT).replace("/", "\\"),
            "defaultBranch": "master", "tags": []})
        check("创建项目", st == 200 and proj and proj.get("id"), f"{st} {proj}")
    else:
        print("  [复用项目]")
    pid = proj["id"]

    # ---------- 0b. 清理上次中断遗留的数据 ----------
    st, tl = call("GET", f"/script-templates?projectId={pid}")
    for t in (tl if isinstance(tl, list) else []):
        call("DELETE", f"/script-templates/{t['id']}")
    st, sl = call("GET", f"/projects/{pid}/servers")
    for s in (sl if isinstance(sl, list) else []):
        call("DELETE", f"/projects/{pid}/servers/{s['id']}")

    # ---------- 1. 注册服务器（FR-01） ----------
    st, ssh = call("POST", f"/projects/{pid}/servers", {
        "name": "本地SSH", "env": "test", "accessType": "ssh",
        "accessConfig": json.dumps({"host": "127.0.0.1", "port": 2222, "username": "test",
                                    "authType": "password", "password": "testpw"}, ensure_ascii=False),
        "capabilities": ["build", "deploy", "test", "release", "logs", "exec", "health"], "enabled": True})
    ssh_id = ssh.get("id") if ssh else None
    check("注册 SSH 服务器", st == 200 and ssh_id, f"{st} {ssh}")

    st, http = call("POST", f"/projects/{pid}/servers", {
        "name": "MockAgent", "env": "prod", "accessType": "http",
        "accessConfig": json.dumps({"baseUrl": "http://127.0.0.1:9100", "token": "tok123"}),
        "capabilities": ["build", "deploy", "test", "release", "logs", "exec", "health"], "enabled": True})
    http_id = http.get("id") if http else None
    check("注册 HTTP 服务器", st == 200 and http_id, f"{st} {http}")

    st, bad = call("POST", f"/projects/{pid}/servers", {
        "name": "错密码SSH", "env": "test", "accessType": "ssh",
        "accessConfig": json.dumps({"host": "127.0.0.1", "port": 2222, "username": "test",
                                    "authType": "password", "password": "WRONGPW"}),
        "capabilities": ["deploy"], "enabled": True})
    bad_id = bad.get("id") if bad else None

    # ---------- 2. 凭证密文落库（FR-07） ----------
    st, sc = call("GET", f"/servers/{ssh_id}/stored-config")
    raw_cfg = sc.get("accessConfig") or "" if sc else ""
    check("密文落库: 含 enc1: 前缀", st == 200 and "enc1:" in raw_cfg, raw_cfg[:120])
    check("密文落库: 无明文密码", "testpw" not in raw_cfg, raw_cfg[:120])
    check("字段加密状态标记", sc and any(f["encrypted"] for f in sc.get("fields", [])), str(sc))

    # 读取回显解密（前端可编辑）
    st, servers = call("GET", f"/projects/{pid}/servers")
    sv = next((s for s in (servers or []) if s["id"] == ssh_id), None)
    check("读取回显明文密码", st == 200 and sv and "testpw" in (sv["accessConfig"] or ""), str(sv and sv["accessConfig"])[:80])

    # ---------- 3. 命令模板白名单（FR-05） ----------
    deploy_tpl = {"projectId": pid, "code": "deploy", "name": "部署脚本", "templateText": 'echo "deploying ${artifact} to ${env}"\necho DONE',
                  "params": [{"name": "artifact", "required": True, "label": "制品"},
                             {"name": "env", "required": True, "label": "环境", "defaultValue": "prod"}],
                  "allowed": ["deploy", "build", "test", "release", "logs", "exec"]}
    st, t = call("POST", "/script-templates", deploy_tpl)
    check("创建 deploy 模板", st == 200 and t.get("code") == "deploy", f"{st} {t}")
    st, dup = call("POST", "/script-templates", deploy_tpl)
    check("重复模板 code → 409", st == 409, f"{st}")
    st, logs_tpl = call("POST", "/script-templates", {"projectId": pid, "code": "logs", "name": "拉日志",
                         "templateText": 'echo "=== LOGS ==="', "params": [], "allowed": ["logs"]})
    check("创建 logs 模板", st == 200, f"{st}")
    st, r_tpl = call("POST", "/script-templates", {"projectId": pid, "code": "restricted", "name": "受限",
                         "templateText": "echo restricted", "params": [], "allowed": ["build"]})
    check("创建 restricted 模板(allowed=build)", st == 200, f"{st}")

    # ---------- 4. 连通性测试（FR-02） ----------
    st, r = call("POST", f"/servers/{ssh_id}/test")
    check("SSH 连通测试通过", st == 200 and r.get("ok") is True and "连接成功" in (r.get("message") or ""), f"{st} {r}")
    st, r = call("POST", f"/servers/{http_id}/test")
    check("HTTP 连通测试通过", st == 200 and r.get("ok") is True and "连通" in (r.get("message") or ""), f"{st} {r}")
    st, r = call("POST", f"/servers/{bad_id}/test")
    check("错误密码连通失败", st == 200 and r.get("ok") is False, f"{st} {r}")

    # ---------- 5. 模板执行 ----------
    st, r = call("POST", f"/servers/{ssh_id}/execute",
                 {"templateCode": "deploy", "params": {"artifact": "app.jar"}, "capability": "deploy"})
    out = r.get("stdout", "") if r else ""
    check("SSH 执行 deploy（默认 env=prod）", st == 200 and r.get("success") and "deploying app.jar to prod" in out and "DONE" in out, f"{st} {out[:120]}")
    st, r = call("POST", f"/servers/{http_id}/execute",
                 {"templateCode": "deploy", "params": {"artifact": "app.jar", "env": "staging"}, "capability": "deploy"})
    out = r.get("stdout", "") if r else ""
    check("HTTP 执行 deploy", st == 200 and r.get("success") and "deploying app.jar to staging" in out, f"{st} {out[:120]}")
    st, r = call("POST", f"/servers/{ssh_id}/execute",
                 {"templateCode": "deploy", "params": {}, "capability": "deploy"})
    check("缺必填参数 artifact → 400", st == 400, f"{st}")

    # 白名单（FR-05）：未知模板 404
    st, r = call("POST", f"/servers/{ssh_id}/execute", {"templateCode": "ghost", "params": {}, "capability": "deploy"})
    check("白名单外未知模板 → 404", st == 404, f"{st}")
    # 能力不被模板 allowed → 403
    st, r = call("POST", f"/servers/{ssh_id}/execute", {"templateCode": "restricted", "params": {}, "capability": "deploy"})
    check("模板不允许该能力 → 403", st == 403, f"{st}")
    # 能力不被服务器 capabilities → 403
    st, r = call("POST", f"/servers/{ssh_id}/execute", {"templateCode": "deploy", "params": {"artifact": "x"}, "capability": "flying"})
    check("服务器不具备该能力 → 403", st == 403, f"{st}")

    # ---------- 6. 上传 / 下载（FR-02） ----------
    local = TMP / "cap07-local.txt"
    local.write_text("CAP07_UPLOAD_PAYLOAD_中文", encoding="utf-8")
    st, r = upload(ssh_id, local, "cap07-ssh-upload.txt")
    check("SSH 上传", st == 200 and r.get("ok"), f"{st} {r}")
    st, text = raw_get(f"/servers/{ssh_id}/download?path=cap07-ssh-upload.txt")
    check("SSH 下载内容一致", st == 200 and "CAP07_UPLOAD_PAYLOAD_中文" in text, f"{st} {text[:60]}")
    st, r = upload(http_id, local, "cap07-http-upload.txt")
    check("HTTP 上传", st == 200 and r.get("ok"), f"{st} {r}")
    st, text = raw_get(f"/servers/{http_id}/download?path=cap07-http-upload.txt")
    check("HTTP 下载内容一致", st == 200 and "CAP07_UPLOAD_PAYLOAD_中文" in text, f"{st} {text[:60]}")

    # ---------- 7. 健康检查（FR-02） ----------
    st, r = call("POST", f"/servers/{ssh_id}/health", {"type": "command", "command": "echo healthy"})
    check("SSH 健康检查(命令)", st == 200 and r.get("ok") is True, f"{st} {r}")
    st, r = call("POST", f"/servers/{http_id}/health", {"type": "http", "url": "http://127.0.0.1:9100/api/agent/healthz", "expectedStatus": 200})
    check("HTTP 健康检查(URL)", st == 200 and r.get("ok") is True, f"{st} {r}")

    # ---------- 8. 日志（logs 模板） ----------
    st, r = call("GET", f"/servers/{ssh_id}/logs")
    check("SSH 拉日志", st == 200 and r.get("success") and "LOGS" in (r.get("stdout") or ""), f"{st} {r}")
    st, r = call("GET", f"/servers/{http_id}/logs?template=logs")
    check("HTTP 拉日志", st == 200 and r.get("success"), f"{st} {r}")

    # ---------- 9. 审计留痕（FR-06） ----------
    st, logs = call("GET", f"/audit-logs?projectId={pid}")
    actions = {a.get("action") for a in (logs or [])}
    check("审计含 5 类动作", st == 200 and {"connect_test", "execute", "upload", "download", "health_check"} <= actions, str(actions))
    st, slogs = call("GET", f"/servers/{ssh_id}/audit")
    check("单服务器审计非空", st == 200 and len(slogs) > 3, str(len(slogs)))
    # 审计不含凭证明文
    blob = json.dumps(logs, ensure_ascii=False)
    check("审计不落凭证明文", "testpw" not in blob and "tok123" not in blob)

    # ---------- 清理 ----------
    for tid in [t.get("id") for t in (call("GET", f"/script-templates?projectId={pid}")[1] or [])]:
        call("DELETE", f"/script-templates/{tid}")
    for sid in [ssh_id, http_id, bad_id]:
        call("DELETE", f"/projects/{pid}/servers/{sid}")
    print(f"\n== CAP-07 验证结果: {passed} passed, {failed} failed ==")
    return failed == 0


if __name__ == "__main__":
    ok = main()
    stop_harnesses()
    sys.exit(0 if ok else 1)
