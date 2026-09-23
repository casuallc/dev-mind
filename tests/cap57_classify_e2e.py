#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-57 分类服务全链 E2E：上传假边车包 → 分发节点（pkg 帧拉取物化）→ 建实例 → start（proc 帧）
→ 轮询 RUNNING（健康轮询打 /healthz）→ playground 直打实例 → 决策记录留痕 → stop。

## 前置（本脚本自己写假边车包、起真 runner，但**不起 app**）

1. 依赖已构建（**含 runner jar，且必须是 v15 的**——proc/pkg 帧是 CAP-57 新增）：

     mvn -q install -DskipTests

   注意：已长驻的 runner 不会自动换新 jar（见 memory：runner jar 重建必须重启）。
   本脚本从 jar 新起一个 runner 进程，不碰本机 WinSW 服务那个。

2. app 隔离实例已起，库全新、健康轮询调快（默认 30s 一轮，E2E 等不起）：

     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18098 \
       --spring.profiles.active=e2e \
       --spring.datasource.url=jdbc:h2:file:D:/apusic/dev-mind/tmp/cap57-e2e/data/devmind;AUTO_SERVER=TRUE \
       --devmind.classify.health-interval-ms=2000"

   - `--spring.profiles.active=e2e` 必需：默认 local profile 会去抢 MySQL 驱动；
   - 库必须全新：脚本启动时自查 classify 三表，非空拒跑。重跑前停 app、
     `rm -rf tmp/cap57-e2e/data`、按上面重启。

3. 从 Git Bash 跑：`python tests/cap57_classify_e2e.py`
   缺省连 `http://localhost:18098/api`（`DEVMIND_BASE` 覆盖）；边车端口默认 18377（`CAP57_PORT` 覆盖）。

## 这一遍验的是什么

- 安装包：multipart 上传（sha256 落库）、pkg 帧下发 → runner 拉包 → INSTALLED + installDir 回收；
- 实例：start 组 proc 帧（argv=commandOverride 拆词、workdir=packages/pkg-<id>）、pidfile 存活、
  env `${PKG_DIR:<id>}` 在服务端展开成节点绝对路径并真传到进程（stub 把它写进 env-probe.txt）；
- 健康轮询把 STARTING 翻成 RUNNING（/healthz 通了）；
- playground instanceId 通道直打实例 /v1/predict，且 decision_records 落 capability=classify-playground 行；
- stop 后进程真死（端口不再应答）、实例回 STOPPED。

不验：GPU/真 laya（stub 是标准库 http.server）、v15 门控拒绝老 runner（单测已钉）。
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

BASE = os.environ.get("DEVMIND_BASE", "http://localhost:18098/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp" / "cap57-e2e"
WS_DIR = TMP / "ws"
PORT = int(os.environ.get("CAP57_PORT", "18377"))
SIDECAR = f"http://127.0.0.1:{PORT}"
MARK = f"cap57-{int(time.time()) % 1000000}"

TOKEN = None
PASSED = 0
FAILED = 0

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(errors="replace")   # Windows 控制台 GBK：编不出的符号打 "?"，别抛
    except Exception:
        pass


def check(name, ok, detail=""):
    global PASSED, FAILED
    if ok:
        PASSED += 1
        print(f"  PASS  {name}")
    else:
        FAILED += 1
        print(f"  FAIL  {name}" + (f"  → {detail}" if detail else ""))


def step(title):
    print(f"\n{title}")


def _decode(raw):
    text = raw.decode("utf-8", "replace") if raw else ""
    if not text:
        return None
    try:
        return json.loads(text)
    except Exception:
        return text


def call(method, url, body=None, raw=None, ctype=None, timeout=120):
    """返回 (状态码, body)；HTTP 错误也返回状态码而不抛。"""
    data = raw if raw is not None else (None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8"))
    req = urllib.request.Request(url, data=data, method=method)
    if ctype:
        req.add_header("Content-Type", ctype)
    elif data is not None:
        req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, _decode(r.read())
    except urllib.error.HTTPError as e:
        return e.code, _decode(e.read())


def api(method, path, body=None, expect=200):
    st, payload = call(method, BASE + path, body)
    assert st == expect, f"{method} {path} → {st}（期望 {expect}）: {str(payload)[:400]}"
    return payload


def multipart(field, filename, content, extra):
    boundary = "----cap57" + uuid.uuid4().hex
    parts = []
    for k, v in extra.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        f"Content-Type: application/zip\r\n\r\n".encode() + content + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def upload_pkg(kind, name, version, zip_bytes, filename):
    raw, ct = multipart("file", filename, zip_bytes,
                        {"kind": kind, "name": name, "version": version})
    st, payload = call("POST", BASE + "/classify/packages/upload", raw=raw, ctype=ct, timeout=300)
    assert st == 200, f"上传 {name} → {st}: {str(payload)[:300]}"
    return payload


def wait(probe, what, timeout=120):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = probe()
        if v:
            return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}（{timeout}s）")


def kill(proc):
    if proc is None:
        return
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)


# ---------------------------------------------------------------- 假边车 stub（打进 zip）

# 标准库 http.server：/healthz 报 ok + 一个常驻槽位；/v1/predict 按 questions 派生固定答案；
# 启动时把 CAP57_PROBE 环境变量写进 env-probe.txt（验 env 真传到了进程、${PKG_DIR} 展开成绝对路径）。
STUB_SIDECAR = r'''
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1])

with open("env-probe.txt", "w", encoding="utf-8") as f:
    f.write(os.environ.get("CAP57_PROBE", "<missing>"))

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/healthz":
            self._send(200, {"status": "ok", "laya_version": "0.0.0-stub",
                             "loaded": ["multilingual"], "devices": {"multilingual": "cpu"}})
        else:
            self._send(404, {"detail": "not found"})

    def do_POST(self):
        if self.path != "/v1/predict":
            self._send(404, {"detail": "not found"})
            return
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])).decode("utf-8"))
        answers = {}
        for qid, q in (body.get("questions") or {}).items():
            t = q.get("type")
            if t == "choice":
                first = next(iter((q.get("criteria") or {"keep": None}).keys()))
                answers[qid] = {"type": "choice", "choice": first, "confidence": 0.9,
                                "probabilities": {first: 0.9}}
            elif t == "score":
                answers[qid] = {"type": "score", "score": 5, "confidence": 0.8,
                                "probabilities": {"5": 0.8}}
            else:
                answers[qid] = {"type": "noul", "noul": 0.2, "confidence": 0.5,
                                "probabilities": {}}
        self._send(200, {"answers": answers,
                         "routing": {"model": "stub-multilingual", "reason": "cap57 e2e stub"},
                         "usage": {"input_tokens": 1, "output_tokens": 0}})

print(f"cap57 stub sidecar on 127.0.0.1:{PORT}", flush=True)
ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
'''


def make_zip(files):
    """files: {zip 内路径: 文本内容} → zip bytes"""
    out = TMP / "pkg-staging.zip"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, text in files.items():
            z.writestr(name, text)
    data = out.read_bytes()
    out.unlink()
    return data


# ---------------------------------------------------------------- main

def main():
    global TOKEN
    login = api("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    TOKEN = login["accessToken"]
    python = sys.executable.replace("\\", "/")
    assert " " not in python, f"解释器路径含空格（commandOverride 按空白拆词）: {python}"
    print(f"[0] 登录 admin OK（{BASE}）；节点解释器 {python}")

    for path, what in (("/classify/instances", "实例"), ("/classify/packages", "安装包"),
                       ("/classify/installs", "安装记录")):
        rows = api("GET", path)
        assert rows == [], (f"{what}表已有 {len(rows)} 条历史 —— 本脚本要求全新库：停 app、"
                            f"rm -rf {TMP / 'data'}、按文件头注释重启后再跑。")

    TMP.mkdir(parents=True, exist_ok=True)
    runner = None
    try:
        step("[1] 上传安装包（边车程序包 + 语料包）")
        sidecar_zip = make_zip({"fake_sidecar.py": STUB_SIDECAR})
        corpus_zip = make_zip({"corpus/readme.txt": f"cap57 e2e corpus {MARK}\n"})
        app_pkg = upload_pkg("SIDECAR_APP", f"{MARK}-sidecar", "1.0.0", sidecar_zip, "fake-sidecar.zip")
        corpus_pkg = upload_pkg("CORPUS", f"{MARK}-corpus", "1.0.0", corpus_zip, "fake-corpus.zip")
        check("程序包上传落库（sha256 64 位）",
              len(app_pkg["sha256"]) == 64 and app_pkg["sizeBytes"] == len(sidecar_zip),
              f"{app_pkg}")
        check("语料包上传落库", corpus_pkg["kind"] == "CORPUS", f"{corpus_pkg}")

        step("[2] 注册节点并起真 runner（v15）")
        issued = api("POST", "/agent-nodes", {"name": f"{MARK}-node"})
        node_id = issued["node"]["id"]
        assert RUNNER_JAR.exists(), f"缺 {RUNNER_JAR}：先 mvn -q install -DskipTests"
        WS_DIR.mkdir(parents=True, exist_ok=True)
        ws_url = BASE[:-4] if BASE.endswith("/api") else BASE
        ws_url = ws_url.replace("http://", "ws://").replace("https://", "wss://") + "/ws/agent"
        props = TMP / "runner.properties"
        props.write_text(
            f"serverUrl={ws_url}\n"
            f"token={issued['token']}\n"
            "executor=fake\n"
            f"workDir={WS_DIR.as_posix()}\n",
            encoding="utf-8")
        log = open(TMP / "runner.log", "w", encoding="utf-8")     # noqa: SIM115
        runner = subprocess.Popen(["java", "-jar", str(RUNNER_JAR), str(props)],
                                  cwd=str(TMP), stdout=log, stderr=subprocess.STDOUT)
        node = wait(lambda: next((n for n in api("GET", "/agent-nodes")
                                  if n["id"] == node_id and n["status"] == "ONLINE"), None),
                    f"节点 {node_id} 上线", timeout=90)
        check("runner 协议 ≥ v15（proc/pkg 帧门控）",
              (node.get("protocolVersion") or 0) >= 15,
              f"protocolVersion={node.get('protocolVersion')} —— runner jar 是旧的？重建并重启 runner")

        step("[3] 分发两个包到节点（pkg 帧 → 拉取物化）")
        api("POST", f"/classify/packages/{app_pkg['id']}/install?nodeId={node_id}", expect=202)
        api("POST", f"/classify/packages/{corpus_pkg['id']}/install?nodeId={node_id}", expect=202)

        def installs_done():
            rows = api("GET", f"/classify/installs?nodeId={node_id}")
            if len(rows) < 2:
                return None
            return rows if all(r["status"] in ("INSTALLED", "FAILED") for r in rows) else None

        installs = wait(installs_done, "两个包安装收口", timeout=300)
        by_pkg = {r["packageId"]: r for r in installs}
        check("程序包 INSTALLED 且 installDir 回收",
              by_pkg[app_pkg["id"]]["status"] == "INSTALLED" and by_pkg[app_pkg["id"]]["installDir"],
              f"{by_pkg.get(app_pkg['id'])}")
        check("语料包 INSTALLED",
              by_pkg[corpus_pkg["id"]]["status"] == "INSTALLED",
              f"{by_pkg.get(corpus_pkg['id'])}")
        corpus_dir = by_pkg[corpus_pkg["id"]].get("installDir") or ""

        step("[4] 建实例并 start（proc 帧，env 带 ${PKG_DIR} 占位符）")
        inst = api("POST", "/classify/instances", {
            "name": f"{MARK}-inst",
            "agentNodeId": str(node_id),
            "port": PORT,
            "baseUrl": SIDECAR,
            "appPackageId": app_pkg["id"],
            "commandOverride": f"{python} fake_sidecar.py {PORT}",
            "env": {"CAP57_PROBE": "${PKG_DIR:" + str(corpus_pkg["id"]) + "}"},
        })
        started = api("POST", f"/classify/instances/{inst['id']}/start")
        check("start 受理 → STARTING", started["status"] == "STARTING", f"{started}")

        running = wait(lambda: (lambda i: i if i["status"] in ("RUNNING", "UNHEALTHY", "STOPPED") and i["lastHealthAt"] else None)(
            api("GET", f"/classify/instances/{inst['id']}")), "健康轮询翻出终态", timeout=120)
        check("健康轮询翻 RUNNING（/healthz 通了）", running["status"] == "RUNNING",
              f"status={running['status']} lastError={running.get('lastError')}（runner 日志 {TMP / 'runner.log'}）")
        if running["status"] == "RUNNING":
            health = running.get("lastHealth") or {}
            check("健康快照带槽位/版本", health.get("layaVersion") == "0.0.0-stub"
                  and "multilingual" in (health.get("loaded") or []), f"{health}")

        # 工作目录 = 程序包 installDir（pkg_ack 回收的节点侧绝对路径，比猜 runner 布局可靠）
        probe_file = Path(by_pkg[app_pkg['id']]['installDir']) / 'env-probe.txt'
        probe = probe_file.read_text(encoding="utf-8").strip() if probe_file.exists() else "<no file>"
        check("env 真传到进程（stub 落 env-probe.txt）", probe not in ("<no file>", "<missing>"), probe)
        check("${PKG_DIR:<id>} 展开成语料包安装目录",
              probe.replace("\\", "/") == corpus_dir.replace("\\", "/"),
              f"probe={probe} installDir={corpus_dir}")

        step("[5] playground 直打实例 + 决策记录留痕")
        pg = api("POST", "/classify/playground/run", {
            "instanceId": inst["id"],
            "state": {"proposal": "cap57 e2e"},
            "questions": {"q1": {"type": "choice", "instructions": "是否沉淀",
                                 "criteria": {"keep": "值得", "discard": "不值得"}}},
        })
        check("playground 应答（choice=keep 0.9，routing 来自 stub）",
              pg["answers"]["q1"]["choice"] == "keep" and pg["routingModel"] == "stub-multilingual",
              f"{pg}")
        check("目标描述是实例直打", inst["name"] in pg["target"], pg["target"])
        check("recordRefId 形如 pg-*", pg["recordRefId"].startswith("pg-"), pg["recordRefId"])
        recs = api("GET", "/decision/records?capability=classify-playground&size=20")
        hit = [r for r in recs["items"] if r["refId"] == pg["recordRefId"]]
        check("decision_records 落 classify-playground 行（按 refId 找回）", len(hit) == 1,
              f"total={recs['total']}")

        step("[6] stop → 进程真死")
        stopped = api("POST", f"/classify/instances/{inst['id']}/stop")
        check("stop 受理 → STOPPED", stopped["status"] == "STOPPED", f"{stopped}")

        def port_dead():
            st, _ = call("GET", SIDECAR + "/healthz", timeout=3)
            return st != 200

        # urlopen 对拒连抛 URLError（不是 HTTPError）：包一层
        def port_really_dead():
            try:
                return port_dead()
            except Exception:
                return True

        check("边车进程已退出（端口不再应答）", wait(port_really_dead, "端口关闭", timeout=30))

        live = api("GET", f"/classify/instances/{inst['id']}/status")
        check("status 对账仍是 STOPPED", live["status"] == "STOPPED", f"{live['status']}")
    finally:
        kill(runner)
        # stop 没走到的兜底：stub 是 runner 子进程，taskkill /T 会带走

    print(f"\n通过 {PASSED}，失败 {FAILED}")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
