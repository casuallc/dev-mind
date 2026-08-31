# CAP-12 统一执行底座（Execution Platform）

> 能力 ID：CAP-12 ｜ 分类：底座 ｜ 状态：已落地（build 已迁移） ｜ 日期：2026-08-31

## 1. 目的

消除执行器的纵向烟囱：CAP-08/09/10 曾各自复制「状态机 + Runner + WS 日志枢纽」一整套。
本能力抽出共用执行层（模块 `devmind-execution`），各执行器只保留业务配置与结果语义
（构建配置、部署计划、测试套件）。**CAP-11 及后续执行器必须基于本底座，不再新起拷贝。**

## 2. 功能需求

- **FR-01 步骤模型**：`StepSpec{name, command, workingDir, location}`，触发时固化为 JSON 快照
  （字段名稳定，兼容历史快照）；本地 command 为 shell 脚本，远程 command 为 CAP-07 脚本模板 code。
- **FR-02 步骤链引擎**：`StepChainRunner` 顺序执行步骤快照，追加步骤边界日志，任一步失败即中断，
  聚合 `ChainResult{ok, exitCode, error, failedIndex}`；每步如何执行由调用方 `StepInvoker` 决定。
- **FR-03 本地 Runner**：`LocalStepRunner` 在指定目录执行 shell（配置 `devmind.execution.shell`），
  环境变量由业务方注入（如构建注入 `BUILD_PROJECT_ID/BUILD_COMMIT/BUILD_BRANCH/BUILD_STEP`），
  stdout/stderr 双流实时回传，单步超时 kill（`devmind.execution.step-timeout-ms`）。
- **FR-04 远程 Runner**：`RemoteStepRunner` 委托 CAP-07 `ServerOperationService`，
  模板参数与 capability 域（build/deploy/test/…）由业务方传入。
- **FR-05 统一日志枢纽**：`ExecutionLogHub` 按 topic 分组 WS 会话，帧协议统一：
  `log`（日志行）/ 业务事件帧 `{"type":<t>,<t>:…}`（字段名=事件类型，前端按 `f[f.type]` 取）/ `done`（终态）。
  `ExecutionWsHandler` 通用处理器：连接先推 `snapshot`（历史日志 + `extra` 业务快照字段合并入帧），
  终态立即补 `done`；由各业务 `WebSocketConfigurer` 按路径前缀注册 + 提供 `ExecutionSnapshotProvider` 快照查询。

## 3. 插件化接口

- `StepChainRunner.StepInvoker`：单步执行策略（本地/远程/自定义），按步骤 location 或业务上下文选择。
- `ExecutionSnapshotProvider`：`lookup(topic) → ExecutionSnapshot{logsText, status, terminal, extra}`，
  业务模块从各自 Repository 提供（如 build 查 BuildRepository；deploy/test 以 `extra` 捎带
  步骤列表/用例结果等业务快照字段，合并进 snapshot 帧）。

## 4. 依赖关系

- 依赖：CAP-07（远程执行）。
- 被依赖：CAP-08 构建（已迁移）、CAP-09 部署 / CAP-10 测试（已迁移，帧协议不变前端零改动）、
  CAP-11 发版（直接使用）。

## 5. 迁移约定

- 业务模块保留自己的执行记录实体（BuildEntity/DeploymentEntity/TestRunEntity）与状态机语义；
  底座不引入统一 Job 表，避免过度设计。
- WS 路径不变（如 `/ws/builds/**`），仅实现替换为通用 Handler；topic = 业务记录 id 字符串。
- 配置前缀 `devmind.build.shell/step-timeout-ms` 已迁移至 `devmind.execution.*`（application.yml 同步）。
