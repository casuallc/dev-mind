# -*- coding: utf-8 -*-
"""CAP-03 文档管理端到端验证：建档/模板/多版本/diff/回退/状态机/检索/git 同步/push。
运行前提：后端已起（:8080），DB 干净，docs-repo 为空。"""
import json
import pathlib
import sys
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://localhost:8080/api"
DOCS_REPO = pathlib.Path(r"D:/apusic/devmind-docs")
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


V1 = "# 需求文档 ALPHA\n\n## 背景\n标识词ALPHAUNIQUE。\n\n## 验收\n完成。"
V2 = "# 需求文档 BETA\n\n## 背景\n标识词BETAUNIQUE。\n\n## 变更\n新增评审环节。"
TPL_TITLE = "模板生成的需求"

# ---------- 1. 模板（FR-07） ----------
st, tpl = call("GET", "/documents/templates")
check("模板列表 4 类", st == 200 and len(tpl) == 4, str(len(tpl) if tpl else 0))

# ---------- 2. 新建（FR-01）+ 模板预填 ----------
st, d = call("POST", "/documents", {"kind": "requirement", "title": TPL_TITLE, "template": "requirement"})
did = d.get("id") if d else None
check("套模板建档 v1", st == 200 and d.get("versionNo") == 1 and d.get("status") == "draft", f"{st} {d}")
check("模板内容已预填", (d.get("contentMd") or "").startswith("# 需求文档"), "模板未生效")
check("git commit sha 已记录", bool(d.get("commitSha")), str(d.get("commitSha")))

# ---------- 3. git 文件镜像（FR-05） ----------
fp = DOCS_REPO / "requirements" / "_" / "模板生成的需求.md"
check("docs-repo 文件已写", fp.exists(), str(fp))
on_disk = fp.read_text(encoding="utf-8", errors="replace") if fp.exists() else ""
check("git 文件内容 = v1", on_disk.startswith("# 需求文档"), "文件内容不符")

# ---------- 4. 自建内容文档：多版本 + diff + 回退 ----------
st, d2 = call("POST", "/documents", {"kind": "design", "title": "登录改造方案", "projectId": "default",
                                     "requirementId": "REQ-1", "contentMd": V1})
id2 = d2.get("id") if d2 else None
check("自建 design 文档 v1", st == 200 and d2.get("status") == "draft" and d2.get("projectId") == "default", f"{st}")

st, v2 = call("POST", f"/documents/{id2}/versions", {"contentMd": V2, "changeNote": "补评审环节"})
check("保存 v2 成功", st == 200 and v2.get("versionNo") == 2, f"{st} {v2}")
st, vs = call("GET", f"/documents/{id2}/versions")
check("版本列表 2 条降序", st == 200 and len(vs) == 2 and vs[0]["versionNo"] == 2, str(vs))

st, dif = call("GET", f"/documents/{id2}/versions/1/diff")
check("diff v1→当前 有差异", st == 200 and dif.get("hasChanges") and dif.get("additions", 0) > 0, str(dif))
joined = "\n".join(dif.get("lines", []))
check("diff 行含 +BETA 与 -ALPHA", "-标识词ALPHAUNIQUE" in joined and "+标识词BETAUNIQUE" in joined, joined[:200])

st, rv = call("POST", f"/documents/{id2}/versions/1/revert")
check("回退 v1 → 新 v3", st == 200 and rv.get("versionNo") == 3, f"{st} {rv}")
st, cur = call("GET", f"/documents/{id2}")
check("回退后内容 = v1", "标识词ALPHAUNIQUE" in (cur.get("contentMd") or "") and "BETAUNIQUE" not in (cur.get("contentMd") or ""), "回退内容错误")
st, bad = call("POST", f"/documents/{id2}/versions/3/revert")
check("回退到当前版本 → 400", st == 400, f"{st}")

# ---------- 5. 检索（FR-06） ----------
st, sr = call("GET", "/documents/search?q=ALPHAUNIQUE")
check("检索内容命中 v1 文档", any(x.get("id") == id2 for x in (sr or [])), str(sr))
st, sr = call("GET", "/documents/search?q=登录改造")
check("检索标题命中", any(x.get("id") == id2 for x in (sr or [])), str(sr))

# ---------- 6. 状态机（FR-04） ----------
st, r = call("POST", f"/documents/{id2}/status", {"action": "freeze"})
check("草稿直接冻结 → 409", st == 409, f"{st}")
st, r = call("POST", f"/documents/{id2}/status", {"action": "submit"})
check("提交确认 → pending_confirm", st == 200 and r.get("status") == "pending_confirm", f"{st} {r}")
st, r = call("POST", f"/documents/{id2}/status", {"action": "freeze"})
check("冻结 → frozen", st == 200 and r.get("status") == "frozen", f"{st} {r}")
st, r = call("POST", f"/documents/{id2}/versions", {"contentMd": "# 未说明的变更"})
check("冻结保存无变更说明 → 400", st == 400, f"{st}")
st, r = call("POST", f"/documents/{id2}/versions", {"contentMd": "# 已说明的变更\n冻结后新版本", "changeNote": "冻结后基线调整"})
check("冻结带说明保存 → v4", st == 200 and r.get("versionNo") == 4, f"{st} {r}")
st, r = call("POST", f"/documents/{id2}/status", {"action": "unfreeze"})
check("解除冻结 → draft", st == 200 and r.get("status") == "draft", f"{st} {r}")

# ---------- 7. 删除 + git 同步 ----------
st, deldoc = call("DELETE", f"/documents/{id2}")
check("删除文档", st == 200, f"{st}")
fp2 = DOCS_REPO / "designs" / "REQ-1" / "default" / "登录改造方案.md"
check("git 文件已随删除移除", not fp2.exists(), str(fp2))

# ---------- 8. push / repo 信息（FR-05） ----------
st, pu = call("POST", "/documents/push")
check("无远端 push 提示", st == 200 and "remote" in (pu.get("message") or ""), str(pu))
st, ri = call("GET", "/documents/repo")
check("repo 信息 headSha 非空", st == 200 and bool(ri.get("headSha")), str(ri))
check("docs-repo 有提交历史", (DOCS_REPO / ".git").exists(), "无 .git")

# ---------- 清理：删除模板文档 ----------
call("DELETE", f"/documents/{did}")

print(f"\n== CAP-03 验证结果: {passed} passed, {failed} failed ==")
sys.exit(1 if failed else 0)
