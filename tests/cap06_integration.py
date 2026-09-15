import json, time, urllib.request, urllib.error, sys

BASE = "http://localhost:8080/api"

def req(method, path, body=None):
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        return e.code, (json.loads(raw) if raw else None)

def poll(path, pred, timeout=90, interval=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, data = req("GET", path)
        if st == 200 and pred(data):
            return data
        time.sleep(interval)
    return None

# 1. 创建真实会话（trivial 任务，快速到 WAITING_INPUT）
st, s = req("POST", "/sessions", {"projectId": "default", "taskSpec": "只回复两个字：完成。不要调用任何工具，不要分析。"})
sid = s["id"] if s else None
print(f"session created: {sid} state={s.get('state') if s else None} st={st}")
assert sid, "create failed: " + str(s)

# 2. 等待该会话的 P0 WAITING_INPUT 通知出现
def find_waiting(lst):
    return next((n for n in lst if n["eventType"] == "WAITING_INPUT" and n["entityId"] == sid and n["level"] == "P0"), None)

notifs = poll("/notifications?limit=100", lambda l: find_waiting(l) is not None, timeout=120)
n_wait = find_waiting(notifs) if notifs else None
print(f"WAITING_INPUT notification: id={n_wait['id'] if n_wait else None} actions={n_wait['actions'] if n_wait else None}")

# 3. 验证 SESSION_STARTED (P2) 也到了
n_start = next((n for n in (notifs or []) if n["eventType"] == "SESSION_STARTED" and n["entityId"] == sid), None)
print(f"SESSION_STARTED notification: id={n_start['id'] if n_start else None} level={n_start['level'] if n_start else None}")

# 4. 执行 finish 快捷动作（从通知中心一键结束会话）
st, n_after = req("POST", f"/notifications/{n_wait['id']}/action", {"action": "finish"})
print(f"action finish -> st={st} readAt={n_after.get('readAt') if n_after else None}")

# 5. 等待会话结束
def done(data):
    return data and data.get("state") in ("DONE", "FAILED")
fin = poll(f"/sessions/{sid}", done, timeout=60)
print(f"session final state={fin.get('state') if fin else None} summary={(fin.get('summary') or '')[:60] if fin else None}")

ok = bool(n_wait) and bool(n_start) and n_wait["level"] == "P0" and n_start["level"] == "P2" \
     and n_after and n_after.get("readAt") is not None and fin and fin.get("state") == "DONE"
print(f"\n{'PASS' if ok else 'FAIL'}  session->notification->action integration")
sys.exit(0 if ok else 1)
