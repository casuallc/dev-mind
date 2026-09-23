# CAP-58 会话工作区远程终端（单条命令执行）

> 能力 ID：CAP-58 ｜ 分类：底座 ｜ 状态：**需求定稿** ｜ 日期：2026-09-23
> 依赖 CAP-21/30/34/42/51/53/54。只读优先：默认白名单只允许不改动文件/仓库状态的
> 探查类命令；runner 侧强制白名单，写操作需节点管理员显式放开。

## 1. 目的

CAP-34 之后一切执行收敛到 runner 节点，会话工作区（代码目录）对页面是黑盒。
CAP-54 补了「看」的盲区（git 变更推送 + 文件树/内容/diff 拉取），但覆盖不了
**临时探查**场景——想确认某个目录结构、grep 一段配置、看一眼 git log，都要么
预置成文件树逐级点开，要么等 agent 自己提。

本能力给会话/问答页的工作区面板加**第三个 Tab「终端」**：输入单条 shell 命令
（ls/cd/cat/git 等）→ 服务端经 runner WS 下发 → runner 在会话代码目录执行 →
stdout/stderr/exit code 回显。**单条命令执行**（非交互式 PTY）：每条命令一个
独立进程，`cd` 状态由前端持有 cwd 随命令下发，runner 执行后回传新 cwd。

## 2. 功能需求

### FR-01 单条命令下发与回显

- 前端终端面板输入一行命令（支持 `|`、`&&`、`;` 组合，如 `cat pom.xml | head -5`），
  REST 提交服务端 → 服务端经节点连接下发 `terminal_exec` 帧 → runner 在**会话代码目录**
  （CAP-53 口径，同 CAP-54 采集基准）执行 → `terminal_exec_ack` 回传
  `{exitCode, stdout, stderr, cwd}` → 面板追加渲染（提示符行 + 输出 + 非零 exit code 标红）。
- **cwd 状态在前端**：面板组件持有相对代码目录的 cwd（初始空=根），随每条命令下发；
  ack 带回命令执行后的新 cwd（`cd frontend && ls`、`cd ..` 天然生效），前端更新提示符。
  服务端**不存 cwd 状态**（纯透传）；刷新页面 cwd 回落代码目录根，可接受。
- **超时**：runner 侧单命令上限 `terminalTimeoutSec`（默认 60s），超时整树杀并回
  `timedOut`；服务端等 ack 上限 90s（命令超时 + 余量），超时按失败返回。
- **输出上限**：stdout/stderr 各 cap 128KB（保留尾部，标注截断）；WS 文本帧缓冲
  512KB（CAP-54 已调）。

### FR-02 runner 侧白名单（可配置，缺省只读档）

一切命令过 runner 本地白名单（双层防线的 runner 层，语义同 CAP-36 execAllowlist）：

- 配置项 `terminalAllowlist`（agent.properties，CSV 前缀匹配）：
  **缺省（未配置）= 内置只读档**——
  `ls, dir, pwd, cat, type, head, tail, find, grep, echo, wc, du, df, tree, which, where, git`；
  显式配置则全覆盖（节点管理员自行负责）。
- 校验方式：命令按 `|`、`||`、`&&`、`;`、换行**切段**，逐段取首 token 前缀命中白名单；
  shell 内置命令/关键字豁免（复用 ExecHandler.SHELL_BUILTINS）。
- **git 特例**：首 token 为 git 时校验子命令 ∈ 只读集（status/diff/log/show/branch/
  remote/rev-parse/ls-files/blame/grep/tag/describe/shortlog/reflog/config）；
  白名单条目 `git:*` 放行全部子命令。
- 默认档拒绝 `>`/`>>` 重定向写入（`terminalAllowRedirect=true` 放开）。

### FR-03 安全边界（runner 侧强制）

- 会话定位走 `RunnerSessionRegistry.knownDirOf`（运行中优先，终态会话走 recentDirs——
  同 CAP-54，收口保留工作树仍可探查）；定位不到 → ack ok=false。
- cwd 一律相对代码目录解析，`resolve+normalize+startsWith` 防 `..` 逃逸，
  再 `toRealPath()` 防符号链接逃逸（复用 WorkspaceQueryHandler.resolveConfined 口径）；
  命令内 `cd` 到代码目录外（`cd /`、`cd ../../..`）→ ack ok=false，cwd 维持原值。
- 输出过既有 `sanitize` 脱敏约定后才上行；token 红线不变（终端命令不带任何凭据）。

### FR-04 协议与兼容

- `AgentProtocol.CURRENT = 16`，新增常量 `TERMINAL_EXEC = 16`；
  下行 `terminal_exec {requestId, sessionId, command, cwd?}`，
  上行 `terminal_exec_ack {requestId, ok, exitCode?, stdout?, stderr?, cwd?, error?, timedOut?}`。
- **老 runner（<v16）+ 新服务端**：不认识 terminal_exec 帧会静默忽略 → REST 端点先
  `supports(nodeId, TERMINAL_EXEC)` 门控，不足直接 409 引导升级（不等超时）；
- **新 runner + 老服务端**：上行 ack 帧被老服务端忽略（未知 type 丢弃），不门控；
- 会话/问答两链路对称接入（同一 runner 实现、session/chat 两个 REST 端点）。

### FR-05 前端终端面板

- `WorkspacePanel`（sessions 与 chats 共用）加第三个 Tab「终端」；终端 tab 激活时
  面板宽 360 → 560。
- 面板 = 深色等宽输出区（配色沿用 LogView 风格）+ 底部输入框：
  - 条目 = 提示符行（`[cwd] $ command`）+ stdout + stderr（红色）+ exitCode≠0 红色标记；
  - Enter 提交、↑/↓ 翻历史（内存态）、执行中禁用输入；
  - 错误（409 版本过低 / 410 工作区已释放 / 超时）渲染为红色输出行，话术透传服务端；
  - MODEL 执行体（CAP-49，无 runner）整个工作区面板隐藏（既有门控），无额外处理。

## 3. 关键设计

- **为什么单条命令而非 PTY**：交互式 PTY（xterm.js + 常驻 shell + 双向流式帧 + resize）
  协议与前后端改动面大一个数量级，而探查类诉求 90% 是「跑一条看输出」；单条执行
  可直接照抄 CAP-54 workspace_query 的请求/应答帧模式与 CAP-36 exec 的白名单/执行姿势，
  且每条命令独立成审计单元。`cd` 状态外置到前端（cwd 随命令走）即覆盖目录漫游场景。
- **为什么白名单放 runner 侧而非服务端**：与 CAP-36 同一理由——服务端下发的命令
  最终在节点本地执行，白名单是节点管理员对本机的最后防线，必须 runner 本地强制
  （服务端被绕过/误配也不失守）。缺省只读档让新节点开箱即用且安全。
- **为什么 cwd 不落服务端**：cwd 是「这个浏览器面板当前逛到哪」的 UI 状态，无共享
  与追溯价值；前端持有 + runner 强制限定，服务端零状态，重启/多端打开互不干扰。

## 4. 插件化接口

- common：`AgentProtocol.TERMINAL_EXEC`（v16）；`AgentNodeConnector.terminalExec(...)`
  default 方法；`TerminalExecResult` DTO。
- runner：`TerminalHandler`（白名单校验 + cwd 限定 + 临时 .sh 执行 + marker 取新 cwd），
  `RunnerConfig` 增 `terminalAllowlist`/`terminalTimeoutSec`/`terminalAllowRedirect`。
- 服务端：`AgentConnectionRegistry.terminalExec(...)`（门控 + waiter + 断连清理），
  `AgentNodeWsHandler` 加 `terminal_exec_ack` case；session/chat 各加 REST 端点。

## 5. API 概要

| 端点/帧 | 方向 | 说明 |
|---|---|---|
| `terminal_exec` / `terminal_exec_ack` | 双向 | 单条命令请求应答（协议 v16 门控） |
| `POST /api/sessions/{id}/terminal/exec` | REST | 会话终端执行，body `{command, cwd?}`，返回 `{exitCode, stdout, stderr, cwd}` |
| `POST /api/chats/{id}/terminal/exec` | REST | 问答沙箱对称端点 |

## 6. 验收标准

1. 会话页终端 tab 跑 `pwd`/`ls`/`cat pom.xml | head -5`/`git status`：输出正确回显，
   exitCode≠0 标红；`cd frontend && ls` 后提示符 cwd 变为 `frontend`，后续命令在其下执行；
2. `cd ../../..` / `cd /` / 符号链接逃逸：拒绝且 cwd 不变；`../` 路径参数一律拒绝；
3. 默认白名单外命令（如 `rm`、`sed`）、`>` 重定向、`git commit`：拒绝并提示白名单命中情况；
   节点配置 `terminalAllowlist` 显式放开后可用；
4. 老 runner（<v16）：REST 直接 409「runner 版本过低请到节点页升级」（非超时）；
   老服务端 + 新 runner 无异常；
5. 命令超 60s：整树杀并回 timedOut；输出超 128KB 截尾标注；
6. 问答沙箱会话：终端可用（非 git 目录 git 命令报 exitCode≠0 即可）；MODEL 执行体无面板；
7. 终态会话（工作树未释放）：终端仍可探查（recentDirs 定位）。

## 7. 落地状态

- **M1 —— 需求定稿**：本文档。
- **M2 —— 全链路实现（2026-09-23）**：协议 v16 + `terminal_exec`/`terminal_exec_ack` 帧 +
  runner `TerminalHandler`（可配置白名单缺省只读档 + cwd 限定 + marker 取新 cwd）+
  服务端 registry/SPI/REST（session/chat 对称）+ 前端工作区面板终端 tab。
  单测：runner 白名单纯函数面、agent 帧链路面（组帧/ack 路由/v16 门控/断连清理）。
  E2E：`tests/cap58_e2e.py`（fake runner + 独立实例）已实跑通过。
