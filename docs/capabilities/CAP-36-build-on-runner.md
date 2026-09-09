# CAP-36 构建执行 Runner 化（exec 帧落地 + SSH 下线）

> 能力 ID：CAP-36 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-10

## 1. 目的

把「在其他机器上构建/测试/部署/发版」的统一路径从 **SSH/HTTP 服务器适配器（CAP-07）**
切换为 **agent-runner 节点执行（CAP-34 FR-06 exec 帧）**，解决两个结构性问题：

1. **凭证断裂**：SSH 模式下代码获取外包给目标机预置脚本，平台集成库里的
   GitLab token（CAP-18/35）带不过去；runner 模式下代码获取由平台编排
   （runner 侧 `RunnerWorkspace` clone），token 可随任务下发——**直接复用会话链路
   已验证的凭证下发模式**（服务端 `CloneTokenResolver` 解析 → 帧携带 → runner 内存持有
   不落盘 → 输出 sanitize）。
2. **双执行路径**：服务端零执行（CAP-34）之后，SSH 是唯一残留的「非 runner 远程执行」
   路径，且 SSH/HTTP 两种形态只被构建/测试/部署/发版四处消费。项目处于开发阶段、
   无存量数据，**整体下线 CAP-07 server-adapter 模块**（含 HTTP daemon 形态——
   runner 本身就是那个更安全的轻量 agent：出站连接、无监听端口），
   远程执行只余「服务端 → runner」一条路径。

## 2. 功能需求

- **FR-01 exec 帧协议落地**（CAP-34 FR-06 正名，协议版本 EXEC_FRAMES=2 已预留）：
  ```
  ↓ exec{execId, cwd?, env?, command, timeoutSec, repo?}    command = 服务端渲染后的完整命令串
  ↑ exec_log{execId, stream, chunk}                          stdout/stderr 流式
  ↑ exec_exit{execId, code, timedOut?}                       退出码收口
  ```
  - `command` 为**渲染后的完整命令串**（模板/参数渲染只发生在服务端，runner 不感知模板——
    延续 CAP-34「数据所有权在服务端」）；runner 按平台 shell 执行（Windows `cmd /c`、
    其余 `sh -c`）。
  - `repo` 块可选：`{remoteUrl, branch, commit, token}`，语义照搬 launch 帧 `RepoSpec`；
    存在时 runner 先用 `RunnerWorkspace` 同款 clone 缓存准备构建工作区（checkout 到 commit），
    `cwd` 缺省 = 该工作区。token 红线沿用：仅内存、clone 后清 origin URL、日志 sanitize。
  - 命令双层校验：服务端只下发项目预定义构建步骤（非自由命令）；runner 配置
    `execAllowlist`（命令前缀 CSV，如 `mvn,./mvnw,npm,git`）二次过滤，空 = 拒绝一切 exec。
- **FR-02 服务端下发通道**：`AgentConnectionRegistry` 增加 exec 下发与结果归集
  （`supports(nodeId, 2)` 门控，旧 runner 提示升级；节点离线 409 不静默）。
  `devmind-execution` 新增 `AgentNodeStepRunner`（与 LocalStepRunner 并列的 StepRunner 语义），
  exec_log 帧回流 `ExecutionLogHub`，构建日志页零改动。
- **FR-03 构建接入（CAP-08 改造）**：`executor` 取值 `LOCAL | AGENT`（REMOTE 废除）；
  AGENT 路由链与会话一致：显式 `agentNodeId` > 项目默认节点 > 平台默认节点 >
  `requiredLabels` 在线匹配 > 皆无命中 409「无可用执行节点」。
  触发时 `CloneTokenResolver` 按项目仓库集成实例解析 token 随帧下发
  （host 一致性/ENABLED 校验现成）；commit/branch 解析沿用 CAP-26（触发期 fetch +
  origin/ 引用为基准，服务端 clone 缓存仍用于解析 commit，不在服务端跑构建步骤）。
- **FR-04 测试/部署/发版迁移（CAP-09/10/11）**：三模块对 `ServerOperationService`
  的依赖全部替换为 `AgentNodeStepRunner`；目标机语义 = 「装 runner 的机器」，
  经节点标签选择（如 `deploy:test` / `deploy:prod`）。
- **FR-05 server-adapter 模块下线**：删除 `devmind-server-adapter` Maven 模块
  （servers/script_templates 表随模块删除不再建）、`RemoteStepRunner`、
  前端服务器管理页与路由、相关文档标注。构建/测试/部署/发版配置中
  `remote_server_id` 字段语义迁移为 `agent_node_id`。
- **FR-06 节点能力建设**：runner `agent.properties` 新增 `execAllowlist`；
  工具链探测（CAP-34 FR-07 已落地）作为构建机选择依据，节点页展示不变。

## 3. 插件化接口

- `AgentNodeStepRunner`（devmind-execution）：`runStep(nodeId, StepSpec, env, sink) → StepResult`，
  内部经 exec 下发通道完成「下发 → 日志回流 → 退出码收口」的一次往返。
- exec 帧模型定义在 `devmind-common`（`agent.exec` 包，瘦 jar 可打进 runner）；
  runner 侧 `ExecHandler` 归 runner 宿主独享，服务端不引用执行实现。

## 4. 依赖关系

- 依赖：CAP-21（节点接入/WS 协议）、CAP-34（FR-06 预留协议/门控、FR-07 标签调度）、
  CAP-25（RunnerWorkspace clone 缓存/token 红线）、CAP-12（StepRunner/ExecutionLogHub）、
  CAP-18/35（CloneTokenResolver 凭证解析）。
- 被依赖：CAP-08/09/10/11（执行器获得节点执行路径）；CAP-07 被本能力取代下线。

## 5. 数据模型

```
build_configs / deploy / test / release 配置：remote_server_id → agent_node_id（+ required_labels）
agent_nodes：沿用 labels/toolchain/protocol_version（无新列）
servers / script_templates 表：随模块删除不再建（无存量数据，直接 DROP 语义）
```

## 6. API 概要

```
构建/部署/测试/发版触发请求：executor=AGENT, agentNodeId?, requiredLabels?（取代 remoteServerId）
WS /ws/agent：帧扩展 exec/exec_log/exec_exit（protocolVersion=2 门控）
runner agent.properties：+ execAllowlist=mvn,./mvnw,npm,...（空=禁用 exec）
```

## 7. 验收标准

- 项目仓库绑定 GitLab 集成、构建 executor=AGENT：构建在选定 runner 节点执行，
  工作区由 runner clone（token 随帧下发，节点机无需预置任何凭证），
  日志实时回流前端构建页，退出码正确收口；
- runner `execAllowlist` 外的命令被拒并在构建日志留痕；
- 无可调度节点（离线/标签不符/协议版本不足）触发即 409，不产生挂死构建；
- 测试/部署/发版经节点标签执行成功；server-adapter 模块、服务器管理页、
  REMOTE executor 全部移除后 `mvn -q test` 与 `npx tsc -b` 通过；
- 旧 runner（protocolVersion=1）连上新服务端：exec 下发被门控拒绝并提示升级，
  会话功能不受影响。

## 8. MVP 范围（暂不做）

制品从 runner 回传服务端集中存储（artifactRef 仍登记日志识别串，制品留节点工作区）；
exec 帧交互式 stdin；构建工作区按 commit 复用优化（先每构建独立 checkout，复用 clone 缓存）；
部署的滚动/批量编排。
