# CAP-104 测试资产与执行平台 v2 —— 实施任务清单

> 对应能力文档：[docs/capabilities/CAP-104-test-platform-v2.md](../capabilities/CAP-104-test-platform-v2.md)
> 日期：2026-10-31 ｜ 状态：M1/M1.5/M2/M3 已交付（2026-11-01），CAP-62 数据隔离仍按 D3/O1 跟踪
>
> 使用约定：
> - 任务完成后勾选，并在同一提交回写 CAP-104 头部状态与 `docs/capabilities/README.md` 索引。
> - 涉及 E2E 的新增/修改，先读 `docs/core/e2e-testing-standard.md` 和 `tests/README.md`；正式用例登记 `tests/e2e/catalog.json`；共享实例只跑 smoke，写入回归使用入口自起的隔离实例。
> - 任务估算为人日粗估，含联调与自测，不含 UI 精修和真实客户环境适配。

## 0. 前置决策与冻结项

| 状态 | ID | 决策项 | 结论/动作 | 阻塞 |
|---|---|---|---|---|
| [x] | D1 | 产品映射 | POC 以现有 `project` 作为产品；功能模块新增 `test_modules`。跨仓库产品不阻塞 POC，二期再加 `test_products` | M1 |
| [x] | D2 | 分布式语义 | 先做 `SHARD`（用例分片），`MATRIX`（同批用例多节点）在 M3 后半段；方案里显式选模式 | M3 |
| [ ] | D3 | CAP-62 状态 | 代码未见 `ProjectAccessChecker`/`project_members`，与 README“已实现”不一致；实施 T3.6 前由负责人确认，POC 先按 projectId 过滤 | M3 |
| [x] | D4 | 证据边界 | MVP 单文件 marker 上限（建议 2MB、单次运行 20MB）；超限提示走 M3 上传端点；保留期跟随附件策略 | M1 |
| [x] | D5 | 兼容策略 | 只做 additive 列/新表；旧 CAP-10/CAP-69 API 与前端入口保留；旧用例迁移为 `LEGACY-<id>`、版本 1、APPROVED | M1 |
| [x] | D9 | 程序型用例执行方式 | 只执行预构建产物：程序包 ZIP 上传 + 入口脚本；平台不运行 Maven/Gradle/npm/pip 构建或依赖安装 | M1.5 |
| [x] | D10 | 程序契约启用方式 | `CaseSpec.command.contractVersion` 可空；为空保持现有 COMMAND 语义，`"1"` 启用附录 C 输入/输出契约；旧用例不迁移 | M1.5 |

## 1. M1 资产与单节点闭环（POC 必须）

| 状态 | ID | 任务 | 交付/涉及 | 依赖 | 验收 | 估算 |
|---|---|---|---|---|---|---|
| [x] | T1.1 | 规范冻结 | CaseSpec、AssertionResult、状态/失败分类、证据 manifest 的 JSON Schema 与样例；写入 CAP-104 附录或 `docs/design` | D1/D5 | 产品/测试/研发评审通过；HTTP、COMMAND、JUNIT 各一份样例 | 2 |
| [x] | T1.2 | 数据模型与迁移 | 新表 `test_modules`、`test_case_versions`、`test_assets`、`test_evidence`；扩展 `test_suites/test_cases/test_runs/test_case_results/environments`；实体与 Repository | T1.1 | `ddl-auto=update` 平滑加列；旧数据可打开；CAP-10/69 单测不回归 | 3 |
| [x] | T1.3 | 模块与四级目录 | `test_modules` CRUD、套件挂模块、用例树查询；前端模块树与未分类兜底 | T1.2 | 产品→模块→套件→用例逐级可查；模块非空才能删；存量套件落“未分类” | 4 |
| [x] | T1.4 | 用例 CRUD、版本、评审、检索 | 用例接口；版本快照/审核/废弃；关键字/标签/评审状态检索；签名去重提示 | T1.2 | 草稿→审核→v1；修改生成 v2；废弃用例阻止新增运行引用；检索正确 | 5 |
| [x] | T1.5 | 测试资产导入 | git/zip 导入；来源、commit、sha256、大小、存储引用；评审闸门；zip-slip/大小/文件数限制 | T1.2/T1.4 | fixture 仓库与 zip 可导入；未评审资产不可运行；越界 zip 被拒 | 4 |
| [x] | T1.6 | 脚本套件资产化 | `kind=script` 套件补 module/owner/tags/review/commit/sourceAsset；commit 固定版本；JUnit 结果映射 | T1.2 | 旧套件兼容；commit 固定生效；JUnit 结果可查 | 2 |
| [x] | T1.7 | 环境预检与清理 | `environments` 加 productVersion/precheck/cleanup；预检服务；清理钩子 | T1.2 | 节点离线、协议过低、URL 不可达、版本不匹配均给出具体原因；cleanup 留证据 | 3 |
| [x] | T1.8 | 单节点执行与过程采集 | Executor SPI；HTTP/COMMAND/JUNIT/MANUAL 执行；命令、退出码、耗时、stdout/stderr、envSnapshot、证据落库 | T1.2/T1.7 | 单用例与脚本套件在单节点跑通；secret 掩码；证据可下载 | 5 |
| [x] | T1.9 | 断言引擎与单次报告 | HTTP/COMMAND/JUNIT 断言；expected/actual/diff；失败分类；Markdown+JSON 报告；CAP-03 报告文档 | T1.8 | 断言失败能看到差异；报告含通过率、失败清单、证据链接 | 4 |
| [x] | T1.10 | 前端用例库与运行详情 | `/tests` 增加用例/运行页签；用例编辑器、版本抽屉、评审、导入、运行弹窗、断言差异、证据查看 | T1.3~T1.9 | 浏览器黄金路径与边界用例通过；按钮权限生效 | 5 |
| [x] | T1.11 | 测试、回归与文档 | 单测、E2E、旧能力回归；更新 CAP 状态/索引/system-overview；补使用说明 | T1.1~T1.10 | `Regression.test_case_version_review_run_and_evidence`、`Regression.test_test_asset_review_gate_and_deletion` 登记 `tests/e2e/catalog.json` 并通过；CAP-10/69 回归绿 | 3 |

### M1 关键实现说明

**T1.1 规范**

- `CaseSpec` 至少包含：`caseKey`、`title`、`type`、`priority`、`tags`、`preconditions`、`steps`、`assertions`、`cleanup`、`executor`、`source`。
- `AssertionResult` 固定为 `{source, op, expected, actual, pass, message}`，报告/统计/前端共用。
- 结果状态固定为 `PASS/FAIL/BLOCKED/SKIP/ERROR`；失败分类固定为 `ASSERTION/INFRA/TIMEOUT/PRECHECK/SCRIPT_ERROR/UNKNOWN`。
- 证据 manifest 固定包含：运行、方案、环境、节点、命令、退出码、耗时、环境快照、文件列表与 sha256。

**T1.2 迁移**

- 旧 `test_cases.kind=http/health` 迁移为 `type=HTTP`，`case_key=LEGACY-<id>`，`version=1`，`review_status=APPROVED`。
- 旧套件 `module_id` 留空，前端显示“未分类”；不阻断旧 suite/case 接口。
- 新列全部可空，禁止改旧列语义；旧脚本套件仍走 `ScriptSuiteService`。

**T1.8 执行矩阵**

| 用例类型 | 执行位置 | 执行方式 | 结果来源 |
|---|---|---|---|
| HTTP | 默认 SERVER；可配 NODE | 服务端 RestClient；NODE 时下发 curl/脚本 | 响应状态/头/体 + 断言 |
| COMMAND | NODE | `AgentNodeStepRunner.runStep` | stdout/stderr/exitCode + 断言 |
| JUNIT | NODE | 脚本套件命令 + JUnit 回收 | JUnit XML 解析 |
| MANUAL | 无 | 页面人工判定 | 人工结果 + 备注/证据 |

- 每个用例的 stdout/stderr 用有界缓冲收集，避免大日志打爆内存；完整日志落证据文件。
- `test_evidence.prev_hash` 按同一 run 串行写入；并发写入用 run 级锁或顺序号，禁止并发破坏 hash 链。
- envSnapshot 记录节点 id、runner 版本、OS、环境名/版本、变量（secret 掩码）、实际 commit。

**T1.9 报告**

- 报告同时产出 Markdown 与 JSON；JSON 是稳定契约，供后续基线与统计使用。
- 失败清单按 `failure_category` 分组；每条带 assertion diff 与证据入口。
- 旧 `GET /api/test-runs/{id}/report` 返回 Markdown 的行为保持不变，新增 JSON 端点或在响应头协商。

## 2. M1.5 程序型用例（M1 增补）

> 目标：代码型测试（如 Java 同步发送消息验证程序）以**已构建好、可直接执行**的 ZIP 程序包上传，平台负责下发、输入注入、输出回收、结果映射与证据。平台内不做构建。契约见 CAP-104 附录 C。

| 状态 | ID | 任务 | 交付/涉及 | 依赖 | 验收 | 估算 |
|---|---|---|---|---|---|---|
| [x] | T1.12 | 契约冻结与模型 | 冻结 `.devmind-program.json`、input v1、result v1、退出码/失败分类映射；`CaseSpec.command.contractVersion`；`test_assets` 加 `asset_role/runtime/entrypoint/target_os/target_arch/manifest_json`；`test_case_results` 加 `program_asset_id/program_contract_version/program_manifest_json` | D9/D10/T1.1/T1.2 | 契约样例与 JSON Schema 可校验；旧用例 `contractVersion` 为空行为不变；加列为 additive | 2 |
| [x] | T1.13 | 程序资产上传与物化 | `POST /api/test-assets/upload`（multipart）；清单解析/校验；`asset_role=PROGRAM` 评审闸门；zip 限制沿用；runner 将程序包物化到隔离工作区并记录 sha256；OS/架构/运行时预检 | T1.5/T1.12 | 合法包可上传并评审；缺清单/入口越界/超限被拒；节点无运行时或架构不符 → BLOCKED 且原因具体 | 4 |
| [x] | T1.14 | 程序执行与结果映射 | 生成 input.json、注入 `DEVMIND_INPUT/RESULT/ARTIFACTS`；执行入口脚本；解析 result.json；退出码优先；artifacts 路径/大小校验与哈希；证据 `PROGRAM_MANIFEST/INPUT_JSON/RESULT_JSON/STDOUT/STDERR/ARTIFACT`；超时整树 kill 与 cleanup | T1.8/T1.13 | 非 0 退出码覆盖 JSON status；result 缺失/畸形 → SCRIPT_ERROR；超时 → TIMEOUT；证据链可校验 | 5 |
| [x] | T1.15 | 前端、E2E 与文档 | 资产页程序角色与 runtime/OS/架构字段；用例编辑器选择程序资产/入口/契约版本；运行详情展示输入摘要、结构化断言、metrics、artifacts；样例程序 fixture；回归 E2E；文档状态回写 | T1.13/T1.14 | 浏览器完成“上传程序→评审→建用例→运行→看结果/证据”；E2E 登记 `catalog.json` 并通过 | 3 |

### M1.5 关键实现说明

**T1.12 契约冻结**

- 契约 v1 字段冻结在 CAP-104 附录 C；只增不破，破坏性变更另开 v2。
- `contractVersion` 为空 = 现有 COMMAND 行为（退出码/stdout/stderr/文件断言），不触发清单/输入输出要求；`"1"` 才要求 `source.kind=ASSET` 且程序资产为 `PROGRAM`。
- 程序资产不可变；升级程序 = 新资产 + 新用例版本，禁止原地替换。

**T1.13 物化与预检**

- 上传时解析清单并校验：`contractVersion="1"`、runtime 枚举、入口为包内普通文件、targetOs/targetArch 枚举、ZIP 无 zip-slip/绝对路径/符号链接。
- 执行前预检：资产 APPROVED、sha256 与上传记录一致、节点 OS/架构匹配、runtime 命令可用、入口文件存在；失败 → `BLOCKED / PRECHECK`。
- 物化到 `<workspace>/program`，input/result 在 `<workspace>/.devmind`，artifacts 在 `<workspace>/artifacts`；工作目录默认程序包根。
- 下发通道复用现有 runner 文件通道或一次性 token；通道不可用时运行 fail-visible，禁止跳过执行直接判通过。

**T1.14 结果映射**

- 非 0 退出码优先于 result.json status：1→FAIL/ASSERTION、2→BLOCKED/PRECHECK、3→ERROR/SCRIPT_ERROR、其他→ERROR/UNKNOWN。
- 退出码 0 时以 result.json status 为准；任一 `assertions[].pass=false` 时判 FAIL，不盲目信任程序自报 pass。
- result 缺失/畸形/`contractVersion` 不符 → ERROR/SCRIPT_ERROR；超时 kill → ERROR/TIMEOUT。
- artifacts 只接受 `$DEVMIND_ARTIFACTS` 下相对路径，拒绝绝对路径/`..`/符号链接；逐个计算 sha256 追加证据。
- stdout/stderr 有界收集；secret 掩码复用现有规则，input.json 不写 secret。

## 3. M2 方案编排与证据包

| 状态 | ID | 任务 | 交付/涉及 | 依赖 | 验收 | 估算 |
|---|---|---|---|---|---|---|
| [x] | T2.1 | 方案与基线模型 | `test_plans`、`test_plan_items`、`test_plan_runs`、`test_baselines`；实体/Repository | M1 | 表可建；旧运行不受影响 | 2 |
| [x] | T2.2 | 方案 CRUD、模板、快照 | 方案/条目接口；从用例库/套件选择；模板复制；运行前快照 | T2.1/T1.4 | 方案可复制；编辑不影响历史运行；废弃用例拒绝新增 | 3 |
| [x] | T2.3 | 方案编排器 | 预检→快照→创建父运行→顺序/并行组→子运行→汇总；FAIL_FAST/CONTINUE；重试；cleanup | T2.2/T1.7/T1.8 | 串行/并行、两种失败策略、重试 attempt、cleanup 均符合定义 | 5 |
| [x] | T2.4 | 父子运行与汇总 | `test_plan_runs` 父记录；子 `test_runs`；汇总状态；父运行查询/WS 快照 | T2.3 | 父运行可下钻到子运行/用例；汇总通过率正确 | 3 |
| [x] | T2.5 | 证据包导出 | zip 流式导出；manifest；sha256 + prev_hash 校验；权限与审计 | T1.8/T2.4 | 导出包可校验；大运行不 OOM；无权限 403 | 3 |
| [x] | T2.6 | 基线与回归对比 | 基线 CRUD；方案运行 vs 基线：新增失败/已修复/持续失败/新增用例/通过率变化 | T2.1/T2.4 | 对比结果正确；不改历史运行 | 3 |
| [x] | T2.7 | 需求/工作项联动 | 方案/运行 requirementId；失败一键创建工作项；报告反链 | T2.1 | 工作项带运行/用例/断言/证据；报告显示需求编号 | 2 |
| [x] | T2.8 | 检索与去重增强 | 标签/失败原因/关键字检索；signature 归一化；重复提示 | T1.4 | 重复用例提示；检索组合正确 | 2 |
| [x] | T2.9 | 前端方案与对比 | 方案编辑/排序/并行组/环境/参数/并发/失败策略；方案运行详情；证据包下载；基线对比视图 | T2.2~T2.8 | 浏览器完成“建方案→跑→看对比→导证据” | 5 |
| [x] | T2.10 | 测试与文档 | 单测、E2E、旧能力回归、CAP 状态更新 | T2.1~T2.9 | `Regression.test_plan_orchestration_baseline_stats_and_evidence` 绿；文档同步 | 3 |

### M2 关键实现说明

**T2.2 方案快照**

- 运行快照包含：方案版本、条目顺序、用例版本、套件 commit、环境变量（secret 掩码）、节点选择器、并发、失败策略、参数覆盖。
- 方案编辑只影响后续运行；历史运行读快照，不读当前方案。

**T2.3 编排器**

- `PlanRunOrchestrator` 不复制 `StepChainRunner`；单用例执行仍走 `CaseExecutor`。
- 顺序项按 `seq`；`parallelGroup` 相同且连续（或显式分组）的条目并行，默认最大并发 = 方案 concurrency。
- `FAIL_FAST` 只停止调度，不强制杀死正在执行的节点任务；未执行项写 `SKIP/CANCELLED` 与原因。
- 重试只对配置的失败类型生效；每次 attempt 独立证据，最终结果引用最后一次，历史 attempt 可查。
- 方案 teardown 在所有条目结束后执行；用例 cleanup 在单用例 finally 执行。

**T2.4 父子运行**

- 父运行 `status` 与 `conclusion` 分开：`status` 表示编排状态（RUNNING/DONE/FAILED），`conclusion` 表示测试结论（PASS/FAIL/PARTIAL/BLOCKED）。
- 子运行沿用 `test_runs`，保留既有 WS/报告/缺陷线索端点；父运行新增汇总端点。
- 父运行汇总按“计划条目 + 实际结果”计算，未执行条目不能静默消失。

## 4. M3 分布式与统计

| 状态 | ID | 任务 | 交付/涉及 | 依赖 | 验收 | 估算 |
|---|---|---|---|---|---|---|
| [x] | T3.1 | 节点选择与分片 | 标签/能力/环境节点组选择；SHARD/MATRIX；节点内串行、节点间并行；`AgentNodeRouter` 能力匹配扩展 | M2 | 两个以上节点可分片/矩阵执行；节点选择符合优先级 | 4 |
| [x] | T3.2 | 分布式执行与失败处理 | 子运行按节点创建；节点离线/超时/失联；其他节点继续；未执行用例 BLOCKED；按分片重跑 | T3.1 | 单节点失败不拖垮整体；父结论 PARTIAL/FAIL；重跑可用 | 5 |
| [x] | T3.3 | 汇总与下钻 | 父运行节点维度汇总；节点→子运行→用例→证据下钻；前端节点视图 | T3.2 | 每个节点独立结果可见；下钻链路完整 | 3 |
| [x] | T3.4 | 统计分析 | 趋势/Top 失败/flaky/耗时 p50/p95/环境对比；索引；统计 API 与前端图表 | M2 | 口径与 CAP-104 FR-21 一致；flaky 单测覆盖 | 4 |
| [x] | T3.5 | 大文件证据上行 | 一次性 token；`POST /api/test-runs/{id}/evidence`；命令包装上传；限流/限大小/MIME/sha256 | T1.8 | 截图/大日志可上传；token 过期/越权/超限被拒 | 3 |
| [x] | T3.6 | 权限与隔离 | CAP-103 页签/按钮/API 规则；项目维度过滤；CAP-62 状态核实与缺口记录 | T1.10/T2.9 | VIEWER 只读；未授权写接口 403；项目过滤验证 | 3 |
| [x] | T3.7 | 通知与审计 | 方案运行完成/失败通知；导入/评审/运行/导出/删除审计 | M2 | 通知带报告链接；审计可检索 | 2 |
| [x] | T3.8 | 多节点 E2E | 隔离实例 + 双节点/模拟节点；SHARD/MATRIX；离线/超时；下钻 | T3.1~T3.3 | `Regression.test_distributed_matrix_and_shard`、`Regression.test_runner_partial_failure_and_program_case` 绿 | 3 |
| [x] | T3.9 | 文档与状态回写 | CAP-104 状态、README 索引、system-overview、使用指南 | 全部 | 文档与代码一致；索引状态更新 | 2 |

### M3 关键实现说明

**T3.1 分片**

- `SHARD`：按 `caseKey` hash 或标签分片，保证同一用例不重复下发；分片计划写入父运行快照。
- `MATRIX`：同一批用例按节点/环境复制执行，结果按节点维度汇总；适合环境对比。
- 节点选择优先级：显式节点 > 环境节点组 > 标签匹配 > 能力匹配 > 平台默认；离线节点在预检阶段拦截。

**T3.2 失联语义**

- 子运行在 exec 超时/连接断开时置 `ERROR`，已执行用例保留结果，未执行用例置 `BLOCKED` 并写“节点失联/超时”。
- 其他节点继续执行；父运行结论为 `PARTIAL`（有完成也有未完成）或 `FAIL`（存在失败）。
- POC 不做自动迁移分片；人工按分片重跑，重跑生成新的子运行并保留历史。

**T3.4 统计口径**

- 通过率 = PASS / (PASS + FAIL + ERROR)；BLOCKED/SKIP 单独展示，不进分母。
- flaky：窗口内同一 `caseKey` 出现 PASS 与 FAIL，或重试后通过；最小样本默认 5。
- 耗时 p50/p95 按 `test_case_results.duration`；环境对比按 `environment_id` 分组。
- 先按需查询 + 索引；数据量上来再加每日汇总表，不在 POC 预建。

## 5. 依赖顺序与建议排期

```text
T1.1 → T1.2 → T1.3 / T1.4 / T1.6
T1.2 → T1.7 → T1.8 → T1.9 → T1.10 → T1.11
T1.2 → T1.5（可与 T1.3/T1.4 并行）

T1.12 → T1.13 → T1.14 → T1.15（M1.5，依赖 T1.5/T1.8）

T2.1 → T2.2 → T2.3 → T2.4 → T2.5 / T2.6 / T2.7 / T2.8 → T2.9 → T2.10

T3.1 → T3.2 → T3.3 → T3.4 / T3.5 / T3.6 / T3.7 → T3.8 → T3.9
```

建议先做纵向切片，不要按表铺开：

1. 第 1 周：T1.1 + T1.2，冻结契约与表结构。
2. 第 2 周：T1.8 + T1.9 + T1.7，用一个 COMMAND 用例跑通“预检→执行→证据→断言→报告”。
3. 第 3~4 周：T1.3/T1.4/T1.5/T1.6，补资产库与导入。
4. 第 5 周：T1.10/T1.11，前端闭环、E2E、文档。
5. M1.5（T1.12→T1.15）在 M1 前端闭环后插入，先跑通“上传程序→评审→运行→结果/证据”。
6. M2/M3 按依赖顺序推进；T3.5（大文件证据）如果 M1 截图需求强烈，可提前到 M1 末尾。

## 6. Definition of Done

每个任务完成需同时满足：

- 代码 + 单测；关键分支（失败/超时/空数据/权限）有覆盖。
- 新增/修改 API 有请求/响应示例；旧 API 无破坏性变更。
- 涉及 UI 的任务在浏览器验证黄金路径与至少两个边界场景。
- 涉及执行链的任务验证命令、退出码、日志、证据、hash 链。
- 涉及 E2E 的新增/修改登记 `tests/e2e/catalog.json`，共享实例只跑 smoke。
- 不把 secret/token 写入日志、证据或报告；envSnapshot 已脱敏。
- 同一提交回写 CAP-104 状态与 `docs/capabilities/README.md`；必要时更新 `docs/design/system-overview.md`。

## 7. 验收与回归清单

### M1

- [x] 四级目录 CRUD 与“未分类”兜底
- [x] 用例草稿/审核/发布/废弃/版本 diff
- [x] git/zip 资产导入与评审闸门
- [x] 环境预检四类失败原因
- [x] COMMAND 用例单节点执行 + 证据
- [x] JUNIT 脚本套件单节点执行 + 证据
- [x] HTTP 断言 expected/actual
- [x] 单次报告 Markdown/JSON + CAP-03 文档
- [x] CAP-10/CAP-69 回归
- [x] `Regression.test_case_version_review_run_and_evidence`、`Regression.test_test_asset_review_gate_and_deletion` 绿

### M1.5

- [x] 预构建程序 ZIP 上传 + 清单校验 + 评审闸门
- [x] 输入 input.json 与 `DEVMIND_INPUT/RESULT/ARTIFACTS` 注入
- [x] result.json 结构化断言/metrics/artifacts 映射
- [x] 非 0 退出码优先、result 缺失/畸形、超时三类兜底
- [x] 程序证据 `PROGRAM_MANIFEST/INPUT_JSON/RESULT_JSON/STDOUT/STDERR/ARTIFACT` 入链
- [x] 节点无运行时/OS/架构不匹配 → BLOCKED
- [x] 执行时不发生构建或依赖安装
- [x] 新回归 E2E 登记 `catalog.json` 并通过（`Regression.test_runner_partial_failure_and_program_case`）

### M2

- [x] 方案勾选/模板/快照
- [x] 顺序 + parallelGroup 并行
- [x] FAIL_FAST/CONTINUE
- [x] 重试 attempt 与 cleanup
- [x] 父子运行汇总与下钻
- [x] 证据包 zip + hash 校验
- [x] 基线对比
- [x] 失败一键创建工作项
- [x] `Regression.test_plan_orchestration_baseline_stats_and_evidence` 绿

### M3

- [x] 标签/能力选节点
- [x] SHARD/MATRIX
- [x] 节点离线/超时/失联汇总
- [x] 统计接口与图表
- [x] CAP-103 权限与项目过滤（CAP-62 仍按 O1 跟踪，生产隔离前补齐）
- [x] 大文件证据上传
- [x] `Regression.test_distributed_matrix_and_shard` 绿

## 8. 风险检查点

| 检查点 | 触发时机 | 动作 |
|---|---|---|
| R1 证据回传 | T1.8 完成 | 验证 marker 大小上限；若截图超限，提前排 T3.5 |
| R2 CAP-62 | T3.6 开始前 | 核实成员模型与 `ProjectAccessChecker`；未实现则明确 POC 隔离边界 |
| R3 导入安全 | T1.5 开始前 | 安全评审 zip 解包、命令白名单、资源限制 |
| R4 节点并发 | T3.2 开始前 | 用 runner `maxConcurrent` 做并发压测，确定节点内串行策略 |
| R6 统计性能 | T3.4 开始前 | 估算结果表数据量；必要时先加索引/日汇总 |
| R7 兼容性 | T1.2 完成后 | 跑 CAP-10/CAP-69 全量回归，确认旧 API 与旧数据 |
| R9 程序环境匹配 | T1.13 开始前 | 确认目标节点 OS/架构/运行时清单；预检覆盖 runtimeVersion 约束 |
| R10 程序包下发 | T1.13 完成 | 测大包上限与通道可用性；不可用时 fail-visible |
| R11 上传程序执行 | T1.12 契约评审 | 安全评审入口白名单、命令白名单、资源/超时限制与审计 |

## 9. 开放问题跟踪

| 编号 | 问题 | 负责人 | 截止点 |
|---|---|---|---|
| O1 | CAP-62 状态与代码不一致 | 平台负责人 | T3.6 前 |
| O2 | MANUAL 用例是否纳入 POC 验收 | 产品/测试 | T1.1 评审 |
| O3 | 产品版本字符串维护方式 | 产品 | T2.6 前 |
| O4 | 证据保留期与导出包留存 | 运维/产品 | T2.5 前 |
| O5 | 大文件证据上限与存储成本 | 运维 | T3.5 前 |
