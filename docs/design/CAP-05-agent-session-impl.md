# CAP-05 Agent 会话管理 —— 实现方案

> 对应需求文档：`docs/capabilities/CAP-05-agent-session.md`
> 版本：v0.1 ｜ 日期：2026-08-30 ｜ 状态：待评审
>
> **建设目标**：做出替代"多开 PowerShell"的浏览器会话管理能力——起/管/收 headless Agent，worktree 隔离，看板实时监控，卡住时通知人、人远程回复。这是整个平台的地基之一，先把它做扎实。

---

## 1. 范围

### 做
- 起/停/挂起/强杀 Agent 会话（headless Claude Code 子进程）；
- 会话状态机与看板（REST + WebSocket 实时）；
- 实时输出流 + 浏览器终端视图；
- 向会话注入输入（普通消息 + 快捷回复）与授权响应；
- git worktree 隔离、CLAUDE.md 注入（MVP 用简化注入器，预留 CAP-04 接口）、worktree 清理；
- 会话完成后的 diff 摘要展示；
- 会话模板：预设 prompt 骨架（如"实现+单测"、"修 bug"），起会话时一键选择。

### 不做（本阶段）
- CAP-06 通知中心（只留事件钩子，先输出到日志/控制台，下一阶段接入）；
- CAP-04 知识库完整能力（MVP 只做"读本地 knowledge-repo 目录拼 CLAUDE.md"的简化注入器）；
- 认证/登录（本地单用户，先不做鉴权；接口为后续 CAP-01 预留）。
- 任务编排（Orchestrator）、需求流程；
- 远程 Agent 执行（SessionExecutor 已留 SPI，本阶段仅本机，后续扩展）。

---

## 2. 总体架构

```
┌────────────────────────── 浏览器前端 (React + AntD) ──────────────────────────┐
│  会话看板页 /sessions     会话详情页 /sessions/:id                             │
│  列表·筛选·新建会话        实时输出流·输入框·快捷回复·kill/挂起·diff 查看        │
└──────────────────────────────┬────────────────────────────────────────────────┘
                               │ REST + WebSocket (WS /ws/sessions/{id})
┌──────────────────────────────▼────────────────────────────────────────────────┐
│  Spring Boot 后端                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ SessionController (REST)        SessionWsHandler (WebSocket)          │  │
│  ├────────────────────────────────────────────────────────────────────────┤  │
│  │ SessionManagerService                                                 │  │
│  │   ├─ 状态机（RUNNING/WAITING_INPUT/WAITING_AUTH/DONE/FAILED/SUSPENDED）│  │
│  │   ├─ 会话注册表 ConcurrentMap<id, SessionRuntime>                      │  │
│  │   └─ 事件总线 → 通知钩子(预留 CAP-06)                                   │  │
│  ├────────────────────────────────────────────────────────────────────────┤  │
│  │ SessionRuntime（每个会话一个）                                           │  │
│  │   ├─ Process（claude -p 子进程）                                        │  │
│  │   ├─ CliEventParser（解析 stream-json → 统一事件模型）                   │  │
│  │   ├─ 环形缓冲(最近 N 条事件，供迟到订阅者回放)                            │  │
│  │   └─ 虚拟线程：stdout 读 / stderr 读 / stdin 写 三路                      │  │
│  ├────────────────────────────────────────────────────────────────────────┤  │
│  │ WorktreeManager    KnowledgeInjector(stub)   ProcessHelper(Windows)     │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬────────────────────────────────────────────────┘
                               │ spawn
                        headless Claude Code 子进程
                        （cwd = 项目 worktree，读注入的 CLAUDE.md）
```

**核心数据流（一次会话）**：
1. `POST /sessions {projectId, taskSpec, baseBranch}` → WorktreeManager 创建 worktree → KnowledgeInjector 写 CLAUDE.md → 拉起 `claude -p --input-format stream-json --output-format stream-json`，cwd=worktree；
2. 后端读 stdout，`CliEventParser` 解析为统一事件（assistant/tool_use/permission_request/result/error…）；
3. 事件经 WebSocket 广播给前端，同时入环形缓冲；状态变化触发通知钩子；
4. 用户在前端发消息 → WS/input → 后端写 stdin（JSONL）；permission 请求 → 前端授权按钮 → 后端写 `permission_result`；
5. 进程退出 → 解析最终 result → 状态 DONE/FAILED → worktree 保留供 diff 查看，手动删除。

---

## 3. 关键技术决策

### 3.1 进程驱动方式：`claude -p` + stream-json 双端流（首选）

用 Claude Code CLI 的 **stream-json 模式**程序化驱动：

```
claude -p \
  --input-format  stream-json \   # stdin 读 JSONL（用户消息 / permission_result）
  --output-format stream-json \   # stdout 写 JSON 事件流
  --verbose \
  --model <可配置> \
  --permission-mode <可配置，默认 acceptEdits> \
  <taskSpec 作为初始 prompt>
```

- **为什么不用交互式 TUI**：交互模式需要伪终端（PTY），Windows 上没有 Java 内建 PTY，程序化驱动不可行；
- **为什么不用"单发 prompt 等结果"**：无法中途注入消息、无法响应权限请求，就无法实现"远程回复"和"等待授权"；
- stream-json 事件类型（供解析）：`system/init_result`、`assistant`、`user`、`tool_use`、`tool_result`、`stream_event`、`permission_request`、`permission_result`、`result`、`error`。

> ⚠️ **实现期必须做一次 spike（M2 首步）**：核实本机 `claude --help` 的确切参数与事件 schema（不同版本字段可能有差异）。**所有 CLI 相关细节隔离在 `CliProcessLauncher` + `CliEventParser` 两个类里**，schema 变动只改这两处。

### 3.2 "需要人"的状态判定

- **WAITING_AUTH**：收到 `permission_request` 事件 → 状态置为 WAITING_AUTH，向前端/通知钩子抛出"授权请求"（含工具名、描述、可批量授权）；
- **WAITING_INPUT**：headless 模式不会像交互模式那样主动停下来提问；我们把"用户随时可注入消息"作为常态，且 `permission_request` 是主要的"卡住"信号。WAITING_INPUT 保留给两类场景：agent 输出的末尾是明确提问（启发式检测：结尾为 `?` 且近 3 条无 tool_use），或前端手动标记"我在等待回复"。
- 兜底：任何状态下用户都能发消息、kill、挂起。

### 3.3 线程模型：虚拟线程（Java 21+）

Spring Boot 4.x 默认支持虚拟线程。每个会话固定 3 条虚拟线程（stdout 读 / stderr 读 / stdin 写锁串行化），加上 WebSocket 会话管理。进程 stdout 必须**始终被消费**（否则 pipe 缓冲写满导致子进程阻塞——死锁）。stderr 单独读并合入事件流（标注来源）。

### 3.4 WebSocket

- 用 Spring 原生 WebSocket（`TextWebSocketHandler`），消息为 JSON，非 STOMP（单场景轻量即可）；
- 每条会话一个订阅主题，前端连 `WS /ws/sessions/{id}`；
- **迟到订阅者回放**：SessionRuntime 持环形缓冲（默认 1000 条），新连接先推回放再推增量；
- 事件为追加式（append-only），前端维护一个游标；心跳 `ping/pong` 保活。

### 3.5 Windows 进程处理

- `claude` 可执行文件定位：配置项 `claudePath`（默认空 = 自动探测：`where claude` → 依次尝试 `claude.exe` / `claude.cmd`）；.cmd 需经 `cmd.exe /c` 启动；
- **进程树杀灭**：直接 `process.destroy()` 杀不掉 Claude 的 node 子进程 → 用 `taskkill /F /T /PID <pid>`（封装在 `ProcessHelper`）；
- 输出编码：统一按 UTF-8 读字节解码；必要时启动参数加 `chcp 65001`；
- 超长路径：worktree 路径可能撞 Windows 260 字符限制 → worktree 根目录可配置（默认项目内 `.devmind/worktrees`），会话 ID 用短格式（8 位），并建议系统开启长路径。

### 3.6 Worktree 策略

- 起会话：`git worktree add -b feature/<sid> <proj>/.devmind/worktrees/<sid> <baseBranch>`；
- 会话进入 DONE 后 worktree 保留（供 diff/merge），手动删除或 `git worktree remove --force`；
- FAILED/SUSPENDED 也保留（可续跑）；KILL 后由用户确认是否清理；
- 项目 `.gitignore` 追加 `.devmind/`；
- 同分支重复 checkout 会冲突 → 每次会话新建分支，启动前先 `git fetch` 保证 baseBranch 存在。

### 3.7 CLAUDE.md 注入（MVP 简化版）

- 定义 SPI `KnowledgeInjector.preview(project, taskSpec)` / `.apply(worktree, ...)`；
- **MVP 实现 `LocalDirInjector`**：直接读配置的 knowledge-repo 目录，拼接
  `global/**（按项目 tags 粗略过滤） + projects/<项目>/** + 任务说明`，写入 worktree 根 `CLAUDE.md`（若项目原有 CLAUDE.md 则追加在后，不覆盖）；
- 同时写 `.claude/settings.local.json`（permission 配置等）；
- CAP-04 落地后，替换实现为知识库服务，SPI 不变。

---

## 4. 后端模块设计（包结构）

```
com.devmind
├── DevMindApplication
├── session/
│   ├── controller/  SessionController, SessionWsHandler
│   ├── service/     SessionManagerService, SessionStateMachine
│   ├── runtime/     SessionRuntime, CliProcessLauncher, CliEventParser, EventBus(notify hook)
│   └── model/       Session, SessionEvent, SessionState, EventTypes...
├── project/         ProjectService, WorktreeManager
├── knowledge/       KnowledgeInjector(SPI), LocalDirInjector
├── proc/            ProcessHelper(Windows 进程/编码/taskkill)
└── config/          AppProperties(application.yml), WebSocketConfig, ThreadConfig
```

### 核心类职责

| 类 | 职责 |
|---|---|
| `SessionManagerService` | 会话生命周期入口：create/list/get/input/authorize/suspend/resume/kill/diff；持有注册表；协调 Worktree/Injector/Launcher |
| `SessionRuntime` | 单个会话的内存态：Process、stdin writer、事件解析器、环形缓冲、订阅者集合、状态锁 |
| `CliProcessLauncher` | 组装命令行、定位 claude、设置 env/cwd、spawn，返回 Process |
| `CliEventParser` | stream-json 原始事件 → 统一 `SessionEvent`（类型/内容/时间戳/来源），含 permission_request 结构 |
| `WorktreeManager` | worktree 增删、分支准备、清理、diff 摘要（git CLI） |
| `KnowledgeInjector` | SPI；MVP=LocalDirInjector |
| `ProcessHelper` | taskkill /T/F、UTF-8 包装、退出码归一 |

### 状态机

```
                    ┌─────────────────────┐
   create ─────────▶│      RUNNING        │◀──────── suspend 恢复
                    │   (agent 执行中)     │
                    └──────┬──────┬───────┘
              permission   │      │ 用户注入消息（不改变状态）
              request 事件  │      │
                    ┌──────▼──────┴───────┐
                    │    WAITING_AUTH     │── 授权 response 或拒绝 ──▶ RUNNING
                    └─────────────────────┘
                    （启发式提问判定 → WAITING_INPUT，同样可回复继续）
                    │ process 正常退出 ──▶ DONE（解析 result 摘要）
                    │ process 异常退出 ──▶ FAILED（error/退出码）
                    │ 用户挂起/超时 ──────▶ SUSPENDED（kill 进程但保留 worktree）
                    │ 用户 kill ─────────▶ TERMINATED（可选清理 worktree）
```

---

## 5. 数据模型（H2 表）

```sql
-- sessions：会话元数据
CREATE TABLE sessions (
  id           VARCHAR(32) PRIMARY KEY,      -- 短ID 用于worktree/路径
  project_id   VARCHAR(64) NOT NULL,
  task_spec    TEXT,
  base_branch  VARCHAR(128),
  status       VARCHAR(16),                  -- RUNNING/WAITING_INPUT/WAITING_AUTH/DONE/FAILED/SUSPENDED/TERMINATED
  worktree_path VARCHAR(512),
  pid          BIGINT,
  model        VARCHAR(64),
  summary      TEXT,                         -- 完成后由 agent result 生成
  created_at   TIMESTAMP, updated_at TIMESTAMP, finished_at TIMESTAMP
);

-- session_events：持久化的事件流（追加），供历史查询/回放
CREATE TABLE session_templates (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  code       VARCHAR(32) UNIQUE,      -- implement-test / fix-bug / refactor / ...
  name       VARCHAR(128),
  prompt     TEXT,                    -- 模板 prompt 骨架（支持占位符 {{task}} 等）
  sort_order INT,
  enabled    BOOLEAN
);

CREATE TABLE session_events (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(32),
  seq        BIGINT,
  type       VARCHAR(32),                    -- assistant/tool_use/permission_request/...
  content    TEXT,
  source     VARCHAR(8),                     -- stdout/stderr
  created_at TIMESTAMP
);
-- 环形缓冲只做内存回放；DB 事件表做历史审计（批量落库，避免每条同步 IO）
```

> 落库策略：事件先入内存缓冲，**批量异步落库**（如每 200ms 或满 50 条刷一次），避免高频事件拖垮进程读取线程。

---

## 6. API 与消息协议

### REST

```
POST   /api/sessions                          {projectId, taskSpec, baseBranch?, model?}
GET    /api/sessions?projectId=&status=       看板列表（含摘要、最新状态、耗时）
GET    /api/sessions/{id}                     详情（含 worktree、state、summary）
GET    /api/sessions/{id}/events?afterSeq=    历史事件（分页/游标）
POST   /api/sessions/{id}/input               {text} | {quickReply}
POST   /api/sessions/{id}/authorize           {accepted:bool, toolName?, scope:"once"|"always"|"session"}
POST   /api/sessions/{id}/suspend | /resume | /kill
GET    /api/sessions/{id}/diff                worktree 相对 base 的 diff 摘要（git diff --stat + 变更文件列表）
DELETE /api/sessions/{id}/worktree            清理 worktree（确认后）
CRUD   /api/session-templates                 会话模板管理（预设 prompt 骨架）
POST   /api/session-templates/{code}/preview  渲染模板（替换占位符）预览
```

### WebSocket `/ws/sessions/{id}`

服务端→客户端（JSON）：
```json
{"type":"snapshot","state":"RUNNING","events":[...最近回放...]}
{"type":"event","seq":1024,"event":{"type":"tool_use","name":"Bash","tool_input":{...}}}
{"type":"state","state":"WAITING_AUTH","payload":{"toolName":"Bash","input":"npm install","requestId":"..."}}
{"type":"result","summary":"..."}   {"type":"error","message":"..."}   {"type":"pong"}
```
客户端→服务端：
```json
{"type":"input","text":"继续"}  {"type":"quickReply","reply":"继续"}
{"type":"authorize","requestId":"...","accepted":true,"scope":"once"}
{"type":"ping"}
```

---

## 7. 配置项（application.yml）

```yaml
devmind:
  session:
    claude-path: ""                 # 空=自动探测 where claude
    model: ""                       # 空=CLI默认
    permission-mode: acceptEdits    # 默认权限模式（可被会话级覆盖）
    input-timeout: 300              # 秒；WAITING_* 超时 → P0 提示(预留通知)
    idle-timeout: 0                 # 秒；0=不自动挂起
    ring-buffer: 1000               # 内存回放条数
    event-flush-ms: 200             # 事件批量落库周期
  worktree:
    root: ""                        # 空=项目内 .devmind/worktrees
    base-branch: master
  knowledge:
    repo-path: ""                   # knowledge-repo 路径（LocalDirInjector 用）
    enabled: true
  project:
    default-path: ""                # 项目注册简化：MVP 用 yml 预置一个项目即可
```

> MVP 简化：项目先不建表，用 `devmind.project.default-path` 预置**一个**项目（用户自己改配置指向真实仓库）。CAP-02 落地后接入项目表。

---

## 8. 安全与健壮性

- **防死锁**：stdout/stderr 必须独立线程持续读取；stdin 写加锁；
- **进程回收**：应用关闭时（`@PreDestroy`）批量 taskkill 所有存活会话；会话 SUSPENDED/TERMINATED 保证无孤儿进程；
- **命令注入**：本阶段不执行用户 shell（agent 内部行为由 Claude Code 权限模式管控）；MVP 权限模式默认 `acceptEdits`，可调 `bypassPermissions`（本地开发阶段可接受）；
- **资源上限**：最大并发会话数（默认 4，可配），超出排队并提示；
- **事件大小**：单事件内容截断（如 >100KB 截断并标注），防前端卡死；
- **超时**：WAITING_AUTH 超时自动拒绝 + 提示；进程无输出超时提示（可配）。

---

## 9. 仓库结构（monorepo）

```
dev-mind/
├── docs/
│   ├── capabilities/              # 需求文档（已有）
│   └── design/                    # 实现方案（本文件）
├── backend/                       # Spring Boot 4.2.x (Java 21+, Maven)
│   └── src/main/java/com/devmind/...
├── frontend/                      # React 18 + Vite + AntD 5
│   ├── src/pages/sessions/        # 看板 + 详情
│   └── ...
└── README.md                      # 项目总览 + 启动方式
```

- 前端 `npm run build` 产物复制到 `backend/src/main/resources/static`，后端单 jar 托管；
- 开发期前端走 Vite dev server（proxy /api 与 /ws 到 8080），后端启用 CORS。

---

## 10. 实施里程碑（每步可独立验收）

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| **M0 脚手架** | backend(Spring Boot+H2) + frontend(Vite+AntD) 联通；配置读取；health 接口 | 浏览器能访问前端，前端能调通 `/api/health` |
| **M1 进程生命周期骨架** | 先不接 claude：`POST /api/sessions` 起一个**假进程**（如 `node -e "setInterval..."`），支持 list/get/kill/suspend；状态机落地 | 能起/列/杀一个进程，状态正确翻转，无孤儿进程 |
| **M2 接 claude（spike 优先）** | `CliProcessLauncher`+`CliEventParser`：核实 CLI 参数与 schema；`claude -p` 跑通，解析事件落库；前端看板轮询出列表 | 起一个真实 claude 会话能跑完，事件表有记录 |
| **M3 实时流** | WebSocket 推送 + 环形缓冲回放；前端详情页实时终端视图（自动滚动）+ 迟连回放 | 两个浏览器同时打开，输出实时同步，后开的能回放最近 N 条 |
| **M4 交互与授权** | 输入框 + 快捷回复写 stdin；permission_request → WAITING_AUTH → 前端授权按钮；启发式 WAITING_INPUT | 会话中注入消息生效；agent 请求权限时看板置顶、可授权/拒绝 |
| **M5 worktree + 注入 + 收尾** | WorktreeManager 真建 worktree；LocalDirInjector 写 CLAUDE.md；diff 摘要；kill/timeout 加固；会话清理 | 会话在独立 worktree 跑，CLAUDE.md 被注入，完成后看得到 diff，杀进程无残留 |
| **M6 打磨** | 会话历史、筛选、搜索；事件批量落库调优；异常恢复（服务重启后重建会话状态=全部标记为 TERMINATED） | 长时间使用稳定，重启无脏数据 |

> **顺序原则**：M1 先证明"生命周期管理"（用假进程），再接真实 claude——避免一开始就陷入 CLI schema 的坑。每完成一个里程碑即可让用户实际试用。

---

## 11. 风险与对策

| 风险 | 对策 |
|---|---|
| Claude CLI 参数/schema 随版本变化 | 全部隔离在 Launcher/Parser 两处；M2 先 spike 验证；Parser 单测固化样例事件 |
| Windows 进程树杀不干净 | `taskkill /F /T` + 启动时记录 pid；关闭钩子批量清理；测试留排查手段 |
| stdout 未被消费导致子进程阻塞 | 独立虚拟线程持续读；监控 pipe 缓冲 |
| 长路径超出 260 字符 | worktree 根可配置、短 session ID、提示开启系统长路径 |
| stream-json 输入模式在部分版本不可用 | 备选降级：单发 `-p` + `--session-id` 续跑（牺牲实时交互，保住基础能力） |
| 事件高频导致 DB/内存压力 | 批量落库 + 环形缓冲 + 单事件截断 |
| 多会话并发资源占用（内存/API 配额） | 并发上限可配；每会话 token 统计（预留） |
| 会话与项目原 CLAUDE.md 冲突 | 注入策略=全局+项目+任务+原 CLAUDE.md 追加，不覆盖原文件 |

---

## 12. 已确认决策（2026-08-30）

| # | 问题 | 结论 | 落点 |
|---|---|---|---|
| 1 | taskSpec 形式 | **支持富文本**（textarea + 粘贴多段上下文） | 新建会话面板用富文本输入；API body 为 text |
| 2 | 权限模式默认 | **放手**（`acceptEdits` 起步，会话级可覆盖更严/更松） | 配置项 + 会话级覆盖字段 |
| 3 | 会话模板 | **本阶段做**：预设 prompt 骨架 + 一键选择 | 新增 `session_templates` 表 + 前端选择器 + 占位符渲染 |
| 4 | claude 位置 | **全局命令**（`where claude` 可定位），`claudePath` 留配置兜底 | M2 spike 实测 |
| 5 | 执行位置 | **本阶段本机**；SessionExecutor 留 SPI，后续支持远程 | 仅预留接口，不做实现 |

> 模板占位符约定：`{{task}}`（任务说明）、`{{project}}`（项目名）、`{{branch}}`（分支）；起会话时替换。
