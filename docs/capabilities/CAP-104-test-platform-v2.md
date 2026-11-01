# CAP-104 测试资产与执行平台 v2（POC）

> 能力 ID：CAP-104 ｜ 分类：执行器 ｜ 状态：已交付（M1/M1.5/M2/M3，2026-11-01） ｜ 日期：2026-11-01
> 依赖：CAP-10（测试执行器）、CAP-69（脚本测试套件）、CAP-12（执行底座）、CAP-21/CAP-34/CAP-36（节点与 runner）、CAP-03（文档报告）、CAP-32（附件/证据文件）、CAP-13（需求/工作项）、CAP-103（三级权限）、CAP-62（数据隔离，状态待核实）。
> 决策记录：
> 1. **扩展现有 `devmind-test`，不新建独立平台**。节点、远程 exec、日志 Hub、环境、报告、缺陷线索、附件/产物存储全部复用。
> 2. **POC 阶段产品 = 现有 project**；新增功能模块层；跨仓库产品二期再加 `test_products`。
> 3. **用例资产分两种粒度**：平台管理的用例（HTTP/COMMAND/MANUAL）与套件级脚本资产（JUNIT）。不强行把 Playwright/pytest 的用例发现搬进平台。
> 4. **运行模型采用父子运行**：`test_plan_run` 为父，`test_run` 按节点/分片为子；保留现有单 run 语义与前端 WS/报告入口。
> 5. **用例版本不可变**；方案触发时固化版本快照；仅 `APPROVED` 可被新方案引用，`DEPRECATED` 禁止新增引用，已有方案按固定版本继续。
> 6. **证据只追加 + sha256 + 运行级 hash 链**；MVP 用小文件 marker，二期补大文件上传端点。
> 7. **分布式先支持 `SHARD`（用例分片），再补 `MATRIX`（同一批用例多节点各跑一遍）**；MVP 单节点。
> 8. **数据隔离不阻塞 POC**：先用项目维度查询 + CAP-103 权限；生产级跨产品隔离依赖 CAP-62。当前 `README` 索引把 CAP-62 标为已实现，但代码未见 `ProjectAccessChecker`/`project_members`，按 CAP-62 文档状态“待实施”处理，实施前必须核实（见 O1）。
> 9. **程序型用例只执行预构建产物**：代码型测试（如 Java 同步消息验证）由使用方在平台外构建，以 ZIP 程序资产上传、评审后执行；平台不在执行时运行 Maven/Gradle/npm install 等构建步骤。入口脚本随包提供并由平台调用，程序包、脚本、依赖作为同一不可变资产以 sha256 追溯。节点只需具备目标运行时，避免构建环境漂移和测试结果不可复现。
> 10. **程序契约是 COMMAND 的可选执行模式**：`CaseSpec.command.contractVersion` 为空时保持现有 COMMAND 语义（退出码/stdout/stderr/文件断言）；为 `"1"` 时启用附录 C 的输入/输出契约与结构化结果映射。旧用例不需要迁移。

## 1. 目的

现有 CAP-10 能对部署目标做冒烟/API 测试，CAP-69 能把 Playwright/pytest 整包脚本下发 runner 跑并回收 JUnit；但测试资产仍停留在“项目内套件 + 用例”的扁平结构，缺少：

- 统一用例描述规范与可解释断言；
- 产品/功能模块分层、用例版本、变更记录、评审与废弃；
- 测试方案编排、环境/参数/并发/顺序配置；
- 跨节点分布式执行与部分失败汇总；
- 不可篡改证据与证据包导出；
- 长期统计（趋势、Top 失败、flaky、耗时、环境对比）。

本能力把这些能力收敛到 `devmind-test`，复用现有 runner 与执行底座，形成“用例可积累、方案可复用、执行可追溯、结果可解释”的 POC 测试平台。目标是先跑通单节点闭环，再扩展到多节点和统计。

**非目标（POC）**：性能/压测、完整 DAG 编排、跨租户隔离、浏览器云真机、平台内录屏、未评审代码自动执行、平台内构建测试程序（代码型测试只执行使用方预构建产物，见 M1.5）。

## 2. 功能需求

### M1 资产与单节点闭环

**FR-01 四级目录**

- 新增 `test_modules`；项目（产品）→ 功能模块 → 测试套件 → 用例。
- `test_suites.module_id` 可空；存量套件归入“未分类”，不阻塞升级。
- 模块支持 tags、owner、sortNo；列表/详情按层级返回，禁止全量平铺。
- 产品版本与用例基线在 M2 的 `test_baselines` 落地；M1 先保留 `project` 作为产品。

**FR-02 统一用例描述规范（CaseSpec）**

- 统一字段：`caseKey`、`title`、`type`、`priority`、`tags`、`preconditions`、`steps`、`assertions`、`cleanup`、`executor`、`source`。
- `type`：HTTP / COMMAND / MANUAL；JUNIT 作为 suite 级脚本资产，结果映射为用例结果。
- `executor`：`location`（SERVER/NODE）、`labels`、`timeoutSec`、`retry{max,on}`。
- `caseKey` 项目内唯一，建议 `{产品简码}-{模块简码}-{序号}`；发布后不可改。
- 保留现有 CAP-10 `test_cases` 字段兼容旧用例；新类型写 `spec_json`，旧字段只读回显。

**FR-03 版本、变更记录与评审**

- `test_case_versions` 保存不可变快照：`versionNo`、`specJson`、`reviewStatus`、`changeNote`、`createdBy/At`、`reviewedBy/At`。
- 编辑已审核用例生成新草稿版本，不覆盖历史；审核通过后当前头指向新版本。
- 状态：DRAFT / APPROVED / DEPRECATED。
- 新方案只能引用 APPROVED；DEPRECATED 不可新增引用；已有方案按固定版本运行并显示废弃提示。
- 版本列表即变更记录，支持查看 diff（复用 CAP-03 `TextDiff` 思路或前端 JSON diff）。

**FR-04 测试资产导入**

- `test_assets`：`kind`（GIT/ZIP/FILE）、`source`、`branch/commit`、`sha256`、`size`、`storageRef`、`reviewStatus`、`uploadedBy/At`。
- git 导入保留 repoUrl + branch + commit；zip/file 导入保存原包与解包目录，做 zip-slip、文件数、大小限制。
- 资产必须评审通过后才能被方案引用；导入动作落审计。
- 复用 CAP-32 附件/产物存储；证据与资产分开，不混用生命周期。

**FR-05 脚本套件资产化**

- 现有 CAP-69 `kind=script` 套件增加 `moduleId`、`owner`、`tags`、`reviewStatus`、`commit`、`sourceAssetId`。
- 运行 repo 块使用固定 commit；未填 commit 时记录实际 HEAD hash 到证据。
- JUnit 结果按 `classname#name` 快照；suite 级视为一个可编排资产。
- 现有 API/前端字段保持兼容，新增字段可空。

**FR-06 环境预检与清理**

- `environments` 增加 `productVersion`、`precheckJson`、`cleanupJson`。
- 预检项：NODE_ONLINE、EXEC_PROTOCOL、HTTP、COMMAND、VERSION_MATCH；每项记录 expected/actual/message。
- 预检在创建运行前执行；失败则运行 `BLOCKED`，不产生假失败。
- 清理：用例级 cleanup + 方案级 teardown，无论成败都执行；清理失败记 warning 与证据，不覆盖主判定。

**FR-07 单用例/用例集执行**

- 单用例、套件（含脚本套件）可在指定节点/环境执行；复用 `AgentNodeRouter` 路由链。
- HTTP 默认服务端执行，可配置 NODE 以验证节点侧网络；COMMAND/JUNIT 必须 NODE。
- 结果复用 `test_runs` / `test_case_results`；单用例运行可作为 `run_scope=CASE` 的子运行。
- 失败即停/继续在 M2 方案级实现；M1 单用例/套件默认顺序执行。

**FR-08 过程采集与证据**

- 每个用例结果记录：`command`、`workingDir`、`exitCode`、`duration`、`stdout/stderr`、`envSnapshot`（secret 掩码）、`nodeId`、`attempt`、`artifactHash`、`startedAt/finishedAt`。
- 新增 `test_evidence`：`runId`、`resultId`、`nodeId`、`kind`、`filename`、`sha256`、`storageRef`、`createdAt`；只追加，不提供更新/删除 API。
- 运行级日志继续走 `test_runs.logs_text` 与 WS；证据表保存可下载原件。
- MVP 小文件（JUnit、小截图）用 marker 回传；单文件/总量上限可配，超限提示改用资产上传（M3 FR-24）。

**FR-09 断言与可解释判定**

- 断言执行器按类型：
  - HTTP：status、header、JSONPath、contains、regex、schema；
  - COMMAND：exitCode、stdout/stderr contains/regex、文件存在、sha256；
  - JUNIT：pass/fail/skip/error 映射。
- 每条断言产出 `{source, op, expected, actual, pass, message}`；失败时展示差异，不只给“失败”。
- 结果状态：PASS / FAIL / BLOCKED / SKIP / ERROR；失败分类：ASSERTION / INFRA / TIMEOUT / PRECHECK / SCRIPT_ERROR / UNKNOWN。
- 人工复核：reviewer 可 CONFIRM/REJECT，原始判定与证据不变，报告同时展示“判定 + 复核”。

**FR-10 单次执行报告**

- 汇总：总数、通过、失败、阻塞、跳过、错误、通过率、耗时。
- 失败清单：用例、节点、失败分类、断言差异、证据链接。
- 报告同时生成 Markdown 与结构化 JSON；Markdown 沉淀 CAP-03 `report` 文档。
- 现有 `GET /api/test-runs/{id}/report` 扩展，不破坏旧格式。

**FR-11 用例检索与去重**

- 按产品、模块、套件、标签、类型、评审状态、关键字、失败原因检索。
- `caseKey` 唯一；另存 `signature`（type + method/path + assertion 归一化哈希），新建时提示疑似重复，不自动合并。
- H2 阶段用 LIKE/索引；数据量大后接 Lucene（CAP-84 已有检索经验）。

### M1.5 程序型用例（M1 增补，FR-25~29）

> 定位：代码型测试（例如写一个 Java 程序验证同步发送消息）不把源码/构建过程搬进平台，而是上传**已经构建好、可直接执行**的程序包。入口脚本调用测试程序，平台负责程序包下发、输入注入、输出回收、结果映射与证据。
> FR-25~29 为增补编号，不回改 FR-01~24；M1.5 不包含“节点现场构建”。

**FR-25 预构建程序资产**

- 程序以 ZIP 上传到 `test_assets`，资产角色 `PROGRAM`；包内必须包含 `.devmind-program.json` 清单和入口脚本，推荐结构：
  ```text
  sync-msg-test-1.2.0.zip
    .devmind-program.json
    run.sh
    lib/sync-msg-test.jar
    lib/deps/...
  ```
- 清单字段：`contractVersion`、`name`、`version`、`runtime`（JAVA/NATIVE/NODE/PYTHON/SHELL）、`runtimeVersion`（可选，如 `>=21`）、`entrypoint`、`targetOs`、`targetArch`、`description`（可选）。清单在 M1.5 只支持 `contractVersion="1"`；字段与示例见附录 C.1。
- **禁止执行时构建**：平台不运行 `mvn`/`gradle`/`npm install`/`pip install` 等构建或依赖安装命令；程序、脚本、依赖必须在包内自包含。源码可作为附带文件上传用于审计，但不作为执行入口。
- 程序资产不可变；升级程序 = 上传新资产 + 用例版本引用新 assetId。执行前必须 `APPROVED`，运行证据记录 assetId、sha256、runtime、targetOs/targetArch、entrypoint。
- 上传/解包限制沿用 CAP-104 资产限制：zip-slip、绝对路径、符号链接、文件数与单文件/总量上限；超限拒绝上传或执行。

**FR-26 输入规范 v1**

- 平台在执行前生成 `$DEVMIND_INPUT`（默认 `<workspace>/.devmind/input.json`），注入 `DEVMIND_INPUT`、`DEVMIND_RESULT`、`DEVMIND_ARTIFACTS` 三个绝对路径；入口脚本通过环境变量读取，不依赖参数顺序。
- 输入只包含运行与用例上下文：`runId`、`caseId`、`caseKey`、`projectId`、`environment`、`parameters`、`timeoutSec`、`workspace`，字段定义冻结在附录 C。
- 参数来源为环境变量、用例 `executor.env`、运行覆盖参数；同名优先级：运行覆盖 > 用例 > 环境。
- **secret 只走环境变量**，不写入 input.json；`envSnapshot`、日志、报告中脱敏，规则复用现有敏感键掩码。
- 输入文件由平台生成后只读，单文件上限 1MB。

**FR-27 输出规范 v1**

- 测试程序把结构化结果写到 `$DEVMIND_RESULT`（默认 `<workspace>/.devmind/result.json`）；stdout/stderr 只放人读日志，平台原样收集为证据。
- 输出包含 `status`（pass/fail/blocked/skip/error）、`message`、`assertions`（`source=CUSTOM`，复用现有 expected/actual/pass/message 模型）、`metrics`、`artifacts`；完整 JSON 契约见附录 C。
- 退出码用于兜底：`0=pass`、`1=fail`、`2=blocked`、`3=error`；**非 0 退出码优先于 result.json 的 status**，避免程序异常崩溃被写成通过。
- 契约 v1 下 `$DEVMIND_RESULT` 缺失、JSON 畸形、`contractVersion` 不符或字段非法 → `error / SCRIPT_ERROR`，不静默通过；超时/整树 kill → `error / TIMEOUT`。
- `artifacts` 只允许 `$DEVMIND_ARTIFACTS` 下的相对路径，拒绝绝对路径、`..` 与符号链接；平台计算 sha256 后入证据链，单文件/单次运行上限沿用证据配置。
- 结构化断言失败展示 expected/actual；`metrics` 只作展示与后续统计，不参与判定。

**FR-28 执行与证据集成**

- 用例使用 `type=COMMAND`、`executor.location=NODE`、`source.kind=ASSET`、`source.assetId=<程序资产>`，并在 `command.contractVersion="1"`；`command.command` 调用包内入口脚本（例如 `sh run.sh`），`workingDir` 相对程序包根目录。
- 执行顺序：预检（资产 APPROVED、清单/入口存在、节点 OS/架构匹配、运行时可用）→ 下发并物化程序包到隔离工作区 → 写 input.json、注入环境变量 → 执行入口脚本 → 回收 result.json 与 artifacts → 映射结果 → 追加证据 → 生成报告。
- 预检失败运行 `BLOCKED`，不产生假失败；运行时不可用、targetOs/targetArch 不一致要给出具体原因。
- 证据类型新增：`PROGRAM_MANIFEST`、`INPUT_JSON`、`RESULT_JSON`、`STDOUT`、`STDERR`、`ARTIFACT`；均进入 sha256 + prev_hash 链，并记录程序资产 id/sha256。
- 超时与 cleanup 语义同现有 COMMAND：超时杀整棵进程树，cleanup 无论成败执行；失败分类按附录 C 映射到 ASSERTION/INFRA/TIMEOUT/PRECHECK/SCRIPT_ERROR/UNKNOWN。
- 资产下发到节点的通道（runner 文件通道或一次性下载 token）在实施时按执行底座现状选定；通道不可用时运行必须 fail-visible，不允许跳过程序直接判通过。

**FR-29 管理、权限与安全**

- 程序资产上传/删除需 `project.tests.action.manage-asset`，评审需 `review-case`，运行需 `run`；VIEWER 只读。
- 执行用户上传的程序本质是受控远程代码执行：只允许 `APPROVED` 程序资产、只允许清单声明的入口脚本、禁止任意绝对路径命令；节点 exec 白名单、资源上限、超时整树 kill 仍然生效。
- 上传、评审、运行、下载、删除全部审计；程序包 sha256 与运行证据绑定，禁止运行中替换程序。
- 程序包不得携带平台凭据；连接串、账号、token 通过环境变量引用并脱敏；程序输出中的敏感值不进入报告正文。
- 前端：资产页支持程序角色与 runtime/targetOs 字段；用例编辑器展示程序资产、入口脚本与契约版本；运行详情展示输入摘要、结构化输出、artifacts 与证据链。

### M2 方案编排与证据包

**FR-12 测试方案**

- `test_plans`：`projectId`、`name`、`description`、`envId`、`failStrategy`、`concurrency`、`parametersJson`、`requirementId`、`isTemplate`、`version`。
- `test_plan_items`：`planId`、`refType`（CASE/SUITE）、`refId`、`refVersion`、`seq`、`parallelGroup`、`overridesJson`、`enabled`。
- 方案支持从用例库勾选、按套件选择、复制模板；方案运行前固化 `planSnapshotJson`，后续编辑不影响历史运行。
- 方案保存时校验引用用例为 APPROVED；废弃用例拒绝新增。

**FR-13 方案运行与编排**

- 触发：预检 → 固化快照 → 创建 `test_plan_run` 父记录 → 按顺序/并行组创建子 `test_run` → 汇总。
- 串行/并行：M2 支持顺序项 + `parallelGroup` 并行组；完整 DAG 后置。
- 并发度受方案 `concurrency` 与节点能力双重限制；默认节点内串行、节点间并行。
- 复用现有 WS `/ws/test-runs/{id}` 与报告端点；父运行提供汇总 WS/查询。

**FR-14 失败策略、重试与清理**

- `FAIL_FAST`：停止调度新用例/分片，未执行标 SKIP/CANCELLED；`CONTINUE`：全部执行。
- 重试：按用例 `retry{max,on}`，保留每次 attempt 结果与证据；最终判定取最后一次，flaky 另算。
- 超时：用例级 `timeoutSec`；节点失联/exec 超时按 BLOCKED/ERROR 处理。
- 清理：用例 cleanup、方案 teardown 在 finally 执行；清理失败记 warning。

**FR-15 证据包导出**

- 按运行/方案运行导出 zip：`manifest.json`（运行、方案、环境、节点、命令、退出码、哈希）、报告、日志、断言、文件。
- 运行级 hash 链：每条证据 sha256 + 前一条哈希；导出清单带链校验值。
- 仅授权角色可导出；导出动作落审计。

**FR-16 基线与回归对比**

- `test_baselines`：`projectId`、`name`、`productVersion`、`caseVersionsJson`、`createdBy/At`。
- 方案运行可对比基线：新增失败、已修复、持续失败、新增用例、通过率变化、耗时变化。
- 对比结果是查询视图，不修改历史运行。

**FR-17 需求/工作项联动**

- 方案/运行可关联 `requirementId`；报告反向引用需求编号。
- 失败用例可一键创建工作项/缺陷（复用 `WorkItemService`），带运行、用例、断言差异、证据链接。
- 现有 `test_runs.work_item_id` 保留；新增 `requirement_id`。

**FR-18 方案模板与复用**

- 方案 `isTemplate=true` 可复制为新方案；模板变更不影响已创建方案。
- 方案参数（环境变量、节点标签、并发、超时）支持运行前覆盖，覆盖值写入快照。
- 历史运行按方案/版本筛选，趋势在 M3。

### M3 分布式与统计

**FR-19 节点选择与分片**

- 节点选择：显式节点 > 环境节点组 > 标签匹配 > 能力匹配 > 平台默认；复用 `AgentNodeRouter`，补 capability 选择。
- `SHARD`：用例按 hash/标签分片到多个节点；`MATRIX`：同一批用例在多个节点/环境各跑一遍。
- 默认节点内串行；方案 `concurrency` 控制节点间并行；runner `maxConcurrent` 作为硬上限。
- 每个子运行记录 `nodeId`、`shardKey`；父运行记录分片策略。

**FR-20 分布式汇总与失联处理**

- 父运行结论：PASS / FAIL / PARTIAL / BLOCKED。
- 子运行状态：PASS / FAIL / BLOCKED / ERROR；未执行用例标 BLOCKED 并写原因。
- 节点离线/超时：该子运行 ERROR/BLOCKED，其他节点继续；父运行 PARTIAL/FAIL，不伪装整体通过。
- 保留每个节点独立结果；支持从父运行下钻到子运行、用例、证据。
- MVP 不做失败分片自动迁移；提供按分片重跑。

**FR-21 统计分析**

- 通过率趋势（按日/周/方案/环境）；Top 失败用例；flaky 识别；耗时 p50/p95；环境维度对比。
- 口径：通过率 = PASS / (PASS+FAIL+ERROR)，BLOCKED/SKIP 不计入分母但单独展示。
- flaky：窗口内同一用例既有 PASS 又有 FAIL，或重试后通过；最小样本数可配（默认 5）。
- 查询按需聚合；为 `test_case_results(run_id, case_id, status, duration)` 等加索引。

**FR-22 权限与隔离**

- 复用 CAP-103：新增页签 `cases/plans/stats` 与按钮 `manage-case/review-case/manage-plan/run-plan/export-evidence/manage-baseline`。
- 维护者、执行者、查看者通过自定义角色 + ACTION 权限组合；VIEWER 只读。
- POC 数据按 `projectId` 过滤；跨产品隔离依赖 CAP-62。CAP-62 完成前不宣称“产品级数据隔离”。
- 导入/评审/运行/导出/删除全部落审计。

**FR-23 通知与审计**

- 方案运行完成/失败发通知（复用 CAP-06），带通过率、失败数、报告链接。
- 审计：资产导入、用例评审、方案变更、运行触发、证据导出、删除。

**FR-24 大文件证据上行**

- 增加一次性上传 token + `POST /api/test-runs/{id}/evidence`；命令包装在结束后上传截图/大日志。
- 服务端校验 token、大小、类型、sha256，落证据表与对象存储；marker 保留为小文件兜底。
- 上传通道必须限流、限大小、限 MIME，防被当作通用文件服务。

## 3. 否决方案

| 方案 | 否决理由 |
|---|---|
| 新建独立测试平台/独立数据库 | 节点、exec、环境、鉴权、报告全部要重做；与现有 runner 和项目/需求链路割裂，POC 成本最高 |
| 所有测试类型统一成一张 case 表，平台逐条编排脚本用例 | Playwright/pytest 的用例发现与 fixture 在框架侧；强行拆开会丢掉框架语义。保留 suite 级脚本资产 |
| 用 CAP-03 文档承载用例与版本 | 文档适合报告/方案，不适合结构化断言、检索、执行与引用完整性；用例需要专用表 |
| 单层 `test_runs` 记录分布式结果 | 无法保留每节点/分片独立结果与下钻；采用父子运行 |
| 证据只存 `logs_text` | 无法核验命令、退出码、文件与哈希；必须独立只追加证据表 |
| 先做完整 DAG/审批流再执行 | POC 需要先验证执行与证据闭环；M2 顺序 + 并行组足够，DAG 后置 |
| 节点上现场构建测试程序（mvn/gradle/npm/pip） | 构建环境漂移、依赖不可复现、放大节点攻击面；改为使用方预构建并以不可变 ZIP 资产上传（决定 9） |
| 等 CAP-62 完成再做 POC | 会阻塞验证；POC 先项目维度过滤，生产隔离前补齐 CAP-62 |

## 4. 架构与模块归属

```
devmind-test
├── asset/        TestModule/TestCase/TestCaseVersion/TestAsset
├── plan/         TestPlan/TestPlanItem/TestBaseline/PlanSnapshot
├── orchestrator/ PlanRunOrchestrator/Precheck/Shard/Aggregate
├── executor/     HttpCaseExecutor/CommandCaseExecutor/JUnitResultAdapter/ManualCaseExecutor
│                 ProgramManifestParser/ProgramCaseExecutor/ProgramResultAdapter（M1.5）
├── assertion/    AssertionEvaluator + AssertionResult
├── evidence/     EvidenceService/EvidenceStorage/EvidenceExport
├── stats/        TestStatsService
├── controller/   TestAssetController/TestPlanController/TestStatsController...
└── existing      TestRunService/TestSuiteService/ScriptSuiteService（增量扩展）

复用：
devmind-execution  AgentNodeRouter/AgentNodeStepRunner/ExecutionLogHub
devmind-agent      节点、标签、能力、心跳
devmind-project    Environment/WorkItem，环境预检字段
devmind-docs       报告文档（kind=report）
devmind-attachment/artifact  证据与资产存储
devmind-notification 方案运行完成通知
devmind-auth       CAP-103 菜单/页签/按钮 + ApiPermissionCatalog
frontend/src/features/test  用例库/方案/运行详情/统计
```

职责边界：

- `devmind-test` 拥有测试资产、方案、证据、统计；不复制 runner 逻辑。
- `devmind-execution` 只负责“下发 + 日志 + 退出码”，不认识测试用例。
- `devmind-project` 只提供环境/工作项，不依赖 test 实现。
- 父子运行和证据导出归 test；WS 继续复用 CAP-12 Hub。

## 5. 数据模型

```
test_modules(
  id, project_id, name, tags, owner, sort_no, created_at, updated_at,
  unique(project_id, name)
)

test_case_versions(
  id, case_id, version_no, spec_json, review_status, change_note,
  created_by, created_at, reviewed_by, reviewed_at,
  unique(case_id, version_no)
)

test_assets(
  id, project_id, kind, asset_role, source, branch, commit_hash, sha256, size_bytes,
  storage_ref, review_status, uploaded_by, created_at,
  runtime, entrypoint, target_os, target_arch, manifest_json   -- M1.5 程序包（asset_role=PROGRAM）
)

test_plans(
  id, project_id, name, description, env_id, fail_strategy, concurrency,
  parameters_json, requirement_id, is_template, version, created_by, created_at, updated_at
)

test_plan_items(
  id, plan_id, ref_type, ref_id, ref_version, seq, parallel_group,
  overrides_json, enabled
)

test_plan_runs(
  id, plan_id, plan_snapshot_json, env_snapshot_json, conclusion,
  started_at, finished_at, triggered_by, created_at
)

test_baselines(
  id, project_id, name, product_version, case_versions_json, created_by, created_at
)

test_evidence(
  id, plan_run_id, run_id, result_id, node_id, kind, filename,
  sha256, prev_hash, storage_ref, created_at
)

扩展：
test_suites + module_id, owner, tags, review_status, commit_hash, source_asset_id
test_cases + case_key, type, spec_json, version, review_status, signature, source_asset_id
test_runs + plan_run_id, plan_item_id, requirement_id, shard_key, conclusion, run_scope
test_case_results + node_id, attempt, failure_category, assertion_json, command, exit_code,
                    env_snapshot_json, evidence_id, started_at, finished_at,
                    program_asset_id, program_contract_version, program_manifest_json   -- M1.5
environments + product_version, precheck_json, cleanup_json
```

兼容性：

- 新列可空；旧 CAP-10/CAP-69 API 不变。
- `test_case_results.case_id` 继续允许 NULL（脚本套件结果）。
- 证据表不更新/删除；清理只允许按保留期归档到冷存储，不物理删当前证据（或由管理员归档，另记审计）。

## 6. API 概要

```
# 资产
GET/POST/PUT/DELETE /api/projects/{pid}/test-modules
GET/POST/PUT/DELETE /api/test-cases
GET/POST /api/test-cases/{id}/versions
POST /api/test-cases/{id}/versions/{v}/review
GET /api/test-cases?projectId=&moduleId=&tags=&keyword=&reviewStatus=
POST /api/test-assets/import
POST /api/test-assets/upload             # M1.5 PROGRAM 包上传（multipart），解析并校验清单
GET /api/test-assets?projectId=
GET /api/test-assets/{id}/manifest       # M1.5 程序清单与入口/运行时信息
POST /api/test-assets/{id}/review

# 套件（增量）
PUT /api/test-suites/{id}              # moduleId/owner/tags/reviewStatus/commit
POST /api/test-suites/{id}/publish    # 既有，保留

# 环境
POST /api/projects/{pid}/environments/{eid}/precheck
PUT /api/projects/{pid}/environments/{eid}

# 方案
GET/POST/PUT/DELETE /api/test-plans
GET/POST/PUT/DELETE /api/test-plans/{id}/items
POST /api/test-plans/{id}/run
GET /api/test-plan-runs/{id}
POST /api/test-plan-runs/{id}/cancel
GET /api/test-plan-runs/{id}/report
GET /api/test-plan-runs/{id}/evidence.zip
GET/POST/DELETE /api/test-baselines

# 运行（增量）
GET /api/test-runs/{id}
GET /api/test-runs/{id}/report
GET /api/test-runs/{id}/evidence
GET /api/test-runs/{id}/evidence.zip
POST /api/test-runs/{id}/evidence        # M3 大文件上传，一次性 token
POST /api/test-runs/{id}/work-items      # 失败一键创建工作项
PUT /api/test-runs/{id}/results/{rid}/review

# 统计
GET /api/test-stats/overview?projectId=&envId=&from=&to=&planId=
GET /api/test-stats/trend
GET /api/test-stats/top-failures
GET /api/test-stats/flaky
GET /api/test-stats/duration
GET /api/test-stats/environments
```

复用：`/ws/test-runs/{id}`、`POST /api/tests/runs`（旧入口保留）、`POST /api/script-suites/{id}/run`（旧入口保留）。

## 7. 安全

- 导入资产必须评审；未 APPROVED 资产不可被方案引用或执行。
- ZIP 解包：zip-slip、绝对路径、文件数、单文件/总量限制；解包目录隔离。
- COMMAND/脚本执行：仅允许审批过的资产与命令；节点侧资源上限、超时、整树 kill；禁止把平台当通用远程 shell。
- 程序型用例（M1.5）：执行本质是受控远程代码执行——只执行 `APPROVED` 程序资产；入口脚本必须由清单声明且位于包内；平台不在节点执行构建/依赖安装命令；节点 OS/架构/运行时预检不通过即 `BLOCKED`；程序包 sha256 与运行证据绑定，运行中禁止替换；程序 stdout/stderr/result.json 中的敏感值按统一掩码规则处理。
- 程序输出路径：仅接受 `$DEVMIND_ARTIFACTS` 下相对路径，拒绝绝对路径、`..`、符号链接与包外文件；result.json 大小、artifacts 数量/单文件/总量设上限。
- 环境 secret 只存引用/掩码；`envSnapshot` 写证据前脱敏；日志中的 token 不得落库。
- 证据只追加；导出需 `export-evidence` 权限；导出动作审计。
- CAP-103：新增页签/按钮权限与 API 规则；VIEWER 只读；ADMIN 短路。
- 数据边界：POC 按 `projectId` 过滤；CAP-62 未实施前不提供跨产品硬隔离。若 POC 验收要求隔离，需先补 CAP-62 最小成员模型。
- 审计：导入、评审、运行、导出、删除、权限变更。

## 8. 里程碑

| 里程碑 | 范围 | 交付 |
|---|---|---|
| M1 资产与单节点闭环 | FR-01~11 | 四级目录、CaseSpec、版本/评审、git/zip 导入、环境预检/清理、单用例/套件单节点执行、证据、断言差异、单次报告 |
| M1.5 程序型用例（M1 增补） | FR-25~29 | 预构建程序 ZIP 资产与清单、输入/输出契约 v1、程序执行与结果映射、程序证据链、权限与安全 |
| M2 方案编排与证据包 | FR-12~18 | 方案模型/模板、串并行、失败策略/重试/清理、父子运行、证据包导出、基线对比、需求/工作项联动、检索去重 |
| M3 分布式与统计 | FR-19~24 | 节点选择/分片/矩阵、失联汇总、下钻、统计、权限与隔离、通知审计、大文件证据上行 |

M1 为 POC 必须交付；M1.5 为代码型测试（Java 程序等）接入的增补范围，只执行预构建产物；M2/M3 为第二期。M1 不引入分布式与趋势统计，避免范围膨胀。

> 交付记录（2026-11-01）：M1、M1.5、M2、M3 均已落地，`mvn test`（devmind-test 单测）与隔离 E2E 全绿。正式回归已登记 `tests/e2e/catalog.json`：`Regression.test_case_version_review_run_and_evidence`、`Regression.test_test_asset_review_gate_and_deletion`（M1）、`Regression.test_runner_partial_failure_and_program_case`（M1.5 + M3 节点部分失败）、`Regression.test_plan_orchestration_baseline_stats_and_evidence`（M2）、`Regression.test_distributed_matrix_and_shard`（M3）；共享实例只跑 smoke，写入回归使用入口自起的隔离实例与真实 runner 进程。CAP-62 数据隔离仍按 O1 跟踪，POC 以项目维度过滤为边界。

## 9. 验收标准

### M1

- 建产品/模块/套件/用例，四级树可查；旧套件自动落“未分类”。
- 用例可建草稿、提交评审、发布 v1；修改生成 v2；废弃后新方案无法选择，旧方案仍可运行。
- git 仓库与 zip 资产可导入，保留 commit/sha256/来源；未评审资产不可运行。
- 单用例和脚本套件在单节点跑通：命令、退出码、耗时、环境快照、stdout/stderr、JUnit/截图证据可查。
- HTTP 断言失败展示 expected/actual；命令断言失败展示 exitCode/stdout 差异；JUnit 失败保留 failure/error 文本。
- 环境预检失败展示具体检查项与原因；运行后 cleanup 执行并留证据。
- 单次报告含通过率、失败清单、失败分类、证据链接；CAP-03 报告文档可打开。
- 单测：`CaseSpecValidatorTest`、`AssertionEvaluatorTest`、`TestAssetImportTest`、`EvidenceChainTest`、`EnvironmentPrecheckTest`。
- E2E：`Regression.test_case_version_review_run_and_evidence`、`Regression.test_test_asset_review_gate_and_deletion`（已登记 `tests/e2e/catalog.json`）；共享实例只跑 smoke，写入回归用隔离实例。
- 现有 CAP-10/CAP-69 测试不回归，旧 API 兼容。

### M1.5

- 上传已构建好的程序 ZIP（如 Java 同步消息验证程序），清单/入口脚本校验通过后进入评审；未 APPROVED 不可运行。
- 执行时不发生构建或依赖安装；节点缺少目标运行时或 OS/架构不匹配时运行 `BLOCKED` 并展示原因。
- 平台生成 input.json 并注入 `DEVMIND_INPUT/DEVMIND_RESULT/DEVMIND_ARTIFACTS`；参数优先级与 secret 掩码符合 FR-26。
- 程序写出 result.json 后，结构化断言、metrics、artifacts 进入运行详情与证据链；非 0 退出码优先于 JSON status。
- result.json 缺失/畸形/契约版本不符 → `SCRIPT_ERROR`；超时 → `TIMEOUT` 且整树 kill；均不判通过。
- artifacts 路径越界、超大或超量被拒绝，拒绝原因进入证据/运行日志。
- 程序资产 id/sha256/runtime/entrypoint 可在运行详情与报告中追溯。
- 单测：清单解析与版本校验、input/result 契约校验、退出码优先级、artifacts 路径校验、证据链。
- E2E：`Regression.test_runner_partial_failure_and_program_case`（隔离实例 + 真实 runner：预构建程序包上传 → 评审 → 单用例运行 → 结果/证据断言）。

### M2

- 方案可从用例库/套件勾选，配置环境、参数、并发、顺序、失败策略。
- 顺序 + parallelGroup 并行执行；FAIL_FAST 与 CONTINUE 行为符合定义。
- 重试保留每次 attempt；cleanup 失败不覆盖主判定但报告可见。
- 证据包 zip 含 manifest 与 hash 链，可校验；导出有权限与审计。
- 基线对比输出新增失败/已修复/持续失败/新增用例/通过率变化。
- 失败用例一键创建工作项，报告反链需求编号。
- E2E：`Regression.test_plan_orchestration_baseline_stats_and_evidence`（隔离实例，覆盖编排、基线、统计、证据包与导出权限）。

### M3

- 两个以上节点按标签/能力选择，SHARD/MATRIX 至少各一条用例验证；父运行可下钻到节点/用例/证据。
- 一个节点离线/超时，其他节点继续，父运行 PARTIAL/FAIL 且未执行用例标 BLOCKED 原因。
- 统计接口返回趋势/Top/flaky/耗时/环境对比；flaky 口径有单测。
- CAP-103 页签/按钮显隐与后端 403 生效；项目维度过滤验证。
- E2E：`Regression.test_distributed_matrix_and_shard`（隔离实例 + 两个真实 runner，覆盖 MATRIX/SHARD 与节点选择校验）；节点部分失败见 `Regression.test_runner_partial_failure_and_program_case`。

## 10. 风险与开放问题

| 编号 | 风险/问题 | 口径 |
|---|---|---|
| R1 | exec 通道无通用文件上行，截图/大日志难回收 | M1 用 marker 小文件；M3 已补一次性 token 上传端点；大文件限流限型 |
| R2 | CAP-62 数据隔离未落地，README 与文档状态不一致 | O1 先核实；POC 项目维度；生产隔离前必须补 CAP-62 |
| R3 | 导入的测试代码在节点执行，存在安全风险 | 评审闸门 + 命令白名单 + 资源/大小限制 + 审计；未评审不执行 |
| R4 | runner 并发与节点资源冲突 | 默认节点内串行、节点间并行；`maxConcurrent` 硬上限；方案 concurrency 双重限制 |
| R5 | 产品映射：project=product 可能不覆盖多仓库产品 | POC 接受；跨仓库产品二期加 `test_products`，避免早期过度设计 |
| R6 | H2 统计随数据增长变慢 | 先加索引；数据量大后日汇总表/外部库；POC 不预建 |
| R7 | 旧 CAP-10/CAP-69 兼容性 | 新列可空、旧 API 保留；迁移脚本/启动种子只补默认值，不改旧行为 |
| R8 | 分布式语义歧义（分片 vs 矩阵） | 两种都已实现；方案里显式选 `SEQUENTIAL/SHARD/MATRIX`，默认串行 |
| R9 | 预构建程序与节点环境不匹配（OS/架构/运行时版本/动态库） | 清单声明 runtime、targetOs、targetArch；预检实际版本与目标平台，不匹配即 BLOCKED；不自动构建兜底 |
| R10 | 程序包较大，证据/资产下发通道可能撑不住 | 先复用 runner 文件通道并设包大小上限；超限拒绝上传并提示拆分依赖；通道不可用时运行 fail-visible |
| R11 | 执行用户上传的程序本身是 RCE | 只执行 APPROVED + 清单入口；节点 exec 白名单、资源/超时限制、审计与 sha256 绑定；禁止任意绝对路径命令 |
| O1 | CAP-62 状态核实 | CAP-62 文档写“待实施”，README 写“已实现”，代码未见成员表；实施前由负责人确认并以代码为准 |
| O2 | 手工用例范围 | M1 只存 MANUAL 用例与人工判定，不做手工步骤执行器；自动化后置 |
| O3 | 基线定义 | 基线 = 一组用例版本快照；产品版本字符串由人维护，不自动推导 |
| O4 | 报告格式 | Markdown 给人看，JSON 给对比/统计；以 JSON 为稳定契约 |
| O5 | 证据保留期 | POC 默认跟随附件保留策略；导出包长期留存；清理策略需产品确认 |
| O6 | 程序契约演进 | 清单与输入/输出均带 `contractVersion`；v1 冻结后只增不破，新版本另开 v2，旧程序资产不强制迁移 |

## 附录 A：原始 26 项需求映射

| 原始需求 | 落点 |
|---|---|
| 1 用例描述规范 | FR-02 |
| 2 执行规范 | FR-02/FR-06/FR-07 |
| 3 过程采集规范 | FR-08 |
| 4 结果验证规范 | FR-09 |
| 5 四级目录 | FR-01 |
| 6 产品版本/模块标签/责任人 | FR-01/FR-05/FR-16 |
| 7 持久化用例/逻辑/验证/方案/历史 | FR-03/FR-04/FR-05/FR-12 |
| 8 导入程序/脚本与版本来源 | FR-04/FR-25 |
| 9 用例版本/变更/评审/废弃 | FR-03 |
| 10 runner 下发/节点标签/能力 | FR-07/FR-19 |
| 11 单用例/套件/方案级批量；并行串行；失败策略 | FR-07/FR-13/FR-14 |
| 12 跨节点分布式与部分失败汇总 | FR-19/FR-20 |
| 13 节点独立结果与下钻 | FR-20 |
| 14 自定义方案（勾选/环境/参数/并发/顺序） | FR-12 |
| 15 方案模板/趋势/回归对比 | FR-16/FR-18/FR-21 |
| 16 证据：日志/命令/退出码/耗时/环境/哈希/文件 | FR-08/FR-24 |
| 17 证据绑定/不可篡改/导出 | FR-08/FR-15 |
| 18 可解释判定与断言差异 | FR-09 |
| 19 单次与周期报告 | FR-10/FR-21 |
| 20 趋势/Top/flaky/耗时/环境对比 | FR-21 |
| 21 环境定义/预检/凭据引用/节点/版本 | FR-06 |
| 22 执行后清理/还原 | FR-06/FR-14 |
| 23 去重与检索 | FR-11 |
| 24 需求/工作项串联与反链 | FR-17 |
| 25 权限与隔离 | FR-22 |
| 26 MVP 建议 | M1 范围（FR-01~11）；程序型用例为 M1.5（FR-25~29）；分布式/方案/趋势在 M2/M3 |

## 附录 B：实施任务清单

见 [docs/design/CAP-104-test-platform-v2-impl.md](../design/CAP-104-test-platform-v2-impl.md)。

## 附录 C：程序执行契约 v1（M1.5）

> 适用范围：`CaseSpec.command.contractVersion="1"` 且 `source.kind=ASSET` 的程序型用例。契约 v1 冻结；任何破坏性变更另开 v2。所有 id 以字符串传递，避免跨语言精度差异；所有路径均为绝对路径或相对约定根目录的相对路径。

### C.1 程序包与清单

推荐包结构：

```text
sync-msg-test-1.2.0.zip
  .devmind-program.json
  run.sh
  lib/sync-msg-test.jar
  lib/deps/...
```

`.devmind-program.json`：

```json
{
  "contractVersion": "1",
  "name": "sync-msg-test",
  "version": "1.2.0",
  "runtime": "JAVA",
  "runtimeVersion": ">=21",
  "entrypoint": "run.sh",
  "targetOs": "linux",
  "targetArch": "x64",
  "description": "同步发送消息验证"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| contractVersion | 是 | 固定 `"1"` |
| name / version | 是 | 程序名与版本；同名升级上传新资产 |
| runtime | 是 | JAVA / NATIVE / NODE / PYTHON / SHELL |
| runtimeVersion | 否 | 运行时版本约束，如 `>=21`；缺省不校验版本 |
| entrypoint | 是 | 包内相对路径，禁止绝对路径与 `..`；必须是普通文件 |
| targetOs | 否 | `linux` / `windows` / `any`（缺省 any） |
| targetArch | 否 | `x64` / `arm64` / `any`（缺省 any） |
| description | 否 | 人读说明 |

包约束：ZIP 单包与解包总量上限沿用 CAP-104 资产配置；禁止 zip-slip、绝对路径、符号链接、设备文件；入口脚本与所有依赖必须自包含；**平台不执行任何构建或依赖安装命令**。

### C.2 运行环境与路径

| 变量/路径 | 说明 |
|---|---|
| `$DEVMIND_INPUT` | 平台生成的输入 JSON 绝对路径，默认 `<workspace>/.devmind/input.json`；只读 |
| `$DEVMIND_RESULT` | 程序写出的结果 JSON 绝对路径，默认 `<workspace>/.devmind/result.json` |
| `$DEVMIND_ARTIFACTS` | 产物目录绝对路径，默认 `<workspace>/artifacts`；已创建，可写 |
| 工作目录 | `<workspace>/program`（程序包解压根目录） |
| `$DEVMIND_RUN_ID` / `$DEVMIND_CASE_KEY` | 便于日志定位的只读环境变量 |

平台只注入上述路径与调用方声明的参数/环境变量；secret 不进 input.json，只以环境变量传入并在证据中掩码。

### C.3 输入 JSON v1

```json
{
  "contractVersion": "1",
  "runId": "123",
  "caseId": "45",
  "caseKey": "MSG-SYNC-001",
  "projectId": "P-1",
  "environment": {
    "id": "7",
    "name": "TEST-1",
    "baseUrl": "http://10.0.0.8:8080",
    "productVersion": "1.2.0"
  },
  "parameters": {
    "brokerUrl": "tcp://10.0.0.9:61616",
    "topic": "sync-smoke"
  },
  "timeoutSec": 120,
  "workspace": "/opt/devmind/workspace/run-123"
}
```

- `parameters` 合并优先级：运行覆盖 > 用例 `executor.env` > 环境变量；只合并调用方允许暴露的键。
- 输入文件写入后只读，单文件上限 1MB；生成失败运行 `BLOCKED`。

### C.4 输出 JSON v1 与退出码

```json
{
  "contractVersion": "1",
  "status": "fail",
  "message": "同步发送 1500ms 未收到 ACK",
  "assertions": [
    {
      "name": "sendAck",
      "source": "CUSTOM",
      "op": "equals",
      "expected": "ACK",
      "actual": "TIMEOUT",
      "pass": false,
      "message": "等待 ACK 超时"
    }
  ],
  "metrics": {
    "sendLatencyMs": 1500,
    "messageCount": 1
  },
  "artifacts": [
    { "name": "broker.log", "path": "logs/broker.log", "description": "broker 侧日志" }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| contractVersion | 是 | 固定 `"1"`，不符按 SCRIPT_ERROR |
| status | 是 | pass / fail / blocked / skip / error |
| message | 否 | ≤ 4KB，超长截断并标注 |
| assertions | 否 | `source` 固定 `CUSTOM`；`pass=false` 任一存在时最终判 FAIL |
| metrics | 否 | 仅数值（long/double），仅展示与统计，不参与判定 |
| artifacts | 否 | `path` 必须是 `$DEVMIND_ARTIFACTS` 下相对路径；禁止绝对路径、`..`、符号链接 |

退出码兜底：

| 退出码 | 含义 | 最终判定 |
|---|---|---|
| 0 | 正常结束 | 以 result.json 的 status 为准；result 缺失/畸形 → error / SCRIPT_ERROR |
| 1 | 用例失败 | FAIL / ASSERTION（无结构化断言时补一条 exitCode 断言） |
| 2 | 前置不满足 | BLOCKED / PRECHECK |
| 3 | 程序错误 | ERROR / SCRIPT_ERROR |
| 其他非 0 | 未知错误 | ERROR / UNKNOWN |
| 超时被 kill | 平台终止 | ERROR / TIMEOUT |

规则：**非 0 退出码优先于 result.json 的 status**；退出码 0 且 status=pass 但存在 `pass=false` 断言时判 FAIL，不盲目信任程序自报状态。result.json 上限 1MB；artifacts 默认最多 20 个、单文件 10MB、单次运行 50MB（以证据配置为准）。

### C.5 证据与追溯

每次程序运行追加以下证据（sha256 + prev_hash 链）：

- `PROGRAM_MANIFEST`：清单原文；
- `INPUT_JSON`：实际输入（已脱敏）；
- `RESULT_JSON`：程序输出原文；
- `STDOUT` / `STDERR`：完整捕获（超限截断并注明）；
- `ARTIFACT`：每个产物一条，记录相对路径、大小、sha256。

`test_case_results` 同时记录 `program_asset_id`、`program_contract_version`、`program_manifest_json`，运行详情/报告可追溯到程序资产 sha256、runtime、targetOs/targetArch、entrypoint。

### C.6 入口脚本示例（Java）

```bash
#!/usr/bin/env bash
set -uo pipefail

java -cp "lib/sync-msg-test.jar:lib/deps/*" com.example.SyncSendTest \
  --input "$DEVMIND_INPUT" \
  --result "$DEVMIND_RESULT" \
  --artifacts "$DEVMIND_ARTIFACTS"
exit $?
```

程序自行读取 input.json、写出 result.json；平台不解析 Java 异常栈，只按退出码与 result.json 判定。同步发送场景的失败细节通过 assertions 与 artifacts（broker 日志、发送记录）表达。
