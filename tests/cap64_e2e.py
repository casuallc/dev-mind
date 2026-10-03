#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-64 收藏夹 E2E（前置：后端已用新代码启动；见 tests/README.md「起独立实例跑 E2E」）。

覆盖验收闭环「建收藏→探测→挂账号→分享→接收方可见→撤销→404」：
1. FR-01 CRUD：建/改/删、http(s) 校验、同 URL 不拦截、关键词与分组子树筛选；
2. FR-04 探测：脚本自起本地 HTTP 端（127.0.0.1 私有段，默认 allow-private=true 才可达）——
   HEAD 200 → OK；HEAD 405 → 回落 GET 且带 Range: bytes=0-0；404 → FAIL；连接拒绝 → CONNECT；
3. FR-05 账号：密文入库、出参恒掩码、明文仅 /secret 按次取（非 owner 404）；
4. FR-07 分享：单条 + 分组（含后续新增自动进范围）、接收方只读（主端点 404）、密码字段剔除、
   复制为自己的、撤销后立即不可见、重复分享 409；
5. FR-08 归属：非 owner 直敲主端点一律 404（文案与「不存在」一致，不暴露存在性）。
6. FR-02/03 分组与标签：分组删除二档、标签筛选与删除只解关联。
7. FR-09 导入：结构化树 → 分组层级/备注/标签落库、非法地址跳过、重复导入幂等、
   他人同 URL 不阻塞、空树与超 8 层 400。

脚本自建分享接收方（username cap64-e2e-bob，密码每次随机重置），跑完清理本次创建的收藏/分组/标签。
运行产物写 tmp/（本脚本只留一个 http 端口的日志）。
"""
import http.server
import json
import os
import secrets
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import quote

# Windows 控制台默认 GBK，中文断言信息会乱码（不是数据问题）
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
BOB = "cap64-e2e-bob"
BOB_PW = "cap64-" + secrets.token_hex(8)
TS = str(int(time.time()))
MARK = "cap64-" + TS
PASSED = []


def req(method, path, body=None, token=None, expect=200):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}，期望 {expect}"
            return json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        if e.code == expect:
            try:
                return json.loads(text or "null")
            except json.JSONDecodeError:
                return text
        raise AssertionError(f"{method} {path} -> {e.code}（期望 {expect}）: {text[:400]}")


def login(username, password):
    r = req("POST", "/auth/login", {"username": username, "password": password})
    return r.get("accessToken") or r.get("token")


def ok(cond, what):
    assert cond, "断言失败：" + what
    PASSED.append(what)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class ProbeHandler(http.server.BaseHTTPRequestHandler):
    """伪装被测站点：/ok 正常、/nohead 不支持 HEAD、/gone 404。顺带记录 Range 头。"""

    seen_range = None

    def log_message(self, *a):
        pass

    def _respond(self, code, with_body):
        self.send_response(code)
        self.send_header("Content-Length", "0" if not with_body else "2")
        self.end_headers()
        if with_body:
            self.wfile.write(b"ok")

    def do_HEAD(self):
        path = self.path.split("?")[0]
        if path == "/nohead":
            self._respond(405, False)
        elif path == "/gone":
            self._respond(404, False)
        else:
            self._respond(200, False)

    def do_GET(self):
        ProbeHandler.seen_range = self.headers.get("Range")
        path = self.path.split("?")[0]
        self._respond(404 if path == "/gone" else 200, True)


def start_probe_server():
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ProbeHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, srv.server_address[1]


def main():
    admin = login("admin", "admin123")
    print("[0] 登录 admin OK")

    # 分享接收方：存在就重置密码，不存在就建（跑完不删，删除用户端点不存在）
    users = req("GET", "/auth/users", token=admin)
    bob_user = next((u for u in users if u.get("username") == BOB), None)
    if bob_user is None:
        bob_user = req("POST", "/auth/users",
                       {"username": BOB, "displayName": "CAP-64 E2E 接收方",
                        "password": BOB_PW, "role": "DEVELOPER"}, token=admin)
    else:
        req("POST", f"/auth/users/{bob_user['id']}/reset-password", {"password": BOB_PW}, token=admin)
    bob = login(BOB, BOB_PW)
    print(f"[0] 接收方 {BOB} 就绪")

    srv, port = start_probe_server()
    base = f"http://127.0.0.1:{port}"
    created_bookmarks, created_groups, created_tags = [], [], []

    try:
        # ---------- FR-01 CRUD ----------
        bm = req("POST", "/bookmarks",
                 {"title": f"  E2E 控制台 {TS}  ", "url": f"  {base}/ok  ",
                  "description": "E2E 建的入口"},
                 token=admin)
        bid = bm["id"]
        created_bookmarks.append(bid)
        ok(bm["title"] == f"E2E 控制台 {TS}", "标题去空格")
        ok(bm["url"] == f"{base}/ok", "URL 去空格")
        ok(bm["lastStatus"] == "UNKNOWN", "新建默认未探测")

        req("POST", "/bookmarks", {"title": "坏地址", "url": "file:///etc/passwd"},
            token=admin, expect=400)
        ok(True, "非 http/https 地址被拒（400）")

        dup_url = req("PUT", f"/bookmarks/{bid}", {"title": bm["title"], "url": f"{base}/ok"},
                      token=admin)
        ok(dup_url["url"] == f"{base}/ok", "同 URL 不拦截（编辑回自身）")
        req("DELETE", f"/bookmarks/{bid}", token=admin)
        created_bookmarks.remove(bid)
        ok(True, "删除收藏 OK")

        # ---------- FR-04 探测 ----------
        bm = req("POST", "/bookmarks", {"title": f"E2E 探测 {TS}", "url": f"{base}/ok"}, token=admin)
        bid = bm["id"]
        created_bookmarks.append(bid)
        r = req("POST", f"/bookmarks/{bid}/probe", token=admin)
        ok(r["status"] == "OK" and r["statusCode"] == "200", "HEAD 200 → OK/200")
        ok(r["latencyMs"] is not None and r["checkedAt"], "探测落延迟与时间")
        got = req("GET", f"/bookmarks/{bid}", token=admin)
        ok(got["lastStatus"] == "OK" and got["lastCheckedAt"], "探测结果已落库")

        nb = req("POST", "/bookmarks", {"title": f"E2E 无 HEAD {TS}", "url": f"{base}/nohead"}, token=admin)
        created_bookmarks.append(nb["id"])
        rn = req("POST", f"/bookmarks/{nb['id']}/probe", token=admin)
        ok(rn["status"] == "OK", "HEAD 405 → 回落 GET 成功")
        ok(ProbeHandler.seen_range == "bytes=0-0", "回落 GET 带 Range: bytes=0-0（不取正文）")

        gb = req("POST", "/bookmarks", {"title": f"E2E 404 {TS}", "url": f"{base}/gone"}, token=admin)
        created_bookmarks.append(gb["id"])
        rg = req("POST", f"/bookmarks/{gb['id']}/probe", token=admin)
        ok(rg["status"] == "FAIL" and rg["statusCode"] == "404", "站点活着但入口 404 → FAIL/404")

        dead = req("POST", "/bookmarks",
                   {"title": f"E2E 端口关闭 {TS}", "url": f"http://127.0.0.1:{free_port()}/x"}, token=admin)
        created_bookmarks.append(dead["id"])
        rd = req("POST", f"/bookmarks/{dead['id']}/probe", token=admin)
        ok(rd["status"] == "FAIL" and rd["statusCode"] == "CONNECT", "连接拒绝 → FAIL/CONNECT")

        acc = req("POST", "/bookmarks/probe-batch", {"ids": [int(bid), int(nb["id"])]},
                  token=admin, expect=202)
        ok(acc["accepted"] == 2, "批量探测受理 2 条（202）")
        req("POST", "/bookmarks/probe-batch", {"ids": []}, token=admin, expect=400)
        ok(True, "批量探测空列表被拒（400）")

        # ---------- FR-05 账号 ----------
        secret_pw = "P@ss-" + secrets.token_hex(4)
        upd = req("PUT", f"/bookmarks/{bid}",
                  {"title": f"E2E 探测 {TS}", "url": f"{base}/ok",
                   "accounts": [{"label": "管理员", "username": "admin", "password": secret_pw,
                                 "note": "生产"}]},
                  token=admin)
        a = upd["accounts"][0]
        aid = a["id"]
        ok(a["label"] == "管理员" and a["username"] == "admin", "账号 label/username 回显")
        ok(a["passwordMasked"] == "******" and a["hasPassword"], "出参密码掩码")
        ok(secret_pw not in json.dumps(upd), "出参不泄漏明文")

        sec = req("GET", f"/bookmarks/{bid}/accounts/{aid}/secret", token=admin)
        ok(sec["password"] == secret_pw, "明文仅经 /secret 按次取")

        # 密码留空 = 不修改
        upd2 = req("PUT", f"/bookmarks/{bid}",
                   {"title": f"E2E 探测 {TS}", "url": f"{base}/ok",
                    "accounts": [{"id": aid, "label": "管理员账号", "username": "admin"}]},
                   token=admin)
        ok(upd2["accounts"][0]["label"] == "管理员账号", "账号整组提交可改 label")
        ok(req("GET", f"/bookmarks/{bid}/accounts/{aid}/secret", token=admin)["password"] == secret_pw,
           "密码留空 = 保持原密码")

        # ---------- FR-08 归属 ----------
        req("GET", f"/bookmarks/{bid}", token=bob, expect=404)
        req("PUT", f"/bookmarks/{bid}", {"title": "劫持", "url": f"{base}/ok"}, token=bob, expect=404)
        req("DELETE", f"/bookmarks/{bid}", token=bob, expect=404)
        req("GET", f"/bookmarks/{bid}/accounts/{aid}/secret", token=bob, expect=404)
        req("POST", f"/bookmarks/{bid}/probe", token=bob, expect=404)
        ok(True, "非 owner 直敲主端点全部 404")
        err = req("GET", f"/bookmarks/{bid}", token=bob, expect=404)
        # ensure_ascii=False：默认转义会让中文断言恒不命中（脚本坑，不是接口问题）
        ok("收藏不存在" in json.dumps(err, ensure_ascii=False),
           "越权文案与「不存在」一致（不暴露存在性）")
        ok(req("GET", "/bookmarks", token=bob) == [], "他人列表看不到")

        # ---------- FR-03 标签 ----------
        tag = req("POST", "/bookmark-tags", {"name": f"e2e标签{TS}"}, token=admin)
        created_tags.append(tag["id"])
        # 注意：账号是**整组提交**（未出现的即删除），所以这里必须把已有账号一起带上
        req("PUT", f"/bookmarks/{bid}",
            {"title": f"E2E 探测 {TS}", "url": f"{base}/ok", "tagIds": [tag["id"]],
             "accounts": [{"id": aid, "label": "管理员账号", "username": "admin"}]}, token=admin)
        ok(len(req("GET", f"/bookmarks?tagIds={tag['id']}", token=admin)) == 1, "按标签筛选命中")
        ok(len(req("GET", f"/bookmarks?keyword={TS}", token=admin)) >= 1, "关键词命中标题")
        ok(req("POST", "/bookmark-tags", {"name": f"e2e标签{TS}"}, token=admin)["id"] == tag["id"],
           "同名标签创建幂等")
        req("DELETE", f"/bookmark-tags/{tag['id']}", token=admin)
        created_tags.remove(tag["id"])
        ok(req("GET", f"/bookmarks/{bid}", token=admin)["url"] == f"{base}/ok", "删标签不动收藏")

        # ---------- FR-09 浏览器书签导入（服务端契约 = 结构化 JSON 树） ----------
        import_tree = {"nodes": [
            {"type": "folder", "name": f"e2e导入{TS}", "children": [
                {"type": "bookmark", "title": f"E2E 导入A {TS}", "url": f"{base}/import-a",
                 "description": "导入备注", "tags": [f"e2e导入标签{TS}"]},
                {"type": "folder", "name": "子目录", "children": [
                    {"type": "bookmark", "title": f"E2E 导入B {TS}", "url": "https://b.example.com/x"}]},
            ]},
            {"type": "bookmark", "title": f"E2E 导入散落 {TS}", "url": "https://loose.example.com"},
            {"type": "bookmark", "title": "坏地址", "url": "javascript:alert(1)"},
        ]}
        r = req("POST", "/bookmarks/import", import_tree, token=admin)
        ok(r["createdBookmarks"] == 3 and r["createdGroups"] == 2, f"导入新建 3 收藏 2 分组（实际 {r}）")
        ok(r["skippedInvalid"] == 1, "非法地址跳过并计数")

        gtree = req("GET", "/bookmark-groups", token=admin)
        g_import = next(x for x in gtree if x["name"] == f"e2e导入{TS}")
        created_groups.append(g_import["id"])
        ok(g_import["children"][0]["name"] == "子目录", "导入保留文件夹层级")
        sub_id = g_import["children"][0]["id"]

        imported = req("GET", f"/bookmarks?keyword={quote('E2E 导入')}", token=admin)
        ok(len(imported) == 3, "导入的 3 条收藏可按关键词查到")
        created_bookmarks.extend(b["id"] for b in imported)
        b_a = next(b for b in imported if b["title"] == f"E2E 导入A {TS}")
        ok(b_a["groupId"] == g_import["id"], "导入书签落对应分组")
        ok(b_a["description"] == "导入备注", "备注（DD）落 description")
        ok(any(t["name"] == f"e2e导入标签{TS}" for t in b_a["tags"]), "Firefox TAGS 映射为标签")
        ok(next(b for b in imported if b["title"] == f"E2E 导入B {TS}")["groupId"] == sub_id,
           "子目录书签落子分组")
        ok(next(b for b in imported if b["title"] == f"E2E 导入散落 {TS}")["groupId"] is None,
           "松散书签落未分组")
        tag_import = next(t for t in req("GET", "/bookmark-tags", token=admin)
                          if t["name"] == f"e2e导入标签{TS}")
        created_tags.append(tag_import["id"])

        # 重复导入幂等：分组复用、同 URL 全跳过
        r2 = req("POST", "/bookmarks/import", import_tree, token=admin)
        ok(r2["createdBookmarks"] == 0 and r2["createdGroups"] == 0, "重复导入不重建分组/收藏")
        ok(r2["skippedDuplicates"] == 3 and r2["skippedInvalid"] == 1, "重复导入同 URL 全部跳过")

        # 归属隔离：bob 收藏同 URL 与 admin 互不干扰
        r3 = req("POST", "/bookmarks/import", {"nodes": [
            {"type": "bookmark", "title": "bob 的同名", "url": "https://loose.example.com"}]}, token=bob)
        ok(r3["createdBookmarks"] == 1, "他人已有同 URL 不阻塞我的导入")
        bob_copy = req("GET", "/bookmarks?keyword=bob", token=bob)
        for b in bob_copy:
            req("DELETE", f"/bookmarks/{b['id']}", token=bob)

        # 上限与空树
        req("POST", "/bookmarks/import", {"nodes": []}, token=admin, expect=400)
        ok(True, "空导入树被拒（400）")
        deep = {"type": "bookmark", "title": "deep", "url": "https://deep.example.com"}
        for i in range(9):
            deep = {"type": "folder", "name": f"d{i}", "children": [deep]}
        req("POST", "/bookmarks/import", {"nodes": [deep]}, token=admin, expect=400)
        ok(True, "文件夹超 8 层整体 400")

        # ---------- FR-02 分组 ----------
        g = req("POST", "/bookmark-groups", {"name": f"e2e环境{TS}"}, token=admin)
        created_groups.append(g["id"])
        child = req("POST", "/bookmark-groups", {"name": f"e2e测试{TS}", "parentId": g["id"]}, token=admin)
        created_groups.append(child["id"])
        inbox = req("POST", "/bookmarks",
                    {"title": f"E2E 组内 {TS}", "url": f"{base}/ok", "groupId": child["id"]}, token=admin)
        created_bookmarks.append(inbox["id"])
        ok(len(req("GET", f"/bookmarks?groupId={g['id']}", token=admin)) >= 1,
           "点父分组看到子树内收藏")
        tree = req("GET", "/bookmark-groups", token=admin)
        root = next(x for x in tree if x["id"] == g["id"])
        ok(root["children"][0]["id"] == child["id"], "分组树按父子组装")

        # 拖拽换父（M4）：前端落点 = PUT 带原名只改 parentId；成环校验在改父时兜底
        req("PUT", f"/bookmark-groups/{g['id']}",
            {"name": g["name"], "parentId": child["id"]}, token=admin, expect=400)
        ok(True, "拖父分组进自己的子树被拒（400）")
        moved = req("PUT", f"/bookmark-groups/{child['id']}",
                    {"name": child["name"], "parentId": None}, token=admin)
        ok(moved["parentId"] is None, "子分组可拖到顶级")
        req("PUT", f"/bookmark-groups/{child['id']}",
            {"name": child["name"], "parentId": g["id"]}, token=admin)
        tree2 = req("GET", "/bookmark-groups", token=admin)
        ok(next(x for x in tree2 if x["id"] == g["id"])["children"][0]["id"] == child["id"],
           "拖回后父子关系还原")

        # ---------- FR-07 分享 ----------
        gid = None
        gtree = req("GET", "/bookmark-groups", token=admin)
        if gtree:
            gid = gtree[0]["id"]
        share = req("POST", "/bookmark-shares", {"bookmarkId": int(bid), "targetUser": BOB}, token=admin)
        share_id = share["id"]
        ok(share["targetUser"] == BOB, "分享单条收藏给指定用户")
        req("POST", "/bookmark-shares", {"bookmarkId": int(bid), "targetUser": BOB}, token=admin, expect=409)
        ok(True, "重复分享同一条 → 409")
        req("POST", "/bookmark-shares", {"bookmarkId": int(bid), "targetUser": "no-such-user"},
            token=admin, expect=404)
        ok(True, "分享给不存在的用户 → 404")

        # 分组分享（含子分组）
        gshare = req("POST", "/bookmark-shares", {"groupId": g["id"], "targetUser": BOB}, token=admin)

        seen = req("GET", "/bookmarks/shared-with-me", token=bob)
        mine = [x for x in seen if x["owner"] == "admin"]
        ok(len(mine) == 1, "接收方看到分享者分组视图")
        titles = [b["title"] for b in mine[0]["bookmarks"]]
        ok(f"E2E 探测 {TS}" in titles, "单条分享在接收方可见")
        ok(f"E2E 组内 {TS}" in titles, "分组分享含子分组内的收藏")
        shared_bm = next(b for b in mine[0]["bookmarks"] if b["title"] == f"E2E 探测 {TS}")
        ok(shared_bm["accounts"] and shared_bm["accounts"][0]["username"] == "admin",
           "账号 label/username 随分享可见")
        ok("passwordMasked" not in shared_bm["accounts"][0], "密码字段在分享视图里整体剔除")
        ok(shared_bm["accounts"][0]["hasPassword"] is True, "但如实告知存在密码")
        ok(all(x["name"].startswith("e2e") for x in mine[0]["groups"]), "接收方看到分享分组树")

        # 源变更实时反映
        req("PUT", f"/bookmarks/{bid}",
            {"title": f"E2E 探测改名 {TS}", "url": f"{base}/ok"}, token=admin)
        seen2 = req("GET", "/bookmarks/shared-with-me", token=bob)
        ok(f"E2E 探测改名 {TS}" in [b["title"] for b in seen2[0]["bookmarks"]], "源改名实时反映给接收方")

        # 新增收藏自动进分享范围
        added = req("POST", "/bookmarks",
                    {"title": f"E2E 后加 {TS}", "url": f"{base}/ok", "groupId": child["id"]}, token=admin)
        created_bookmarks.append(added["id"])
        seen3 = req("GET", "/bookmarks/shared-with-me", token=bob)
        ok(f"E2E 后加 {TS}" in [b["title"] for b in seen3[0]["bookmarks"]], "新增收藏自动进分享范围")

        # 复制为自己的
        copy = req("POST", "/bookmarks/shared-with-me/copy", {"bookmarkId": int(bid)}, token=bob)
        ok(copy["id"] != bid and copy["title"] == f"E2E 探测改名 {TS}", "接收方可复制为自己的")
        ok(copy["accounts"] == [], "副本不含账号密码")
        req("DELETE", f"/bookmarks/{copy['id']}", token=bob)
        ok(True, "副本归接收方所有（可自行删除）")

        # 撤销：单条 + 分组一起撤
        req("DELETE", f"/bookmark-shares/{share_id}", token=admin)
        req("DELETE", f"/bookmark-shares/{gshare['id']}", token=admin)
        after = req("GET", "/bookmarks/shared-with-me", token=bob)
        ok(all(x["owner"] != "admin" for x in after), "撤销后接收方立即不可见")
        req("GET", f"/bookmarks/{bid}", token=bob, expect=404)
        ok(True, "撤销后主端点仍 404（分享从不放行主端点）")

        # ---------- FR-02 分组删除二档 ----------
        loose = req("POST", "/bookmarks",
                    {"title": f"E2E 根内 {TS}", "url": f"{base}/ok", "groupId": g["id"]}, token=admin)
        created_bookmarks.append(loose["id"])
        req("DELETE", f"/bookmark-groups/{g['id']}?cascade=false", token=admin)
        created_groups = [x for x in created_groups if x != g["id"]]
        left = req("GET", f"/bookmarks?keyword={quote('E2E 根内')}", token=admin)
        ok(len(left) == 1 and left[0]["groupId"] is None, "默认档：被删组自己的收藏落未分组")
        inside = req("GET", f"/bookmarks?keyword={quote('E2E 组内')}", token=admin)
        ok(len(inside) == 1 and inside[0]["groupId"] == child["id"],
           "存活子分组里的收藏原地不动")
        tree2 = req("GET", "/bookmark-groups", token=admin)
        ok(any(x["id"] == child["id"] for x in tree2), "子分组上提为根")
        req("DELETE", f"/bookmark-groups/{child['id']}?cascade=true", token=admin)
        created_groups.remove(child["id"])
        ok(req("GET", f"/bookmarks?keyword={quote('E2E 组内')}", token=admin) == [],
           "级联档：整棵子树连收藏一起删")
        created_bookmarks = [x for x in created_bookmarks
                             if x not in (inbox["id"], added["id"], loose["id"])]

        print(f"\n[结果] {len(PASSED)} 项断言全部通过")
    finally:
        srv.shutdown()
        for b in created_bookmarks:
            try:
                req("DELETE", f"/bookmarks/{b}", token=admin)
            except Exception as e:
                print(f"    清理收藏 {b} 失败：{e}")
        for g in created_groups:
            try:
                req("DELETE", f"/bookmark-groups/{g}?cascade=true", token=admin)
            except Exception as e:
                print(f"    清理分组 {g} 失败：{e}")
        for t in created_tags:
            try:
                req("DELETE", f"/bookmark-tags/{t}", token=admin)
            except Exception as e:
                print(f"    清理标签 {t} 失败：{e}")
        print("[清理] 本次创建的收藏/分组/标签已删除（接收方用户 %s 保留）" % BOB)


if __name__ == "__main__":
    main()
