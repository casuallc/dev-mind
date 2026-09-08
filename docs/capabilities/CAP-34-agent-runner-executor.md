# CAP-34 Agent Runner 执行代理化（统一执行内核）

> 能力 ID：CAP-34 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-08

## 1. 目的

runner 从「claude 进程代理」升级为「**本地执行代理**」：远端服务端只负责任务分发、
结果回收与调度管理，**一切执行逻辑收敛到 runner 层**——包括 claude 会话拉起、
工作区编排、上下文物化，以及节点本地工具链（mvn/node/docker 等）的直接利用。

核心架构决策（已定）：**本机会话同样经 runner 层执行**。执行内核上移至
`devmind-common`，服务端以「内嵌 runner」（同进程直调内核，不经 WS）跑本机会话，
远程节点跑同一内核的瘦 jar——本地/远程只有「传输层不同」，执行行为天然一致，
从根上消除 CAP-21 时代「本机注入知识、远程不注入」这类行为分叉。

```
浏览器 ⇄WS⇄ devmind-app（分发/调度/回收）─┬─ 内嵌 runner（common 内核，本机）
                                          └─ ⇄WS⇄ agent-runner 节点（同一内核，瘦 jar）
```

数据与执行的接缝（已定）：**数据所有权在服务端**（knowledge/docs/skills 均在服务端 DB），
**执行所有权在 runner**。服务端把装配结果打包为 ContextPackage 交给 runner 物化，
runner 不反向查服务端业务库。

## 2. 功能需求

- **FR-01 执行内核上移 common**：新增 `devmind-common` 的 `agent.exec` 包（无 Spring 依赖，
  瘦 jar 可打进）：工作区管理（现 `RunnerWorkspace` 逻辑上移：clone 缓存/会话 worktree/
  chat 沙箱/结束 push 收口）、上下文物化器（CLAUDE.md 组装、`.claude/skills/<name>/` 落盘、
  `.claude/settings.local.json`，现 `KnowledgeBaseInjector` 的文件操作部分下移至此）、
  进程拉起与事件解析（复用已有 `agent.runtime`）。服务端 `WorkspaceService` 的本地
  worktree 实现同步切换为该内核，单一代码路径。
- **FR-02 内嵌 runner**：服务端本机会话不再直接 `ProcessBuilder` 拉起 claude，改经
  内嵌 runner 走同一内核（准备工作区 → 物化上下文 → 拉起进程 → 收口）。
  `sessions.agent_node_id IS NULL = 本机` 语义不变；本机会话获得与远程完全一致的
  上下文注入与工作区行为。
- **FR-03 上下文包传输（远程）**：launch 帧新增 `contextManifest`（轻量清单：条目数/
  总大小/sha256）；runner 凭节点 token 走 HTTP `GET /api/agent/context/<sessionId>`
  拉取 ContextPackage（skill 含二进制文件，不走 WS 帧；HTTP 拉取已有先例——
  RunnerUpgrader 下载升级包），内核物化到会话工作区后再拉起 claude。
  内嵌 runner 直取装配结果对象，不经 HTTP。包拉取失败 = launch 失败回
  `launched{ok:false}`，不静默降级为无上下文会话。
- **FR-04 会话隔离强化**：进程树整体回收（kill 时杀子孙进程，Windows 经 Job Object /
  `taskkill /T`，Linux 进程组）；runner 重启现场对账（启动扫描 `sessions/` 目录 +
  hello 上报全量会话清单，孤儿 claude 进程回收、无主目录登记待清）；同 projectId
  clone 缓存的 fetch/worktree 操作互斥锁（并发会话同库不再踩同一缓存）。
- **FR-05 工作区 GC**：会话目录生命周期治理——结束收口（已有）之外，新增超龄目录清理
  （N 天未活动且会话分支已 push 才删，配置项 `gcDays`，默认 14）与磁盘占用上报
  （hello 带 `workspaceBytes`，服务端节点页展示）。
- **FR-06 本地执行能力（exec 帧）**：runner 接受命令执行指令（如 mvn/npm 构建测试步骤），
  stdout/stderr 流式回传日志帧，退出码收口。命令双层校验：服务端按白名单模板渲染
  （沿用 CAP-07 命令模板思路）+ runner 配置 `execAllowlist` 二次过滤。CAP-12
  StepRunner 新增 AgentNodeRunner 实现，build/test 执行器获得「在 agent 节点本地执行」
  选项（节点即构建机，不再只能 SSH 到目标服务器）。
- **FR-07 工具链探测与标签调度**：hello 上报工具链清单（java/mvn/node/docker 版本、
  os/arch）+ 配置 labels；`agent_nodes` 存 capabilities/labels/toolchain，会话与
  exec 调度按标签匹配（如构建任务只派给带 `mvn` 的节点），替代现状盲选。
- **FR-08 协议版本协商**：hello 带 `protocolVersion`，服务端按 runner 版本决定下发
  字段集（延续 CAP-21/25/30/31 的优雅降级惯例：旧 runner 忽略未知字段，新字段恒可选）。

## 3. 插件化接口

- `AgentExecutionKernel`（common）：`prepareWorkspace / materializeContext / launch / finish`，
  内嵌 runner 与远程 runner 共用同一实现，差别只在指令来源（直调 vs WS 帧）。
- `StepRunner` SPI（CAP-12）新增 `AgentNodeStepRunner`：经节点连接下发 exec 帧，
  日志帧回流 ExecutionLogHub，与 LocalStepRunner/SshStepRunner 并列可选。

## 4. 依赖关系

- 依赖：CAP-21（节点接入/WS 协议/自升级）、CAP-25（runner 托管工作区）、
  CAP-30/31（kind/repos 帧语义）、CAP-12（StepRunner/日志 Hub，FR-06 落点）。
- 被依赖：CAP-33（ContextPackage 的内容模型由 CAP-33 定义，传输与物化机制由本能力提供）；
  CAP-08/10（build/test 经 AgentNodeStepRunner 获得节点本地执行选项）。

## 5. 数据模型

```
agent_nodes  ── + labels (JSON 数组)                # FR-07 调度标签
                + toolchain (JSON)                  # FR-07 工具链探测结果 {java:"21",mvn:"3.9",...}
                + protocol_version                  # FR-08
                + workspace_bytes                   # FR-05 hello 上报
sessions     不变（agent_node_id IS NULL = 内嵌 runner 本机执行，语义沿用）
```

WS 协议扩展（JSON 帧）：

```
↓ launch{..., contextManifest:{entries,totalBytes,sha256}}   # FR-03
↓ exec{execId, sessionId?, command, args, cwd, timeoutSec}   # FR-06
↑ exec_log{execId, stream, chunk} / exec_exit{execId, code}  # FR-06
↑ hello{..., labels, toolchain, protocolVersion, workspaceBytes}  # FR-05/07/08
```

## 6. API 概要

```
GET    /api/agent/context/{sessionId}   ContextPackage 拉取（节点 token 认证；FR-03）
WS     /ws/agent                        帧扩展见上（向下兼容）
```

## 7. 验收标准

- 本机会话改走内嵌 runner 后行为零回归（创建/交互/kill/resume/收口全绿）；
- 远程会话首次获得上下文注入：worktree 内 CLAUDE.md 与 `.claude/skills/` 与本机一致；
- runner 进程被强杀后重启：孤儿 claude 进程被回收，节点页会话状态对账正确；
- 同项目两个并发会话不再因 clone 缓存竞争失败；
- exec 帧：向带 mvn 标签的节点下发构建命令，日志实时回流，退出码正确收口；
- 旧版 runner 连上新服务端：未知字段忽略，会话功能不炸（优雅降级）。

## 8. MVP 范围（暂不做）

容器化隔离（docker 沙箱）、CPU/内存硬限额（cgroup/Job Object 只用于进程树回收）、
跨节点会话迁移、exec 帧交互式命令（stdin 双向）、工具链自动安装。
