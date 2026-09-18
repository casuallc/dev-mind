# -*- coding: utf-8 -*-
"""CAP-47 假 Jira Server（仅 /rest/api/2，Python 标准库 http.server，无第三方依赖）。

只用 CAP-47 推送链路真正打到的端点；控制面 /__* 供 E2E 断言收到的 payload 与切换场景。
用法：python tests/fixtures/jira-mock.py [port]（默认 18192）

端点：
  GET  /rest/api/2/myself | /serverInfo | /project | /priority
  GET  /rest/api/2/issue/createmeta/<KEY>/issuetypes   任务类型（子任务含在内，由服务端过滤）
  POST /rest/api/2/issue                               创建 issue（记录收到的 payload）
  GET  /rest/api/2/issue/<KEY>                         单条读取（回读/手动刷新用）
  GET  /rest/api/2/user/assignable/search              可指派用户（__gdpr 打开时拒 query 参数）
  GET  /rest/api/2/search                              同步分页拉取（返回全部已建 issue）
控制面：
  GET  /__state    {"issues":[…回读形态…], "requests":[{method,path,auth,body}]}
  POST /__mutate   {"key":"PROJ-101","patch":{"status":"In Progress",…}} 改远端字段
  POST /__gdpr     {"on":true} 让 user/assignable/search 对 query 参数报 400 GDPR
  POST /__create-error {"error":{…}} 让 POST /issue 回该 400 体（字段级错误透出用；{"error":null} 关闭）
  POST /__reset    清空 issue、请求记录与错误注入
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18192

# 任务类型目录：含一个子任务（subtask=true），验证服务端过滤
ISSUE_TYPES = [
    {"id": "10001", "name": "任务"},
    {"id": "10002", "name": "缺陷"},
    {"id": "10003", "name": "子任务", "subtask": True},
]
PRIORITIES = [
    {"id": "1", "name": "Highest"},
    {"id": "2", "name": "High"},
    {"id": "3", "name": "Medium"},
    {"id": "4", "name": "Low"},
    {"id": "9"},  # 脏数据：无 name，服务端须过滤
]
PROJECTS = [
    {"key": "PROJ", "name": "E2E 项目"},
    {"key": "OPS", "name": "E2E 运维"},
]
USERS = [
    {"name": "lisi", "displayName": "李四"},
    {"name": "wangwu"},  # 无显示名：displayName 回退 name
]

STATE = {"issues": {}, "seq": 100, "gdpr": False, "requests": [], "create_error": None}


def new_issue_fields(payload_fields):
    """按 Jira 的 issue 形态落库：reporter/fixVersions 刻意留空（推送参数本就不含它们，
    留空才能验证「推送不套用 syncFromJira」的托管字段边界）。"""
    type_id = (payload_fields.get("issuetype") or {}).get("id")
    type_name = next((t["name"] for t in ISSUE_TYPES if t["id"] == type_id), type_id)
    return {
        "summary": payload_fields.get("summary"),
        "description": payload_fields.get("description"),
        "issuetype": {"name": type_name},
        "priority": payload_fields.get("priority"),
        "assignee": payload_fields.get("assignee"),
        "labels": payload_fields.get("labels") or [],
        "status": {"name": "To Do"},
        "fixVersions": [],
        "duedate": payload_fields.get("duedate"),
        "created": "2026-09-18T10:00:00.000+0800",
        "updated": "2026-09-18T10:00:00.000+0800",
        "timeoriginalestimate": None,
        "timespent": None,
    }


def apply_patch(fields, patch):
    """控制面改字段：按 Jira 的真实嵌套形态套用（priority/status/assignee/reporter 是对象，
    fixVersions 是 [{name}] 而非字符串数组——写成字符串数组映射端会当成空。labels 才是字符串数组）。"""
    for key, value in (patch or {}).items():
        if key in ("priority", "status"):
            fields[key] = {"name": value}
        elif key in ("assignee", "reporter"):
            fields[key] = {"name": value} if value else None
        elif key == "fixVersions":
            fields[key] = [{"name": v} for v in (value or [])]
        else:
            fields[key] = value
    fields["updated"] = "2026-09-18T12:00:00.000+0800"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # 静音（E2E 只看断言结果）
        pass

    # ---------------- 基础设施 ----------------
    def send_json(self, code, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def read_body(self):
        """请求体：兼容 Content-Length 与 chunked（Spring RestClient 走的是后者，
        只认 Content-Length 会把创建 issue 的 payload 读成空 dict → 必填字段全缺）。"""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            chunks = []
            while True:
                line = self.rfile.readline().strip()
                if not line:  # chunk 之间的空行
                    continue
                size = int(line.split(b";")[0], 16)
                if size == 0:
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()  # 每个 chunk 尾随 CRLF
            raw = b"".join(chunks)
        else:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
        if not raw:
            return None
        text = raw.decode("utf-8")
        if self.headers.get("Content-Type", "").startswith("application/json"):
            return json.loads(text)
        return text

    def do_GET(self):
        self.handle_any("GET")

    def do_POST(self):
        self.handle_any("POST")

    def handle_any(self, method):
        parsed = urlparse(self.path)
        path, query = parsed.path, parse_qs(parsed.query)
        try:
            body = self.read_body()
        except Exception:
            body = None
        if not path.startswith("/__"):
            STATE["requests"].append({
                "method": method, "path": path,
                "auth": self.headers.get("Authorization"),
                "body": body,
            })
        try:
            handler = ROUTES.get((method, path)) or self.dynamic(method, path)
            if handler is None:
                return self.send_json(404, {"errorMessages": ["Not found: " + path]})
            return handler(self, path, query, body)
        except Exception as exc:  # 兜底：任何异常回 500 + 原文，便于排错
            return self.send_json(500, {"errorMessages": ["mock 内部错误: %s" % exc]})

    def dynamic(self, method, path):
        """带路径参数的端点（issue key / 项目 key）。"""
        prefix = "/rest/api/2/issue/createmeta/"
        if path.startswith(prefix) and path.endswith("/issuetypes"):
            return lambda self_, p, q, b: self_.issue_types_page(q)
        prefix = "/rest/api/2/issue/"
        if method == "GET" and path.startswith(prefix):
            return lambda self_, p, q, b: self_.get_issue(p[len(prefix):])
        return None

    # ---------------- 只读端点 ----------------
    def issue_types_page(self, query):
        start = int(query.get("startAt", ["0"])[0])
        size = int(query.get("maxResults", ["50"])[0])
        page = ISSUE_TYPES[start:start + size]
        return self.send_json(200, {"startAt": start, "maxResults": size, "total": len(ISSUE_TYPES),
                                    "isLast": start + size >= len(ISSUE_TYPES), "values": page})

    def get_issue(self, raw_key):
        key = raw_key.split("?")[0]
        issue = STATE["issues"].get(key)
        if issue is None:
            return self.send_json(404, {"errorMessages": ["Issue does not exist: " + key]})
        return self.send_json(200, issue)

    def assignable_search(self, path, query, body):
        # GDPR 严格模式：query 参数被拒（连接器须退 username 重试一次）
        if STATE["gdpr"] and "query" in query:
            return self.send_json(400, {"errorMessages": [
                "Cannot search for users by query due to GDPR restrictions"]})
        q = (query.get("query") or query.get("username") or [""])[0].lower()
        hits = [u for u in USERS if not q or q in u["name"].lower()
                or q in (u.get("displayName") or "").lower()]
        return self.send_json(200, hits)

    def create_issue(self, path, query, body):
        # 注入的 400（__create-error）：模拟 Jira 项目给该任务类型配了必填字段/取值非法
        if STATE["create_error"]:
            return self.send_json(400, STATE["create_error"])
        fields = (body or {}).get("fields") or {}
        missing = [k for k in ("project", "issuetype", "summary") if not fields.get(k)]
        if missing:
            return self.send_json(400, {"errorMessages": ["缺少必填字段: " + ",".join(missing)]})
        project_key = fields["project"].get("key")
        STATE["seq"] += 1
        key = "%s-%d" % (project_key, STATE["seq"])
        issue = {"id": str(10000 + STATE["seq"]), "key": key, "self": path,
                 "fields": new_issue_fields(fields)}
        STATE["issues"][key] = issue
        return self.send_json(201, {"id": issue["id"], "key": key, "self": path})

    def search(self, path, query, body):
        start = int(query.get("startAt", ["0"])[0])
        issues = list(STATE["issues"].values())
        return self.send_json(200, {"startAt": start, "maxResults": len(issues),
                                    "total": len(issues), "issues": issues})

    # ---------------- 控制面 ----------------
    def control_state(self, path, query, body):
        return self.send_json(200, {"issues": list(STATE["issues"].values()),
                                    "requests": STATE["requests"]})

    def control_mutate(self, path, query, body):
        key = (body or {}).get("key")
        issue = STATE["issues"].get(key)
        if issue is None:
            return self.send_json(404, {"errorMessages": ["未知 issue: %s" % key]})
        apply_patch(issue["fields"], (body or {}).get("patch"))
        return self.send_json(200, {"ok": True, "issue": issue})

    def control_gdpr(self, path, query, body):
        STATE["gdpr"] = bool((body or {}).get("on"))
        return self.send_json(200, {"gdpr": STATE["gdpr"]})

    def control_create_error(self, path, query, body):
        """body {"error": {"errorMessages":[…], "errors":{字段: 原因}}} 注入；{"error": null} 关闭"""
        STATE["create_error"] = (body or {}).get("error") or None
        return self.send_json(200, {"createError": STATE["create_error"]})

    def control_reset(self, path, query, body):
        STATE["issues"].clear()
        STATE["requests"].clear()
        STATE["seq"] = 100
        STATE["gdpr"] = False
        STATE["create_error"] = None
        return self.send_json(200, {"ok": True})


ROUTES = {
    ("GET", "/rest/api/2/myself"): lambda s, p, q, b: s.send_json(
        200, {"name": "e2e-bot", "displayName": "E2E Bot"}),
    ("GET", "/rest/api/2/serverInfo"): lambda s, p, q, b: s.send_json(
        200, {"version": "9.4.0", "serverTitle": "E2E Jira"}),
    ("GET", "/rest/api/2/project"): lambda s, p, q, b: s.send_json(200, PROJECTS),
    ("GET", "/rest/api/2/priority"): lambda s, p, q, b: s.send_json(200, PRIORITIES),
    ("GET", "/rest/api/2/user/assignable/search"): Handler.assignable_search,
    ("POST", "/rest/api/2/issue"): Handler.create_issue,
    ("GET", "/rest/api/2/search"): Handler.search,
    ("GET", "/__state"): Handler.control_state,
    ("POST", "/__mutate"): Handler.control_mutate,
    ("POST", "/__gdpr"): Handler.control_gdpr,
    ("POST", "/__create-error"): Handler.control_create_error,
    ("POST", "/__reset"): Handler.control_reset,
}


def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print("jira-mock listening on http://127.0.0.1:%d/rest/api/2" % PORT, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
