# CAP-56 决策模型评测与微调闭环（Decision Model Evaluation & Fine-tuning Loop）

> 状态：**真机全链跑通（2026-09-23，172.20.140.88 GPU 节点，真 laya 模型 + 真 torch 训练 + 真边车）**
> （FR-01 边车槽位覆盖 5638823 + serve 自检消费端 f4896d9、FR-02 评测集与冻结校验 fe137fa
> + 回流收编 0241cbd、FR-03 评测运行编排 7854f8e + 指标脚本 7f7725f、FR-04 温度校准（评测侧 `fitTemperature`，
> 同 7854f8e/7f7725f）、FR-05 微调编排 2ae2dc3 + RLCD 脚本 7f7725f、FR-06 登记与指纹 9263697/2ae2dc3、
> FR-07 准入闸门 9263697、FR-08 前端四视图 0bf4737 已落地；FR-09 口径更正见 c6ab1a0。
> 承载机制：题面上提 95ec1c2、exec 帧 bundle 端点 + runner 协议 v14 e128cc1。
>
> **真机结论**（`tests/cap56_gpu_e2e.py`，41 条断言全绿）：
> ① **退化复现**——基座在 37 题基准集上 accuracy **0.216** < 随机基线 0.389 < 多数类基线 0.676；
> 分组 空召回 0.25 / 逐字重复 0.583 / 不相关 0.25 / 普通 0.4（noulRate 0.80）——即"恒答重复"，
> 与本 CAP 缘起里那次实测（0.988/0.931/0.972）同源。
> **按题面拆开看，坏的是分类那一腿**：choice 准确率 0.216 而平均置信度 0.927（ECE **0.731**，
> `answered` 37/37 全 `project`——层级恒 project 与"恒答重复"是同一件事的两面）；`score`（质量等级）
> 反而 0.622、MAE 0.436、±1 级内 **0.973**；`noul` 0.324 @ 0.902（ECE 0.648）。
> 即基座的病灶是**分类腿 argmax 恒定 + 全腿置信度虚高**，不是整体不会判。基准报告原文存
> `tests/fixtures/cap56-base-eval-report.json`，后续实验直接拿它逐题对照。
> ② **数据飞轮**——28 条提案经真模型分诊（28/28，零降级）→ 人工裁决 → 决策记录 → 收编回流集
> （收编 28 条、跳过 1 条并逐条给原因）→ 手工补齐收编认不出的对照组 → 冻结 30 条。
> ③ **RLCD 微调跑通**（真 torch：40 步 / 10 epoch / 28 题 / 24 训练 6 验证 / 6.7s，权重留节点）→
> 自动回评**与基座打平**（0.216 vs 0.216，逐题 39 胜 / 70 负 / 2 平）。
> 训练**并非没在学**：组内平均奖励 step1 **-4.55** → step35 **-1.27** 单调向好（全程均值 -2.67；
> 末步 -6.57 是组仅 4 样本、按组标准差归一化把噪声放大的坏 minibatch）。
> 但逐题胜负是按官方 `proper_reward` 比"这题谁判得更好"（不是比准确率，见 `_rl_common.py:compare_block`），
> **净负**：70/111 题上 gold 得分变差、39 题变好——配上 ⑤ 那条（置信度 0.9754 → 0.9852、判定不变），
> 等于**它更自信了，而校准恰好证明它本来就过度自信，方向是反的**。
> 定性：24 条样本 × 40 步（编码器冻结，14.97M / 321.91M 可训）不足以翻转 111 道题的 argmax，
> 而**被测的病灶恰恰就是那个 argmax 恒定**——所以这批数据既判不了 RLCD 有用、也判不了它没用，
> 报告如实暴露，不粉饰。
> ④ **温度校准真机生效**（本 CAP 的正面结果里最硬的一条，此前只有单测/mock 覆盖）——基座评测
> `fitTemperature=true`，held-out 18 拟合 / 19 评估：ECE **0.5297 → 0.1594**（全集 0.4966 → 0.1749），
> 桶 `choice:3-5=4.7`、`noul:2=3.8`、`score:3-5=0.7`。choice 那个 4.7 已贴到 `TEMP_MAX=5.0` 轨道上——
> 基座过度自信到允许的软化区间几乎不够用。**温度是单调变换、翻不了 argmax**：这既解释了 ⑤ 里
> "换模型只动 probability"，也说明"修置信度"与"修判断"是两条独立的路。
> 微调产物报"服务温度覆盖 0 个桶"不是缺陷：`fit_temperature(min_samples=8)` 在 6 条验证样本上
> 按设计拒绝（3 条样本上拟合温度出来的是噪声）；自动回评报告 `eceBefore/eceAfter` 为 null 同理，
> `FinetuneService` 有意不重复校准。
> ⑤ **放行与 serve**——槽位来源覆盖真被 serve（自检「换来源前必 FAIL、换来源后 OK」两侧都实测）、
> 人工放行顶掉同槽位基座（失宠不作废，留放行史）、同一条探针提案的徽标随 served 产物变化：
> 真机实测是**判定三件套不变、只有置信度移动**（0.9754 → 0.9852），断言与日志都照这个口径写，
> 不把"徽标变了"说成"判断变好了"。
>
> **本轮真机抓到并修掉 4 个真缺陷**（前两个只有真起实例/真跑模型才照得出来）：
> `DecisionCheckpointRepository` 派生名不被 Spring Data 4 认（1d206aa，本模块此前从未真正启动过）、
> 收编预览的原因表带 null 键致整条 500（6835df4）、`DatasetService.delete` 缺事务
> → 删带条目的集必 500（aa4b42c）、RLCD 训练前向按"批的设备"搬张量
> → 模型落在 cuda 时必炸 `Expected all tensors to be on the same device`（a40609f）。
>
> **两个必须钉住的环境项**（写进 guides/decision-lab-guide.md §6/§7）：与 vLLM 共卡时边车
> `LAYA_DEVICE=cpu`（cuda 在显存吃满时随机 500 / 退 CPU → 平台侧整片分诊降级；CPU ~2.0s/条零降级）、
> DECISION 端点 `model` 钉到槽位（留空 = 边车按语言路由，会撞上 laya 0.3.6 内置 english 槽位指向
> 非 checkpoint 的坑）。平台侧链路另有 `tests/cap56_e2e.py` 全绿（78 条断言：真 runner + mock 边车 +
> stdlib stub 脚本，边界见脚本头注释）｜ 日期：2026-09-23
>
> 新增能力。缘起：CAP-55 把 laya 决策模型接进了产品路径（知识库提案分诊），但**没有任何"这套判断准不准"
> 的环节**——没有评测集、没有指标、没有基线，接上就是"看起来在工作"。2026-09-22 真机实测坐实了这个洞：
> `multilingual` base checkpoint 在分诊题面上 `duplicate`(noul) 三组对照全判「重复」（0.988 / 0.931 / 0.972，
> 含**空召回**组与**不相关**组），`adopt_layer` 三组全 `project`，`quality` 全档 2。官方 `research/README.md`
> 同口径：「base checkpoints are near chance on typed-decisions zero-shot — 0.362 / 0.352 against a 0.318
> random baseline and a 0.461 majority-class baseline」，且「confidence gating cannot catch it」。
>
> 结论（用户决策，2026-09-22）：**先把评测与微调做成页面上的流程并跑通，验证通过后才考虑接入其他模块**。
> 本 CAP 因此不含任何新消费方——只建"判断模型好坏"与"把模型训好"这两条腿，外加一道准入闸门。

## 1. 目的

- **评测先行**：把「模型准不准」变成可复现、可对比、可归档的流程——固定评测集 + 官方口径指标 + 随机/多数类
  基线 + 与上一版 checkpoint 的逐题胜负明细。
- **微调闭环**：用我们自己的 gold、在我们的题面上微调（官方 recipe 是 RLCD 策略梯度 + 2×T4 DDP，
  1200 例 **4-6 分钟**），产物自动回评、人工确认通过才准入。
- **准入闸门**：消费方（知识库分诊）只在存在「已验证通过」的 checkpoint 时才允许启用，否则照 CAP-55 FR-06
  降级为纯人工——即用户要的「等都验证完了再接入系统其他模块」的机械保证。
- **口径更正**：CAP-55 §1 与 FR-04 写的「首发用官方微调产物 `laya-typed-decisions`」**作废**，见 FR-09。

**关键约束**：评测与微调都是**批量负载**（几千题、几分钟），按 CAP-55 §3 的定调走 **runner exec 帧 + 日志 Hub**，
不走边车的同步 HTTP（那是 33ms 级单次决策的通道）；但**权重产物不回传服务端**，只回传小体积指标与指纹。

## 2. 功能需求

- **FR-01 边车槽位覆盖（serve 自训产物）**：`tools/laya-sidecar` 目前只能 preload 三个内置名
  （`Router.preload` → `normalise_name()` 只认 `english|multilingual|typed-decisions`，其它抛 `ValueError`），
  且 `app.py` 未暴露任何来源配置。新增「槽位 → 来源」覆盖配置（环境变量或 `models.json`），**只换来源不改名字**：
  `Agent` 已支持本地绝对/相对路径（非路径才当 HF id 去下载），故自训产物放节点本地目录即可被 serve。
  `GET /healthz` 增报每个槽位的实际来源（供连接测试与闸门判据）。
- **FR-02 评测集与版本冻结**：两种来源，都落库、都可冻结版本（改 = 新版本，否则指标不可比）：
  - **基准集**：页面人工标注（新标注流程），样本量 60-100 起步；
  - **回流集**：从 `decision_records` 的人工裁决一键收编为候选 gold（数据飞轮已跑通一半，CAP-55 FR-05）。
  - **必须含对照组（红线）**：① 空召回（`similar_entries` 只有「（未召回到相似条目）」）② 逐字重复
    ③ 明显不相关的干扰条目。没有对照组，「恒答重复」这种退化看起来也"合理"——这是 CAP-56 的立身教训。
- **FR-03 评测运行**：一个 checkpoint × 一套题面版本 × 一个评测集 → 指标照抄官方口径
  （`accuracy` / `soft_accuracy` / `brier` / `ece` / `score_mae` / `within_1_level` / 延迟 p50·p95），
  逐题分解（choice 混淆矩阵、score 等级分布、noul 分布），**每条都同时报随机基线与多数类基线**
  （否则类别倾斜会把 accuracy 撑得很漂亮：实测 `adopt_layer` 恒 `project`），并给与基线 checkpoint 的
  **逐题胜负明细**（赢在哪、输在哪，而不是只给一个总分）。
- **FR-04 温度校准**：官方 `research/README.md`：「Refitting **one temperature per (question type, option count)**
  on held-out data moves mean ECE 0.466 → 0.081」——按(题型, 选项数)各拟合一个温度，**是拟合不是训练**，
  不需 GPU。校准前后 ECE 对比进评测报告；校准参数随 checkpoint 元数据走。CAP-55 的「置信度只作展示不作
  自动执行依据」在本 CAP 保持不变，校准的意义是让徽标上的百分比别骗人。
- **FR-05 微调任务（runner 编排）**：数据切分（train/val，判据进库）→ 按标签调度到 GPU 节点
  → 跑官方 recipe 的移植脚本（`laya.common` 的 `build_sequence`/`render_options`/`proper_reward` 原语
  **在 0.3.5 已齐备**，训练只需 `pip install laya torch`，不需要外部训练仓库）→ 日志走 CAP-12 日志 Hub
  → 结束自动触发一次 FR-03 评测。禁 `@Transactional`；节点选择复用 `AgentNodeRouter`。
- **FR-06 产物登记与指纹**：**权重目录留在节点**（exec 链路没有任何文件上行通道，见 §3），只回传
  指标 JSON + 产物指纹（路径、大小、sha256）+ 训练日志。登记一条 checkpoint 记录，边车经 FR-01 的槽位
  覆盖指向该路径。
- **FR-07 准入闸门**：checkpoint 需被人工确认「验证通过」才可被消费方启用。**落点是唯一的**：
  `devmind-decision/.../HttpDecisionEngine.unavailableReason()` 同时是「按钮置灰」（`KnowledgeTriageService.status()`
  → `TriageStatusView`）与「运行时降级」（`decide()` 先查它）的共同上游，加一句判定即全链生效。
- **FR-08 前端**：`frontend/src/features/decision` 扩为多视图（评测集 / 评测运行 / 微调任务 / checkpoint 登记），
  遵循 `docs/core/前端内容区布局约定.md`（Card + Segmented、按钮进 `extra`、`FitTable` + `LIST_PAGINATION`）。
- **FR-09 CAP-55 口径更正**：`laya-typed-decisions` 的微调工作流只有四套且**要求题 id 集合精确匹配**
  （`customer_service{category,urgency,action,churn_risk,needs_human}`、`invoice_processing`、`security_incidents`、
  `agent_trace_observability`），**均不含** `{adopt_layer, duplicate, quality}`——故它既不会被
  `auto_task_detection` 路由到我们的题面，拿它跑我们的题面也属跨任务迁移。CAP-55 §1/FR-04 与
  `docs/capabilities/README.md` 的对应表述需回填更正。

## 3. 关键设计

- **权重不回传（已定）**：`AgentExecResult` 只有 stdout/stderr/退出码；唯一文件上行通道是会话产出通道
  （`OutputUploader` → `POST /api/agent/output/{sessionId}` → `SessionOutputSink`），**绑定 sessionId**，
  exec 型任务没有这条路径。硬造新帧搬几百 MB 权重不划算——边车本来就按**本地路径**加载 checkpoint，
  所以「训练与 serve 同机（或共享存储）+ 只回传小 JSON」是成本最低且足够的设计。若要跨机 serve，
  另立 FR（新帧或对象存储），本期不做。
- **评测不占服务端算力（已定）**：评测脚本在节点上直接 `laya.Agent(ckpt_dir)` 本地批量推理并算指标，
  不回打边车 HTTP——省掉数千次往返，且与被 serve 的代码同源。服务端只负责编排、落库与展示。
- **指标必带两条基线（已定）**：随机 + 多数类。这是我们自己实测的教训：单看 accuracy，一个「恒答 project」
  的退化模型也能有不错的数字。
- **校准与评测分开（已定）**：校准是小拟合（held-out 上按题型×选项数），随时可重跑；不要把它绑进训练任务，
  否则调一次温度要重训一遍。
- **闸门在配置侧、不探活（沿用 CAP-55 口径）**：available 只看"有没有已验证通过的 checkpoint + 端点配没配"，
  不做实时探活——边车真挂了也不该让按钮变灰，那是运行时降级（`degraded`）该管的事。
- **题面版本与评测集版本都进指标主键（已定）**：题面是代码常量（CAP-55 `TriageQuestions` 注释：改题面等于换
  训练目标），所以评测结果必须记下题面版本，否则历史指标会被无声地对齐到新题面。

## 4. 插件化接口

- `DecisionEngine`（既有，CAP-55）：闸门与降级链的接入点，本 CAP 只加"验证状态"判定，不改契约。
- `ModelEndpointService` / `ModelEndpointView`（既有，CAP-48）：DECISION 端点登记与 `defaultEndpoint(DECISION)`
  解析链复用；`probeDecision` 已能拿到 `LayaDecisionClient.Health.loaded`（常驻 checkpoint 列表），
  **它是"边车实际能 serve 哪些槽位"的现成事实来源**，登记校验直接用。
- `AgentNodeRouter` / `AgentNodeStepRunner` / `ExecutionLogHub`（既有，CAP-12/21/34）：微调与评测任务的
  节点调度、下发、日志回流全部复用，零改造；新模块只依赖 `devmind-common` + `devmind-execution`
  （**禁直接依赖 `devmind-agent`**，实现方经 `ObjectProvider<AgentNodeConnector>` 探测注入）。
- `LayaTrainingJsonl` / `GoldDistributions`（既有，CAP-55）：微调数据集导出复用（`{state, questions, gold}`
  三字段已对齐官方训练格式形状）。
- runner 侧配置（**必须同步，否则下发即被拒**；实操见 [guides/decision-lab-guide.md](../guides/decision-lab-guide.md) §4）：
  `execAllowlist` 加 `python,torchrun,accelerate` 首 token 前缀
  （空值 = 拒绝一切 exec）；GPU 节点用 `labels=gpu,<型号>` 表达（`AgentNodeRouter.route(..., requiredLabels)` 消费），
  注意 runner 上报的 labels 会**覆盖**服务端值。

## 5. 数据模型

```
decision_datasets       评测集：name, kind(BENCHMARK|REPLAY), version(冻结版本号),
                        frozen(Boolean), item_count, note, created_by, created_at
decision_dataset_items  samples: dataset_id, state_json CLOB, questions_json CLOB,
                        gold_json CLOB, source(MANUAL|RECORD), origin_record_id?, case_group?
                        （case_group 标对照组：EMPTY_RECALL|VERBATIM_DUP|IRRELEVANT|NORMAL）
decision_evaluations    评测运行：checkpoint_id, dataset_id, question_set_version,
                        status(QUEUED|RUNNING|SUCCESS|FAILED), metrics_json CLOB,
                        baseline_metrics_json CLOB?, per_item_json CLOB?,
                        compare_json CLOB?（与基线逐题胜负）, latency_p50/p95,
                        agent_node_id?, logs_text CLOB, error_summary?, started_at, finished_at
decision_finetunes      微调任务：dataset_id, train_split/val_split, base_slot, output_path,
                        status（同上状态机）, metrics_json CLOB?（回评结果）, fingerprint_json CLOB?
                        （路径/大小/sha256）, agent_node_id, logs_text CLOB, error_summary?,
                        created_by, created_at, finished_at
decision_checkpoints    登记：slot(english|multilingual|typed-decisions 槽位名),
                        source_path/source_repo, kind(BASE|FINETUNED), calibration_json CLOB?,
                        eval_id?（通过的评测）, verified(Boolean 初始值，禁 @ColumnDefault),
                        verified_by, verified_at, created_at
```

**红线**：`@ColumnDefault` 字符串默认值带引号；`@Lob` 必带 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` +
`@Column(length = 16_777_216)`；Boolean 列禁 `@ColumnDefault`（实体初始值 + getter 兜底）；异步触发方法
（trigger/execute/run）禁 `@Transactional`；可空筛选参数在 JPQL 必须 `cast(:x as …) is null`（PG 推不出裸参类型）。

## 6. API 概要

```
/api/decision/datasets                   评测集 CRUD + 冻结；items 分页增删
/api/decision/datasets/{id}/freeze       冻结版本（冻结后只读）
/api/decision/datasets/from-records      从 decision_records 收编候选 gold（capability/since）
/api/decision/evaluations                发起评测（202）+ 列表/详情（含逐题明细与基线对比）
/api/decision/finetunes                  发起微调（202，带 requiredLabels/agentNodeId）+ 列表/详情
/api/decision/checkpoints                登记/列表；PUT /{id}/verify 人工确认「验证通过」
api/model-endpoints（既有）               DECISION 端点 status/default 校验挂闸门
tools/laya-sidecar  GET /healthz          增报槽位实际来源（FR-01）
```

## 7. 验收标准

- 能在页面上建一个含**对照组**的基准集并冻结；跑一次评测，报告同时给出官方口径指标、
  **随机与多数类基线**、以及与基线 checkpoint 的逐题胜负明细；
- 用 `decision_records` 收编的样本跑微调，产物登记后自动回评，**指标优于 base**（在基准集上）
  ——**真机实测这一条未达成**：base 与微调产物在 37 题基准集上同分 0.216；
  按官方 `proper_reward` 逐题比更差（39 胜 / 70 负 / 2 平，70/111 题上 gold 得分变差），
  且置信度反而被推高（探针 0.9754 → 0.9852）——**方向是反的**。
  平台侧行为（跑通、留指纹、自动回评、如实报"不差于基座"）都达成，退化原因（24 条样本 × 40 步
  翻不动基准题面上那个"恒定 argmax"）见文首状态块 ①③；"优于"要等更大更干净的数据 + 更多步数
  + 多 seed，那是下一轮的事，这里不粉饰成达标。人工确认后边车经槽位覆盖 serve 该产物，
  知识库分诊徽标随之改变（真机是判定不变、置信度移动，见状态块）；
- **闸门可证伪**：未验证 checkpoint 时 `TriageStatusView` 为不可用、按钮置灰（悬停给原因）、
  `decide()` 运行时降级；验证通过后全链放行——两侧行为都覆盖到测试；
- 校准前后 ECE 有对比数字；`adopt_layer` 恒 `project` 这类退化能被报告显式暴露（不是靠人偶然发现）；
- E2E：脚本沉淀 `tests/`（运行产物写 `tmp/`），mock 边车 + mock 节点跑通「建集→冻结→评测→微调→回评→
  验证→serve→分诊」全链。→ **已达成**：`tests/cap56_e2e.py`（78 条断言全绿，真 runner + mock 边车）。
  节点上执行的是 stdlib stub（复刻退化形态），所以这一遍证的是**平台把数字算清并摆到一起**，
  不是「RLCD 收敛了」——收敛性只能在真机验。→ **真机那一遍也已沉淀**：
  `tests/cap56_gpu_e2e.py`（真 laya 模型 + 真 torch 训练 + 真边车，41 条断言全绿，结论见文首状态块）。

## 8. 非目标

- **新消费方**：检索重排、自动打标、通知分流、prompt 护栏仍各另立 CAP，本 CAP 一个都不接（用户明确要求
  验证完再谈）。
- **自动采纳**：CAP-55 §3 的「高置信度直接落层」仍待线上准确率验证后另立 CAP，本 CAP 只提供判断依据。
- **权重跨机搬运**：共享存储之外的分发（对象存储 / HF 推送 / 新上行帧）本期不做；官方 notebook 里的
  `push to HF Hub` 能力不在平台内实现。
- **多租户/多题面并行**：一期只服务 `kb-proposal-triage` 这一套题面，题面版本化只做"记录"不做"并行管理与 A/B"。
- **训练超参搜索、数据增强、自动建模**：训练脚本以官方 recipe 为准，参数可配即可。
