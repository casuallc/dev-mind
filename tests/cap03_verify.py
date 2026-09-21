# -*- coding: utf-8 -*-
"""CAP-03 文档管理端到端验证：建档/模板/多版本/diff/回退/状态机/检索/删除。
正文存 DB 单副本（FR-05），无外部仓库、无本机路径依赖。
运行前提：后端已起（:8080；写操作需鉴权，脚本自带登录）。
用法: python tests/cap03_verify.py [baseUrl] [username] [password]"""
import json
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/") + "/api"
USER = sys.argv[2] if len(sys.argv) > 2 else "admin"
PWD = sys.argv[3] if len(sys.argv) > 3 else "admin123"
ROOT = pathlib.Path(r"D:/apusic/dev-mind")
REPO = ROOT / "tmp" / "cap03-repo"   # 归属用例用的临时本地 git 仓库（运行产物落 tmp/）
PROJ_NAME = "CAP03 文档测试"
passed = 0
failed = 0


def call(method, path, body=None, token=None):
    url = quote(BASE + path, safe=":/?&=%,.-")
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
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


def rows(x):
    return x if isinstance(x, list) else []


def obj(x):
    return x if isinstance(x, dict) else {}


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def git(*args):
    return subprocess.run(["git", *args], cwd=str(REPO), capture_output=True, text=True)


def ensure_repo():
    """临时本地 git 仓库（项目归属用例的入参，validateRepo 要求真仓库）。"""
    if not (REPO / ".git").exists():
        REPO.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "init", "-b", "master"], cwd=str(REPO), capture_output=True)
    if git("rev-parse", "HEAD").returncode != 0:
        (REPO / "README.md").write_text("# cap03 临时仓库\n", encoding="utf-8")
        git("add", "-A")
        git("-c", "user.name=cap03", "-c", "user.email=cap03@local", "commit", "-m", "init")


V1 = "# 需求文档 ALPHA\n\n## 背景\n标识词ALPHAUNIQUE。\n\n## 验收\n完成。"
V2 = "# 需求文档 BETA\n\n## 背景\n标识词BETAUNIQUE。\n\n## 变更\n新增评审环节。"
TPL_TITLE = "模板生成的需求"

# ---------- 0. 登录（写操作需鉴权） ----------
st, r = call("POST", "/auth/login", {"username": USER, "password": PWD})
token = obj(r).get("token") or obj(r).get("accessToken")
check("登录取得 token", st == 200 and bool(token), f"{st} {r}")
if not token:
    sys.exit(1)

# ---------- 0b. 归属用项目 + 需求（CAP-13：文档挂需求） ----------
ensure_repo()
st, projects = call("GET", "/projects", token=token)
proj = next((obj(p) for p in rows(projects) if obj(p).get("name") == PROJ_NAME), None)
if not proj:
    st, proj = call("POST", "/projects", {"name": PROJ_NAME, "path": str(REPO).replace("/", "\\"),
                                         "defaultBranch": "master", "tags": []}, token)
    proj = obj(proj)
pid = proj.get("id")
check("项目就绪", st == 200 and bool(pid), f"{st} {proj}")
st, reqm = call("POST", f"/projects/{pid}/requirements",
                {"title": "CAP03 文档归属需求", "description": "验证文档挂需求"}, token)
rid = obj(reqm).get("id")
check("需求就绪", st == 200 and bool(rid), f"{st} {reqm}")
if not pid or not rid:
    print("无法取得项目/需求，终止")
    sys.exit(1)

# ---------- 1. 模板（FR-07） ----------
st, tpl = call("GET", "/documents/templates", token=token)
check("模板列表 5 类", st == 200 and len(rows(tpl)) == 5, f"{st} {tpl}")

# ---------- 2. 新建（FR-01）+ 模板预填 + DB 单副本（FR-05） ----------
st, d = call("POST", "/documents", {"kind": "requirement", "title": TPL_TITLE, "template": "requirement"}, token)
d = obj(d)
did = d.get("id")
check("套模板建档 v1", st == 200 and d.get("versionNo") == 1 and d.get("status") == "draft", f"{st} {d}")
check("模板内容已预填", (d.get("contentMd") or "").startswith("# 需求文档"), "模板未生效")
check("详情无 git 残留字段", "filePath" not in d and "commitSha" not in d,
      f"filePath={'filePath' in d} commitSha={'commitSha' in d}")

# ---------- 3. 版本列表无 commitSha（FR-02/FR-05） ----------
st, tv = call("GET", f"/documents/{did}/versions", token=token)
check("版本列表无 commitSha 字段", st == 200 and rows(tv) and "commitSha" not in rows(tv)[0], f"{st} {tv}")

# ---------- 4. 自建内容文档（挂需求）：多版本 + diff + 回退 ----------
st, d2 = call("POST", "/documents", {"kind": "design", "title": "登录改造方案",
                                     "requirementId": rid, "contentMd": V1}, token)
d2 = obj(d2)
id2 = d2.get("id")
check("自建 design 文档 v1（挂需求）", st == 200 and d2.get("status") == "draft"
      and d2.get("requirementId") == rid, f"{st} {d2}")
check("projectId 由需求反推", d2.get("projectId") == pid, f"projectId={d2.get('projectId')} 期望 {pid}")

st, v2 = call("POST", f"/documents/{id2}/versions", {"contentMd": V2, "changeNote": "补评审环节"}, token)
check("保存 v2 成功", st == 200 and obj(v2).get("versionNo") == 2, f"{st} {v2}")
st, vs = call("GET", f"/documents/{id2}/versions", token=token)
check("版本列表 2 条降序", st == 200 and len(rows(vs)) == 2 and rows(vs)[0]["versionNo"] == 2, str(vs))

st, dif = call("GET", f"/documents/{id2}/versions/1/diff", token=token)
dif = obj(dif)
check("diff v1→当前 有差异", st == 200 and dif.get("hasChanges") and dif.get("additions", 0) > 0, f"{st} {dif}")
joined = "\n".join(dif.get("lines", []))
check("diff 行含 +BETA 与 -ALPHA", "-标识词ALPHAUNIQUE" in joined and "+标识词BETAUNIQUE" in joined, joined[:200])

st, rv = call("POST", f"/documents/{id2}/versions/1/revert", token=token)
check("回退 v1 → 新 v3", st == 200 and obj(rv).get("versionNo") == 3, f"{st} {rv}")
st, cur = call("GET", f"/documents/{id2}", token=token)
cur_body = obj(cur).get("contentMd") or ""
check("回退后内容 = v1", "标识词ALPHAUNIQUE" in cur_body and "BETAUNIQUE" not in cur_body, "回退内容错误")
st, bad = call("POST", f"/documents/{id2}/versions/3/revert", token=token)
check("回退到当前版本 → 400", st == 400, f"{st}")

# ---------- 5. 检索（FR-06） ----------
st, sr = call("GET", "/documents/search?q=ALPHAUNIQUE", token=token)
check("检索内容命中 v1 文档", any(obj(x).get("id") == id2 for x in rows(sr)), f"{st} {sr}")
st, sr = call("GET", "/documents/search?q=登录改造", token=token)
check("检索标题命中", any(obj(x).get("id") == id2 for x in rows(sr)), f"{st} {sr}")

# ---------- 6. 状态机（FR-04） ----------
st, _ = call("POST", f"/documents/{id2}/status", {"action": "freeze"}, token)
check("草稿直接冻结 → 409", st == 409, f"{st}")
st, r = call("POST", f"/documents/{id2}/status", {"action": "submit"}, token)
check("提交确认 → pending_confirm", st == 200 and obj(r).get("status") == "pending_confirm", f"{st} {r}")
st, r = call("POST", f"/documents/{id2}/status", {"action": "freeze"}, token)
check("冻结 → frozen", st == 200 and obj(r).get("status") == "frozen", f"{st} {r}")
st, _ = call("POST", f"/documents/{id2}/versions", {"contentMd": "# 未说明的变更"}, token)
check("冻结保存无变更说明 → 400", st == 400, f"{st}")
st, r = call("POST", f"/documents/{id2}/versions", {"contentMd": "# 已说明的变更\n冻结后新版本", "changeNote": "冻结后基线调整"}, token)
check("冻结带说明保存 → v4", st == 200 and obj(r).get("versionNo") == 4, f"{st} {r}")
st, r = call("POST", f"/documents/{id2}/status", {"action": "unfreeze"}, token)
check("解除冻结 → draft", st == 200 and obj(r).get("status") == "draft", f"{st} {r}")

# ---------- 7. 删除：记录与版本一并清掉，无文件残留 ----------
st, _ = call("DELETE", f"/documents/{id2}", token=token)
check("删除文档", st == 200, f"{st}")
st, _ = call("GET", f"/documents/{id2}", token=token)
check("删除后详情 404", st == 404, f"{st}")
st, _ = call("GET", f"/documents/{id2}/versions", token=token)
check("删除后版本列表 404", st == 404, f"{st}")

# ---------- 8. 已废除的 git 端点：不再提供 ----------
# 路由层面这两个路径已无 handler：POST 落到 /documents/{id} 的 GET 映射 → 405；
# GET /documents/repo 落到 /documents/{id} 但 id 转 Long 失败 → 400。
# 断言精确状态码（客户端错误语义，见 tests/e2e-error-semantics.sh）。
st, body = call("POST", "/documents/push", token=token)
check("push 端点已移除（405）", st == 405, f"{st} {body}")
st, body = call("GET", "/documents/repo", token=token)
check("repo 端点已移除（400）", st == 400, f"{st} {body}")

# ---------- 清理：删除模板文档 ----------
call("DELETE", f"/documents/{did}", token=token)

print(f"\n== CAP-03 验证结果: {passed} passed, {failed} failed ==")
sys.exit(1 if failed else 0)
