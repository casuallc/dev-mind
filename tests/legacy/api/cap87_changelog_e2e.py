# -*- coding: utf-8 -*-
"""CAP-87 控制台更新日志 E2E（自起隔离 app，文件源 + 筛选 + 详情 + 热替换全链）。

运行前提：
  1. `mvn -q install -DskipTests`（devmind-changelog 已装进 .m2，app 才能加载新模块）；
  2. 端口 18104 空闲（脚本收尾会按端口清理残留）；
  3. JDK 21：JAVA_HOME 未设时回落 Eclipse Adoptium 本机路径。

脚本自起隔离 app（:18104，--spring.profiles.active=e2e 独立 H2 落 tmp/cap87-e2e/，
`devmind.changelog.path` 指向 TMP 内 CHANGELOG.md）。跑完杀进程，不留残留。

验收覆盖（CAP-87 §9）：
  [1] 默认列表只含已发布版本且按版本数值倒序，[Unreleased] 不出现；
  [2] 版本号 / 日期区间 / 关键词筛选各自命中与落空；服务端分页翻页不重叠；
  [3] 详情返回完整 Markdown（类型/关联编号/正文），未知版本 404；
  [4] 文件缺失时返回空列表且 available=false（不 500）；
  [5] 文件被替换后不重启即可看到新条目（mtime/size 缓存失效）。
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PORT = int(os.environ.get("E2E_PORT", "18104"))
BASE = f"http://localhost:{PORT}/api"
TMP = ROOT / "tmp" / "cap87-e2e"
CHANGELOG = TMP / "CHANGELOG.md"
APP_LOG = TMP / "app.log"

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

passed, failed = [], []


def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    print(f"  {'PASS' if cond else 'FAIL'}  {name}" + ("" if cond else f"  {detail}"))


def req(method, path, body=None, token=None, expect=200):
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        payload = e.read().decode("utf-8", "replace")
        if e.code != expect:
            raise AssertionError(f"{method} {path} -> {e.code} (expect {expect}): {payload[:400]}")
        try:
            return json.loads(payload or "null")
        except json.JSONDecodeError:
            return payload


def wait_health(timeout=150):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"http://localhost:{PORT}/api/health", timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(2)
    return False


def wait_port_free(timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(f"http://localhost:{PORT}/api/health", timeout=2)
        except Exception:
            return True
        time.sleep(1)
    return False


def kill_tree(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)


def kill_port_strays():
    out = subprocess.run(["netstat", "-ano"], capture_output=True).stdout.decode("utf-8", "replace")
    for line in out.splitlines():
        if f":{PORT}" in line and "LISTENING" in line:
            subprocess.run(["taskkill", "/F", "/PID", line.split()[-1]], capture_output=True)


def start_app():
    log = open(APP_LOG, "w", encoding="utf-8")
    args = (
        f"--server.port={PORT} "
        f"--spring.datasource.url=jdbc:h2:file:{(TMP / 'db/devmind').as_posix()};AUTO_SERVER=TRUE "
        f"--devmind.changelog.path={CHANGELOG.as_posix()}"
    )
    cmd = (f'cmd /c mvn -q -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e '
           f'-Dspring-boot.run.arguments="{args}"')
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
    if not wait_health():
        log.close()
        raise AssertionError(f"app 150s 内未起来（看 {APP_LOG}）")
    return proc, log


INITIAL_MD = """# Dev-Mind 更新日志

## [Unreleased]
### 新增
- 未发布内容不应出现（CAP-99）

## [1.10.0] - 2026-10-22
### 优化
- 数值排序检查（1.10 应排在 1.9 之前）

## [1.9.0] - 2026-09-01
### 破坏性变更
- 移除旧接口

## [1.2.0] - 2026-10-20
- 关联：CAP-87, REQ-12

### 新增
- 控制台更新日志页面，支持筛选与详情预览

### 修复
- 修复分页边界问题
"""

REPLACED_MD = """# Dev-Mind 更新日志

## [2.1.0] - 2026-10-25
### 新增
- 文件替换后无需重启即可见（热加载）

## [1.10.0] - 2026-10-22
### 优化
- 旧版本保留
"""


def versions_of(resp):
    return [x["version"] for x in resp["items"]]


def main():
    if TMP.exists():
        def _force_remove(func, path, _exc):
            os.chmod(path, 0o666)
            func(path)
        shutil.rmtree(TMP, onexc=_force_remove)
    TMP.mkdir(parents=True, exist_ok=True)
    CHANGELOG.write_text(INITIAL_MD, encoding="utf-8")

    app, app_log = start_app()
    token = None
    try:
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]
        print(f"[0] 隔离 app 已就绪（:{PORT}，日志 {APP_LOG}）")

        # [1] 默认列表：只含已发布版本、数值倒序、Unreleased 不出现
        page = req("GET", "/changelog?page=0&size=20", token=token)
        check("1 默认列表按版本数值倒序", versions_of(page) == ["1.10.0", "1.9.0", "1.2.0"],
              f"items={versions_of(page)}")
        check("1 total=3 且 Unreleased 不可见",
              page["total"] == 3 and all("未发布" not in (x.get("summary") or "") for x in page["items"]),
              f"total={page['total']} items={versions_of(page)}")
        check("1 数据源 available=true 且带相对 sourcePath",
              page["available"] is True and (page["sourcePath"] or "").endswith("CHANGELOG.md"),
              f"available={page['available']} sourcePath={page['sourcePath']}")
        check("1 列表项只含摘要（无 contentMd）",
              all("contentMd" not in x for x in page["items"]), f"keys={list(page['items'][0])}")
        check("1 1.2.0 摘要/类型/关联编号解析正确",
              any(x["version"] == "1.2.0" and x["types"] == ["ADDED", "FIXED"]
                  and x["refs"] == ["CAP-87", "REQ-12"] and "分页边界" in x["summary"]
                  for x in page["items"]),
              f"row={next((x for x in page['items'] if x['version'] == '1.2.0'), None)}")

        # [1] 服务端分页
        p0 = req("GET", "/changelog?page=0&size=2", token=token)
        p1 = req("GET", "/changelog?page=1&size=2", token=token)
        check("2 分页 size=2 两页不重叠且 total 一致",
              versions_of(p0) == ["1.10.0", "1.9.0"] and versions_of(p1) == ["1.2.0"]
              and p0["total"] == p1["total"] == 3,
              f"p0={versions_of(p0)} p1={versions_of(p1)}")

        # [2] 版本号包含匹配
        fv = req("GET", "/changelog?version=1.9", token=token)
        check("2 版本筛选命中", versions_of(fv) == ["1.9.0"], f"items={versions_of(fv)}")
        miss = req("GET", "/changelog?version=9.9", token=token)
        check("2 版本筛选落空", miss["total"] == 0 and miss["items"] == [], f"resp={miss}")

        # [2] 日期闭区间
        fd = req("GET", "/changelog?from=2026-09-01&to=2026-10-20", token=token)
        check("2 日期区间命中（含两端）", versions_of(fd) == ["1.9.0", "1.2.0"],
              f"items={versions_of(fd)}")
        fd2 = req("GET", "/changelog?from=2026-10-21&to=2026-10-23", token=token)
        check("2 日期区间仅命中 1.10.0", versions_of(fd2) == ["1.10.0"], f"items={versions_of(fd2)}")
        fd3 = req("GET", "/changelog?from=2027-01-01&to=2027-12-31", token=token)
        check("2 日期筛选落空", fd3["total"] == 0, f"resp={fd3}")

        # [2] 关键词（正文 / 关联编号）
        fk = req("GET", "/changelog?keyword=%E5%88%86%E9%A1%B5", token=token)  # 分页
        check("2 关键词命中正文", versions_of(fk) == ["1.2.0"], f"items={versions_of(fk)}")
        fr = req("GET", "/changelog?keyword=CAP-87", token=token)
        check("2 关键词命中关联编号", versions_of(fr) == ["1.2.0"], f"items={versions_of(fr)}")
        fk0 = req("GET", "/changelog?keyword=no-such-keyword", token=token)
        check("2 关键词落空", fk0["total"] == 0, f"resp={fk0}")

        # [3] 详情：完整 Markdown + 未知版本 404 + Unreleased 404
        d = req("GET", "/changelog/1.2.0", token=token)
        check("3 详情含完整 Markdown 正文",
              "### 新增" in d["contentMd"] and "控制台更新日志页面" in d["contentMd"]
              and "### 修复" in d["contentMd"], f"contentMd={d['contentMd'][:120]!r}")
        check("3 详情元信息正确",
              d["releasedAt"] == "2026-10-20" and d["types"] == ["ADDED", "FIXED"]
              and d["refs"] == ["CAP-87", "REQ-12"] and "关联：" not in d["contentMd"],
              f"detail={ {k: d[k] for k in ('releasedAt', 'types', 'refs')} }")
        dv = req("GET", "/changelog/v1.2.0", token=token)
        check("3 详情兼容 v 前缀", dv["version"] == "1.2.0", f"version={dv.get('version')}")
        req("GET", "/changelog/9.9.9", token=token, expect=404)
        check("3 未知版本 404", True)
        req("GET", "/changelog/Unreleased", token=token, expect=404)
        check("3 Unreleased 详情 404", True)

        # [4] 文件缺失：空列表 + available=false，不 500
        CHANGELOG.unlink()
        gone = req("GET", "/changelog?page=0&size=20", token=token)
        check("4 文件缺失返回空列表且 available=false",
              gone["items"] == [] and gone["total"] == 0 and gone["available"] is False,
              f"resp={gone}")

        # [5] 文件替换：不重启即可读到新条目（mtime/size 缓存失效）
        CHANGELOG.write_text(REPLACED_MD, encoding="utf-8")
        future = time.time() + 5
        os.utime(CHANGELOG, (future, future))
        hot = req("GET", "/changelog?page=0&size=20", token=token)
        check("5 文件替换后新条目可见（热加载）", versions_of(hot) == ["2.1.0", "1.10.0"],
              f"items={versions_of(hot)}")
        check("5 替换后 available 恢复且 total 更新",
              hot["available"] is True and hot["total"] == 2
              and all("未发布" not in (x.get("summary") or "") for x in hot["items"]),
              f"resp={hot}")

        # 当前部署版本来源（前端「关于」跳转口径）：/about 可用
        about = req("GET", "/about", token=token)
        check("当前部署版本可读（/about.version）", bool(about.get("version")),
              f"about={about}")
    finally:
        # 只读能力，无数据需要清理；杀隔离 app 与端口残留
        kill_tree(app.pid)
        time.sleep(2)
        kill_port_strays()
        app_log.close()
        wait_port_free()

    print(f"\n== 结果：{len(passed)} PASS / {len(failed)} FAIL")
    if failed:
        print("失败项：")
        for f in failed:
            print(f"  - {f}")
        sys.exit(1)


if __name__ == "__main__":
    main()
