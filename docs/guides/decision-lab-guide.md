# 决策实验室使用指南（评测集 → 评测 → 微调 → 回评 → 放行 → serve）

> 适用：CAP-56 决策模型评测与微调闭环（依赖 CAP-55 决策引擎 / CAP-48 模型端点 / CAP-12·21·34 执行底座）。
> 页面入口：**后台 → 决策实验室**（`/admin/decision-lab`）。日期：2026-09-22

## 0. 这套东西解决什么问题

CAP-55 把 laya 决策模型接进了产品路径（知识库提案分诊），但**没有任何「这套判断准不准」的环节**。
2026-09-22 真机实测坐实了这个洞：`multilingual` base checkpoint 在分诊题面上，`duplicate`(noul) 三组对照
（含**空召回**组与**不相关**组）全判「重复」（0.988 / 0.931 / 0.972），`adopt_layer` 三组全 `project`——
即**恒答同一个答案**，单看准确率却「看起来在工作」。

决策实验室建的是两条腿 + 一道闸门：

| 腿 | 做什么 | 回答的问题 |
|---|---|---|
| 评测 | 固定题面 + 官方口径指标 + **必报随机/多数类基线** | 这套判断到底比瞎猜好多少 |
| 微调 | 用我们自己的 gold 在我们的题面上跑 RLCD 微调，自动回评 | 训完之后真的更好了吗 |
| 闸门 | 只有人工「验证通过」的产物才允许消费方启用 | 现在跑的那份，是放行的那份吗 |

**本 CAP 不含任何新消费方**——只把「判断好坏」做成流程；验证通过后才谈接入检索重排 / 自动打标等。

## 1. 全景图

```
① 评测集（含三类对照组）──冻结──▶ 定格的「考题 + 标准答案」
        ▲                              │
        │ 从 decision_records 收编       │ ② 发起评测（选 checkpoint × 数据集 × 节点）
        │（线上已人工裁决的记录）          ▼
   CAP-55 分诊使用                    runner 节点上跑 laya_eval.py
        ▲                              │ 本地批量推理，不回打边车 HTTP
        │ ④ 闸门放行后才可用              ▼
        │                          报告：指标 + 随机/多数类基线 + 逐题胜负 + 校准前后 ECE
        │                              │
        │                              ▼ ③ 发起微调（GPU 节点，requiredLabels=gpu,<型号>）
        │                          laya_train.py（RLCD）→ 权重**留在节点**
        │                              │ 只回传 指标 + 指纹（路径/大小/sha256）
        │                              ▼
        │                          结束自动回评（FR-03 评测，对照 base）
        │                              │
        └──────────── ④ 人工「验证通过」◀── serve 自检核对「登记的那份 == 边车正在跑的那份」
```

四个视图 = 流程的四个阶段：**评测集 / 评测运行 / 微调任务 / Checkpoint 登记**。

## 2. 页面操作：四视图

### 2.1 评测集（`datasets`）

- **新建**：名称 + 类型（`BENCHMARK` 人工基准集 / `REPLAY` 回流集）+ 说明。新建后逐条加题。
- **加题**：每条题面 = `state`（召回上下文，JSON）+ `questions`（题面定义，JSON）+ `gold`（人工裁决，JSON）
  + `case_group`。页面提供**「取标准题面 + gold 骨架」**，从内置模板预填，避免手写 schema 出错；
  题面与 gold 的 JSON 由页面 `JSON.parse` 校验，非法直接拒绝。
- **对照组（红线）**：`case_group` 必须覆盖三类
  `EMPTY_RECALL`（空召回）/ `VERBATIM_DUP`（逐字重复）/ `IRRELEVANT`（不相关干扰），
  外加 `NORMAL` 普通样本。**没有对照组，「恒答重复」这种退化看起来也「合理」**——这是 CAP-56 的立身教训。
- **从决策记录收编**（`from-records`）：按 capability + 时间窗拉线上已人工裁决的记录 → **先预览**
  （跳过原因 + 候选样本）→ 确认收编。跳过原因逐条给出（没裁决 / 题面不匹配 / 已有同题…），
  不会静默丢样本。收编时能按内容**自动识别**对照组。
- **冻结**：冻结后本集只读，成为「指标的分母」（改 = 修订为新版本，否则指标不可比）。
  冻结校验三条，任一不过直接拒绝并给原因：
  1. **非空**；
  2. **对照组齐且名副其实**——数量齐是「标注做完了」，标签与 state 内容对得上才是「没标错」
     （对不上的点名到条目 id）；
  3. **题面版本唯一**——混着不同题面版本说明代码换过题面，指标会被无声对齐到新题面。
- **修订为新版本**：冻结后要改，走 `revise` 派生新版本，旧版本与它名下的历史指标都保留。

> 为什么题面版本要进指标主键：题面是**代码常量**（`TriageQuestions`），改题面等于换训练目标。

### 2.2 评测运行（`evaluations`）

发起时选：**被测 checkpoint** × **评测集**（只列已冻结的）× 节点/必需标签，可选
**基线 checkpoint**（对照出逐题胜负）与**温度校准**（`fitTemperature`，默认开）。

- **报告永远带两条基线**：随机 + 多数类。没有基线，「准确率 0.72」读不出好坏。
- 指标口径照抄 laya 官方，分 `choice` / `score` / `noul` 三种题型块：
  `questions` / `accuracy` / `avgConfidence` / `ece` / `softAccuracy` / `brier`，
  score 另有 `mae` / `within1Level`，noul 另有 `noulRate` / `answered`；再给延迟 `p50` / `p95`。
- **逐题明细**：`perItem[]` 每行给出题面、gold、预测、对错、置信度、延迟——赢在哪输在哪，
  而不是只给一个总分。列表页 5 秒轮询活跃行，明细在详情抽屉，实时日志走 WS 抽屉。
- **温度校准**（FR-04）：按 `(题型, 选项数)` 各拟合一个温度，**是拟合不是训练，不需 GPU**。
  报告里给校准前后 ECE 对比与每个桶的 T。意义是让徽标上的百分比别骗人——
  CAP-55 的「置信度只作展示、不作自动执行依据」在本 CAP **不变**。
- **硬规则：缺项显示「—」不显示 0**。准确率 0 与「没测」必须两回事。

### 2.3 微调任务（`finetunes`）

发起时选：**训练集**（回流集）× **回评集**（**不能与训练集同一份**，前端直接拦）× base checkpoint
× 输出路径 × 超参（epochs / learningRate / batchSize / trainRatio / trainSeed / splitSeed）。
切分种子固定默认值的好处是「两次跑同一份集、只改超参」时验证集是同一批题——
否则指标变化里混着「换了一批考题」，说不清是模型变好了还是题目变简单了。

- 数据切分（train/val，条数进库）→ 按 `requiredLabels` 调度到 GPU 节点 → 跑 RLCD 移植脚本
  → 日志走日志 Hub（WS 抽屉）→ **结束自动触发一次 FR-03 评测**（回评）。
- **「训练成功」不等于「学得更好」**：列表的「产物与回评」列先显示回评结论，回评失败显示原因，
  都还没有显示「—」。看回评，不看退出码。
- **权重留在节点**（exec 链路没有文件上行通道），平台只收**指标 + 指纹**（路径 / 大小 / sha256）
  并自动登记一条 checkpoint。

### 2.4 Checkpoint 登记（`checkpoints`）

- **登记**：名称 + 槽位（`typed-decisions` / `multilingual` / `english`，页面给 AutoComplete）+
  类型（`BASE` 官方基础模型 / `FINETUNED` 微调产物）+ **来源路径（必填）** + 指纹（FINETUNED 应有 sha256）
  + 指标 JSON + 说明。微调结束会自动登记，手工登记用于官方 base 或手工产物。
- **serve 自检**：打边车 `/healthz`，核对**「登记的那份 == 边车正在服务的那份」**——
  比对 `sources[槽位].path` 与登记的 `sourcePath`、`ready`（两个必需文件在不在）、设备。结果落库。
  这是「放行的就是正在跑的」的**唯一凭据**，所以没自检过就放行时页面会警告你。
- **验证通过 / 撤销放行**：人工确认。放行要写说明，撤销要写原因（都进审计）。
- **顶部闸门横幅**：闸门开启时说明现在为什么不可用、有几种产物、要做什么。

## 3. 闸门（FR-07）与它的副作用

判定落点**唯一**：`HttpDecisionEngine.unavailableReason()` —— 它同时是
**按钮置灰**（`KnowledgeTriageService.status()` → 分诊页徽标/按钮）与
**运行时降级**（`decide()` 先查它）的共同上游。加一句判定即全链生效。

闸门口径（沿用 CAP-55，配置侧不探活）：

- 只要**存在** `verified=true` 的 checkpoint → 放行；一份都没有 → 拦住并给出具体原因文案
  （「还没登记任何产物」/「登记了 N 份但都没验证」），页面直接展示这段文案。
- 不做实时探活——边车真挂了不该让按钮变灰，那是运行时降级（`degraded`）该管的事。
- **模块不在场 = 无闸门 = 保持 CAP-55 行为**（`ObjectProvider<DecisionGate>` 探测）。

> ⚠ **上线副作用（必读）**：闸门生效后，**没有任何已验证 checkpoint 时知识库分诊整体不可用**、
> 按钮置灰（悬停给原因）。这正是要的机械保证，但 224 环境上线后必须**先登记 + 验证一个 checkpoint**
> 才恢复可用。`tests/cap55_*.py` 的期望需随之更新（待办：与本 CAP 的 E2E 一并在下一步补）。

## 4. 节点侧（runner）准备 —— 缺一项就下发即被拒

| 项 | 要求 | 说明 |
|---|---|---|
| `execAllowlist` | 加 `python`（用了多卡再加 `torchrun`）**首 token 前缀** | 逐行取首 token 校验；**空值 = 拒绝一切 exec**。`torchrun` 由脚本内部按 shlex 起子进程，不占首 token，但节点上仍要有它 |
| `labels` | GPU 节点用 `labels=gpu,<型号>`（如 `gpu,T4`） | `AgentNodeRouter.route(..., requiredLabels)` 消费；**runner 上报的 labels 会覆盖服务端编辑值** |
| `pythonPath` | 节点上解释器**单 token 无空白**全路径 | 带空格会被白名单打回；Windows 用 venv 的 `Scripts/python.exe`，Linux 用 `/opt/laya/venv/bin/python`。平台侧默认 `python` |
| Python 依赖 | 节点 venv 里 `pip install laya torch`（训练额外需要 torch 优化器，laya 0.3.5 已含 `build_sequence`/`render_options`/`proper_reward` 原语） | **不需要**外部训练仓库 |
| 基础 checkpoint | 放在节点本地目录（`rl_agent_config.json` + `model.safetensors` 缺一不可） | 权重与 serve 同机是本期设计前提 |
| 协议版本 | runner 需支持 **协议 v14**（exec 帧带 bundle） | 老 runner 会在节点页提示版本不足 |

**执行链怎么跑**（排错时看这里）：服务端渲染**单行**命令
`"<pythonPath>" "$DEVMIND_LAB_SCRIPT" --payload "$DEVMIND_LAB_PAYLOAD" --out "<输出路径>" …`；
脚本与数据**不内联**（源码行会被逐行白名单校验打回），而是 runner 经
`GET /api/agent/decision-lab/bundles/{kind}/{id}?token=` 主动拉包 → 物化 → 注入
`DEVMIND_LAB_SCRIPT` / `DEVMIND_LAB_PAYLOAD` 两个环境变量。**拉取失败即 exec 失败，不降级**
（同 CAP-34 launch 帧口径）。指标与指纹靠脚本打印单行 marker，服务端从日志流里抓取后
**把 marker 行从日志里剔掉**（页面上看不到这些噪音）。

## 5. 服务端配置

`devmind.decision-lab.*`（开发态默认值可用；分发包写在 `config/application.yml`）：

```yaml
devmind:
  decision-lab:
    scripts-dir: lab          # 评测/微调脚本目录；服务端打包执行包时读它
                              # 开发态默认 tools/laya-sidecar/lab（cwd=仓库根）；
                              # 分发包里脚本在 APP_HOME/lab，配置为相对路径 lab（跟随启动目录）
    python-path: python       # 节点上解释器（命令行首 token，单 token 无空白）
    max-concurrent-runs: 2    # 同时进行的评测/微调上限（一次运行独占一个节点许可）
    default-timeout-sec: 3600 # 单次运行默认超时；上限 max-timeout-sec=86400（RLCD 可能跑整夜）
    # 微调默认超参（请求里可逐项覆盖）：epochs=3 / learning-rate=1e-4 / batch-size=8
    # train-seed=42 / split-seed=42 / train-ratio=0.8 / launcher=""（空 = 单进程）
```

`scripts-dir` 直接决定能不能发起：未配置或目录不存在时，**发起阶段就被拒绝并说明该配哪一项**，
不会把问题留到节点上报错。

## 6. 边车：槽位来源覆盖与 serve 自检（FR-01）

自训产物要能被 serve，靠的是**「槽位 → 来源」覆盖**（`tools/laya-sidecar`）：

```bash
# 方式一：环境变量（JSON 或 k=v）
LAYA_SLOT_MODELS='{"typed-decisions": "/data/out/ft7"}' \
HF_ENDPOINT=https://hf-mirror.com LAYA_PRELOAD=multilingual \
python -m uvicorn app:app --port 8377
# 方式二：models.json（与本文件同目录；LAYA_MODELS_FILE 可覆盖路径）
#   默认路径不存在 = 用内置默认；显式指定却不存在 = 报错（不静默回落）
```

- **只换来源，不改槽位名**：`typed-decisions` 永远叫 `typed-decisions`，变的只是它背后加载哪个目录/仓库。
  平台侧登记的「槽位」因此不需要跟着变。
- 值可以是**本地绝对路径**（微调产物就落在节点上）或 HF 仓库名。
- `GET /healthz` 增报每个槽位的**实际来源** `sources{slot: {kind, path, overridden, ready, …}}`
  + `source_origin`（这份配置从哪来）+ `overridden_slots` + `unready_slots`。
  报的是 Router **此刻真正持有的 spec**，不是配置文件原文——两者不一致时，该信的是前者。
- 页面的 **serve 自检** = 读 `/healthz` 的 `sources` 与登记信息比对。**先自检、再放行。**

## 7. 排错速查

| 现象 | 原因 / 处置 |
|---|---|
| 发起评测/微调就被拒，报 `scripts-dir` | 服务端没配脚本目录或目录不存在；按 §5 配 `devmind.decision-lab.scripts-dir` |
| 发起被拒，报 pythonPath 含空白 | 解释器路径带空格；换成不含空格的 venv 全路径（§4） |
| 节点报「命令不在 execAllowlist 白名单」 | 报错会列出允许前缀；把缺的首 token（`python` / `torchrun`）加进节点 `agent.properties` 并**重启 runner** |
| 节点报拉包失败（404/401） | 边车/服务端地址或节点 token 不对；runner 用自身 `serverUrl` 拉包（§4 执行链） |
| launch/exec 帧的字段像没生效 | 新字段漏拼帧的经典事故：`AgentConnectionRegistry.exec/launch` 未补 `put`（见 `docs/core/开发注意事项.md`） |
| 知识库分诊按钮置灰 | **闸门**：还没有 `verified=true` 的 checkpoint；去决策实验室登记 + 自检 + 验证通过（§3） |
| 报告里指标是「—」 | 该项**没测**（不是 0）；如 `noul` 块只在题面含 noul 题时才有数 |
| 分诊徽标置信度看着离谱 | 跑一次带温度校准的评测（FR-04），校准后 ECE 会降下来；校准参数随 checkpoint 元数据走 |
| 报告看不出模型退化 | 看 `byCaseGroup`：对照组全判同一个答案 = 恒答退化（这正是要对照组的原因） |
| 微调「成功」但回评更差 | 正常结果，以回评为准（§2.3）；RLCD 收敛性只能靠真机验证，退化会被报告显式暴露 |

## 8. 相关文档

- 需求与验收标准：[docs/capabilities/CAP-56-decision-eval-finetune.md](../capabilities/CAP-56-decision-eval-finetune.md)
- 上游决策引擎与分诊：[docs/capabilities/CAP-55-decision-engine.md](../capabilities/CAP-55-decision-engine.md)
- runner 部署：[runner-service-deploy.md](runner-service-deploy.md)、[admq-manager-cicd-guide.md](admq-manager-cicd-guide.md)（execAllowlist 实务）
- 开发坑位（H2/Jackson/WS 等）：[docs/core/开发注意事项.md](../core/开发注意事项.md)
