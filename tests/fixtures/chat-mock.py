# -*- coding: utf-8 -*-
"""CAP-48 假 OpenAI 兼容对话服务（Python 标准库 http.server，无第三方依赖）。

只实现 CAP-48 真正打到的东西：POST /v1/chat/completions（CAP-48 FR-11）。
控制面 /__* 供 E2E 改回复文本、注入故障、回看收到的请求。

**刻意不实现 /v1/embeddings**：对话端点被误当成向量端点（探针没按 kind 分派）时会拿到 404，
测试当场就红，而不是悄悄拿对话模型的名字去要向量。

用法：python tests/fixtures/chat-mock.py [port]（默认 18194）

端点：
  POST /v1/chat/completions  请求体 {"model":…,"messages":[{role,content}]} →
                             choices[0].message.content = 当前回复文本
  POST /__reply {"reply":"…"}  改回复文本（默认"可用"）
  POST /__status {"code":N}   让 /v1/chat/completions 回该状态码，body 回显 Authorization
                              （默认 200；注入 401 验「失败诊断且不回显密钥」）
  POST /__reset               清空请求记录与故障注入（回复文本保留）
  GET  /__state              {"status":200,"reply":"可用","requests":[{model,prompt,auth,path,hasMaxTokens}]}
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18194

STATE = {"status": 200, "reply": "可用", "requests": []}


class Handler(BaseHTTPRequestHandler):

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
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/__reply":
            STATE["reply"] = self._body().get("reply", STATE["reply"])
            self._send(200, {"reply": STATE["reply"]})
            return
        if path == "/__status":
            STATE["status"] = int(self._body().get("code", 200))
            self._send(200, {"status": STATE["status"]})
            return
        if path == "/__reset":
            STATE["requests"] = []
            STATE["status"] = 200
            self._send(200, {"ok": True})
            return
        if not path.endswith("/chat/completions"):
            self._send(404, {"error": "not found"})
            return

        body = self._body()
        messages = body.get("messages") or []
        first = messages[0] if messages else {}
        STATE["requests"].append({
            "model": body.get("model"),
            "prompt": first.get("content"),
            "role": first.get("role"),
            "auth": self.headers.get("Authorization") or "",
            "path": path,
            "hasMaxTokens": "max_tokens" in body,
        })
        if STATE["status"] != 200:
            # 故意回显 Authorization：验证服务端把凭据片段抹成 ***（FR-02）
            self._send(STATE["status"], {"error": {"message": "invalid api key: "
                                                    + (self.headers.get("Authorization") or ""),
                                                    "type": "invalid_request_error"}})
            return
        self._send(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": body.get("model"),
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": STATE["reply"]}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        })


if __name__ == "__main__":
    print(f"chat-mock listening on 127.0.0.1:{PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
