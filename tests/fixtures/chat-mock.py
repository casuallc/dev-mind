# -*- coding: utf-8 -*-
"""CAP-48/49 假 OpenAI 兼容对话服务（Python 标准库 http.server，无第三方依赖）。

实现 CAP-48 真正打到的东西：POST /v1/chat/completions（CAP-48 FR-11，非流式）；
CAP-49 增补流式：请求体 stream=true 时按 SSE 分段下发（text/event-stream + chunked）。
控制面 /__* 供 E2E 改回复文本、注入故障、改分片、回看收到的请求。

**刻意不实现 /v1/embeddings**：对话端点被误当成向量端点（探针没按 kind 分派）时会拿到 404，
测试当场就红，而不是悄悄拿对话模型的名字去要向量。

用法：python tests/fixtures/chat-mock.py [port]（默认 18194）

端点：
  POST /v1/chat/completions  请求体 {"model":…,"messages":[{role,content}],"stream":?} →
                             stream 为真：SSE 逐片下发 chunks（data: {...delta.content} … data: [DONE]）；
                             否则整包回落：choices[0].message.content = 当前回复文本
  POST /__reply {"reply":"…"}  改非流式回复文本（默认"可用"）
  POST /__chunks {"chunks":["…"],"holdMs":N}  改流式分片（默认三段，每段都超过服务端 24 字合并阈值，
                             因此每段各自成一条 text_delta）；holdMs>0 时首片之后停住 N 毫秒
                             ——中断用例要的正是"流还在飞的时候按下停止"
  POST /__status {"code":N}   让 /v1/chat/completions 回该状态码，body 回显 Authorization
                              （默认 200；注入 401 验「失败诊断且不回显密钥」）
  POST /__reset               清空请求记录与故障注入（回复文本/分片恢复默认）
  GET  /__state              {"status":200,"reply":"可用","chunks":[…],"holdMs":N,
                              "requests":[{model,prompt,role,auth,path,hasMaxTokens,stream,messages}]}
                             messages = 本轮完整对话装配（CAP-49 多轮上下文与知识注入断言看这个）
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18194

# 默认流式分片：每段都 > 24 字（devmind.chat.stream-flush-chars），服务端攒到阈值立刻发一条
# text_delta ⇒ 分片数 == 增量条数，"多条 text_delta" 的断言不依赖调度时序。
DEFAULT_CHUNKS = [
    "这是模型执行体返回的第一段正文，来自已接入的对话端点。",
    "第二段继续输出，长度同样超过合并阈值，于是会单独成为一条增量事件。",
    "第三段收尾，整段回答到此结束，可以断言全量正文与增量之和一致。",
]

STATE = {"status": 200, "reply": "可用", "chunks": list(DEFAULT_CHUNKS), "holdMs": 0, "requests": []}


class Handler(BaseHTTPRequestHandler):

    # HTTP/1.1：SSE 的 Transfer-Encoding: chunked 在 1.0 下非法，而整包下发就等于没有流
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
        if path == "/__chunks":
            body = self._body()
            if body.get("chunks"):
                STATE["chunks"] = list(body["chunks"])
            if "holdMs" in body:
                STATE["holdMs"] = int(body["holdMs"])
            self._send(200, {"chunks": STATE["chunks"], "holdMs": STATE["holdMs"]})
            return
        if path == "/__reset":
            STATE["requests"] = []
            STATE["status"] = 200
            STATE["chunks"] = list(DEFAULT_CHUNKS)
            STATE["holdMs"] = 0
            self._send(200, {"ok": True})
            return
        if not path.endswith("/chat/completions"):
            self._send(404, {"error": "not found"})
            return

        body = self._body()
        messages = body.get("messages") or []
        first = messages[0] if messages else {}
        stream = bool(body.get("stream"))
        STATE["requests"].append({
            "model": body.get("model"),
            "prompt": first.get("content"),
            "role": first.get("role"),
            "auth": self.headers.get("Authorization") or "",
            "path": path,
            "hasMaxTokens": "max_tokens" in body,
            # CAP-49：流式标志 + 全量 messages（多轮装配/知识注入剥离靠它断言）
            "stream": stream,
            "messages": [{"role": m.get("role"), "content": m.get("content")} for m in messages],
        })
        if STATE["status"] != 200:
            # 故意回显 Authorization：验证服务端把凭据片段抹成 ***（FR-02）
            self._send(STATE["status"], {"error": {"message": "invalid api key: "
                                                    + (self.headers.get("Authorization") or ""),
                                                    "type": "invalid_request_error"}})
            return
        if stream:
            self._sse(body)
            return
        self._send(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": body.get("model"),
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": STATE["reply"]}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        })

    # ---------------- 流式（CAP-49） ----------------

    def _sse(self, body):
        """按 SSE 规范逐片下发 chunks：每片一个 data: 事件，末尾 data: [DONE]。

        用 chunked 而不是 Content-Length：写死长度会让"整包到达"，服务端也就分不出增量，
        中断用例（首片之后停住、此时按下停止）更无从谈起。写入失败照常吞掉——用户点了停止，
        服务端会立刻关流，这里下一次写就是 BrokenPipe，属预期。
        """
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for i, piece in enumerate(STATE["chunks"]):
                payload = {"id": "chatcmpl-mock", "object": "chat.completion.chunk",
                           "model": body.get("model"),
                           "choices": [{"index": 0, "delta": {"content": piece}, "finish_reason": None}]}
                self._write_chunk("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n")
                if i == 0 and STATE["holdMs"] > 0:
                    time.sleep(STATE["holdMs"] / 1000.0)
            self._write_chunk("data: [DONE]\n\n")
            self._write_chunk("")  # 终止块 0\r\n\r\n
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    def _write_chunk(self, text):
        """一个 HTTP chunk 编码帧：十六进制长度 + CRLF + 内容 + CRLF（立刻 flush 才是真流式）"""
        raw = text.encode("utf-8")
        self.wfile.write(b"%X\r\n" % len(raw) + raw + b"\r\n")
        self.wfile.flush()


if __name__ == "__main__":
    print(f"chat-mock listening on 127.0.0.1:{PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
