import json, urllib.request, urllib.error, sys

BASE = "http://localhost:8080/api"
results = []

def req(method, path, body=None, expect=200):
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read().decode("utf-8")
            status = resp.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        status = e.code
    ok = status == expect
    results.append((ok, f"{method} {path} -> {status} (expect {expect})"))
    try:
        return status, json.loads(raw) if raw else None
    except Exception:
        return status, raw

# 1. 创建项目
st, p = req("POST", "/projects", {"name": "dev-mind", "path": "D:/apusic/dev-mind",
                                  "defaultBranch": "master", "tags": ["java", "spring", "frontend"],
                                  "description": "验证用", "status": "ACTIVE"})
pid = p["id"] if p else None
results.append((bool(pid), f"created project id={pid}"))

# 2. 重复路径应冲突
req("POST", "/projects", {"name": "dup", "path": "D:/apusic/dev-mind"}, expect=409)

# 3. 更新
st, p2 = req("PUT", f"/projects/{pid}", {"name": "dev-mind2", "path": "D:/apusic/dev-mind",
                                         "defaultBranch": "main", "tags": ["java"], "description": "改", "status": "ARCHIVED"})
results.append((p2 and p2.get("defaultBranch") == "main" and p2.get("status") == "ARCHIVED", "update fields"))

# 4. 摘要生成
st, sm = req("POST", f"/projects/{pid}/summary/refresh")
results.append((sm and "项目上下文摘要" in (sm.get("summary") or ""), "summary refresh generated"))
results.append((sm and sm.get("generatedAt") is not None, "summary generatedAt set"))

# 5. 人工修正摘要
st, sm2 = req("PUT", f"/projects/{pid}/summary", {"text": "# 人工修正的摘要\n- 这是手动内容"})
results.append((sm2 and sm2.get("summary") == "# 人工修正的摘要\n- 这是手动内容", "summary manual edit"))

# 6. 服务器
req("POST", f"/projects/{pid}/servers", {"name": "test-server", "env": "test", "accessType": "ssh",
                                         "accessConfig": "{\"host\":\"10.0.0.1\",\"port\":22}",
                                         "capabilities": ["deploy", "test"], "enabled": True})
req("POST", f"/projects/{pid}/servers", {"name": "prod-server", "env": "prod", "accessType": "http",
                                         "accessConfig": "{\"baseUrl\":\"https://prod.example.com\"}",
                                         "capabilities": ["deploy"], "enabled": True})
st, srv = req("GET", f"/projects/{pid}/servers")
results.append((st == 200 and len(srv) == 2, f"servers listed (n={len(srv)})"))
sid0 = srv[0]["id"]
st, srvU = req("PUT", f"/projects/{pid}/servers/{sid0}", {"name": "test-server-r2", "env": "staging", "accessType": "ssh",
                                                          "accessConfig": "{}", "capabilities": ["test"], "enabled": False})
results.append((srvU and srvU.get("env") == "staging" and srvU.get("enabled") is False, "server update"))
req("DELETE", f"/projects/{pid}/servers/{sid0}")
st, srv = req("GET", f"/projects/{pid}/servers")
results.append((len(srv) == 1, "server delete"))

# 7. 构建步骤 + 排序
req("POST", f"/projects/{pid}/build-steps", {"sortOrder": 0, "name": "compile", "command": "mvn -q compile", "location": "LOCAL"})
req("POST", f"/projects/{pid}/build-steps", {"sortOrder": 1, "name": "package", "command": "mvn -q package", "location": "REMOTE", "workingDir": "app/"})
st, steps = req("GET", f"/projects/{pid}/build-steps")
results.append((len(steps) == 2 and steps[0]["name"] == "compile", "build steps added"))
# 整表替换（模拟排序）
req("PUT", f"/projects/{pid}/build-steps", [
    {"sortOrder": 0, "name": "package", "command": "mvn -q package", "location": "REMOTE", "workingDir": "app/"},
    {"sortOrder": 1, "name": "compile", "command": "mvn -q compile", "location": "LOCAL"},
])
st, steps = req("GET", f"/projects/{pid}/build-steps")
results.append((len(steps) == 2 and steps[0]["name"] == "package", "build steps reordered"))

# 8. 发版配置
req("POST", f"/projects/{pid}/release-config", {"nexusRepo": "releases", "scriptTemplateRef": "nexus-push", "versionRule": "patch+1"})
st, rel = req("GET", f"/projects/{pid}/release-config")
results.append((rel and rel.get("nexusRepo") == "releases", "release config"))

# 9. 锁定
st, lk = req("PUT", f"/projects/{pid}/lock", {"maxConcurrent": 1})
results.append((lk and lk.get("maxConcurrent") == 1, "lock max=1"))
st, lk = req("POST", f"/projects/{pid}/lock/claim")
results.append((lk and lk.get("activeWrites") == 1, "claim #1 ok"))
st, _ = req("POST", f"/projects/{pid}/lock/claim", expect=409)
results.append((st == 409, "claim #2 conflict(409)"))
st, lk = req("POST", f"/projects/{pid}/lock/release")
results.append((lk and lk.get("activeWrites") == 0, "release ok"))

# 10. worktrees（当前为空）
st, wt = req("GET", f"/projects/{pid}/worktrees")
results.append((st == 200 and isinstance(wt, list), "worktrees listed (empty)"))

# 11. 非法路径校验
st, _ = req("POST", "/projects", {"name": "bad", "path": "C:/nonexistent"}, expect=400)
results.append((st == 400, "invalid repo path rejected(400)"))

# 12. 删除
req("DELETE", f"/projects/{pid}")
st, plist = req("GET", "/projects")
results.append((pid not in [p["id"] for p in plist], "project deleted"))

print("\n".join(f"{'PASS' if ok else 'FAIL'}  {msg}" for ok, msg in results))
bad = sum(1 for ok, _ in results if not ok)
print(f"\n{len(results)-bad}/{len(results)} passed")
sys.exit(1 if bad else 0)
