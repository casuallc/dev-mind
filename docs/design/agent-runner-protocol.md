# Agent Runner 协议契约（WS 帧 + REST 端点）

> 本文是 runner ↔ 服务端的**线协议**权威清单，从代码梳理生成（截至协议 v17）。
> 服务端组帧在 `devmind-agent` 的 `AgentConnectionRegistry`，上行解析在 `AgentNodeWsHandler`；
> runner 侧入口 `AgentRunnerMain.handleFrame` + 各 Handler。改协议先改本文。
> 用途：① 新字段/新帧的登记处（防"帧看着发出去了、runner 没拿到"类事故）；② 异语言
> runner（如 Go）重写的验收基准。

## 1. 通道总览

| 通道 | 方向 | 用途 |
|------|------|------|
| `WS {serverUrl}?token=<节点token>` | 双向 | 全部指令/事件帧（JSON 文本帧）。serverUrl 形如 `ws://host:8080/ws/agent` |
| `GET /api/agent/context/{sessionId}?token=` | runner ← server | CAP-34 上下文包拉取（zip，60s 超时） |
| `POST /api/agent/output/{sessionId}?token=` | runner → server | CAP-37/39 会话产出回传（JSON body `{files:[{name,content}]}`，60s） |
| `GET /api/agent-nodes/runner-package/download?token=` | runner ← server | CAP-21 FR-09 runner 自升级包（jar，sha256 校验） |
| `GET /api/agent/decision-lab/bundles/{kind}/{id}?token=` | runner ← server | CAP-56 执行包拉取（zip 含 manifest，120s 超时） |
| `GET /api/agent/classify/packages/{id}?token=` | runner ← server | CAP-57 安装包拉取（zip，GB 级流式） |

- HTTP base 由 `serverUrl` 派生：`ws→http`、`wss→https`，换 scheme 去 path。
- 所有 REST 拉取凭**节点 token**（query 参数），与 WS 接入同通道先例。
- **红线：token 仅随帧/请求传输 + runner 内存持有，严禁进日志与落库**；上行日志一律经 sanitize 脱敏。

## 2. 连接生命周期

### 2.1 接入与认证
- runner 主动反连；服务端在握手完成后的 `afterConnectionEstablished` 里校验 token——**握手成功 ≠ 接入成功**。
- 认证失败：服务端以关闭码 **1008**（POLICY_VIOLATION）关闭。
- 同节点重复接入：服务端以关闭码 **4000** 踢掉**旧**连接（私用段，防双实例互踢风暴）。

### 2.2 心跳与看门狗
- runner 每 **15s** 发 `heartbeat`（必须明显小于服务端超时）。
- 服务端 `heartbeatTimeoutMs`（默认 **45s**）未收到任何帧 → 主动断开判 OFFLINE（watchdog 巡检 15s）。
- 任何上行帧都刷新 lastSeen，不只 heartbeat。

### 2.3 重连退避（runner 侧）

| 场景 | 初始 | 封顶 | 复位条件 |
|------|------|------|----------|
| 普通断线 | 1s，×2 | 30s | 连接存活 ≥10s（证明认证通过） |
| 1008 认证拒绝 | 30s，×2 | 5min | 同上 |
| 4000 重复实例被踢 | 60s，×2 | 10min | 同上 |

### 2.4 断线语义
- **断线期间上行帧丢弃**，重连后 `hello.activeSessions` 对账兜底（服务端据此把僵尸会话判 FAILED）。
- 断连时服务端把该节点所有**等待中的请求-应答对**批量失败（exec/collect/push/finalize/release/query/proc/pkg/terminal/file）；file 等待器失败同时使对应中转 transfer 失效（FR-03）。
- 出口串行化（CAP-50）：runner 所有上行帧入队由**单条写线程**顺序落线（JDK WS 并发 sendText 会静默丢帧——丢 exit = 会话永久卡 RUNNING）。队列上限 10000，单帧写超时 10s。Go 实现同样必须保证上行帧顺序与可靠落线。

## 3. 协议版本协商

- runner 在 `hello.protocolVersion` 上报版本；缺席按 **v1** 对待（`DEFAULT_WHEN_ABSENT`）。
- 当前版本 **CURRENT = 18**（`AgentProtocol`）。
- 门控原则：**「必须认识」的帧/字段**在服务端 `supports(nodeId, v)` 拦截，不达标直接 409 引导升级（不静默发送）；**可选字段**不门控，老 runner 忽略即优雅降级。
- 版本史（详见 `AgentProtocol` javadoc）：

| 版本 | 引入 | 内容 |
|---|---|---|
| v1 | CAP-21 | 基线：launch/input/authorize/finish/kill/suspend + hello/heartbeat/event/exit/launched/upgrade/upgrade_ack |
| v2 | CAP-34 FR-04~08 | 对账/GC/版本协商/工具链标签（hello 可选字段，无新下行帧） |
| v3 | CAP-36 | exec/exec_log/exec_exit |
| v4 | CAP-39 | collect_output/output_collected |
| v5 | CAP-41 | launch `kind:"worklog"` + worklogOwner（**门控**） |
| v6 | CAP-41 M3 | worklog_push/worklog_push_ack（**门控**） |
| v7 | CAP-42 | launch `workspaceOwner` + workspace_finalize（**门控**） |
| v8 | CAP-43 | 帧携带 `proxy{url,scopes}`（节点配了代理时**门控**） |
| v9 | CAP-42 补丁 | workspace_release/workspace_release_ack（**门控**） |
| v10 | CAP-51 | 三帧携带 `workspaceKey`（**仅字段非空时门控**；缺席=旧布局是存量契约，不是降级） |
| v11 | CAP-54 | workspace_status（上行，不门控）+ workspace_query（**门控**） |
| v12 | CAP-24 FR-06 | workspace_finalize 携带 gitAuthorName/gitAuthorEmail（可选，不门控） |
| v13 | CAP-51 FR-06 | workspace_release 携带 deleteRemoteBranch（可选，不门控） |
| v14 | CAP-56 | exec 帧 `bundle{kind,id}`（**门控**） |
| v15 | CAP-57 | proc/proc_ack + pkg/pkg_ack（**门控**） |
| v16 | CAP-58 | terminal_exec/terminal_exec_ack（**门控**） |
| v17 | CAP-59 | terminal_complete + terminal_cancel（**门控**）；terminal_exec_ack 增 cancelled（可选，不门控） |
| v18 | CAP-65 | file/file_ack（节点文件浏览器，**门控**——roots 全量随帧下发，大文件走 HTTP 中转 `files-transfer`） |

## 4. 下行帧（server → runner）

每帧必有 `type`。带 ⏳ 的是「发帧 + 同步等 ack」模式（runner 必须回对应 ack 帧）。

### 4.1 `launch` ⏳（ack: `launched`，超时 `launchAckTimeoutMs`）
拉起 claude 会话。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sessionId | string | ✅ | 服务端分配，runner 透传回传 |
| projectId | string | 条件 | repo/repos 块存在时必填；否则走节点 project.\<id\> 映射/兜底目录 |
| taskSpec | string | - | 初始 prompt（写入 agent stdin 首条 user message） |
| model | string | - | 空 = runner/CLI 默认 |
| permissionMode | string | - | 空 = runner 配置默认 |
| kind | string | - | `session`（缺省）/ `chat`（问答沙箱 `_chat/<sid>`）/ `worklog`（v5+，持久工作区） |
| env | object | - | 附加进程环境变量（GIT_AUTHOR_* 等） |
| repo | object | - | 单库工作区 `{remoteUrl, baseBranch, branch, token}` |
| repos | array | - | 多库（>1 才下发），每项 repo 字段 + `name`（子目录名） |
| contextManifest | object | - | `{entries, totalBytes, sha256}`；runner 拉包物化失败 = launch 失败，**不降级** |
| resumeSessionId | string | - | claude --resume 目标 CLI 会话 id |
| worklogOwner | string | kind=worklog 必填 | 定位 `{worklogRoot}/<owner>/`（v5+ 门控） |
| workspaceOwner | string | repo 会话必填 | 固定工作区归属用户名（v7+ 门控；缺 → runner 报错，防静默落旧布局） |
| workspaceKey | string | - | `req-<需求id>` / `sid-<会话id>` → `worktrees/<key>`；**缺席 = 旧布局 work/**（v10+，字段非空才门控） |
| proxy | object | - | `{url, scopes[]}`；v8+ 恒带（显式空 url = 清空 runner holder） |

ack `launched`：`{sessionId, ok, error?}`。runner 侧任一失败（目录/拉包/起进程）回 ok:false。

### 4.2 会话控制（fire-and-forget，无 ack）
| type | 字段 | 语义 |
|------|------|------|
| `input` | sessionId, text, images?[] | 写 stdin user message；images=`{mediaType, data(base64)}`（CAP-32，老 runner 忽略=丢图） |
| `authorize` | sessionId, requestId, accepted, scope | 写 stdin permission_result；scope 缺省 `once` |
| `finish` | sessionId | 关 stdin（EOF 优雅退出） |
| `kill` / `suspend` | sessionId | 整树杀进程 |

### 4.3 `upgrade` ⏳（ack: `upgrade_ack`，超时 `upgradeAckTimeoutMs`）
`{version, sha256, sizeBytes, force}`。runner 忙碌回 `ok:false, reason:"busy", activeSessions:n`；
force=true 先 killAll 排空（上限 30s）再下载。ack 落线后 spawn SelfUpdater 退出换包；
服务管理模式（`DEVMIND_RUNNER_SERVICE=1`）以**退出码 42** 退出让服务管理器拉起。

### 4.4 `collect_output` ⏳ v4+（ack: `output_collected`，等 75s）
`{sessionId}`。运行中会话即时扫描 `.devmind/output/` 上传（先上传后 ack，服务端 ack 后回读无竞态）。

### 4.5 `worklog_push` ⏳ v6+（ack: `worklog_push_ack`，等 330s）
`{requestId:"wp-<ms>-<owner>", worklogOwner, remoteUrl, branch, token?, proxy?}`。
ack：`{requestId, ok, detail?|error?}`。

### 4.6 `workspace_finalize` ⏳ v7+（ack: `workspace_finalize_ack`，等 60+310×库数 s）
手动收口：逐库 merge 会话分支→基线、push、删 worktree。
`{requestId:"wf-<ms>-<sid>", sessionId, projectId, workspaceOwner, discardChanges, workspaceKey?, gitAuthorName?, gitAuthorEmail?, repos[], proxy?}`。
安全防护：会话仍在本节点运行时 runner 拒绝（ack ok:false）。

### 4.7 `workspace_release` ⏳ v9+（ack: `workspace_release_ack`，等 60+120×库数 s）
删除会话释放固定工作区：丢弃改动、删 worktree、删本地分支（不合并不 push）。
`{requestId:"wr-<ms>-<sid>", sessionId, projectId, workspaceOwner, workspaceKey?, deleteRemoteBranch?(v13), repos[]}`。
**不可静默跳过**——老 runner 忽略会留孤儿目录锁死 (项目,用户) 工作区，故先判在线再判版本。

### 4.8 `workspace_query` ⏳ v11+（ack: `workspace_query_ack`，等 30s）
`{requestId:"wq-<ms>-<sid>", sessionId, action, repo?, path?}`。
action ∈ `tree`（目录一层）/ `file`（≤256KB 文本）/ `diff`（单文件 vs HEAD）/ `status`（git 快照）。
ack：`{requestId, ok, payload?|error?}`，payload 结构见 §5.7。runner 侧路径限定代码目录内
（normalize+startsWith 防 `..`，toRealPath 防符号链接，深度 ≤8）。

### 4.9 `exec` ⏳ v3+（上行 `exec_log` 流 + `exec_exit` 收口，等 timeoutSec+120s）
构建/部署/测试/发版下发执行。
`{execId, projectId?, workspaceId?, command, workingDir?, timeoutSec(默认1800), env?, repo?{remoteUrl,branch,commit,token}, bundle?{kind,id}(v14+), proxy?}`。
- command 写临时 .sh 以 `execShell`（默认 bash）执行；runner 侧 `execAllowlist` 二次校验（空=拒绝一切）。
- bundle：先 `GET /api/agent/decision-lab/bundles/{kind}/{id}` 拉包物化，注入 env
  `DEVMIND_LAB_SCRIPT` / `DEVMIND_LAB_PAYLOAD`（名字两边同步，改名=协议变更）。
- 带 repo 块 = 构建工作区（克隆缓存 + detach checkout）；否则 runner 本地目录。

### 4.10 `proc` ⏳ v15+（ack: `proc_ack`，等 60s）
受管长驻进程管控（分类边车等）。`{requestId, action, instanceId, argv[], command, env{}, workdir, pidFile, logFile}`。
- action ∈ `start` / `stop` / `restart` / `status`；argv **不过 shell** 直接 ProcessBuilder。
- 安全：instanceId 白名单字符 `[a-zA-Z0-9._-]+`；workdir/pidFile/logFile 一律**相对 `<workspaceRoot>/classify/`** 收容根，越界即拒。
- 幂等：已运行 start 回 RUNNING；未运行 stop 回 STOPPED。不守护（崩溃由服务端健康轮询标红）。
- 受管进程**不随 runner 重启被杀**（proc.json 对账收回）。

### 4.11 `pkg`（v15+，ack `pkg_ack` 异步收口，无服务端等待超时）
`{requestId, packageId, sha256(64hex), sizeBytes, fileName, installDir}`。
流式下载 `GET /api/agent/classify/packages/{id}?token=` → sha256 校验 → zip 解到 `<installDir>.tmp`
（zip-slip 防护）→ 原子换版（旧版挪 .old 回滚兜底）→ 写 `<installDir>.sha256`。
installDir 同样收容于 classify/ 根。ack：`{requestId, ok, installDir?(节点侧绝对路径)|error?}`。

### 4.12 `terminal_exec` ⏳ v16+（ack: `terminal_exec_ack`，等 90s）
`{requestId:"tx-<ms>-<sid>", sessionId, command, cwd}`。会话工作区持久 shell 执行（CAP-59）。
- 白名单：`terminalAllowlist` 缺省=内置只读档；git 默认只放行只读子命令（`git:*` 全放行）；
  `>/>>` 重定向默认拒绝（`terminalAllowRedirect=true` 放开）。按 `|/||/&&/;/换行` 切段逐段校验。
- **cwd 权威在 runner**：ack 回传的 cwd 是 shell 执行后真实目录（相对代码目录 POSIX 路径）；
  帧 cwd 仅 shell 首启时作初始目录。cd 越界 → ok:false 且 shell 强制拉回代码目录。
- 命令超时 `terminalTimeoutSec`（默认 60s）→ timedOut:true。

### 4.13 `terminal_complete` ⏳ v17+（ack: `terminal_complete_ack`，等 15s）
`{requestId:"tc-<ms>-<sid>", sessionId, input, cwd}`。一次性子进程 compgen（不走持久 shell）。
ack：`{requestId, ok, word, candidates[](≤100, 目录带/后缀)|error?}`。

### 4.14 `terminal_cancel`（v17+，fire-and-forget）

### 4.15 `file` ⏳ v18+（ack: `file_ack`，超时 `fileAckTimeoutMs`）
CAP-65 节点文件浏览器。`{type:"file", requestId, op, root, path, newName?, recursive?, content?, transferId?, size?, sha256?, roots[]}`。
`roots[]` 每帧携带**服务端 DB 全量白名单**（归一化精确匹配，runner 不持久化）。op：`list/read/write/rename/delete/upload/download`；
upload/download（≤100MB）走 runner 主动 HTTP 中转（`GET/POST /api/agent/files-transfer/{id}?token=`，sha256 对账 + 原子落位），WS 只过指令与结果。安全边界（逃逸/越白名单/超限/二进制嗅探）服务端与 runner 双重校验。
`{sessionId}`。整树杀该会话执行中的终端命令；进行中的 terminal_exec 以 `cancelled:true` 收口。

## 5. 上行帧（runner → server）

### 5.1 `hello`（每次（重）连后首帧）
`{type, os, capabilities:"claude", version, activeSessions[], protocolVersion, labels?[], toolchain?{}, workspaceBytes?}`。
- labels 非空才带（带上即覆盖服务端编辑值）；toolchain 探测完成才带；workspaceBytes 未算完（-1）不带。
- activeSessions 是**对账真相源**（连接前先做孤儿进程/无主目录对账再上线）。

### 5.2 `heartbeat`（15s 周期）
`{type:"heartbeat", workspaceBytes?}`。

### 5.3 `event`（会话事件流，事件解析已下沉 runner）
`{type:"event", sessionId, eventType, content, source("stdout"|"stderr"|"system"), timestamp(epoch ms), payload{}}`。

eventType 与 payload（`CliEventParser` 产出，服务端对 CLI schema 无感）：

| eventType | content | payload |
|-----------|---------|---------|
| `init` 走的 `system` | `init: <session_id>` | `{subtype, model?, sessionId?}` |
| `assistant` | 文本块（截断） | `{model?}` |
| `user` | 文本 | - |
| `tool_use` | 工具名 | `{name, toolUseId, toolInput(JSON 串)}` |
| `tool_result` | 结果文本 | `{isError, toolUseId}` |
| `text_delta` | 增量文本 | -（CAP-50 流式；聚合器按阈值合并，其余事件先 flush 保序） |
| `permission_request` | `<tool> <input>` | `{requestId, action, toolName, input, options}` |
| `permission_result` | 结果描述 | `{permission}` |
| `result` | 最终文本 | `{isError, subtype, cost?, durationMs?, usage?}` |
| `error` | 错误消息 | - |
| `log` | 未识别行/verbose 噪音 | - |
| `system` | runner 注入（工作区收尾/产出上传结果等） | - |

### 5.4 `exit`
`{type:"exit", sessionId, code}`。**收口顺序红线**：聚合器 flush → 产出回传（output hook）→
finalizer（push/清理）→ exit 帧。finalizer 失败不影响 exit 上行。

### 5.5 请求-应答 ack 帧
| type | 对应下行 | 字段 |
|------|----------|------|
| `launched` | launch | `{sessionId, ok, error?}` |
| `upgrade_ack` | upgrade | `{ok, reason?, activeSessions?}`（reason="busy" 时带活跃数） |
| `output_collected` | collect_output | `{sessionId, ok, error?}` |
| `worklog_push_ack` | worklog_push | `{requestId, ok, detail?|error?}` |
| `workspace_finalize_ack` | workspace_finalize | `{requestId, ok, detail?|error?}` |
| `workspace_release_ack` | workspace_release | `{requestId, ok, detail?|error?}` |
| `workspace_query_ack` | workspace_query | `{requestId, ok, payload?|error?}` |
| `exec_log` | exec（流式，多帧） | `{execId, stream("stdout"|"stderr"), chunk}` |
| `exec_exit` | exec（收口） | `{execId, code, timedOut?, error?}` |
| `proc_ack` | proc | `{requestId, ok, action, status(RUNNING/STOPPED/UNKNOWN), pid?, error?, detail?}` |
| `pkg_ack` | pkg | `{requestId, ok, installDir?|error?}` |
| `terminal_exec_ack` | terminal_exec | `{requestId, ok, exitCode, stdout, stderr, cwd, timedOut?, cancelled?(v17), error?}` |
| `terminal_complete_ack` | terminal_complete | `{requestId, ok, word, candidates[], error?}` |
| `file_ack` | file | `{requestId, ok, payload?(entries/content/size/sha256 按 op)|error?}` |

### 5.6 `workspace_status`（v11+，瞬态旁路，不门控）
`{type:"workspace_status", sessionId, snapshot{}}`。事件触发（改文件类工具的 tool_result / 一轮 result）
+ 500ms 去抖 + 哈希去重 + 15s 兜底轮询。服务端不缓存不落库，原样透传给 session/chat bridge。
snapshot（`GitStatusCollector`）：
```
{ gitAvailable: bool,
  repos: [ { name, branch, changes: [ {path, code(porcelain XY), adds?, dels?} ], error? } ],
  total: { files, adds, dels },
  ts: epochMs }
```

### 5.7 workspace_query_ack 的 payload 结构
| action | payload |
|--------|---------|
| `tree` | `{entries:[{name, path, dir, size?}], truncated}`（目录优先排序，跳过 .git，上限 500） |
| `file` | `{content, size}`（≤256KB 且前 8KB 无 NUL） |
| `diff` | `{untracked, diff}`（未跟踪文件 untracked:true） |
| `status` | 同 §5.6 snapshot |

## 6. runner 配置（agent.properties）

| 键 | 默认 | 说明 |
|----|------|------|
| serverUrl / token | **必填** | WS 端点 / 节点 token |
| claudePath | 空=平台探测（where/which） | claude 二进制 |
| claudeConfigDir | 空=`{workspaceRoot}/../claude-config` | 以 CLAUDE_CONFIG_DIR 恒注入 claude 子进程；runner 落 cleanupPeriodDays=180 |
| permissionMode | acceptEdits | |
| workDir / project.\<id\> | `.` | 兜底工作目录 / 项目路径映射（CAP-25 起仅降级回退） |
| workspaceRoot | ./workspaces | 托管工作区根（布局见 RunnerConfig javadoc） |
| maxConcurrent | 4 | 会话与 exec 共享的并发许可 |
| executor | claude | `fake`=内置假进程自测 |
| gcDays / gcIntervalMinutes / gcInitialDelayMinutes | 14 / 360 / 10 | 会话目录 GC |
| worktreeGcDays | 30 | 需求工作树 GC（未提交/未推送永不删） |
| buildGcHours | 24 | 构建工作区 GC |
| labels | 空 | 节点标签 CSV（非空覆盖服务端值） |
| execAllowlist | 空=拒绝一切 | exec 命令白名单 CSV 前缀 |
| execShell | bash | exec/终端脚本解释器 |
| worklogRoot | 空={user.home}/worklog | worklog 持久工作区根 |
| partialMessages | true | claude --include-partial-messages（老版本不认识会 FAILED，置 false 回退） |
| terminalAllowlist | 缺席=内置只读档 | 显式配置（含空串=全拒）全覆盖 |
| terminalTimeoutSec / terminalAllowRedirect / terminalShellIdleMin | 60 / false / 30 | 终端命令超时 / 重定向放开 / 持久 shell 空闲回收 |

## 7. 不变量清单（重写/改协议时必须保住）

1. **帧字段完整性**：新字段必须在 `AgentConnectionRegistry` 组帧处 put + 配套测试断言
   （LaunchTest/ExecTest/ProcTest/PkgTest/TerminalExecTest）——本仓库三次"静默丢字段"事故。
2. **上行顺序**：单写线程串行落线；exit 帧永远最后（flush→产出→finalizer→exit）。
3. **ack 先行**：collect_output/finalize 等 ack 在其副作用（上传/push）完成后才发——服务端 ack 后回读无竞态。
4. **token 红线**：只随帧/HTTPS 请求传输，内存持有，上行日志 sanitize。
5. **门控纪律**：「必须认识」的帧/字段先 `supports()` 再发，409 话术引导升级；可选字段不门控。先判在线再判版本（报错落在真实原因上）。
6. **关闭码语义**：1008=认证拒绝、4000=重复实例；runner 按码走不同退避，不互踢。
7. **安全边界**：exec/terminal 白名单 runner 本地强制执行；proc/pkg 路径收容 classify/ 根；
   workspace_query 路径限定代码目录；zip 解包 zip-slip 防护。
8. **幂等**：proc start/stop 幂等；launch 上限拒绝（`maxConcurrent`）；hello 对账为最终真相。

## 8. 相关代码锚点

| 位置 | 内容 |
|------|------|
| `devmind-common .../agent/AgentProtocol.java` | 版本常量 + 完整版本史 javadoc |
| `devmind-common .../agent/AgentLaunchCommand.java` 等 | 帧对应的 Java 契约 record |
| `devmind-agent .../registry/AgentConnectionRegistry.java` | 下行组帧 + 上行路由 + 等待者管理 |
| `devmind-agent .../ws/AgentNodeWsHandler.java` | 上行帧解析入口 |
| `devmind-agent-runner .../AgentRunnerMain.java` | runner 帧分发 + launch/upgrade 处理 |
| `devmind-agent-runner .../ServerConnection.java` | 重连退避 + 出口串行化 |
| `devmind-common .../agent/runtime/CliEventParser.java` | CLI stream-json → eventType 映射 |
