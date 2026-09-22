# -*- coding: utf-8 -*-
"""CAP-55 假 laya 决策边车（Python 标准库 http.server，无第三方依赖）。

真边车是 `tools/laya-sidecar`（FastAPI + torch 常驻进程），体积与冷启动都不适合 CI/E2E；
本 mock 复刻它对 Java 侧暴露的协议与**应答形状**（照 0.3.5 真机输出抄的），
并按请求里的 questions 按原语派生固定答案：
choice → 第一个选项 0.9；score → 最高等级 0.8；noul → 0.2。
于是同一份 mock 既接得住连接测试的样例（sample_choice），也接得住提案分诊的三题
（E2E 断言"徽标显示的是 mock 给的那个层级"就看这里）。

控制面 /__* 供 E2E 改答案、换 checkpoint、注入故障、回看收到的请求。

用法：python tests/fixtures/laya-sidecar-mock.py [port]（默认 18195）

端点：
  GET  /healthz              存活 + 常驻 checkpoint + 设备（CAP-48 连接测试第一段）；
                             经 /__sources 注入后另报 sources/source_origin（CAP-56 FR-01/06）
  POST /v1/predict           {"state":…,"questions":{…},"model"?} → laya 形状的 answers/routing/usage
  POST /__answers {"answers":{…}}   固定答案（传 null / {} 恢复按原语派生）
  POST /__checkpoint {"model":"…","reason":"…"}   改 routing（默认 multilingual + 中文脚本理由）
  POST /__health {"status":"loading"}   改 /healthz 的 status（验「边车未就绪」分支）
  POST /__status {"code":N,"detail":"…"}   让 /v1/predict 回该状态码与 detail（验调用方错误诊断）
  POST /__sources {"sources":{槽位:{kind,path,repo,local,ready,missing,overridden,…}}}
                             注入槽位→实际来源（CAP-56 FR-06 serve 自检的核对依据）；
                             传 null 恢复「不上报」（= FR-01 之前的老边车形态，自检该报 WARN）
  POST /__reset              清空请求记录与故障注入
  GET  /__state              {"status","health","answers","checkpoint","requests":[…]}
                             requests[i] = {path,state,questions,model,auth,hasModelKey,rawLen}
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18195

DEFAULT_CHECKPOINT = {"model": "multilingual",
                      "reason": "non-Latin script (han, 65% of letters); "
                                "the English checkpoint cannot read it",
                      "repo": "convaiinnovations/laya"}

STATE = {"status": 200, "detail": "", "health": "ok", "answers": None,
         "checkpoint": dict(DEFAULT_CHECKPOINT), "requests": [],
         # CAP-56 FR-01：槽位→实际来源上报。默认不报（= FR-01 之前的老边车形态），
         # 由 /__sources 注入；serve 自检的「来源一致」检查要在有/无两种形态下都验到
         "sources": None}


def derive_answer(question):
    """按原语派生一个"总是同一答案"的应答（形状照真边车：type + 该原语字段 + probabilities + confidence）"""
    typ = question.get("type")
    if typ == "choice":
        options = list((question.get("criteria") or {}).keys())
        chosen = options[0] if options else "option"
        rest = [o for o in options if o != chosen]
        probabilities = {o: (0.9 if o == chosen else round(0.1 / len(rest), 4)) for o in options}
        return {"type": "choice", "choice": chosen, "probabilities": probabilities,
                "confidence": 0.9, "action": {"act_probability": 0.9}}
    if typ == "score":
        levels = question.get("criteria") or []
        n = max(len(levels), 1)
        top = str(n - 1)
        probabilities = {str(i): (0.8 if str(i) == top else round(0.2 / max(n - 1, 1), 4))
                         for i in range(n)}
        return {"type": "score", "score": float(n - 1),
                "legend": {str(i): levels[i] for i in range(n)},
                "probabilities": probabilities, "confidence": 0.8, "action": {"act_probability": 0.8}}
    if typ == "noul":
        return {"type": "noul", "noul": 0.2, "confidence": 0.7, "action": {"act_probability": 0.3}}
    return {"type": "unknown", "confidence": 0.0}


class Handler(BaseHTTPRequestHandler):

    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 保持输出干净
        pass

    def _send(self, code, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _body(self):
        """请求体：兼容 Content-Length 与 chunked（只认 Content-Length 会把请求读成空）"""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                if not size_line:
                    break
                size = int(size_line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            raw = b"".join(chunks)
        else:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            return {}

    def do_GET(self):
        if urlparse(self.path).path == "/__state":
            self._send(200, STATE)
            return
        if urlparse(self.path).path == "/healthz":
            doc = {
                "status": STATE["health"],
                "laya_version": "0.3.5-mock",
                "loaded": ["multilingual"] if STATE["health"] == "ok" else [],
                "devices": {"multilingual": "cpu"} if STATE["health"] == "ok" else {},
                "cuda_available": False,
            }
            if STATE["sources"] is not None:
                # 形状照真边车（tools/laya-sidecar/app.py 的 _slot_source_report）：
                # 每个槽位一条 {kind, path/source, repo, local, ready, missing, device, overridden}
                doc["sources"] = STATE["sources"]
                doc["source_origin"] = "mock:/__sources"
                doc["overridden_slots"] = sorted(n for n, s in STATE["sources"].items()
                                                 if s.get("overridden"))
                doc["unready_slots"] = sorted(n for n, s in STATE["sources"].items()
                                             if s.get("local") and not s.get("ready", False))
            self._send(200, doc)
            return
        self._send(404, {"detail": "Not Found"})

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/__answers":
            answers = self._body().get("answers")
            STATE["answers"] = answers or None
            self._send(200, {"answers": STATE["answers"]})
            return
        if path == "/__checkpoint":
            body = self._body()
            STATE["checkpoint"].update({k: v for k, v in body.items() if v is not None})
            self._send(200, STATE["checkpoint"])
            return
        if path == "/__health":
            STATE["health"] = self._body().get("status", "ok")
            self._send(200, {"status": STATE["health"]})
            return
        if path == "/__sources":
            # 传 {"sources": {...}} 注入槽位来源；传 null/{} 恢复「不报来源」（老边车形态）
            STATE["sources"] = self._body().get("sources") or None
            self._send(200, {"sources": STATE["sources"]})
            return
        if path == "/__status":
            body = self._body()
            STATE["status"] = int(body.get("code", 200))
            STATE["detail"] = body.get("detail", "")
            self._send(200, {"status": STATE["status"], "detail": STATE["detail"]})
            return
        if path == "/__reset":
            self._body()  # 读掉请求体，别留给 keep-alive 的下一个请求
            STATE["status"] = 200
            STATE["detail"] = ""
            STATE["health"] = "ok"
            STATE["answers"] = None
            STATE["checkpoint"] = dict(DEFAULT_CHECKPOINT)
            STATE["requests"] = []
            STATE["sources"] = None
            self._send(200, {"ok": True})
            return
        if path != "/v1/predict":
            self._send(404, {"detail": "Not Found"})
            return

        body = self._body()
        questions = body.get("questions") or {}
        STATE["requests"].append({
            "path": path,
            "state": body.get("state"),
            "questions": questions,
            "model": body.get("model"),
            "hasModelKey": "model" in body,
            "auth": self.headers.get("Authorization") or "",
            "rawLen": len(json.dumps(body, ensure_ascii=False)),
        })
        if STATE["status"] != 200:
            # 真边车把调用方错误（未知 checkpoint 名 / schema 非法）转成 400 + detail
            self._send(STATE["status"], {"detail": STATE["detail"] or "Bad Request"})
            return
        answers = STATE["answers"] or {qid: derive_answer(q) for qid, q in questions.items()}
        self._send(200, {
            "model": "laya-rl-agent",
            "answers": answers,
            "routing": dict(STATE["checkpoint"]),
            "usage": {"input_tokens": 42, "output_tokens": 0},
        })


if __name__ == "__main__":
    print(f"laya-sidecar-mock listening on 127.0.0.1:{PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
