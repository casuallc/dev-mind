# -*- coding: utf-8 -*-
"""CAP-48 假 OpenAI 兼容 embedding 服务（Python 标准库 http.server，无第三方依赖）。

只实现 CAP-48 真正打到的东西：POST /v1/embeddings（OpenAI 兼容协议，CAP-48 FR-05）。
控制面 /__* 供 E2E 改维度、注入故障、回看收到的请求。

用法：python tests/fixtures/embedding-mock.py [port]（默认 18193）

端点：
  POST /v1/embeddings        请求体 {"model":…,"input":[…]} → 每行一个常量单位向量
                             （首元素 1，其余 0）：库内任意 chunk 与查询的余弦恒为 1.0，
                             必然过阈值命中 → 与 LIKE 降级的 score=0 天然可区分，
                             且不依赖分词质量，断言不会闪。
  POST /__dims {"dims": N}   改返回维度（默认 8）——验端点重测维度变化 + 失配重建
  POST /__status {"code":N}  让 /v1/embeddings 回该状态码，body 回显 Authorization
                             （默认 200；注入 401 验「失败诊断且不回显密钥」）
  POST /__reset              清空请求记录与故障注入（维度保留）
  GET  /__state              {"dims":8,"status":200,"requests":[{model,count,auth,path}]}
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18193

STATE = {"dims": 8, "status": 200, "requests": []}


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
        """请求体：兼容 Content-Length 与 chunked（Spring RestClient 走后者，
        只认 Content-Length 会把 embedding 请求读成空 → 回包条数不符 → 全部索引失败）。"""
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
        if path == "/__dims":
            STATE["dims"] = int(self._body().get("dims", 8))
            self._send(200, {"dims": STATE["dims"]})
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
        if not path.endswith("/embeddings"):
            self._send(404, {"error": "not found"})
            return

        body = self._body()
        auth = self.headers.get("Authorization") or ""
        inputs = body.get("input") or []
        STATE["requests"].append({"model": body.get("model"), "count": len(inputs), "auth": auth,
                                  "path": path})
        if STATE["status"] != 200:
            # 故意回显 Authorization：验证服务端把凭据片段抹成 ***（FR-02）
            self._send(STATE["status"], {"error": {"message": "invalid api key: " + auth,
                                                   "type": "invalid_request_error"}})
            return
        dims = STATE["dims"]
        vec = [1.0] + [0.0] * (dims - 1)
        self._send(200, {
            "object": "list",
            "model": body.get("model"),
            "data": [{"object": "embedding", "index": i, "embedding": vec}
                     for i in range(len(inputs))],
            "usage": {"prompt_tokens": 1, "total_tokens": 1},
        })


if __name__ == "__main__":
    print(f"embedding-mock listening on 127.0.0.1:{PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
