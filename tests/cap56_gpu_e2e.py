#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-56 真机全链 E2E：在 GPU 节点（172.20.140.88）上跑**真 laya 模型**的评测与 RLCD 微调。

与 `tests/cap56_e2e.py` 的分工：那一遍用 stdlib stub 验**平台这条链**（执行包、白名单、marker 抓取、
闸门、报告口径），节点上的"模型"是按退化形态写死的假进程；这一遍反过来——**模型、训练、指标全是真的**，
验的是 CAP-56 的立身问题：base checkpoint 在分诊题面上到底有多退化、RLCD 微调之后是不是真的更好、
「放行的就是正在跑的」在真边车上能不能自证。

## 前提（脚本自己不满足这些，只做前置检查）

1. **本机 app**：隔离实例，库全新，脚本目录指向真脚本、解释器指向节点 venv（见 `--from` 帮助里的
   完整命令）。跑在 `:18097`，节点经本机内网 IP 反连它（`--local-ip`）。
2. **节点**：`~/laya-venv`（laya 0.3.6 + torch）、`~/laya/multilingual-base`（基座权重目录，
   含 `rl_agent_config.json` + `model.safetensors`）、`~/laya-sidecar/app.py`、
   **ssh 免密可用**（`ssh <node> true` 通）。脚本会自己上传 runner jar 到 `~/cap56-runner/` 并起它
   ——**不动 `~/devmind-runner`**（那是给 224 生产用的，见「开发注意事项」runner 那条）。
3. 节点与 app 之间没有代理干扰；边车端口 8377 只跑这一个实例。
4. **边车设备**：与 vLLM 共卡（`--gpu-memory-utilization 0.95` 那种）时用
   `DEVMIND_GPU_SIDECAR_DEVICE=cpu`。踩过的坑：`LAYA_DEVICE=cuda` 在显存被占满时，
   边车要么加载完前向直接 500（`'NoneType' object is not subscriptable`），要么中途退 CPU
   （日志里 `GPU memory exceeded during inference. Falling back to CPU`）→ 单条推理几十秒到分钟级
   → 平台侧 30s 超时 → **整片分诊降级**。同期显式 `LAYA_DEVICE=cpu` 实测 **~2.0s/条、零降级**
   （模型小，CPU 完全够用）——真机全链的复现姿势就是 CPU。

## 跑法

    python tests/cap56_gpu_e2e.py                 # 从第 1 阶段跑到底
    python tests/cap56_gpu_e2e.py --from 7        # 训练那步断了，从微调阶段接着跑（状态存 tmp/cap56-gpu/state.json）
    python tests/cap56_gpu_e2e.py --dry-run       # 只打印将要做什么，不落任何数据

阶段：1 前置检查 → 2 节点 runner → 3 端点 → **4 基准集（含对照组）→ 5 基座评测（复现退化）+ 放行基座**
→ 6 数据飞轮（提案→分诊→裁决→决策记录）→ 7 收编为回流训练集 → 8 RLCD 微调 + 自动回评
→ 9 放行微调产物 + serve 自检 + 边车换槽位来源（分诊徽标随之改变）。

> 顺序上有一处**不是随便排的**：数据飞轮必须排在"放行基座"之后。闸门先于端点判定生效
> （CAP-56 FR-07），一份已验证产物都没有时**分诊整体降级**——记录里只会留下降级样本，
> 飞轮空转。真机第一次跑就是被这道门挡住的（记录全空、`triaged=0`），这不是脚本 bug，
> 是闸门在按设计工作；生产上线同理（见 guides/decision-lab-guide.md §3）。

## 这一遍**不**验什么

不验前端 UI（徽标是经 API 读的）；不验 224 生产环境（本脚本只碰本机隔离实例与 140.88 节点）。
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import cap56_e2e as base  # noqa: E402  只借它纯函数式的样本构造（gold 口径必须单一来源）

BASE = os.environ.get("DEVMIND_BASE", "http://localhost:18097/api")
TMP = ROOT / "tmp" / "cap56-gpu"
STATE_FILE = TMP / "state.json"

NODE = os.environ.get("DEVMIND_GPU_NODE", "liuchangqing@172.20.140.88")
NODE_HOME = "/home/liuchangqing"
NODE_RUNNER_DIR = f"{NODE_HOME}/cap56-runner"
VENV_PY = os.environ.get("DEVMIND_GPU_PYTHON", f"{NODE_HOME}/laya-venv/bin/python")
BASE_CKPT = os.environ.get("DEVMIND_GPU_BASE_CKPT", f"{NODE_HOME}/laya/multilingual-base")
FT_ROOT = os.environ.get("DEVMIND_GPU_FT_ROOT", f"{NODE_HOME}/laya/ft")
SIDECAR = os.environ.get("DEVMIND_GPU_SIDECAR", "http://172.20.140.88:8377")
LABELS = os.environ.get("DEVMIND_GPU_LABELS", "gpu,A6000")
# 边车设备：与 vLLM 共卡时用 cpu（见文件头「边车设备」那条——cuda 在显存被占满时随机 500/超时）
SIDECAR_DEVICE = os.environ.get("DEVMIND_GPU_SIDECAR_DEVICE", "cuda")
SLOT = os.environ.get("DEVMIND_GPU_SLOT", "multilingual")
CAPABILITY = "kb-proposal-triage"
MARK = f"cap56gpu-{time.strftime('%m%d-%H%M')}"

# 节点反连本机 app 用的地址候选（开发机内网地址；可用 --local-ip 或 DEVMIND_GPU_LOCAL_IP 覆盖）
LOCAL_IP_CANDIDATES = [ip for ip in [os.environ.get("DEVMIND_GPU_LOCAL_IP"), "172.21.61.60"] if ip]

# 三类对照组（缺一不可，冻结时会强制）
CONTROL = ("EMPTY_RECALL", "VERBATIM_DUP", "IRRELEVANT")

TOKEN = None
DRY = False
PASSED = 0
FAILED = 0

for _stream in (sys.stdout, sys.stderr):
    # 管道里（Git Bash 重定向 / 跑在后台）一律 UTF-8：默认按 GBK 编码会让中文在终端里变乱码；
    # 真控制台里保持原编码，只把编不出的符号打成 "?"（别让一次 UnicodeEncodeError 打断整轮 E2E）
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace") if not _stream.isatty() \
            else _stream.reconfigure(errors="replace")
    except Exception:
        pass


# ---------------------------------------------------------------- 断言与 HTTP

def check(name, ok, detail=""):
    global PASSED, FAILED
    if ok:
        PASSED += 1
        print(f"  PASS  {name}")
    else:
        FAILED += 1
        print(f"  FAIL  {name}" + (f"  → {detail}" if detail else ""))


def step(title):
    print(f"\n=== {title}")


def _decode(raw):
    text = raw.decode("utf-8", "replace") if raw else ""
    if not text:
        return None
    try:
        return json.loads(text)
    except Exception:
        return text


def call(method, url, body=None, timeout=180):
    """返回 (状态码, body)；HTTP 错误也返回状态码不抛——期望失败的分支靠它判。"""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, _decode(r.read())
    except urllib.error.HTTPError as e:
        return e.code, _decode(e.read())


def api(method, path, body=None, expect=200):
    st, payload = call(method, BASE + path, body)
    assert st == expect, f"{method} {path} → {st}（期望 {expect}）: {str(payload)[:400]}"
    return payload


def page(path, **params):
    q = "&".join(f"{k}={v}" for k, v in params.items())
    return api("GET", path + ("?" + q if q else ""))


def proposal(pid):
    """提案详情：知识库那边只有列表端点（`GET /knowledge/proposals?status=`），按 id 挑。"""
    for p in api("GET", "/knowledge/proposals"):
        if p["id"] == pid:
            return p
    raise AssertionError(f"提案 #{pid} 不在列表里")


def triage_snapshots():
    """一次列表请求拿回 `{提案id: triage快照}`——按 id 逐条查是 N 次请求，轮询里别这么干。"""
    return {str(p["id"]): (p.get("triage") or {}) for p in api("GET", "/knowledge/proposals")}


def wait(probe, what, timeout=300, every=3):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = probe()
        if v:
            return v
        time.sleep(every)
    raise AssertionError(f"超时等待: {what}（{timeout}s）")


def retriage(pid, what, attempts=3):
    """手动重分诊，等一条**新的、没降级的**快照。

    分诊是异步的（POST 只排队），所以必须等 `at` 变掉再读——否则读回的是上一次的旧快照，
    这会造出「换模型前后一模一样」的假证据（真机第二轮就是这么翻的车）。
    降级（边车首次调用超时这类环境抖动）不算证据，重试；重试仍降级才如实返回降级快照。
    """
    prev = (proposal(pid).get("triage") or {}).get("at")
    snap = None
    for i in range(1, attempts + 1):
        call("POST", f"{BASE}/knowledge/proposals/{pid}/triage")
        snap = wait(lambda: (lambda t: t if (t or {}).get("at") and t.get("at") != prev else None)(
            proposal(pid).get("triage")), f"{what}（第 {i} 次）", timeout=180)
        if not snap.get("degraded"):
            return snap
        print(f"      {what} 第 {i} 次降级：{snap.get('degradedReason')}")
        prev = snap.get("at")
    return snap


def triage_values(snap):
    """分诊快照 → 可对比的四元组（+ 时间与模型名，便于报告里说清"哪份模型判的"）"""
    return {"at": snap.get("at"),
            "model": snap.get("model"),
            "degraded": snap.get("degraded"),
            "duplicate": (snap.get("duplicate") or {}).get("duplicate"),
            "probability": (snap.get("duplicate") or {}).get("probability"),
            "layer": (snap.get("adoptLayer") or {}).get("value"),
            "quality": (snap.get("quality") or {}).get("level")}


# ---------------------------------------------------------------- 状态文件（可续跑）

def load_state():
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    return {}


def save_state(st):
    TMP.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(st, ensure_ascii=False, indent=2), encoding="utf-8")


# ---------------------------------------------------------------- 节点（ssh）

def _ssh_raw(cmd, timeout=180):
    return subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", NODE, cmd],
                          capture_output=True, timeout=timeout)


def _clean(text):
    """去掉登录横幅那几行：它们混在 stderr 里，取值时会把干净的输出弄脏。"""
    lines = [ln for ln in text.splitlines()
             if ln.strip() and not ln.startswith("**") and "Authorized users only" not in ln]
    return "\n".join(lines)


def ssh_stdout(cmd, timeout=180):
    """只要 stdout（剥掉登录横幅）——按输出做判断时用这个，别拿 stdout+stderr 去比。"""
    r = _ssh_raw(cmd, timeout)
    return r.returncode, _clean(r.stdout.decode("utf-8", "replace"))


def ssh(cmd, timeout=180, must=True):
    r = _ssh_raw(cmd, timeout)
    out = _clean((r.stdout + r.stderr).decode("utf-8", "replace"))
    if must and r.returncode != 0:
        raise AssertionError(f"ssh 失败（{r.returncode}）: {cmd}\n{out[:800]}")
    return r.returncode, out


def scp(local, remote_dir):
    r = subprocess.run(["scp", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
                        str(local), f"{NODE}:{remote_dir}/"], capture_output=True, timeout=300)
    assert r.returncode == 0, f"scp 失败: {local} → {remote_dir}\n{r.stderr.decode('utf-8', 'replace')}"


# ---------------------------------------------------------------- 语料

# 人工基准集：**每条都是人工标注**（gold 由构造决定的部分已在下面写死，其余是标注口径）。
# 三条红线组的构造方式决定了它们的 gold：空召回/不相关 → 不重复；逐字重复 → 重复。
# 退化基座（恒答 project / 判重复 / 全档 2）在这三组上必然现形——这正是要对照组的原因。
#
# 字段：content 提案正文 / similar 召回条目 / layer gold 层级 / quality gold 质量档 /
#       dup gold 是否重复 / group 对照组
BENCH = [
    # ---- 对照①空召回：库里什么都没有，唯一正确的答案就是"不重复" ----
    ("把发布窗口写进周报模板，避免临时撞车", "（未召回到相似条目）", "global", 2, False, "EMPTY_RECALL"),
    ("新人上手第一天先跑通本地三件套再动代码", "（未召回到相似条目）", "global", 1, False, "EMPTY_RECALL"),
    ("日志里不要打全量请求体，敏感字段要脱敏", "（未召回到相似条目）", "global", 2, False, "EMPTY_RECALL"),
    ("本项目的数据目录挂在 D 盘而不是 C 盘", "（未召回到相似条目）", "project", 1, False, "EMPTY_RECALL"),

    # ---- 对照②逐字重复：召回条目与正文一字不差，正确答案必然是"重复" ----
    ("构建失败先看日志末尾两百行再猜网络", "1. 《排查看日志的顺序》\n构建失败先看日志末尾两百行再猜网络",
     "discard", 2, True, "VERBATIM_DUP"),
    ("回滚要连配置一起回滚，只换包会留下脏配置", "1. 《回滚的边界》\n回滚要连配置一起回滚，只换包会留下脏配置",
     "discard", 2, True, "VERBATIM_DUP"),
    ("提测前把验收清单同步给测试同学", "1. 《提测交接》\n提测前把验收清单同步给测试同学",
     "discard", 1, True, "VERBATIM_DUP"),
    ("数据库连接池上限要小于数据库最大连接数", "1. 《连接池与库的上限》\n数据库连接池上限要小于数据库最大连接数",
     "discard", 2, True, "VERBATIM_DUP"),

    # ---- 对照③不相关：同领域但讲的不是一回事，正确答案是"不重复" ----
    ("内网镜像仓库的证书每年换一次，换完要更新信任库", "1. 《容器网络排查》\n容器网络不通先查宿主防火墙与网卡顺序，与证书无关",
     "global", 2, False, "IRRELEVANT"),
    ("K8s 探针的初始延迟要给够，否则滚动更新必然失败", "1. 《数据库慢查询》\n慢查询先从执行计划看起，与探针参数无关",
     "global", 1, False, "IRRELEVANT"),
    ("定时任务要加分布式锁，避免多实例重复跑", "1. 《前端构建缓存》\n前端构建慢多半是依赖缓存失效，与任务调度无关",
     "global", 2, False, "IRRELEVANT"),
    ("灰度发布先放 1% 流量，观察半小时再放量", "1. 《日志采集延迟》\n日志延迟是采集端批量提交造成的，与发布策略无关",
     "project", 1, False, "IRRELEVANT"),

    # ---- 普通样本：层级/质量/重复三题都有人工判断 ----
    ("Java 21 编译报「不支持发行版本」说明 JAVA_HOME 指到了旧版", "1. 《工具链版本》\nnode 版本不匹配也会报奇怪错误",
     "global", 2, False, "NORMAL"),
    ("H2 保留字不能当列名，commit 要写成 commit_sha", "1. 《字段命名》\n保留字冲突在不同数据库上表现不一样",
     "global", 2, False, "NORMAL"),
    ("异步触发的任务不要加事务，否则任务会卡在排队中", "1. 《事务与异步》\n事务提交时机与线程可见性",
     "global", 2, False, "NORMAL"),
    ("本项目的发布流程是先在 143 验证再上 224", "1. 《环境分层》\n测试环境与生产环境的差异清单",
     "project", 2, False, "NORMAL"),
    ("我们的 runner 全部走 Windows 服务托管，别手工起进程", "1. 《进程托管》\n服务化与手工起的区别在开机自启",
     "project", 2, False, "NORMAL"),
    ("这个仓库的提交信息必须带类型前缀", "1. 《提交规范》\n提交信息格式约定",
     "project", 1, False, "NORMAL"),
    ("单元测试跑绿不等于能启动，得至少起一次真实例", "1. 《测试的边界》\n单测覆盖不到的启动期缺陷",
     "global", 2, False, "NORMAL"),
    ("写迁移脚本前先确认表存在，用 COUNT(*) 而不是 SELECT 1", "1. 《迁移脚本》\n建表语句的幂等写法",
     "global", 2, False, "NORMAL"),
    ("前端时间统一走 fmtTime，不要各写各的格式化", "1. 《前端约定》\n时间展示口径要统一",
     "global", 2, False, "NORMAL"),
    ("不要在本机路径里写死密钥，一律进 application-local.yml", "1. 《配置分层》\n本地配置与仓库的关系",
     "global", 2, False, "NORMAL"),
    ("提案正文只有一句「注意一下」这种，不值得沉淀", "1. 《经验的价值》\n含糊的结论别人用不上",
     "discard", 0, False, "NORMAL"),
    ("「重启试试」这类常识不需要写进经验库", "1. 《经验的价值》\n含糊的结论别人用不上",
     "discard", 0, False, "NORMAL"),
    ("把某个一次性事故的完整时间线抄进经验库没有复用价值", "1. 《经验的价值》\n含糊的结论别人用不上",
     "discard", 1, False, "NORMAL"),
    ("这条经验写得还行但步骤不全，整理后才能照着做", "1. 《经验的价值》\n含糊的结论别人用不上",
     "discard", 1, False, "NORMAL"),
    ("SSH 免密登录要先把公钥装到目标机，否则只能走密码", "1. 《免密登录》\n公钥安装是免密的前提",
     "global", 2, True, "NORMAL"),
    ("部署脚本失败要能自己回滚，不能留下半新半旧", "1. 《部署的回滚》\n部署脚本失败要能自己回滚，不能留下半新半旧",
     "global", 2, True, "NORMAL"),
    ("分页接口在数据少时可能直接回数组而不是包装对象", "1. 《接口约定》\n分页包装的两种形态",
     "global", 1, False, "NORMAL"),
    ("WS 事件链里同步等 ack 会自己把自己锁死", "1. 《WS 派发》\n同一连接按帧串行派发",
     "global", 2, False, "NORMAL"),
    ("知识库条目要定期重索引，否则检索会漏新内容", "1. 《检索与索引》\n索引与内容变更的关系",
     "global", 1, False, "NORMAL"),
    ("我们的知识库分两组：全局经验库与项目经验库", "1. 《知识库结构》\n全局与项目两类库的用途",
     "project", 1, False, "NORMAL"),
    ("项目的默认执行节点配在项目设置里，不是全局唯一", "1. 《节点路由》\n节点路由的优先级顺序",
     "project", 2, False, "NORMAL"),
    ("这个仓库的后端测试命令是 mvn -q test，别用 IDE 的绿条代替", "1. 《测试命令》\n命令行跑法与 IDE 的区别",
     "project", 1, False, "NORMAL"),
    ("模型端点要设平台默认，否则分诊会降级成纯人工", "1. 《端点配置》\n默认端点的作用",
     "global", 2, False, "NORMAL"),
    ("换模型之前先在评测集上跑一遍，别凭感觉切", "1. 《模型切换》\n先评测再切换",
     "global", 2, False, "NORMAL"),
    ("编译不过时先确认 JDK 版本，再怀疑依赖", "1. 《工具链版本》\nnode 版本不匹配也会报奇怪错误",
     "global", 2, False, "NORMAL"),
]

# 训练语料：**提案 → 分诊（真模型）→ 人工裁决（本脚本扮演人）→ decision_records**。# gold 只有"采纳层级"一题——采纳动作（adopt）就是人对层级那题的表态；duplicate/quality
# 没有对应的人工动作，所以回流集只训得动层级题（这是 CAP-55 FR-05 的既有口径，不是本脚本的省略）。
# 字段：标题 / 正文 / 裁决去向（global 或 project）
TRAIN = [
    ("构建失败先看日志末尾两百行", "构建失败先翻日志末尾两百行，再去看网络与依赖，顺序反了会白折腾。", "global"),
    ("部署前跑一遍健康检查", "部署脚本里先调 /api/health，返回 UP 才继续换包。", "global"),
    ("回滚要连配置一起回滚", "只换 jar 不换配置，会留下新包配旧配置的脏状态。", "global"),
    ("提测前同步验收清单", "提测前把验收清单发给测试同学，避免来回确认口径。", "global"),
    ("迁移脚本先判表存在", "写迁移前先用 COUNT(*) 判表在不在，别用 SELECT 1。", "global"),
    ("单测跑绿不等于能启动", "新模块提交前至少起一次真实例，启动期缺陷单测看不见。", "global"),
    ("时间展示统一口径", "前端时间一律走 fmtTime，禁止各页面自己格式化。", "global"),
    ("密钥不进仓库", "本机路径与密钥一律写 application-local.yml，commit 前 git status 检查。", "global"),
    ("异步任务别加事务", "异步触发方法加事务会让任务卡在排队中，靠 save 自身事务提交。", "global"),
    ("SSH 免密先装公钥", "免密登录要先把公钥装到目标机，否则只能退回密码。", "global"),
    ("分页返回两种形态", "分页接口数据少时可能直接回数组，脚本两种形态都要兜。", "global"),
    ("WS 事件链别同步等 ack", "WS 每连接按帧串行派发，链内同步等 ack 会自己把自己锁死。", "global"),
    ("换模型前先评测", "切换模型前先在评测集上跑一遍再决定，不要凭感觉。", "global"),
    ("日志不打全量请求体", "日志里不要打全量请求体，敏感字段必须脱敏。", "global"),
    ("灰度先放百分之一", "灰度发布先放 1% 流量，观察半小时再放量。", "global"),
    ("本项目数据目录在 D 盘", "本项目的数据目录挂在 D 盘，不要往 C 盘写。", "project"),
    ("本仓库提交信息带类型前缀", "提交信息格式是 type(scope): 描述，缺前缀会被打回。", "project"),
    ("本项目发布窗口在周四晚", "发布窗口是每周四晚八点，其他时间不发布。", "project"),
    ("runner 走服务托管", "本环境 runner 由系统服务托管，不许手工起进程。", "project"),
    ("本项目先 143 再 224", "发布流程是先在 143 验证，再上 224。", "project"),
    ("本项目默认节点在项目设置里", "项目的默认执行节点配在项目设置里，不是全局唯一。", "project"),
    ("本仓库后端测试命令", "后端测试统一跑 mvn -q test，不用 IDE 的绿条代替。", "project"),
    ("本项目知识库分两组", "知识库分全局经验库与项目经验库，条目按适用范围入库。", "project"),
    ("本项目构建产物不带前端", "本项目的 jar 是瘦包，前端静态资源单独发。", "project"),
    ("本项目端口 8088", "224 环境跑在 8088，8080 被别的进程占了。", "project"),
    ("本项目用 H2 文件库", "开发态用 H2 文件库，路径相对启动目录。", "project"),
    ("本环境 ssh 走跳板", "本环境访问内网机器要走跳板，直连会被拦。", "project"),
    ("本仓库前端类型检查", "前端改动提交前跑 npx tsc -b。", "project"),
]

# 回流集补的对照组。**为什么需要它**（真机跑出来的第二件事）：收编只能按内容自动识别
# EMPTY_RECALL / VERBATIM_DUP 两类（`RecordIntake.detectCaseGroup`，判据就是冻结时那把尺子），
# IRRELEVANT 判不出来——「正文不在召回里」不等于不相关，按那个口径每条普通样本都成了不相关，
# 那一组就再也说明不了任何事情（RecordIntake 的类注释里写着这个取舍）。
# 而冻结红线要求三类齐备、且数据集这一层不分用途同一套规则 → **纯记录收编出来的回流集永远冻不住**。
# 于是缺的那几类由这里手工补（`addItem` 不看 kind，正片补样本本来就是允许的）。
# 内容与 BENCH **不重叠**：训练集里混进评测集的题就是泄漏。
# 字段同 BENCH：content / similar / layer / quality / dup / group
REPLAY_CONTROLS = [
    # 不相关：同是大白话的经验，讲的却是另一回事
    ("每周三凌晨重算一次容量基线",
     "1. 《前端构建缓存》\n前端构建慢多半是依赖缓存失效，与容量统计无关",
     "global", 2, False, "IRRELEVANT"),
    # 逐字重复：召回条目里逐字含正文
    ("回滚前先把当前包备份到上一版目录",
     "1. 《回滚的备份》\n回滚前先把当前包备份到上一版目录，出事还能退回来",
     "discard", 2, True, "VERBATIM_DUP"),
    # 空召回：库里没有可比对条目（这个收编通常认得出，缺了才补）
    ("接口超时时间不要设成无限大",
     "（未召回到相似条目）",
     "global", 1, False, "EMPTY_RECALL"),
]


# ---------------------------------------------------------------- 阶段 1：前置检查

def stage1_preflight(st):
    """前置检查：本机 app / 节点 ssh / 边车 / 反连地址"""
    st_health, _ = call("GET", BASE + "/health")
    check(f"本机 app 在线（{BASE}）", st_health == 200, f"HTTP {st_health}")
    assert st_health == 200, "先按文件头注释起隔离实例（:18097）"

    for path, what in (("/decision/datasets", "评测集"), ("/decision/evaluations", "评测运行"),
                       ("/decision/finetunes", "微调任务"), ("/decision/checkpoints", "产物")):
        total = page(path, size=1)["total"]
        assert total == 0, (f"{what}里已有 {total} 条数据 —— 真机全链要求全新库：\n"
                            f"停 app、rm -rf {TMP.parent / 'cap56-gpu'}、按文件头注释重启后再跑。")
    check("库是全新的（决策实验室四个视图都空）", True)

    rc, _ = ssh("true", must=False)
    check(f"ssh 免密可用（{NODE}）", rc == 0, "先做一次 ssh-copy-id")

    # 远程命令里不放双引号：Windows 的 ssh.exe 对 argv 的转义规则很容易在这里翻车
    rc, out = ssh(f"test -x {VENV_PY} && {VENV_PY} -c 'import laya, torch; "
                  f"print(torch.__version__, torch.cuda.is_available())'")
    check(f"节点 venv 可用且有 CUDA（{VENV_PY}）", "True" in out, out.strip()[:200])
    rc, out = ssh_stdout(f"test -f {BASE_CKPT}/rl_agent_config.json && "
                         f"test -f {BASE_CKPT}/model.safetensors && du -sh {BASE_CKPT} | cut -f1")
    check("节点上有完整的基座 checkpoint（两个必需文件都在）", rc == 0, out.strip()[:200])
    print(f"      基座 {BASE_CKPT} → {out.strip()[:40] if out.strip() else '?'}")

    hz = _get(SIDECAR + "/healthz")
    dev = (hz.get("devices") or {}).get("multilingual") if isinstance(hz, dict) else None
    check(f"节点边车在线且常驻 {SLOT}（device={dev}）",
          isinstance(hz, dict) and SLOT in (hz.get("loaded") or [])
          and (hz.get("sources", {}).get(SLOT, {}) or {}).get("ready") is True, str(hz)[:300])

    ip = st.get("local_ip") or detect_local_ip()
    st["local_ip"] = ip
    check(f"节点能反连本机 app（{ip}:18097）", bool(ip), "节点 ping 不到本机：检查防火墙/网段")


def _get(url, timeout=30):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return _decode(r.read())
    except Exception as e:
        return {"_error": str(e)}


def detect_local_ip():
    port = BASE.rsplit(":", 1)[-1].split("/")[0]
    for ip in LOCAL_IP_CANDIDATES:
        _, out = ssh_stdout(f"curl -s -m 3 -o /dev/null -w '%{{http_code}}' "
                            f"http://{ip}:{port}/api/health", timeout=30)
        if out.strip().endswith("200"):
            print(f"      节点反连本机走 {ip}:{port}")
            return ip
    return None


# ---------------------------------------------------------------- 阶段 2：节点 runner

def stage2_node_runner(st):
    """节点 runner：上传 jar → 发 token → 起进程 → 等上线（协议 v14）"""
    if st.get("node_id"):
        nodes = {n["id"]: n for n in api("GET", "/agent-nodes")}
        n = nodes.get(st["node_id"])
        if n and n["status"] == "ONLINE":
            print(f"      节点 #{st['node_id']} 已在线，跳过部署")
            return
    jar = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
    assert jar.exists(), f"缺 {jar}：先 mvn -q install -DskipTests"

    for n in api("GET", "/agent-nodes"):        # 上一轮失败留下的同名节点（token 已废）先清掉
        if str(n.get("name", "")).startswith(MARK):
            call("DELETE", f"{BASE}/agent-nodes/{n['id']}")

    issued = api("POST", "/agent-nodes", {"name": f"{MARK}-gpu", "labels": LABELS})
    node_id = issued["node"]["id"]
    st["node_id"] = node_id
    save_state(st)                              # 立刻落盘：下面任何一步断了都别再发第二个 token

    script = TMP / "node_runner.sh"
    script.write_text(f"""#!/bin/bash
# CAP-56 真机 E2E 在节点上起一个**临时** runner（指向开发机 app）。
# 刻意与 ~/devmind-runner（指向 224 生产）分开目录、分开 jar，绝不覆盖那份。
set -e
cd {NODE_RUNNER_DIR}
if [ -f runner.pid ] && kill -0 "$(cat runner.pid)" 2>/dev/null; then
  kill "$(cat runner.pid)" || true
  sleep 2
fi
nohup java -jar devmind-agent-runner.jar agent.properties > runner.log 2>&1 &
echo $! > runner.pid
sleep 2
echo "runner pid $(cat runner.pid)"
""", encoding="utf-8", newline="\n")        # 换行钉死 LF：CRLF 的 shell 脚本在节点上报 invalid option

    props = (f"serverUrl=ws://{st['local_ip']}:18097/ws/agent\n"
             f"token={issued['token']}\n"
             "executor=fake\n"                       # 只走 exec 帧，本脚本不起 claude
             f"workDir={NODE_RUNNER_DIR}/work\n"
             f"execAllowlist={VENV_PY}\n"            # 逐行取首 token 校验：缺它一切 exec 被拒
             f"labels={LABELS}\n"                    # requiredLabels 靠它选到这个节点
             "execShell=bash\n")
    (TMP / "agent.properties").write_text(props, encoding="utf-8", newline="\n")

    if DRY:
        print(f"      [dry-run] 将上传 jar + 配置到 {NODE_RUNNER_DIR} 并启动 runner")
        return
    ssh(f"mkdir -p {NODE_RUNNER_DIR}/work {NODE_HOME}/laya-logs")
    scp(jar, NODE_RUNNER_DIR)
    scp(TMP / "agent.properties", NODE_RUNNER_DIR)
    scp(script, NODE_RUNNER_DIR)
    rc, out = ssh(f"bash {NODE_RUNNER_DIR}/node_runner.sh")
    print(f"      {out.strip().splitlines()[-1] if out.strip() else ''}")

    node = wait(lambda: next((n for n in api("GET", "/agent-nodes")
                              if n["id"] == node_id and n["status"] == "ONLINE"), None),
                f"节点 #{node_id} 上线", timeout=120)
    check("节点上线且协议够新（v14 才有带执行包的 exec 帧）",
          (node["protocolVersion"] or 0) >= 14, f"protocolVersion={node['protocolVersion']}")
    check("labels 由 runner 上报（服务端编辑会被它覆盖）", "gpu" in (node["labels"] or ""), f"{node['labels']}")


# ---------------------------------------------------------------- 阶段 3：决策端点

def stage3_endpoint(st):
    """决策端点：登记节点边车为平台默认，并把 `model` 钉到槽位。

    `model` **不能留空**。留空 = 交给边车按语言自动路由，而 laya 0.3.6 内置的 `english` 槽位指的是
    `convaiinnovations/laya`（`DEFAULT_MODELS['english'] = (repo, None)`）——那个仓库根并不是 checkpoint，
    于是任何一条被判定成英文的 state 都会掉进
    `FileNotFoundError: Incompatible model: 'convaiinnovations/laya' does not contain 'rl_agent_config.json'`，
    而且先卡在拉 HF 上（真机日志里抓到过 4 次，每次挂几十秒到分钟级）。
    钉住槽位既绕开这个雷，也才对得上 CAP-56 闸门的语义：放行的是哪份产物，跑的就该是哪份。
    """
    if st.get("endpoint_id"):
        ep = api("GET", f"/model-endpoints/{st['endpoint_id']}")
        if ep.get("model") == SLOT:
            print(f"      端点 #{ep['id']} 已登记（model={SLOT}），跳过")
            return
        body = {k: ep.get(k) for k in ("kind", "name", "provider", "baseUrl",
                                       "timeoutSeconds", "batchSize")}
        body |= {"model": SLOT, "status": ep.get("status") or "active"}
        api("PUT", f"/model-endpoints/{ep['id']}", body)
        print(f"      端点 #{ep['id']} 的 model 补齐为 {SLOT}（原为 {ep.get('model')}）")
        return
    ep = api("POST", "/model-endpoints", {"kind": "DECISION", "name": f"{MARK}-节点边车",
                                          "provider": "laya", "baseUrl": SIDECAR, "model": SLOT})
    api("PUT", f"/model-endpoints/{ep['id']}/default")
    st["endpoint_id"] = ep["id"]
    print(f"      DECISION 端点 #{ep['id']} 已设为平台默认 → {SIDECAR}（model={SLOT}）")
    check("决策端点登记并设为默认", True)


# ---------------------------------------------------------------- 阶段 4：数据飞轮

def stage4_flywheel(st):
    """数据飞轮：提案 → 分诊（真模型）→ 人工裁决 → decision_records"""
    if st.get("flywheel_done"):
        print("      数据飞轮已跑过，跳过")
        return
    # 建项目只为让"采纳到项目"这一半裁决有落点（adopt:project 要项目 id）；
    # path 必须是真实存在的 git 仓库（ProjectService.validateRepo），这里就用本仓库。
    # 已有就复用——同一个仓库路径再登记一次是 409（"该仓库路径已被注册为项目"），
    # 重跑这个阶段不该因为这个崩掉（本阶段其余动作都是追加式，可重跑）。
    if st.get("project_id"):
        project = api("GET", f"/projects/{st['project_id']}")
        print(f"      复用项目 #{project['id']}（{project.get('name')}）")
    else:
        project = api("POST", "/projects", {"name": f"{MARK}-示例项目", "status": "ACTIVE",
                                            "path": str(ROOT).replace("\\", "/")})
        st["project_id"] = str(project["id"])
    kb = api("POST", "/knowledge/bases", {"name": f"{MARK}-经验库", "scope": "global",
                                          "description": "CAP-56 真机 E2E 的召回来源"})

    # 先放几条已收录经验：分诊的"重复判定"要有东西可比（空库时检索会直接降级成"无可比对条目"）
    for name, content in (("构建失败先看日志末尾两百行", "先翻日志末尾两百行，再看网络与依赖。"),
                          ("部署前跑一遍健康检查", "部署脚本先调 /api/health，UP 才继续。"),
                          ("提测前同步验收清单", "把验收清单提前发给测试同学。")):
        api("POST", "/knowledge/entries", {"kbId": kb["id"], "scope": "global", "name": name,
                                           "contentMd": content, "status": "active"})

    opened = []
    for title, content, target in TRAIN:
        p = api("POST", "/knowledge/proposals",
                {"title": title, "contentMd": content, "targetScope": "global",
                 "targetProjectId": st["project_id"]})
        opened.append((p["id"], target))
    print(f"      已提 {len(opened)} 条提案，等真模型分诊（每条几百毫秒）…")

    # 分诊是异步的（提案入库后排队）；等**这一批**每条都拿到快照再裁决。
    # 不能等「决策记录总数」——那是全库累计数，重跑时早就够数，等待立刻通过，
    # 排队中的新提案就被当成降级计进失败里（真机 --from 6 重跑就是这么翻的车）。
    def snapshots():
        return triage_snapshots()

    wait(lambda: all(snapshots().get(str(pid), {}).get("at") for pid, _ in opened),
         f"这一批 {len(opened)} 条提案都拿到分诊快照", timeout=900, every=3)

    def scan():
        """按此刻的快照计数：真模型判过的 / 其中判「重复」的 / 还没判或降级的"""
        ok, yes, bad = 0, 0, []
        snaps = snapshots()
        for pid, _ in opened:
            t = snaps.get(str(pid), {})
            if not t.get("at"):
                bad.append((pid, "还没分诊"))
            elif t.get("degraded"):
                bad.append((pid, t.get("degradedReason")))
            else:
                ok += 1
                if (t.get("duplicate") or {}).get("duplicate"):
                    yes += 1
        return ok, yes, bad

    for pid, target in opened:
        # 人工裁决：采纳去向就是层级题的人工 gold（adopt:project 还要带项目 id）
        q = f"?target={target}" + (f"&projectId={st['project_id']}" if target == "project" else "")
        call("POST", f"{BASE}/knowledge/proposals/{pid}/adopt{q}")
    triaged, dup_yes, degraded = scan()

    # 降级是**可重来**的（平台提供手动重分诊），真机上大面积降级多半是边车被 GPU 争用拖到
    # CPU 退路（节点日志里那句 "GPU memory exceeded during inference. Falling back to CPU"
    # 就是现场：vLLM 占着 95% 显存时，triage 一条要几秒 → 过客户端超时 → 降级）。
    # 环境问题不该把「模型到底判过没有」这条断言判红，所以先重分诊一轮再计数；
    # 重试后仍降级才算真失败（那时是边车本身的问题，正是该报红的）。
    if degraded:
        # 「还没分诊」也要重试：平台的排队任务可能已被前面某次重启吞掉（或边车刚 reload 时
        # 那次调用超时），手动重分诊正是把它捞回来的手段——排除它等于把 21 条一起跳过，
        # 只在报告里留下一个「降级 21 条」的假失败。
        print(f"      {len(degraded)} 条待重分诊（{degraded[0][1][:60] if degraded[0][1] else ''}…）→ 重分诊一轮")
        for pid, _ in degraded:
            retriage(pid, f"提案 #{pid} 重分诊", attempts=2)
        triaged, dup_yes, degraded = scan()

    check(f"{triaged}/{len(opened)} 条提案由真模型分诊完成（重分诊后仍降级的才算失败）",
          triaged >= len(opened) * 0.9, f"降级 {len(degraded)} 条: {degraded[:6]}")
    print(f"      基座在这批题上判「重复」的比例：{dup_yes}/{triaged}"
          f"（恒答重复的退化——训练前的现场证据，不是断言写死的）")

    # 探针：留一条**逐字重复**形态的提案，供第 9 阶段换模型后做前后对比。
    # 分诊是异步的，且边车冷启动可能超时降级 → 走 retriage 等一条新的、没降级的快照，
    # 否则拿到的"训练前判定"是空的（降级快照四元组全 null），后面的对比就没有基线。
    probe = api("POST", "/knowledge/proposals",
                {"title": "构建失败先看日志末尾两百行", "targetProjectId": st["project_id"],
                 "contentMd": "构建失败先看日志末尾两百行，再看网络与依赖，顺序反了会白折腾。",
                 "targetScope": "global"})
    st["probe_id"] = probe["id"]
    st["probe_before"] = triage_values(retriage(probe["id"], "探针提案分诊"))
    print(f"      探针（逐字重复形态）基座判定：{json.dumps(st['probe_before'], ensure_ascii=False)}")

    recs = page("/decision/records", size=5)
    with_gold = [r for r in recs["items"] if r.get("gold") and r.get("trainable")]
    check("裁决落进了决策记录（有 gold 且可训练才收编得动）", bool(with_gold),
          f"抽样 {len(recs['items'])} 条：{json.dumps(recs['items'][0], ensure_ascii=False)[:300]}")
    st["flywheel_done"] = True


# ---------------------------------------------------------------- 阶段 5：回流训练集

def check_dataset_crud(st):
    """评测集 CRUD 的回归：删一个带条目的草稿集

    真机第一轮的发现：`DatasetService.delete` 少了 `@Transactional`，而
    `itemRepo.deleteByDatasetId` 是**派生删除**（Spring Data 逐个 em.remove），没有活事务就 500
    "No EntityManager with actual transaction available"。mock 仓储的单测看不见（事务根本不存在），
    所以这条断言跑在真实例上——它是这个缺陷唯一的回归网。
    """
    throwaway = api("POST", "/decision/datasets", {"name": f"{MARK}-待删草稿集", "kind": "REPLAY",
                                                   "note": "回归用：建完就删"})["dataset"]["id"]
    tpls = {t["caseGroup"]: t for t in api("GET", "/decision/datasets/templates")}
    api("POST", f"/decision/datasets/{throwaway}/items", {
        "state": {"proposal_title": "待删", "proposal_content": "这条样本跟着草稿集一起消失",
                  "project": st.get("project_id"), "similar_entries": "（未召回到相似条目）"},
        "questions": tpls["VERBATIM_DUP"]["questions"],
        "gold": base.gold_for(tpls["EMPTY_RECALL"], "global", 2),
        "caseGroup": "EMPTY_RECALL", "note": "回归用"})
    code, payload = call("DELETE", f"{BASE}/decision/datasets/{throwaway}")
    check("删草稿评测集（带条目）不报错——派生 deleteBy 要有活事务，缺了必 500",
          code == 200, f"HTTP {code}: {str(payload)[:200]}")
    gone = page("/decision/datasets", size=50)["items"]
    check("删掉的草稿集不在列表里", all(d["id"] != throwaway for d in gone), str(payload)[:200])


def topup_control_groups(ds_id, st):
    """给数据集补上缺的对照组（幂等：按 caseGroup 逐类查，缺谁补谁）"""
    tpls = {t["caseGroup"]: t for t in api("GET", "/decision/datasets/templates")}
    questions = tpls["VERBATIM_DUP"]["questions"]        # 三份模板共用同一份标准题面
    for content, similar, layer, quality, dup, group in REPLAY_CONTROLS:
        total = page(f"/decision/datasets/{ds_id}/items", caseGroup=group, size=1)["total"]
        if total:
            print(f"      {group} 已有 {total} 条，不补")
            continue
        gold = dict(base.gold_for(tpls[group], layer, quality))
        for qid, q in questions.items():
            if q.get("type") == "noul":                  # 是非题（duplicate）按构造给，其余是人工标注口径
                gold[qid] = dup
        code, payload = call("POST", f"{BASE}/decision/datasets/{ds_id}/items", {
            "state": {"proposal_title": content[:20], "proposal_content": content,
                      "project": st.get("project_id"), "similar_entries": similar},
            "questions": questions, "gold": gold, "caseGroup": group, "note": "CAP-56 真机回流集对照组"})
        assert code == 200, f"补对照组失败（{group}）: {code} {str(payload)[:300]}"
        after = page(f"/decision/datasets/{ds_id}/items", caseGroup=group, size=1)["total"]
        check(f"回流集补上 {group}（收编认不出这一类，冻结红线要求齐备）", after >= 1, f"{after} 条")


def stage5_replay(st):
    """回流训练集：从决策记录收编 → 补对照组 → 冻结

    **补对照组不是脚本绕过校验**（真机跑出来的第二件事）：收编只能按内容自动识别空召回与
    逐字重复两类，不相关判不出来（判据见 {@link RecordIntake#detectCaseGroup} 的取舍），
    而冻结红线要求三类齐备、数据集这一层不分用途同一套规则——**纯记录收编出来的回流集永远冻不住**。
    缺的那几类按设计手工补（`addItem` 不看 kind）：训练集里带对照组，模型才见得到
    「不该判重复」的样本，顺带也是更好的训练数据。
    """
    ds_id = st.get("replay_id")
    if ds_id:
        print(f"      回流集 #{ds_id} 已建（续跑），跳过收编")
    else:
        ds_id = api("POST", "/decision/datasets", {"name": f"{MARK}-回流集", "kind": "REPLAY",
                                                   "note": "从 decision_records 收编（真人工裁决）"})["dataset"]["id"]
        st["replay_id"] = ds_id
        save_state(st)      # 建完就落盘：否则失败重跑会留下一份孤儿集（真机第一轮就是这样）
        pv = api("GET", f"/decision/datasets/{ds_id}/from-records/preview?capability={CAPABILITY}")
        check("收编预览：候选样本数 = 已裁决的提案数",
              pv["collectable"] >= len(TRAIN) * 0.8,
              f"可收编 {pv['collectable']}，跳过原因 {pv.get('skipReasons')}")
        res = api("POST", f"/decision/datasets/{ds_id}/from-records", {"capability": CAPABILITY})
        check(f"收编入库 {res['added']} 条（跳过 {res['skipped']}，原因逐条给）",
              res["added"] >= len(TRAIN) * 0.8, json.dumps(res.get("skipReasons"), ensure_ascii=False))

    check_dataset_crud(st)
    topup_control_groups(ds_id, st)
    frozen = api("POST", f"/decision/datasets/{ds_id}/freeze")
    counts = {g: n for g, n in (frozen.get("caseGroupCounts") or {}).items() if n}
    check("回流集冻结（训练输入定死；对照组齐且名副其实——冻结校验自己会拦）",
          frozen["dataset"]["frozen"] is True and set(CONTROL) <= set(counts), str(counts))
    print(f"      训练集 #{ds_id}：{frozen['dataset']['itemCount']} 条 {json.dumps(counts, ensure_ascii=False)}")


# ---------------------------------------------------------------- 阶段 6：人工基准集

def stage6_benchmark(st):
    """人工基准集：三类对照组 + 普通样本 → 冻结"""
    if st.get("bench_id"):
        print(f"      基准集 #{st['bench_id']} 已建，跳过")
        return
    tpls = {t["caseGroup"]: t for t in api("GET", "/decision/datasets/templates")}
    questions = tpls["VERBATIM_DUP"]["questions"]        # 三份模板共用同一份标准题面
    ds_id = api("POST", "/decision/datasets", {"name": f"{MARK}-人工基准集", "kind": "BENCHMARK",
                                               "note": "含三类对照组；gold 逐条人工标注"})["dataset"]["id"]
    st["bench_id"] = ds_id
    for content, similar, layer, quality, dup, group in BENCH:
        gold = base.gold_for(tpls[group if group != "NORMAL" else "VERBATIM_DUP"], layer, quality)
        gold = dict(gold)
        for qid, q in questions.items():                 # 三题都按人工判断给 gold（对照组的 duplicate 覆盖构造值）
            if q.get("type") == "noul":
                gold[qid] = dup
        st_code, payload = call("POST", f"{BASE}/decision/datasets/{ds_id}/items", {
            "state": {"proposal_title": content[:20], "proposal_content": content,
                      "project": st.get("project_id"), "similar_entries": similar},
            "questions": questions, "gold": gold, "caseGroup": group, "note": "CAP-56 真机基准集"})
        assert st_code == 200, f"造样本失败（{group}: {content[:16]}）: {st_code} {str(payload)[:300]}"
    frozen = api("POST", f"/decision/datasets/{ds_id}/freeze")
    counts = {g: n for g, n in (frozen.get("caseGroupCounts") or {}).items() if n}
    check(f"基准集冻结通过（{len(BENCH)} 条，对照组齐且名副其实——冻结校验自己会拦）",
          frozen["dataset"]["frozen"] is True and set(CONTROL) <= set(counts), str(counts))
    print(f"      基准集 #{ds_id}：{json.dumps(counts, ensure_ascii=False)}")


# ---------------------------------------------------------------- 阶段 7：基座评测

def stage7_base_eval(st):
    """基座评测：复现「恒答 project / 恒判重复」的退化"""
    ckpt = st.get("base_ckpt_id")
    if not ckpt:
        ckpt = api("POST", "/decision/checkpoints",
                   {"name": f"{MARK}-base-{SLOT}", "serveSlot": SLOT, "kind": "BASE",
                    "sourcePath": BASE_CKPT, "nodeId": st["node_id"],
                    "note": f"节点上的官方基座（{BASE_CKPT}）"})["id"]
        st["base_ckpt_id"] = ckpt
    if st.get("base_eval_id"):
        ev = api("GET", f"/decision/evaluations/{st['base_eval_id']}")
        st["base_accuracy"] = ev["view"]["headline"].get("accuracy")
        print(f"      基座评测 #{st['base_eval_id']} 已完成：accuracy={st['base_accuracy']}")
        return

    # 评测不需要放行（评测恰恰是拿到判断依据的手段），但**分诊需要**——先把闸门两侧都照下来
    gate0 = api("GET", "/decision/checkpoints/gate")
    check("新库 + 一份产物都没放行 → 闸门关着，原因指向决策实验室",
          gate0["open"] is False and "决策实验室" in gate0["reason"], str(gate0)[:200])
    st_code, tri0 = call("GET", f"{BASE}/knowledge/proposals/triage-status")
    check("闸门关着时知识库分诊整体不可用（真机上的运行时降级，不是界面伪灰）",
          st_code == 200 and tri0["available"] is False, f"{st_code} {tri0}")

    ev = api("POST", "/decision/evaluations", {
        "checkpointId": ckpt, "datasetId": st["bench_id"], "nodeId": st["node_id"],
        "requiredLabels": LABELS, "pythonPath": VENV_PY, "fitTemperature": True,
        "outputPath": f"{NODE_HOME}/laya/eval-{MARK}", "timeoutSec": 3600})
    st["base_eval_id"] = ev["id"]
    print(f"      基座评测 #{ev['id']} 已下发（节点上真跑 laya 批量推理），等它跑完…")
    done = wait_settled(f"/decision/evaluations/{ev['id']}", f"基座评测 #{ev['id']}", timeout=3600)
    v, rep = done["view"], done["report"]
    check("基座评测跑通且报告齐备（REPORT_OK）",
          v["status"] == "SUCCESS" and v["reportStatus"] == "OK",
          f"{v['status']} / {v.get('errorSummary')}")
    if v["status"] != "SUCCESS":
        return
    hp = v["headline"]
    print(f"      基座头条：{json.dumps(hp, ensure_ascii=False)}")
    check("头条同时给出随机与多数类两条基线", isinstance(hp.get("random"), (int, float))
          and isinstance(hp.get("majority"), (int, float)), str(hp))
    groups = {g["caseGroup"]: g for g in rep["byCaseGroup"]}
    check("三类对照组都在报告里分组给准确率", set(CONTROL) <= set(groups), list(groups))
    if {"EMPTY_RECALL", "VERBATIM_DUP"} <= set(groups):
        e, d = groups["EMPTY_RECALL"]["accuracy"], groups["VERBATIM_DUP"]["accuracy"]
        check(f"退化现形：空召回组准确率 {e} ≈ 逐字重复组 {d}（两组 gold 相反，模型却答成同一个）",
              e is not None and d is not None and d > e, f"{groups}")
    noul = (rep["metrics"].get("noul") or {})
    check(f"noul 判「重复」的占比 {noul.get('noulRate')}（恒答重复 = 接近 1）",
          isinstance(noul.get("noulRate"), (int, float)), str(noul))
    st["base_accuracy"] = hp.get("accuracy")

    # 放行基座：它就是边车此刻在服务的那份，也是随后整条数据飞轮的准入前提
    if not st.get("base_verified"):
        api("POST", f"/decision/checkpoints/{ckpt}/verify",
            {"note": f"真机 E2E：基座指标已看过（accuracy={st['base_accuracy']}，"
                     f"空召回组 {groups.get('EMPTY_RECALL', {}).get('accuracy')}），"
                     f"边车此刻服务的就是它，先放行以启用分诊"})
        st["base_verified"] = True
    gate1 = api("GET", "/decision/checkpoints/gate")
    st_code, tri1 = call("GET", f"{BASE}/knowledge/proposals/triage-status")
    check("放行基座 → 闸门打开、分诊可用（同一上游，两侧不可能不一致）",
          gate1["open"] is True and tri1["available"] is True and tri1["reason"] == "",
          f"{gate1} / {tri1}")


# ---------------------------------------------------------------- 阶段 8：RLCD 微调

def stage8_finetune(st):
    """RLCD 微调：回流集训练 → 指纹登记 → 自动回评"""
    if not st.get("ft_id"):
        out = f"{FT_ROOT}/{MARK}"
        ft = api("POST", "/decision/finetunes", {
            "datasetId": st["replay_id"], "evalDatasetId": st["bench_id"],
            "baseCheckpointId": st["base_ckpt_id"], "nodeId": st["node_id"],
            "requiredLabels": LABELS, "pythonPath": VENV_PY, "outputPath": out,
            "epochs": int(os.environ.get("DEVMIND_GPU_EPOCHS", "10")),
            "learningRate": 2e-4, "batchSize": 8, "trainSeed": 42, "splitSeed": 42,
            "trainRatio": 0.8, "timeoutSec": 7200})
        st["ft_id"] = ft["id"]
        st["ft_out"] = out
        print(f"      微调 #{ft['id']} 已下发（{ft['trainCount']} 训练 / {ft['valCount']} 验证 → {out}）")
    else:
        print(f"      微调 #{st['ft_id']} 已在库里，接着等")

    done = wait_settled(f"/decision/finetunes/{st['ft_id']}", f"微调 #{st['ft_id']}", timeout=7200)
    fv = done["view"]
    check("RLCD 训练跑通（真 torch 训练，不是 stub）", fv["status"] == "SUCCESS",
          f"{fv.get('errorSummary')}")
    if fv["status"] != "SUCCESS":
        return
    print(f"      训练段：{json.dumps(done.get('train'), ensure_ascii=False)[:300]}")
    ck = api("GET", f"/decision/checkpoints/{fv['checkpointId']}")["checkpoint"]
    st["ft_ckpt_id"] = ck["id"]
    check("产物自动登记：FINETUNED + 64 位 sha256 + 未验证（训练成功 ≠ 放行）",
          ck["kind"] == "FINETUNED" and len(ck["fingerprintSha256"] or "") == 64
          and ck["verified"] is False, json.dumps(ck, ensure_ascii=False)[:300])
    check("来源路径取脚本实报的那份（不是人填的输出路径）",
          base.norm(ck["sourcePath"]) == base.norm(ck["fingerprintPath"]) == base.norm(st["ft_out"]),
          f"{ck['sourcePath']} / {ck['fingerprintPath']} / {st['ft_out']}")

    post = wait_settled(f"/decision/evaluations/{fv['evalId']}", f"回评 #{fv['evalId']}", timeout=3600)
    pv, prep = post["view"], post["report"]
    check("结束自动回评跑通", pv["status"] == "SUCCESS" and pv["reportStatus"] == "OK",
          f"{pv['status']} / {pv.get('errorSummary')}")
    if pv["status"] != "SUCCESS":
        return
    hp = pv["headline"]
    print(f"      回评头条：{json.dumps(hp, ensure_ascii=False)}")
    st["ft_accuracy"] = hp.get("accuracy")
    st["compare"] = prep.get("compare")
    cmp_ = prep.get("compare") or {}
    # 口径是"不差于基座"，不是"必须更好"：RLCD 的收敛性只能在真机验（计划 §九），
    # 训练规模（几十条）决定了打平是**可能且合理的结论**——但打平要显式说出来，
    # 不能拿一个 ≥ 悄悄盖过去（真机第二轮就是打平：24/111 对 24/111，逐题 43 胜 66 负）。
    check(f"回评不差于基座（基座 {st.get('base_accuracy')} → 回评 {hp.get('accuracy')}）；"
          f"逐题胜/负/平 {cmp_.get('win')}/{cmp_.get('lose')}/{cmp_.get('tie')}",
          isinstance(hp.get("accuracy"), (int, float))
          and st.get("base_accuracy") is not None
          and hp["accuracy"] >= st["base_accuracy"],
          f"base={st.get('base_accuracy')} ft={hp.get('accuracy')}")
    if isinstance(hp.get("accuracy"), (int, float)) and st.get("base_accuracy") is not None \
            and hp["accuracy"] == st["base_accuracy"]:
        print("      ！回评与基座打平：逐题有胜有负、净分不变——这不是脚本错误，"
              "是这批数据的结论（几十条样本的 RLCD 微调没在基准题面上变成更好的判断）")
    groups = {g["caseGroup"]: g["accuracy"] for g in prep["byCaseGroup"]}
    st["ft_by_group"] = groups
    print(f"      回评分组准确率：{json.dumps(groups, ensure_ascii=False)}")


# ---------------------------------------------------------------- 阶段 9：放行与 serve

def switch_sidecar(source, tag):
    """把边车槽位来源切到 source（只换来源，不改槽位名），等就绪。

    脚本走 scp 上传再 ssh 执行：**新写的脚本必须 newline="\\n"**——Windows 上 write_text 会把
    \\n 翻成 \\r\\n，节点那边报 `set: - invalid option` / `cd: $'…\\r'`（真机第一轮踩过）。
    """
    script = TMP / f"node_sidecar_{tag}.sh"
    script.write_text(f"""#!/bin/bash
set -e
pkill -f "uvicorn app:app" || true
sleep 2
cd {NODE_HOME}/laya-sidecar
nohup env LAYA_SLOT_MODELS='{{"{SLOT}":"{source}"}}' LAYA_PRELOAD={SLOT} LAYA_DEVICE={SIDECAR_DEVICE} \\
  {VENV_PY} -m uvicorn app:app --host 0.0.0.0 --port 8377 > {NODE_HOME}/laya-logs/sidecar-{tag}.log 2>&1 &
sleep 2
echo "sidecar restarted ({tag})"
""", encoding="utf-8", newline="\n")
    scp(script, NODE_RUNNER_DIR)
    rc, out_msg = ssh(f"bash {NODE_RUNNER_DIR}/node_sidecar_{tag}.sh")
    print(f"      {out_msg.strip().splitlines()[-1] if out_msg.strip() else ''}")
    return wait(lambda: (lambda h: h if isinstance(h, dict)
                         and (h.get("sources", {}).get(SLOT, {}) or {}).get("ready") is True
                         and h.get("sources", {}).get(SLOT, {}).get("path") == source else None)(
        _get(SIDECAR + "/healthz")), f"边车切到 {source} 并就绪", timeout=600, every=5)


def stage9_serve(st):
    """放行与 serve：边车跑基座 → 自检+探针记「前」→ 换来源 → 自检+探针记「后」→ 放行

    这一阶段**每一步都对着边车此刻的真实状态**，所以可重跑：先确保边车在跑基座
    （续跑时它可能已经是微调产物——上一轮走到这里被打断了），"换来源之前自检必 FAIL"
    才有对象。恒假的断言比没有断言更糟，所以宁可多花一次模型加载。
    """
    ft_ckpt, out = st.get("ft_ckpt_id"), st.get("ft_out")
    if not ft_ckpt:
        print("      没有微调产物（上一阶段没跑成），跳过")
        return

    hz0 = _get(SIDECAR + "/healthz")
    cur = ((hz0.get("sources") or {}).get(SLOT) or {}).get("path") if isinstance(hz0, dict) else None
    if cur != BASE_CKPT:
        print(f"      边车此刻服务的是 {cur}（不是基座）→ 先切回基座，把「换来源前」这一步做真")
        switch_sidecar(BASE_CKPT, "base")

    sc = api("POST", f"/decision/checkpoints/{ft_ckpt}/serve-check")
    check("换来源**之前**自检必 FAIL：放行的那份不是边车正在跑的那份",
          sc["status"] == "FAIL", f"{sc['status']} / {sc['summary']}")

    # 「前」就地量：不依赖状态文件里那份（第 4 阶段记的、续跑时可能不存在或已降级）
    before = triage_values(retriage(st["probe_id"], "基座分诊（前）"))
    print(f"      探针（边车跑基座）：{json.dumps(before, ensure_ascii=False)}")

    hz = switch_sidecar(out, "ft")
    check("边车已服务于微调产物（槽位名不变，只换来源）",
          hz["sources"][SLOT]["overridden"] is True and hz["sources"][SLOT]["path"] == out,
          json.dumps(hz.get("sources", {}).get(SLOT), ensure_ascii=False))

    sc2 = api("POST", f"/decision/checkpoints/{ft_ckpt}/serve-check")
    check("换来源后自检 OK：登记的这份 == 边车正在服务的那份",
          sc2["status"] == "OK", f"{sc2['status']} / {sc2['summary']}")

    after = triage_values(retriage(st["probe_id"], "微调产物分诊（后）"))
    st["probe_before"], st["probe_after"] = before, after
    print(f"      探针（边车跑微调产物）：{json.dumps(after, ensure_ascii=False)}")
    graded = ("duplicate", "probability", "layer", "quality")
    # 这条断言只保证「跑的是换过去的那份」，**不保证判定变好**：真机上确实出现过判定三件套
    # （duplicate/layer/quality）一模一样、只有置信度移动的情形——那是模型给出的结论，不是脚本能修的，
    # 所以把"变了什么"打在日志里，别让读日志的人把"徽标变了"读成"判断变好了"。
    moved = [k for k in graded if before.get(k) != after.get(k)]
    flipped = (before.get("duplicate"), before.get("layer"), before.get("quality")) != \
              (after.get("duplicate"), after.get("layer"), after.get("quality"))
    print(f"      换模型后变动：{moved or '无'}"
          f"（{'判定类翻转' if flipped else '判定类没翻，只有置信度动——如实记，不是脚本错误'}）")
    check("知识库分诊徽标随之改变（同一条提案，边车换模型后分诊结论不同）",
          not before.get("degraded") and not after.get("degraded")
          and any(before[k] != after[k] for k in graded),
          f"前后一致或降级：{json.dumps(before, ensure_ascii=False)} / {json.dumps(after, ensure_ascii=False)}")
    check("分诊未降级、模型名报出边车实况", bool(after.get("model")) and not after.get("degraded"),
          json.dumps(after, ensure_ascii=False)[:200])

    if not st.get("verified"):
        v = api("POST", f"/decision/checkpoints/{ft_ckpt}/verify",
                {"note": f"真机 E2E：基准集 accuracy {st.get('ft_accuracy')} "
                         f"≥ 基座 {st.get('base_accuracy')}，自检 OK（换来源后）"})
        check("人工放行微调产物（记下依据与放行人）",
              v["verified"] is True and bool(v["verifiedBy"]), json.dumps(v, ensure_ascii=False)[:300])
        sup = api("GET", f"/decision/checkpoints/{st['base_ckpt_id']}")["checkpoint"]
        check("放行微调产物顶掉同槽位的基座，被顶的那份保留放行史（失宠不作废）",
              sup["verified"] is False and bool(sup["verifiedNote"]),
              json.dumps({k: sup[k] for k in ("verified", "verifiedNote")}, ensure_ascii=False))
        st["verified"] = True

    st_code, tri = call("GET", f"{BASE}/knowledge/proposals/triage-status")
    check("放行后分诊可用（闸门与按钮置灰同一上游）",
          st_code == 200 and tri["available"] is True, f"{st_code} {tri}")


# ---------------------------------------------------------------- 轮询工具

def wait_settled(path, what, timeout=3600):
    """等一条评测/微调离开 QUEUED/RUNNING；长任务每隔一会儿打一行进度。"""
    t0 = time.time()
    last = 0.0
    while time.time() - t0 < timeout:
        done = api("GET", path)
        status = done["view"]["status"]
        if status in ("SUCCESS", "FAILED"):
            if status == "FAILED":
                print(f"      {what} FAILED: {done['view'].get('errorSummary')}")
            return done
        if time.time() - last > 30:
            last = time.time()
            logs = call("GET", f"{BASE}{path}/logs")[1]
            tail = (logs if isinstance(logs, str) else "").strip().splitlines()[-1:] if logs else []
            print(f"      {what} {status}（已 {int(time.time() - t0)}s）{tail[0][:120] if tail else ''}")
        time.sleep(5)
    raise AssertionError(f"超时等待 {what}（{timeout}s）")


# ---------------------------------------------------------------- main

STAGES = [stage1_preflight, stage2_node_runner, stage3_endpoint, stage6_benchmark,
          stage7_base_eval, stage4_flywheel, stage5_replay, stage8_finetune, stage9_serve]


def main():
    global TOKEN, DRY
    ap = argparse.ArgumentParser(description="CAP-56 真机（GPU 节点）全链 E2E")
    ap.add_argument("--from", dest="start", type=int, default=1, help="从第几阶段开始（默认 1）")
    ap.add_argument("--to", dest="end", type=int, default=len(STAGES), help="跑到第几阶段为止")
    ap.add_argument("--local-ip", help="节点反连本机 app 用的地址（默认自动探测）")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不落数据")
    args = ap.parse_args()
    DRY = args.dry_run

    TMP.mkdir(parents=True, exist_ok=True)
    login = api("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    TOKEN = login["accessToken"]
    st = load_state()
    if args.local_ip:
        st["local_ip"] = args.local_ip
    print(f"[0] 登录 OK；app={BASE}；节点={NODE}；解释器={VENV_PY}")

    for i, fn in enumerate(STAGES, 1):
        if i < args.start or i > args.end:
            continue
        step(f"[{i}/{len(STAGES)}] {fn.__doc__.strip().splitlines()[0]}")
        fn(st)
        save_state(st)

    print(f"\n[9] 汇总：{PASSED} 通过 / {FAILED} 失败")
    print(f"    状态留痕 {STATE_FILE}")
    if st.get("base_accuracy") is not None or st.get("ft_accuracy") is not None:
        print(f"    基座 accuracy={st.get('base_accuracy')} → 微调后 {st.get('ft_accuracy')}；"
              f"分组 {json.dumps(st.get('ft_by_group'), ensure_ascii=False)}")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
