#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-71 节点审计日志 E2E（前置：app 起隔离实例，egress 默认开 :18089 无需 runner）。

链路：管理操作（节点/规则）与拒绝路径（上下文包坏 token、SOCKS 未命中规则）真实发生后，
轮询 /agent-nodes/audit-logs 验证落库与检索面。

验收覆盖（CAP-71 §验收）：
  [1]  NODE 分类：建/禁/启/设默认/取消默认/删节点共 6 条，带 actor=admin
  [2]  RULE 分类：建/改/删出口规则 3 条，target=hostPattern
  [3]  CONTEXT 分类：坏 token 拉上下文包 → 401 + PULL/REJECTED 行
  [4]  PROXY 分类：SOCKS5 CONNECT 未命中规则 → rep=2 + REJECTED_NO_RULE 行（target=host:port）
  [5]  关键词过滤：节点名大小写不敏感命中 NODE 行；乱串关键词 0 条
  [6]  真分页：size=2 两页不重叠、total 一致；result/category 过滤生效
  [7]  保留期：purge 由 AgentAuditLogServiceTest 覆盖（cron 日跑不在 E2E 触发），
       此处只验证配置键被读取（启动日志不炸 + 端点可用）

默认打本机 :8080；隔离实例用 E2E_BASE 覆盖（如 http://localhost:8090/api，起法见 tests/README.md，
注意隔离实例须开 egress：devmind.egress.enabled 默认 true，SOCKS 端口默认 18089，
可用 E2E_SOCKS_PORT 覆盖）。跑前先 taskkill 残留 :8080 dev app（不改就是在测老代码）。
"""
import json, os, socket, time
import urllib.request, urllib.error, urllib.parse

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
SOCKS_PORT = int(os.environ.get("E2E_SOCKS_PORT", "18089"))
MARK = f"cap71-{int(time.time())}"
NODE_NAME = f"audit-node-{MARK}"
RULE_HOST = f"{MARK}.corp.example"


def req(method, path, body=None, token=None, expect=200, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            payload = resp.read()
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return payload if raw else json.loads(payload.decode() or "null")
    except urllib.error.HTTPError as e:
        if e.code == expect:
            body_bytes = e.read()
            return body_bytes if raw else json.loads(body_bytes.decode() or "null")
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:400]}")


def login():
    r = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    return r["accessToken"]


def audit_query(token, **params):
    q = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    return req("GET", f"/agent-nodes/audit-logs?{q}", token=token)


def wait_row(token, what, timeout=20, **params):
    """轮询等一条满足条件的审计行（写入异步旁路，通常即时但留余量）"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        page = audit_query(token, **params)
        if page["total"] > 0:
            return page
        time.sleep(0.5)
    raise AssertionError(f"超时等待审计行: {what} params={params}")


def socks5_connect(host, port):
    """最小 SOCKS5 握手（无认证 CONNECT 域名），返回 rep 字节"""
    s = socket.create_connection(("127.0.0.1", SOCKS_PORT), timeout=5)
    try:
        s.sendall(bytes([5, 1, 0]))
        resp = s.recv(2)
        assert resp == bytes([5, 0]), f"SOCKS 握手失败: {resp!r}"
        h = host.encode()
        s.sendall(bytes([5, 1, 0, 3, len(h)]) + h + bytes([(port >> 8) & 0xFF, port & 0xFF]))
        resp = s.recv(10)
        assert len(resp) == 10 and resp[0] == 5, f"SOCKS 响应异常: {resp!r}"
        return resp[1]
    finally:
        s.close()


def main():
    token = login()
    print(f"[0] 登录 admin 成功（标记 {MARK}）")

    # [1] NODE 分类：完整生命周期
    issued = req("POST", "/agent-nodes", {"name": NODE_NAME}, token)
    nid = issued["node"]["id"]
    req("POST", f"/agent-nodes/{nid}/disable", token=token)
    req("POST", f"/agent-nodes/{nid}/enable", token=token)
    req("POST", f"/agent-nodes/{nid}/default", token=token)
    req("POST", f"/agent-nodes/{nid}/unset-default", token=token)
    req("DELETE", f"/agent-nodes/{nid}", token=token)
    page = wait_row(token, "NODE 生命周期", category="NODE", keyword=NODE_NAME, size=50)
    events = {r["event"] for r in page["items"]}
    assert {"CREATE", "DISABLE", "ENABLE", "SET_DEFAULT", "UNSET_DEFAULT", "DELETE"} <= events, events
    assert all(r["actor"] == "admin" for r in page["items"]), "NODE 行须带 actor=admin"
    assert page["total"] >= 6
    print(f"[1] NODE 分类 6 事件齐（total={page['total']}，actor=admin）")

    # [2] RULE 分类（规则 nodeId 须指向存在节点——[1] 的节点已删，重建一个专用节点）
    rule_node = req("POST", "/agent-nodes", {"name": f"rule-node-{MARK}"}, token)
    rnid = rule_node["node"]["id"]
    rule = req("POST", "/egress-rules", {"hostPattern": RULE_HOST, "nodeId": rnid}, token)
    req("PUT", f"/egress-rules/{rule['id']}", {"hostPattern": RULE_HOST, "nodeId": rnid, "sort": 7}, token)
    req("DELETE", f"/egress-rules/{rule['id']}", token=token)
    page = wait_row(token, "RULE 变更", category="RULE", keyword=RULE_HOST, size=50)
    events = {r["event"] for r in page["items"] if r["target"] == RULE_HOST}
    assert {"CREATE", "UPDATE"} <= events, events
    print(f"[2] RULE 分类 CREATE/UPDATE 落库（DELETE 行 target=rule#id）")

    # [3] CONTEXT 分类：坏 token 拉包 → 401 + REJECTED
    req("GET", f"/agent/context/s-{MARK}?token=bad", expect=401)
    page = wait_row(token, "CONTEXT 拒绝", category="CONTEXT", result="REJECTED", keyword=f"s-{MARK}")
    row = page["items"][0]
    assert row["event"] == "PULL" and row["result"] == "REJECTED", row
    print(f"[3] CONTEXT/PULL REJECTED 落库（target={row['target']}）")

    # [4] PROXY 分类：SOCKS 未命中规则 → rep=2 + REJECTED_NO_RULE
    rep = socks5_connect(f"nomatch-{MARK}.example.com", 443)
    assert rep == 2, f"未命中规则应 rep=2，实得 {rep}"
    page = wait_row(token, "PROXY 拒绝", category="PROXY", event="REJECTED_NO_RULE",
                    keyword=f"nomatch-{MARK}")
    row = page["items"][0]
    assert row["target"] == f"nomatch-{MARK}.example.com:443", row
    assert row["result"] == "REJECTED"
    assert "socks5" in (row["detail"] or ""), row
    print(f"[4] PROXY/REJECTED_NO_RULE 落库（target={row['target']}，detail 含 socks5）")

    # [5] 关键词大小写不敏感 + 乱串 0 条
    page = audit_query(token, keyword=NODE_NAME.upper(), size=5)
    assert page["total"] >= 6, f"大写关键词应命中 NODE 行: total={page['total']}"
    page = audit_query(token, keyword=f"zzz-no-such-{MARK}", size=5)
    assert page["total"] == 0, page
    print("[5] 关键词大小写不敏感命中 / 乱串 0 条")

    # [6] 真分页：size=2 两页不重叠、total 一致
    p0 = audit_query(token, category="NODE", keyword=NODE_NAME, page=0, size=2)
    p1 = audit_query(token, category="NODE", keyword=NODE_NAME, page=1, size=2)
    assert len(p0["items"]) == 2 and len(p1["items"]) >= 1
    assert p0["total"] == p1["total"] >= 5
    ids0 = {r["id"] for r in p0["items"]}
    ids1 = {r["id"] for r in p1["items"]}
    assert not (ids0 & ids1), "两页记录不得重叠"
    assert p0["items"][0]["createdAt"] >= p0["items"][-1]["createdAt"], "须按时间倒序"
    # result 过滤：PROXY 拒绝行只出现在 REJECTED
    ok = audit_query(token, category="PROXY", result="OK", keyword=f"nomatch-{MARK}")
    assert ok["total"] == 0, ok
    print(f"[6] 真分页 size=2 两页不重叠（total={p0['total']}）+ result 过滤生效")

    # 收尾：删规则专用节点（也会再写一条 NODE/DELETE，无妨）
    req("DELETE", f"/agent-nodes/{rnid}", token=token)
    print("\nCAP-71 审计日志 E2E 全部通过")


if __name__ == "__main__":
    main()
