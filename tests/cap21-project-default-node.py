#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""项目默认执行节点 E2E：PUT 设置/保持/清除 + 会话创建继承项目默认 + 显式指定优先。
前置：后端已在 :8080 运行（--devmind.session.executor=fake）。"""
import json, sys, time, urllib.request, urllib.error

BASE = "http://localhost:8080/api"

def req(method, path, body=None, token=None, expect=200):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token: r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        if e.code == expect:
            return json.loads(e.read().decode() or "null")
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:300]}")

def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login["token"] if "token" in login else login["accessToken"]
    print("[1] 登录 OK")

    projects = req("GET", "/projects", token=tok)
    proj = next((p for p in projects if p.get("status") == "ACTIVE"), None)
    assert proj, "没有 ACTIVE 项目可测"
    pid = proj["id"]
    print(f"[2] 用项目 {proj['name']} ({pid})，原默认节点={proj.get('agentNodeId')}")

    issued = req("POST", "/agent-nodes", {"name": f"e2e-pnode-{int(time.time())}"}, tok)
    node = issued.get("node") or issued
    node_id = str(node["id"])
    print(f"[3] 节点已注册（离线）id={node_id}")

    sid = None
    try:
        # 4. 设置默认节点 → GET 回显
        req("PUT", f"/projects/{pid}", {"name": proj["name"], "agentNodeId": node_id}, tok)
        got = req("GET", f"/projects/{pid}", token=tok)
        assert got.get("agentNodeId") == node_id, f"回显不符: {got.get('agentNodeId')}"
        print("[4] PUT 设置默认节点 OK，GET 回显一致")

        # 5. 不传 agentNodeId 的 PUT → 保持不变
        req("PUT", f"/projects/{pid}", {"name": proj["name"], "description": "e2e-keep"}, tok)
        got = req("GET", f"/projects/{pid}", token=tok)
        assert got.get("agentNodeId") == node_id, f"未传字段被清掉: {got.get('agentNodeId')}"
        print("[5] PUT 省略 agentNodeId -> 保持不变 OK")

        # 6. 只传 projectId 建会话 → 继承项目默认节点（离线）→ 409
        e = req("POST", "/sessions", {"taskSpec": "e2e-pdefault", "projectId": pid}, tok, expect=409)
        assert node_id in json.dumps(e, ensure_ascii=False) or "节点" in json.dumps(e, ensure_ascii=False), \
            f"409 信息未指向节点: {e}"
        print(f"[6] 继承项目默认节点（离线）-> 409 OK ({str(e.get('message',''))[:60]})")

        # 7. 显式指定优先于项目默认：传一个不存在节点，错误应指向它而非项目默认
        e = req("POST", "/sessions", {"taskSpec": "e2e-pdefault", "projectId": pid, "agentNodeId": "999999"},
                tok, expect=409)
        assert "999999" in json.dumps(e), f"显式节点未优先: {e}"
        print("[7] 显式 agentNodeId 优先于项目默认 OK")

        # 8. 空串清除 → GET 为空
        req("PUT", f"/projects/{pid}", {"name": proj["name"], "agentNodeId": ""}, tok)
        got = req("GET", f"/projects/{pid}", token=tok)
        assert not got.get("agentNodeId"), f"清除失败: {got.get('agentNodeId')}"
        print("[8] PUT 空串清除默认节点 OK")

        # 9. 无默认节点 → 本地会话零回归（fake executor）
        s = req("POST", "/sessions", {"taskSpec": "e2e-local-zero-regression", "projectId": pid}, tok)
        sid = s["id"]
        assert not s.get("agentNodeId"), f"不应有 agentNodeId: {s.get('agentNodeId')}"
        print(f"[9] 无默认 -> 本地会话创建 OK id={sid}")

        # 清理会话与描述
        req("DELETE", f"/sessions/{sid}", token=tok)
        req("PUT", f"/projects/{pid}", {"name": proj["name"], "description": proj.get("description") or ""}, tok)
    finally:
        req("DELETE", f"/agent-nodes/{node_id}", token=tok)
    print("[10] 清理 OK")
    print("\n== E2E 全部通过 ==")

if __name__ == "__main__":
    main()
