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
        return status, (json.loads(raw) if raw and raw.strip() != "null" else None)
    except Exception:
        return status, raw

# 1. 初始为空
st, lst = req("GET", "/notifications?limit=50")
results.append((st == 200 and isinstance(lst, list), f"initial list (n={len(lst) if lst else 0})"))

# 2. emit P0 + P2
st, n0 = req("POST", "/notifications/emit", {"eventType": "WAITING_INPUT", "title": "会话等待输入",
                                             "body": "请输入继续", "level": "P0", "entityType": "SESSION",
                                             "entityId": "test-abc"})
results.append((n0 and n0["level"] == "P0" and n0["id"] is not None, "emit P0 ok"))
st, n2 = req("POST", "/notifications/emit", {"eventType": "SESSION_STARTED", "title": "会话已启动",
                                             "body": "", "level": "P2", "entityType": "SESSION",
                                             "entityId": "test-abc"})
results.append((n2 and n2["level"] == "P2", "emit P2 ok"))

# 3. 通道状态：P0 应 ws/log SENT，bark/wecom 默认 disabled
cs = (n0 or {}).get("channelStatus", {})
results.append((cs.get("ws") == "SENT" and cs.get("log") == "SENT", f"ws/log sent (cs={cs})"))
results.append((cs.get("bark", "").startswith("SKIPPED") and cs.get("wecom", "").startswith("SKIPPED"),
                "bark/wecom disabled-skipped"))

# 4. 去重：同 eventType+entityId 再 emit → 应被忽略
st, dup = req("POST", "/notifications/emit", {"eventType": "WAITING_INPUT", "title": "重复",
                                              "body": "", "level": "P0", "entityType": "SESSION",
                                              "entityId": "test-abc"})
results.append((dup is None, "dedup within 5min"))

# 5. unread-count
st, uc = req("GET", "/notifications/unread-count")
results.append((uc and uc["count"] >= 2, f"unread-count={uc}"))

# 6. 标记已读
req("POST", f"/notifications/{n0['id']}/read")
st, lst = req("GET", "/notifications?unreadOnly=true&limit=50")
results.append((n0["id"] not in [x["id"] for x in lst], "mark read works"))

# 7. read-all
req("POST", "/notifications/read-all")
st, uc = req("GET", "/notifications/unread-count")
results.append((uc["count"] == 0, "read-all zeroes unread"))

# 8. 通道 CRUD
st, chs = req("GET", "/notification-channels")
codes = {c["code"] for c in chs}
results.append((st == 200 and codes == {"ws", "log", "bark", "wecom"}, f"channels seeded {sorted(codes)}"))
bark = next(c for c in chs if c["code"] == "bark")
st, bark2 = req("PUT", f"/notification-channels/{bark['id']}",
                {"enabled": True, "levelThreshold": "P1", "config": {"server": "https://api.day.app"}})
results.append((bark2 and bark2["enabled"] is True and bark2["levelThreshold"] == "P1"
                and bark2["config"].get("server") == "https://api.day.app", "channel update merges config"))

# 9. bark 已启用但 key 空 → P0 emit 应 FAILED（真实发送尝试失败降级）
st, np0 = req("POST", "/notifications/emit", {"eventType": "SESSION_FAILED", "title": "失败",
                                              "body": "err", "level": "P0", "entityType": "SESSION",
                                              "entityId": "test-fail-" + str(__import__("time").time())})
cs = (np0 or {}).get("channelStatus", {})
results.append((cs.get("bark", "").startswith("FAILED"), f"bark empty-key failed gracefully (cs={cs})"))

# 10. 免打扰时段：P1 在 00:00-23:59 内被 quiet 跳过（阈值先调成 P1 以通过 threshold 检查）
req("PUT", "/notification-prefs", {"quietStart": "00:00", "quietEnd": "23:59"})
st, np1 = req("POST", "/notifications/emit", {"eventType": "SESSION_DONE", "title": "完成",
                                              "body": "", "level": "P1", "entityType": "SESSION",
                                              "entityId": "test-quiet-" + str(__import__("time").time())})
cs = (np1 or {}).get("channelStatus", {})
results.append((cs.get("bark", "").startswith("SKIPPED:quiet"), f"quiet-hours skip P1 (cs={cs})"))
# 清除免打扰
req("PUT", "/notification-prefs", {"quietStart": None, "quietEnd": None})

# 11. 静默事件：mute SESSION_DONE → emit P1 应被 muted 跳过
req("PUT", "/notification-prefs", {"mutes": {"eventTypes": ["SESSION_DONE"]}})
st, np2 = req("POST", "/notifications/emit", {"eventType": "SESSION_DONE", "title": "完成2",
                                              "body": "", "level": "P1", "entityType": "SESSION",
                                              "entityId": "test-mute-" + str(__import__("time").time())})
cs = (np2 or {}).get("channelStatus", {})
results.append((cs.get("bark", "").startswith("SKIPPED:muted"), f"muted eventType skip (cs={cs})"))
req("PUT", "/notification-prefs", {"mutes": {"eventTypes": []}})

# 12. 偏好读写
st, prefs = req("PUT", "/notification-prefs", {"quietStart": "23:00", "quietEnd": "07:30",
                                               "perSessionSilence": ["abc123"]})
results.append((prefs and prefs["quietStart"] == "23:00" and "abc123" in prefs["perSessionSilence"],
                "prefs write/read"))
st, prefs = req("GET", "/notification-prefs")
results.append((prefs and prefs["perSessionSilence"] == ["abc123"], "prefs persisted"))

# 13. 动作端点：对非 SESSION 实体（TEST）执行未知动作 → 400
st, _ = req("POST", f"/notifications/{n0['id']}/action", {"action": "nope"}, expect=400)
results.append((st == 400, "unknown action rejected(400)"))

# 14. view 动作 → 标记已读，不报错
st, nv = req("POST", f"/notifications/{n2['id']}/action", {"action": "view"})
results.append((nv and nv["readAt"] is not None, "view action marks read"))

print("\n".join(f"{'PASS' if ok else 'FAIL'}  {msg}" for ok, msg in results))
bad = sum(1 for ok, _ in results if not ok)
print(f"\n{len(results)-bad}/{len(results)} passed")
sys.exit(1 if bad else 0)
