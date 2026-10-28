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

### FR-06 会话内本地 git 的凭据注入（localhost git 代理）

**背景澄清**：会话里 agent（Claude Code Bash 工具）执行的 git 命令是 runner 主机上的
**本地进程调用，不经过 runner WS 协议**——FR-01~05 的终端链路只覆盖面板/REST 入口，
管不到 agent 自己跑的 git。而 launch 下发的 repo.token 按凭据红线仅存内存、只注入
runner 自己发起的 git 进程参数，worktree 的 origin 是裸 URL → agent 裸跑
`git fetch/push` 走本机凭据链（runner 服务账户下通常无凭据、非交互弹不了窗）必失败，
只能人工兜底（2026-10-13 req-jybc5fmg 合并冲突卡 fetch 即此因）。

- runner 内置 **localhost git 代理**：`127.0.0.1:gitProxyPort`（默认 18377，仅回环），
  JDK HttpServer 实现，smart-HTTP 透传（`GET /info/refs?service=*` 与
  `POST /git-upload-pack|git-receive-pack`），按 URL 路径首段 sessionKey 查内存中的
  （upstream base, token），注入 `Authorization` 后转发真实 remote，响应流式回写。
  匿名通道会话（token 为空）origin 不重写、代理查不到 key 直接 404，不静默降级。
- **per-worktree origin 重写**：物化工作区时缓存仓库一次性开 `extensions.worktreeConfig`，
  每个会话 worktree 写 `git config --worktree remote.origin.url =
  http://127.0.0.1:<port>/<sessionKey>/<host>/<path>`。收口推送
  （RunnerWorkspace.pushToRemote 显式 withToken URL）与平台自身 git 操作全部照旧，不受影响。
- **sessionKey = 会话级随机串**，随 RepoCtx 生命周期存活：会话释放（token map 清）即
  代理 404。key 只让本机进程能用代理推拉该会话的库，**取不到 PAT 本体**。
- **token 零落盘不变**：代理转发全在内存；agent 能读到的只有 worktree config 里的
  代理 URL（无凭据）。
- v1 不支持 LFS；ssh 远端沿用 buildRepoSpec 既有降级（不支持）。
- 存量 worktree（本能力上线前创建）origin 未重写：token 只随 launch 帧存活，runner
  重启后内存登记簿即空、无从补写；这些工作树在**下次 launch/resume**（prepare 链路）
  时重写接入代理，此前 agent 本地 git 维持「无凭据」旧行为（平台自身操作不受影响）。

### FR-07 终端白名单 git 子命令细粒度

- `terminalAllowlist` 支持 `git:<sub>` 条目（如 `git:fetch`、`git:pull`、`git:merge`），
  命中的子命令与只读集取并集放行；`git:*` 仍全放。面板链路跑 fetch 不再被迫全开。
- 此白名单只管 FR-01 终端链路；agent 本地 git 由 Claude Code 自身权限体系约束，
  不过此白名单。

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
- **为什么本地 git 凭据用 localhost 代理而非 env/config 注入**：agent 的 git 是本地
  进程执行、不过协议，要让本地 git 有凭据只有三条路——进程 env
  （`GIT_CONFIG_*`/`GIT_ASKPASS`）、落盘 config/helper、代理。前两条 agent 都能读
  （env、`git config --get`、helper 脚本），token 会进对话记录与上行日志，触碰
  CAP-34 凭据红线；代理让 token 只存在于 runner 内存与上游连接里，agent
  「可用不可取」。服务端零改动（协议不 bump，纯 runner 内部实现）。
- **为什么 per-worktree origin 重写而非 shared config**：同一缓存克隆的多个会话
  worktree 共享 `.git/config`，而 token 按 actor 解析可能不同；
  `extensions.worktreeConfig` 的 config.worktree 天然按 worktree 隔离。
- **为什么代理端口固定而非随机**：worktree config 里的代理 URL 要比 runner 进程
  长寿（重启后续命），随机端口会让存量工作树的 origin 失效。

## 4. 插件化接口

- common：`AgentProtocol.TERMINAL_EXEC`（v16）；`AgentNodeConnector.terminalExec(...)`
  default 方法；`TerminalExecResult` DTO。
- runner：`TerminalHandler`（白名单校验 + cwd 限定 + 临时 .sh 执行 + marker 取新 cwd），
  `RunnerConfig` 增 `terminalAllowlist`/`terminalTimeoutSec`/`terminalAllowRedirect`；
  FR-06 增 `GitAuthProxy`（回环 smart-HTTP 透传 + Authorization 注入）、
  `RunnerConfig` 增 `gitProxyPort`/`gitProxyEnabled`，`RunnerSessionRegistry` 挂
  sessionKey→（upstream base, token），`RunnerWorkspace.ensureWorktree` 重写
  per-worktree origin；FR-07 为 `TerminalHandler` 白名单解析增 `git:<sub>` 条目。
- 服务端：`AgentConnectionRegistry.terminalExec(...)`（门控 + waiter + 断连清理），
  `AgentNodeWsHandler` 加 `terminal_exec_ack` case；session/chat 各加 REST 端点。

## 5. API 概要

| 端点/帧 | 方向 | 说明 |
|---|---|---|
| `terminal_exec` / `terminal_exec_ack` | 双向 | 单条命令请求应答（协议 v16 门控） |
| `POST /api/sessions/{id}/terminal/exec` | REST | 会话终端执行，body `{command, cwd?}`，返回 `{exitCode, stdout, stderr, cwd}` |
| `POST /api/chats/{id}/terminal/exec` | REST | 问答沙箱对称端点 |
| `GET/POST http://127.0.0.1:18377/<sessionKey>/**` | runner 回环 | FR-06 smart-HTTP 透传 + Authorization 注入，仅 runner 内部使用 |

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
7. 终态会话（工作树未释放）：终端仍可探查（recentDirs 定位）；
8. 会话内 agent 裸跑 `git fetch`/`git ls-remote origin` 成功（代理注入凭据）；
   `git remote -v` 只见代理 URL 无 token；会话释放后再跑代理 404 清晰失败；
   进程 env 与磁盘文件中 grep 不到 PAT；
9. `terminalAllowlist` 配 `git:fetch` 后面板 `git fetch` 可用、`git push` 仍拒；
   配 `git:*` 两者皆可；
10. 代理端口被占：runner 启动报错提示改 `gitProxyPort`；匿名通道会话 origin 不重写。

## 7. 落地状态

- **M1 —— 需求定稿**：本文档。
- **M2 —— 全链路实现（2026-09-23）**：协议 v16 + `terminal_exec`/`terminal_exec_ack` 帧 +
  runner `TerminalHandler`（可配置白名单缺省只读档 + cwd 限定 + marker 取新 cwd）+
  服务端 registry/SPI/REST（session/chat 对称）+ 前端工作区面板终端 tab。
  单测：runner 白名单纯函数面、agent 帧链路面（组帧/ack 路由/v16 门控/断连清理）。
  E2E：`tests/legacy/api/cap58_e2e.py`（fake runner + 独立实例）已实跑通过。
- **M3 —— FR-06/FR-07 实现（2026-10-13）**：FR-06 会话内本地 git 凭据注入——common
  `GitProxyBroker`（key→(upstreamOrigin, token) 纯内存登记簿）+ `RunnerWorkspace`
  prepare/prepareMulti 链路 `ensureProxyOrigin`（缓存库开 extensions.worktreeConfig +
  per-worktree origin 重写为代理 URL，幂等刷新 token；finalize/release 删 worktree 前
  revoke）；runner `GitAuthProxy`（回环 smart-HTTP 透传 + Authorization 注入，上行钉死
  HTTP/1.1 + 叠加 CAP-43 节点代理 git scope）+ `RunnerConfig` 增
  `gitProxyEnabled`（默认 true）/`gitProxyPort`（默认 18377）+ `AgentRunnerMain` 装配
  （端口被占启动报错指引改配置）与 claude 代理分支补 `NO_PROXY=127.0.0.1,localhost`
  （防 agent 回环 git 被 CAP-43 claude 代理拦走出网）。FR-07：`TerminalHandler` 白名单
  解析增 `git:<sub>` 条目（与只读集并集；不参与首 token 前缀命中）。
  单测：`GitProxyBrokerTest`（key/登记/吊销/URL 正反解）、`RunnerWorkspaceProxyOriginTest`
  （真 git：重写/缓存库不动/幂等刷新/双工作树 key 隔离/release 吊销）、
  `GitAuthProxyTest`（假上游：Authorization 注入/query/POST 字节无损/404）、
  `TerminalHandlerAllowlistTest`+`RunnerConfigTest` 增补。
  起因：req-jybc5fmg 会话 agent 本地 `git fetch` 无凭据卡合并（平台 token 只注入
  runner 自己发起的 git 操作，agent 本地执行不过协议、本机凭据链为空）。
