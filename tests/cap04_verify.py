# -*- coding: utf-8 -*-
"""CAP-04 知识库端到端验证：FR-01~FR-08 + 真实会话注入。
运行前提：后端已起（:8080），DB 干净。"""
import json
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://localhost:8080/api"
passed = 0
failed = 0


def call(method, path, body=None):
    url = quote(BASE + path, safe=":/?&=%,.-")
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def names(entries):
    return [e.get("name") for e in entries]


# ---------- 0. 项目 tags 设 java（FR-03 匹配依据） ----------
st, proj = call("PUT", "/projects/default", {
    "name": "playground", "path": "D:/apusic/devmind-playground",
    "defaultBranch": "master", "tags": ["java"], "status": "ACTIVE"})
check("设置 default 项目 tags=[java]", st == 200 and "java" in (proj or {}).get("tags", []), f"{st} {proj}")

# ---------- 1. 造 4 条条目 ----------
g1 = call("POST", "/knowledge/entries", {"scope": "global", "name": "通用-无标签经验",
                                         "contentMd": "# 通用经验\n无论什么项目都用得上。", "tags": [], "status": "active"})
g2 = call("POST", "/knowledge/entries", {"scope": "global", "name": "Java 命名规范",
                                         "contentMd": "类名用 PascalCase，方法名用 camelCase。", "tags": ["java"], "status": "active"})
g3 = call("POST", "/knowledge/entries", {"scope": "global", "name": "前端样式规范",
                                         "contentMd": "样式统一用 AntD Token。", "tags": ["frontend"], "status": "active"})
p1 = call("POST", "/knowledge/entries", {"scope": "project", "projectId": "default", "name": "playground 特有经验",
                                         "contentMd": "本项目用 Maven 多模块。", "tags": [], "status": "active"})
check("创建 4 条条目成功", st and g1[0] == 200 and g2[0] == 200 and g3[0] == 200 and p1[0] == 200,
      f"g1={g1[0]} g2={g2[0]} g3={g3[0]} p1={p1[0]}")
g1_id, g2_id, g3_id, p1_id = g1[1]["id"], g2[1]["id"], g3[1]["id"], p1[1]["id"]

# ---------- 2. 预览（FR-04）+ 标签过滤（FR-03） ----------
st, prev = call("GET", "/knowledge/preview?projectId=default&taskSpec=给项目加个功能")
used = names(prev["entriesUsed"]) if prev else []
check("项目预览命中 G1/G2/P1", "通用-无标签经验" in used and "Java 命名规范" in used and "playground 特有经验" in used, str(used))
check("项目预览排除不匹配标签 G3", "前端样式规范" not in used, str(used))
content = prev["content"] if prev else ""
check("预览内容含任务段", "## 当前任务" in content and "给项目加个功能" in content, "无任务段")
check("预览内容含全局/项目标题", "通用经验（global）" in content and "项目经验（project）" in content, "无标题")

st, prev0 = call("GET", "/knowledge/preview?taskSpec=t")
used0 = names(prev0["entriesUsed"]) if prev0 else []
check("空项目预览仅含无标签全局条目", used0 == ["通用-无标签经验"], str(used0))

# ---------- 3. 条目 CRUD + 检索（FR-02/FR-08） ----------
st, lst = call("GET", "/knowledge/entries?scope=global")
check("scope=global 列表 3 条", st == 200 and len(lst) == 3, str(len(lst) if lst else 0))
st, r = call("GET", "/knowledge/entries/search?q=Java&projectId=default")
check("检索 q=Java 命中 G2", st == 200 and "Java 命名规范" in names(r), str(names(r) if r else []))
st, r = call("PUT", f"/knowledge/entries/{g2_id}", {"name": "Java 命名规范(修订)"})
check("更新条目名称", st == 200 and r["name"] == "Java 命名规范(修订)", str(r))
st, r = call("GET", f"/knowledge/entries/{g2_id}")
check("按 id 获取更新后名称", st == 200 and r["name"] == "Java 命名规范(修订)", str(r))
st, r = call("DELETE", f"/knowledge/entries/{g3_id}")
check("删除 G3", st == 200)
st, r = call("GET", f"/knowledge/entries/{g3_id}")
check("删除后 GET 404", st == 404, f"{st}")

# ---------- 4. 提案流转（FR-05/FR-06） ----------
st, pr1 = call("POST", "/knowledge/proposals", {"title": "提案-项目A", "contentMd": "某项目经验 A",
                                                "targetScope": "project", "targetProjectId": "default",
                                                "sourceSessionId": "sess_test"})
st2, pr2 = call("POST", "/knowledge/proposals", {"title": "提案-全局B", "contentMd": "某全局经验 B",
                                                 "targetScope": "global"})
st3, pr3 = call("POST", "/knowledge/proposals", {"title": "提案-拒绝C", "contentMd": "不该入库"})
check("创建 3 个提案 open", st == 200 and st2 == 200 and st3 == 200
      and pr1["status"] == "open" and pr2["status"] == "open" and pr3["status"] == "open",
      f"{st} {st2} {st3}")

# 提案创建 → P2 通知（静默进中心）
st, notifs = call("GET", "/notifications?limit=100")
kinds = [n.get("eventType") for n in (notifs or [])]
check("提案创建产生 KNOWLEDGE_PROPOSAL 通知", "KNOWLEDGE_PROPOSAL" in kinds, str(kinds))

# 采纳到项目
st, res = call("POST", f"/knowledge/proposals/{pr1['id']}/adopt?target=project&projectId=default")
check("采纳到项目 → adopted", st == 200 and res["status"] == "adopted" and res["adoptedTo"] == "project", str(res))
st, lst = call("GET", "/knowledge/entries?scope=project")
check("采纳产生 project 条目", any(e["name"] == "提案-项目A" for e in (lst or [])), str(names(lst or [])))
# 采纳到全局
st, res = call("POST", f"/knowledge/proposals/{pr2['id']}/adopt?target=global")
check("采纳到全局 → adopted/global", st == 200 and res["status"] == "adopted" and res["adoptedTo"] == "global", str(res))
st, lst = call("GET", "/knowledge/entries?scope=global")
check("晋升产生 global 条目", any(e["name"] == "提案-全局B" for e in (lst or [])), str(names(lst or [])))
# 拒绝
st, res = call("POST", f"/knowledge/proposals/{pr3['id']}/reject")
check("拒绝 → rejected", st == 200 and res["status"] == "rejected", str(res))
# 重复处理冲突
st, res = call("POST", f"/knowledge/proposals/{pr1['id']}/adopt?target=global")
check("重复采纳 → 409", st == 409, f"{st}")

# ---------- 5. 真实会话注入 ----------
# 已移除（2026-09-21，随 CAP-34 遗留清理）：CAP-34 起服务端零执行，会话一律下 runner 节点工作区，
# SessionView.worktreePath 恒为 null → 本段「读服务端 worktree 里的注入文件」的断言语义已不存在
# （且本脚本不起 runner，永远拿不到物化产物；运行还会误在默认节点上起真实会话）。
# hitCount（FR-07）同样依赖真实注入，一并移出。现行覆盖：
#   - 会话上下文快照 / 拉包内容 / 预览不 bumpHits → tests/cap33_verify.py [2][3][4]
#   - 工作区物化文件（CLAUDE.local.md、settings.local.json、平台路径不进 git）→ tests/e2e-cap42.py [2b]

print(f"\n== CAP-04 验证结果: {passed} passed, {failed} failed ==")
sys.exit(1 if failed else 0)
