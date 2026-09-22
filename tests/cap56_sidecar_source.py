# -*- coding: utf-8 -*-
"""CAP-56 FR-01 验证：laya 边车的「槽位 → 来源覆盖」与 `/healthz` 来源上报。

前置：
  1. 边车依赖已装好：`tools/laya-sidecar/.venv`（`pip install -r tools/laya-sidecar/requirements.txt`）。
     本脚本用**标准库**跑，但拉起的边车必须用那个 venv 的 python（自动探测，可用 `--python` 覆盖）。
  2. `--artifact` 给一个**真实的 checkpoint 目录**（含 `rl_agent_config.json` + `model.safetensors`，
     即 `laya_train.py` 的输出目录或 HF 缓存里的 multilingual 快照）。case A 会真起模型、
     真发一次 `/v1/predict`——「覆盖生效」这件事只有预测走通了才算证过。
     手上没有产物时可先用最小数据跑一次微调（见 docs/guides/CAP-56-使用指南.md）。

覆盖点：
  A 文件分支（`models.json` 的 `slots` 包裹 + `_comment` 剔除）+ `/healthz.sources` 如实上报
    + **覆盖真被 serve**（响应里的 `routing.repo` 必须指向被覆盖的产物）
  B 槽位名不认识 / C 本地路径不存在 / D 环境变量不是合法 JSON / E 来源文件不是合法 JSON
    / F `LAYA_MODELS_FILE` 显式指定却不存在  → 五种坏配置一律**启动即失败**（不静默退回内置模型）
  G 没配覆盖、默认 `models.json` 也不存在 → 用内置默认且不报错（与 B~F 的分界）

运行产物（日志、临时 models.json）落 `tmp/cap56-sidecar/`。从仓库根目录跑：

    python tests/cap56_sidecar_source.py --artifact tmp/cap56/out-ft1
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIDECAR = os.path.join(ROOT, "tools", "laya-sidecar")
OUT_DIR = os.path.join(ROOT, "tmp", "cap56-sidecar")

fails = []


def check(name, cond, detail=""):
    print(("  [OK]   " if cond else "  [FAIL] ") + name + (("  " + detail) if detail else ""))
    if not cond:
        fails.append(name)


def resolve_python(explicit):
    if explicit:
        return explicit
    names = ["Scripts/python.exe", "bin/python"] if os.name == "nt" else ["bin/python", "Scripts/python.exe"]
    for rel in names:
        p = os.path.join(SIDECAR, ".venv", rel)
        if os.path.exists(p):
            return p
    sys.exit("找不到边车 venv 的 python（%s/.venv）。先按 tools/laya-sidecar/README.md 建好环境，"
             "或用 --python 指定一个装了 fastapi/uvicorn/laya 的解释器。" % SIDECAR)


def log_tail(log, n=25):
    with open(log, "rb") as f:
        text = f.read().decode("utf-8", "replace")
    return "".join(text.splitlines(keepends=True)[-n:])


def hit(tail, needle):
    """日志里含 needle 的那一行——失败原因要指到具体那一句，而不是 uvicorn 的通用结束语。"""
    for line in tail.splitlines():
        if needle in line:
            return line.strip()[:200]
    return "（日志里没找到 %r）" % needle


class Sidecar:
    """按端口起一个独立边车进程；日志写 tmp/（不进库）。"""

    def __init__(self, python, port, extra_env):
        self.port = port
        self.log = os.path.join(OUT_DIR, "sidecar-%d.log" % port)
        # 清掉祖辈的 LAYA_*：本脚本每次只测自己那份配置
        env = {k: v for k, v in os.environ.items() if not k.startswith("LAYA_")}
        env.update(extra_env)
        self._fh = open(self.log, "wb")
        self.proc = subprocess.Popen(
            [python, "-m", "uvicorn", "app:app", "--port", str(port), "--app-dir", SIDECAR],
            cwd=SIDECAR, env=env, stdout=self._fh, stderr=subprocess.STDOUT)

    def health(self, seconds=150):
        """等到 /healthz 能应答为止；进程中途死掉返回 None（早退，不用等满超时）。"""
        deadline = time.time() + seconds
        while time.time() < deadline:
            if self.proc.poll() is not None:
                return None
            try:
                with urllib.request.urlopen("http://127.0.0.1:%d/healthz" % self.port, timeout=3) as r:
                    return json.loads(r.read().decode("utf-8"))
            except Exception:
                time.sleep(2)
        return None

    def wait_die(self, seconds=60):
        deadline = time.time() + seconds
        while time.time() < deadline:
            if self.proc.poll() is not None:
                return self.proc.returncode
            time.sleep(1)
        return None

    def predict(self, body, timeout=180):
        req = urllib.request.Request(
            "http://127.0.0.1:%d/v1/predict" % self.port,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.proc.terminate()
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self._fh.close()


def expect_startup_failure(label, python, port, env, needle, hint):
    """起不来 + 报错里能看到 needle ——坏配置必须炸，且要炸得指得出原因。"""
    print("%s → 启动即失败（%s）" % (label, hint))
    with Sidecar(python, port, env) as s:
        rc = s.wait_die()
        tail = log_tail(s.log)
        check("进程非零退出", rc not in (0, None), "rc=%r" % rc)
        check("错误里点了原因", needle in tail, hit(tail, needle))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--artifact", help="真实 checkpoint 目录（含 rl_agent_config.json + model.safetensors）")
    ap.add_argument("--python", help="跑边车的解释器（默认自动探测 tools/laya-sidecar/.venv）")
    ap.add_argument("--port-base", type=int, default=8390, help="起边车的端口基数（默认 8390）")
    ap.add_argument("--payload", help="用于 case A 预测的评测 payload（默认从 --artifact 同级找）")
    args = ap.parse_args()

    python = resolve_python(args.python)
    os.makedirs(OUT_DIR, exist_ok=True)
    print("边车 python: %s\n产物目录: %r\n日志目录: %s\n" % (python, args.artifact, OUT_DIR))

    # ---- A 覆盖生效 + 如实上报 + 真被 serve
    if not args.artifact:
        sys.exit("缺 --artifact：case A（覆盖真被 serve）必须有一个真实 checkpoint 目录才测得出来。"
                 "先跑一次 laya_train.py 出产物，或指向 HF 缓存里的快照目录。")
    artifact = os.path.abspath(args.artifact)
    if not os.path.isdir(artifact):
        sys.exit("--artifact 不是目录：%s" % artifact)
    payload_path = args.payload or _find_payload(artifact)
    if not payload_path:
        sys.exit("找不到评测 payload（用 --payload 指定）：case A 要用它构造一次真实预测。")

    models_file = os.path.join(OUT_DIR, "models.json")
    with open(models_file, "w", encoding="utf-8") as f:
        # 反斜杠形式：顺带验证 Windows 原生路径也能被识别为本地目录
        json.dump({"_comment": "冒烟用：_comment 必须被剔除，slots 包裹必须被接受",
                   "slots": {"typed-decisions": artifact.replace("/", "\\")}}, f, ensure_ascii=False)

    print("A) 文件分支（slots 包裹 + _comment）+ /healthz 来源上报 + 覆盖真被 serve")
    with Sidecar(python, args.port_base, {"LAYA_MODELS_FILE": models_file,
                                          "LAYA_PRELOAD": "typed-decisions",
                                          "LAYA_DEVICE": "cpu"}) as s:
        hz = s.health()
        if hz is None:
            check("边车起来", False, log_tail(s.log))
        else:
            check("边车起来", True)
            check("source_origin 指向该文件", hz.get("source_origin") == models_file,
                  repr(hz.get("source_origin")))
            src = (hz.get("sources") or {}).get("typed-decisions") or {}
            check("typed-decisions = 本地来源且 ready 且已常驻",
                  src.get("kind") == "local" and src.get("ready") is True and src.get("loaded") is True,
                  json.dumps(src, ensure_ascii=False))
            check("overridden_slots 只报被改的那个", hz.get("overridden_slots") == ["typed-decisions"],
                  repr(hz.get("overridden_slots")))
            check("未改的槽位仍是内置 repo",
                  (hz.get("sources") or {}).get("multilingual", {}).get("kind") == "repo")
            check("unready_slots 为空", hz.get("unready_slots") == [])

            item = json.load(open(payload_path, encoding="utf-8"))["items"][0]
            try:
                ans = s.predict({"state": item["state"], "questions": item["questions"],
                                 "model": "typed-decisions"})
                check("预测 200", True)
                answers = ans.get("answers") or {}
                check("预测返回了答案", bool(answers), json.dumps(ans, ensure_ascii=False)[:400])
                check("题目被作答", bool((answers.get("duplicate") or {}).get("choice")),
                      "choice=%r" % (answers.get("duplicate") or {}).get("choice"))
                # 最硬的一条：响应自己说了这份预测由哪个来源服务
                got = str((ans.get("routing") or {}).get("repo", "")).replace("\\", "/").rstrip("/")
                check("routing 指向被覆盖的产物（跑的就是登记的那份）",
                      got.lower() == artifact.replace("\\", "/").lower(), "routing=%r" % (ans.get("routing"),))
            except urllib.error.HTTPError as e:
                check("预测 200", False, "%s %s" % (e.code, e.read().decode("utf-8", "replace")[:300]))
            except Exception as e:  # noqa: BLE001 —— 网络/解析任一失败都算这条不过
                check("预测 200", False, repr(e))

    # ---- B~F 坏配置必须当场炸
    expect_startup_failure("B) 本地路径不存在", python, args.port_base + 7,
                           {"LAYA_SLOT_MODELS": json.dumps({"typed-decisions": "D:/definitely/not/here"}),
                            "LAYA_PRELOAD": ""}, "本地路径不存在", "不静默退回内置模型")
    expect_startup_failure("C) 槽位名不认识", python, args.port_base + 6,
                           {"LAYA_SLOT_MODELS": "nope=/tmp/x", "LAYA_PRELOAD": ""},
                           "不认识", "别名归一后仍不认识的槽位要报错而不是忽略")
    expect_startup_failure("D) 环境变量不是合法 JSON", python, args.port_base + 5,
                           {"LAYA_SLOT_MODELS": '{"typed-decisions": ', "LAYA_PRELOAD": ""},
                           "不是合法 JSON", "解析失败不降级")
    bad = os.path.join(OUT_DIR, "models-bad.json")
    open(bad, "w", encoding="utf-8").write("{not json")
    expect_startup_failure("E) 来源文件不是合法 JSON", python, args.port_base + 4,
                           {"LAYA_MODELS_FILE": bad, "LAYA_PRELOAD": ""},
                           "models-bad.json", "报错要报出是哪个文件")
    expect_startup_failure("F) LAYA_MODELS_FILE 显式指定却不存在", python, args.port_base + 3,
                           {"LAYA_MODELS_FILE": os.path.join(OUT_DIR, "no-such-models.json"),
                            "LAYA_PRELOAD": ""}, "LAYA_MODELS_FILE", "路径敲错不能静默服务旧模型")

    # ---- G 与 B~F 的分界：没配覆盖、默认文件也不存在 = 用内置默认，不该报错
    print("G) 无覆盖且默认 models.json 不存在 → 用内置默认（这条**不该**报错）")
    with Sidecar(python, args.port_base + 2, {"LAYA_PRELOAD": ""}) as s:
        hz = s.health(60)
        check("边车起来", hz is not None, log_tail(s.log) if hz is None else "")
        if hz is not None:
            check("source_origin 说明是内置默认", "内置默认" in str(hz.get("source_origin")),
                  repr(hz.get("source_origin")))
            check("三个槽位都在且都是内置 repo",
                  sorted(hz.get("sources") or {}) == ["english", "multilingual", "typed-decisions"]
                  and hz.get("overridden_slots") == [] and hz.get("unready_slots") == [],
                  json.dumps(hz.get("sources"), ensure_ascii=False)[:200])

    print()
    if fails:
        print("失败 %d 项：%s" % (len(fails), "；".join(fails)))
        return 1
    print("全部通过")
    return 0


def _find_payload(artifact):
    """默认在产物同级/上级找评测 payload：tmp/cap56/eval-payload.json 这个约定位置。"""
    for cand in (os.path.join(os.path.dirname(artifact), "eval-payload.json"),
                 os.path.join(os.path.dirname(os.path.dirname(artifact)), "eval-payload.json")):
        if os.path.exists(cand):
            return cand
    return None


if __name__ == "__main__":
    sys.exit(main())
