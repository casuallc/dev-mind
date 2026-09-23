# CAP-59 远程终端增强（持久 shell / Tab 补全 / 命令取消）

> 能力 ID：CAP-59 ｜ 分类：底座 ｜ 状态：**需求定稿** ｜ 日期：2026-09-23
> 依赖 CAP-58（远程终端单条命令执行）。在不引入交互式 PTY（方向 C，另立项）的前提下，
> 把终端体验补到「日常探查够用」：环境跨命令保持、Tab 补全、Ctrl+C 可取消。

## 1. 目的

CAP-58 的终端是「每条命令一个独立进程」：`cd` 靠前端持有 cwd 随命令重放，
`export`/别名/函数完全不保持（下条命令全丢），没有补全（长路径全手敲），
执行中的命令只能干等超时。距 Xshell 类终端的日常手感差的就是这三件事。

本能力在**安全模型零变化**（白名单逐条校验、cwd 限定、无 PTY 不交互）的前提下增强：

1. **持久 shell**：每会话一个长驻 shell 进程，环境变量/别名/`cd` 跨命令真正保持；
2. **Tab 补全**：命令名/文件路径候选补全（含候选弹层）；
3. **命令取消**：Ctrl+C 整树终止执行中的命令，不必等超时。

## 2. 功能需求

### FR-01 持久 shell（runner 侧，terminal_exec 帧不变）

- 每会话（sessionId）懒起一个长驻 `execShell` 进程（Windows = MSYS/Git Bash，
  与 CAP-36/58 现状同一解释器，无新依赖），初始目录 = 会话代码目录（帧 cwd 非空时
  `cd` 到其限定解析结果）。命令经 stdin 写入，尾部追加哨兵行（DONE:exitCode + CWD marker），
  读到哨兵即本条收口——**非交互管道模型，不是 PTY**，vim/top 等交互程序仍不支持。
- **状态保持**：`export`/别名/函数/`cd` 在同一 shell 内跨命令保持。
  **cwd 权威移到 runner 侧 shell**：ack 仍回传新 cwd，前端跟随；帧 cwd 仅在 shell
  首启时作初始目录（页面刷新后 shell 状态不丢，下条命令的 ack 会让前端重新对齐）。
- **优雅降级**：shell 意外死亡（命令含 `exit`、被杀、崩溃）→ 本条按已收集输出收口，
  下条命令自动以帧 cwd 重启新 shell（env 丢失，cwd 由帧带回重 cd）。
- **资源管理**：同会话命令串行（单 stdin，per-session 锁）；空闲超
  `terminalShellIdleMin`（agent.properties，默认 30 分钟）自动回收；runner 关闭
  killAll 一并整树杀。
- **降级链不变**：老 runner（v16）行为维持 CAP-58 单条进程模型（无状态保持），
  协议帧未变，新旧任意组合可用。

### FR-02 安全边界（沿用 CAP-58，一处加严）

- 白名单逐条校验、重定向默认拒绝、git 子命令白名单：**逐字不变**，进 shell 前检查。
- `cd` 越界检测（CWD marker 出界/符号链接逃逸）→ ack ok=false 且 **shell 立即被
  强制 `cd` 回代码目录**（持久 shell 里 `cd /` 已真实发生，必须拉回，不能像
  单条进程模型那样「cwd 维持原值」就当没发生）。
- 补全只在会话当前 cwd 下取候选（`resolveConfined` 同一限定口径），候选内容不越界。

### FR-03 Tab 补全（terminal_complete 帧 + REST + 前端弹层）

- 下行 `terminal_complete {requestId, sessionId, input, cwd?}`，上行
  `terminal_complete_ack {requestId, ok, word, candidates[], error?}`。
- runner 实现：**一次性 `execShell` 子进程**在会话当前 cwd 下跑 `compgen`
  （首 token 补命令 `compgen -c`；`cd` 只补目录 `compgen -d`；其余补文件+目录
  `compgen -f`，目录候选带 `/` 后缀区分）——刻意不走持久 shell：补全高频低耗，
  独立进程不与执行中的长命令争 stdin（命令跑着也能补全）。代价：感知不到 shell
  内 export 的 PATH/别名，记录在案。候选去重排序，上限 100 条，5s 超时。
- 前端：输入框 Tab 触发 → 唯一候选直接补全；多候选先补公共前缀并弹候选浮层
  （↑↓ 移动、Tab/Enter 选中、Esc 关闭、点击选中）；runner 版本不足（409）回落
  本地历史命令补全并提示一次。

### FR-04 命令取消（terminal_cancel 帧 + REST + Ctrl+C）

- 下行 `terminal_cancel {sessionId}`（fire-and-forget，无独立 ack）：runner 对该会话
  执行中的命令 **killTree 整个 shell**（无 PTY 无法向前台子进程递 SIGINT，
  Windows 下尤其没有可靠手段；杀 shell 是唯一能保证命令树死透的姿势，
  `ProcessHelper.killTree` 的 taskkill /T 已支撑）→ 进行中的 terminal_exec 以
  `cancelled=true`、exitCode=130（SIGINT 惯例）收口 ack → shell 标记死亡，
  下条命令自动重启（FR-01 降级路径）。无进行中命令 = no-op。
- `terminal_exec_ack` 增可选字段 `cancelled`（老服务端忽略、老 runner 不发，双向不门控）。
- 前端：执行中按 Ctrl+C → POST cancel；条目渲染「已取消」。
- REST：`POST /api/sessions/{id}/terminal/cancel`、`POST /api/chats/{id}/terminal/cancel`
  （空 body，204/200 均无内容语义）。

### FR-05 协议与兼容

- `AgentProtocol.CURRENT = 17`，新增常量 `TERMINAL_SHELL = 17`：
  `terminal_complete`/`terminal_cancel` 属「必须认识」（老 runner 静默忽略会让
  REST 空等/操作无效），服务端 supports() 门控，不足 409 引导升级。
- `terminal_exec` 帧**不变**仍按 v16 门控：持久化是新 runner 的实现细节，
  老 runner 退化为 CAP-58 语义（FR-01 降级链）。
- 会话/问答两链路对称接入（session/chat 各两个新 REST 端点，共用 runner 实现）。

### FR-06 前端终端面板

- Tab = 补全（FR-03）；Ctrl+C：执行中=取消（FR-04），空闲=清当前输入行；
  Ctrl+L 清屏（清条目列表，不动 shell）。
- 既有行为保持：Enter 提交、↑↓ 翻历史、执行完自动聚焦输入框、条目 = 提示符行 +
  stdout + stderr 红 + 非零 exit 标红 + 已取消标记。

## 3. 关键设计

- **为什么持久 shell 而不是直接上 PTY**：PTY（xterm.js + 双向流式帧 + resize +
  安全模型重做）改动面大一个数量级，且交互式 shell 等于任意命令、现有白名单失效。
  持久 shell 用「stdin 写命令 + 哨兵 marker 定界」在非交互管道上拿到核心收益
  （env 保持 + cwd 权威 + 可取消），白名单/审计模型零变化——探查类诉求不需要 PTY。
- **为什么 cwd 权威移到 runner**：CAP-58 前端持有 cwd 是「每命令独立进程」的补偿；
  持久 shell 后 shell 本身就是状态源，前端跟随 ack 即可。帧 cwd 保留仅为 shell
  首启定初始目录（兼容旧前端逐条带 cwd 的契约，新旧前端都能用）。
- **为什么补全走一次性子进程**：见 FR-03。与持久 shell 争 stdin 会把「补全」排在
  长命令后面（Xshell 里 `tail -f` 跑着时 Tab 照样能用），独立进程无此问题。
- **为什么取消=杀整个 shell**：无 PTY 时前台进程组概念不存在，Windows 上更没有
  可靠的控制台 Ctrl 事件注入手段；killTree(shell) 唯一可靠。代价是 env 状态随
  shell 丢失，由 FR-01 的自动重启兜底，用户可感知可接受（等价 Xshell 里连接断了重连）。

## 4. 插件化接口

- common：`AgentProtocol.TERMINAL_SHELL`（v17）；`AgentNodeConnector.terminalComplete(...)`/
  `terminalCancel(...)` default 方法；`TerminalCompleteResult` DTO；
  `TerminalExecResult` 增 `cancelled` 字段。
- runner：`PersistentShell`（长驻进程 + 哨兵定界 + 空闲回收）；`TerminalHandler`
  改造为 shell 池（per-session 串行锁 + 越界 cd 拉回 + cancel/complete 帧处理）；
  `RunnerConfig` 增 `terminalShellIdleMin`（默认 30）。
- 服务端：`AgentConnectionRegistry.terminalComplete(...)`（waiter 模式，15s 上限）/
  `terminalCancel(...)`（fire-and-forget，v17 门控）；`AgentNodeWsHandler` 加
  `terminal_complete_ack` case；session/chat 各加 complete/cancel REST 端点。

## 5. API 概要

| 端点/帧 | 方向 | 说明 |
|---|---|---|
| `terminal_complete` / `terminal_complete_ack` | 双向 | Tab 补全请求应答（协议 v17 门控） |
| `terminal_cancel` | 下行 | 取消执行中命令（fire-and-forget，v17 门控） |
| `terminal_exec_ack.cancelled` | 上行可选字段 | 命令被取消收口（exitCode=130），双向不门控 |
| `POST /api/sessions/{id}/terminal/complete` | REST | 补全，body `{input, cwd?}` → `{word, candidates[]}` |
| `POST /api/sessions/{id}/terminal/cancel` | REST | 取消执行中命令 |
| `POST /api/chats/{id}/terminal/complete` | REST | 问答沙箱对称端点 |
| `POST /api/chats/{id}/terminal/cancel` | REST | 问答沙箱对称端点 |

## 6. 验收标准

1. `export FOO=bar` 后 `echo $FOO` 输出 bar；`cd frontend`（单发一条）后 `pwd` 仍在
   frontend；`alias`/`function` 定义后可用；
2. 命令含 `exit`（或 shell 被杀）后，下条命令自动重启 shell 且 cwd 取帧 cwd 重 cd；
3. `cd /` / `cd ../../..`：ack 拒绝，且**再发 `pwd` 确认 shell 已被拉回代码目录内**；
4. 输入 `cd fro` + Tab → 补全出 `frontend/`；首 token `gi` + Tab → 候选含 git；
   多候选先补公共前缀并出弹层可选中；长命令执行中 Tab 补全仍即时响应；
5. `sleep 30`（白名单放行节点）执行中 Ctrl+C → 秒级收口「已取消」exit 130，
   随后命令正常（shell 自动重启）；
6. 老 runner（v16）：exec 正常（无状态保持），complete/cancel 端点 409 引导升级；
   新 runner + 老服务端：complete_ack 被忽略无异常；
7. 空闲 30 分钟（可配）后 shell 自动回收，下条命令重新拉起；
8. 白名单/重定向/git 子命令口径与 CAP-58 逐字一致（回归 TerminalHandlerAllowlistTest）；
9. 问答沙箱会话两新端点对称可用；MODEL 执行体仍 409。

## 7. 落地状态

- **M1 —— 需求定稿**：本文档。
