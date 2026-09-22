# CAP-54 会话工作区实时视图（git 变更推送 + 文件浏览）

> 能力 ID：CAP-54 ｜ 分类：底座 ｜ 状态：**需求定稿** ｜ 日期：2026-09-22
> 依赖 CAP-21/30/34/42/51/53。只读能力：不改变工作区任何生命周期语义（CAP-42/51 的
> 占用/收口/释放/GC 一行不动），只在「看」的层面补盲区。

## 1. 目的

CAP-34 之后一切执行收敛到 runner 节点，会话工作区（代码目录）对页面是黑盒：

- **进行中的会话看不到 agent 正在改什么**——`RemoteDiffService`（CAP-31）走服务端克隆缓存，
  只能 diff **已推送的分支**（收口后才可见），runner 工作区里未提交的改动完全不可见；
- **看不到工作区文件**——agent 读写了哪些文件、产出了什么，只能等会话结束看 transcript
  或产出回传。

本能力给会话/问答页加一个**工作区实时视图**：

1. **git 变更实时推送**：agent 每次落盘修改（Edit/Write/Bash 工具完成）后秒级推送
   当前变更清单（分支、逐文件状态、增删行数）；
2. **文件树浏览与内容查看**：懒加载目录树、文件内容、单文件 diff，按需拉取。

## 2. 功能需求

### FR-01 runner 实时采集与推送（git 变更）

触发**不用文件系统监听**（WatchService 递归监听大目录在 Windows 上又贵又不可靠），
改用 runner 已有的 CLI 事件解析出口做事件驱动：

- **触发源**：`tool_result` 事件且工具名 ∈ {Edit, Write, NotebookEdit, Bash}（claude 改文件的
  全部入口）→ **500ms 去抖**后采集一次（连续多次 Edit 合并成一轮）；
- **兜底**：会话活跃期间每 15s 慢轮询一次（防 Bash 起后台进程写文件等漏网）；
  launch 成功与会话转入 WAITING_INPUT 时各强制采集一次；
- **采集内容**（每轮只是一张小表）：逐库 `{repo, branch, changes:[{path, status(M/A/D/R/?),
  adds, dels, staged}]}`——`git status --porcelain=v1` + `git diff --stat`（含 staged/unstaged
  两段）。多库会话（CAP-31 聚合根）按各子库分别采集、分组上报；
- **哈希去重**：快照与上次一致不推（兜底轮询不刷屏）；
- **采集基准目录 = 代码目录**（CAP-53 口径：`worktrees/<key>/` / 存量 `work/`），
  **不是**上抬后的 claude cwd——cwd 下有 `main/` 克隆缓存与其他需求的工作树，不该暴露；
- **非 git 目录**（chat 问答沙箱 CAP-30）：上报 `gitAvailable=false`，文件浏览不受影响；
- 采集失败（git 不存在、超时 30s）只告警不阻断会话；快照带 `error` 字段下行。

### FR-02 状态旁路传输（不入会话事件流）

git 状态是「最新值覆盖」语义，**不进会话事件环形缓冲**（否则会污染 transcript 回放、
新打开页面时几十条状态帧当历史重放）：

- **上行帧** `workspace_status {sessionId, snapshot}`（协议 v11，见 FR-06）；
- **服务端**：内存存 `sessionId → 最新快照`（ConcurrentHashMap 只留最新一条），
  会话终结/节点断连时清除；快照**不落库**（瞬态视图，无追溯价值）；
- **下行**：复用现有浏览器 WS（`/ws/sessions/{id}`、`/ws/chats/{id}`）加一种帧
  `{type:"workspace", snapshot}`——连接建立时随 snapshot 帧补发当前最新值（有缓存才发），
  之后服务端收到上行帧即转发。老前端对未知帧类型静默忽略（`useChatStream.append`
  只认已知 type），天然兼容；
- 路由不走 `AgentEventListener`（那是会话事件链），服务端新增独立监听接口
  `WorkspaceStatusListener`（common 定义、session/chat 各自实现按 sessionId 认领），
  `AgentConnectionRegistry` 收到上行帧后 `ObjectProvider` 广播，未命中自行忽略
  （同既有 bridge 模式）。

### FR-03 文件树 / 内容 / diff 拉取（无界数据走拉模式）

推送只承载 git 变更小表；文件树、文件内容、diff 这类无界数据**用户点了才拉**：

```
GET /api/sessions/{id}/workspace/tree?path=&depth=1    目录一层（每目录 cap 500 条）
GET /api/sessions/{id}/workspace/file?path=...         文件内容（cap 256KB，二进制探测拒绝）
GET /api/sessions/{id}/workspace/diff?path=...         单文件 diff（git diff HEAD -- path）
GET /api/chats/{id}/workspace/...                      同上（问答沙箱，diff 端点 409）
```

- 服务端收 REST → 下发 `workspace_query {requestId, sessionId, action, path}` 帧 →
  runner 读盘/跑 git → `workspace_query_ack {requestId, ok, payload, error}` 回传——
  照抄 collect_output 的「发帧 + CompletableFuture 等 ack」模式（`AgentConnectionRegistry`），
  超时 30s、节点断连批量失败等待者；
- 文件内容/diff 文本随 ack 帧内嵌（超大拒绝而非分片：256KB 上限对查看场景足够）；
  服务端 `AgentWsConfig` 把 runner 接入 WS 的文本帧缓冲调到 512KB（默认 8KB 装不下）；
- 权限：沿用会话/问答既有读权限（本人会话可见）；会话终态且工作区已释放 → 410
  「工作区已释放」。

### FR-04 前端工作区面板

`ChatPanel`（sessions 与 chats 共用）外层包**可折叠右侧栏**（宽 ~360px，折叠态只剩把手），
两个 Tab：

- **变更 Tab**（推送驱动）：
  - 头部：分支名 + 汇总「N 文件 +x −y」；
  - 多库按库分组（Collapse），库内按 已暂存/未暂存/未跟踪 分段；行 = 状态徽标
    （M 黄 / A 绿 / D 红 / R 蓝 / ? 灰）+ 路径（中间省略）+ `+adds −dels`；
  - 点击行 → Drawer 展示该文件完整 diff（调 FR-03 diff 端点）；
  - **实时体感**：新出现/数值变化的行短暂高亮（~1s）再 settle；WS 断开时面板打
    「已离线·数据可能过时」水印但保留最后一次快照；
  - `gitAvailable=false`（chat 沙箱）：显示「问答沙箱不是 git 仓库」。
- **文件 Tab**（拉取驱动）：antd Tree 懒加载（展开节点才调 tree 端点）；点击文件 →
  Drawer 内容查看器（等宽字体 + 行号；二进制/超限给提示不渲染）；树根 = 代码目录。
- **面板显隐**：MODEL 执行体（CAP-49，服务端直连无 runner）整个面板隐藏；
  runner 协议 < v11 显示「节点 runner 版本过低，升级后可看」；终态会话且工作区已释放
  显示「工作区已释放」。
- 布局遵循《前端内容区布局约定》；代码集中在 `shared/chat/workspace/`（ChatPanel 同源），
  sessions/chats 两个 feature 不各自实现。

### FR-05 安全边界（runner 侧强制）

- 一切路径操作：`resolve` 后强制 `startsWith(代码目录)`，再 `toRealPath()` 校验防符号链接
  逃逸；越界一律 400；
- tree 限深度 ≤8、每目录 500 条；file ≤256KB；diff 限单文件；
- git 只跑只读命令（status/diff/log），输出过既有 `sanitize` token 脱敏约定后才上行；
- token 等凭据红线不变（仅内存、严禁进帧 payload 与日志）。

### FR-06 协议与兼容

- `AgentProtocol.CURRENT = 11`，新增常量 `WORKSPACE_VIEW = 11`；版本史注释补 v11 条目
  （workspace_status 上行帧 + workspace_query/workspace_query_ack 下行/上行帧）；
- **老 runner（<v11）+ 新服务端**：不认识 workspace_query 帧会静默忽略 → 服务端等 ack
  超时。故 REST 端点先 `supports(nodeId, WORKSPACE_VIEW)` 门控，不足直接 409 引导升级
  （不等超时）；上行 workspace_status 不存在 → 缓存为空 → 前端显示「版本过低」；
- **新 runner + 老服务端**：上行帧被老服务端忽略（`AgentNodeWsHandler` 对未知 type 丢弃），
  runner 侧采集开销小，不门控上行；
- chat 链路与 session 链路对称接入（同一套 runner 实现、两套服务端 bridge）。

## 3. 关键设计

- **为什么 tool_result 触发而非 WatchService**：claude 的文件修改全部经由 Edit/Write/Bash
  工具，runner 的 `CliEventParser` 已逐条解析这些事件——触发精确、零额外系统开销、
  跨平台一致；WatchService 递归注册大目录在 Windows 上事件风暴且不可靠。15s 兜底轮询
  补「Bash 起后台进程落盘」这类绕过工具事件的缝隙。
- **为什么旁路而非事件流**：会话事件流是**追加式历史**（环形缓冲回放 + 落库），git 状态是
  **最新值语义**——进事件流既污染回放又无追溯价值。旁路内存缓存 + WS 补发最新值，
  与 CAP-50 的「回放排除增量」同一思路。
- **为什么树/内容走拉模式**：推送通道要保小帧高频可靠（CAP-50 教训：帧率放大暴露链路
  弱点），无界数据（整棵树、大文件）只在用户主动浏览时拉取，推送帧恒为小表。
- **为什么采集基准是代码目录而非 cwd**：CAP-53 把 cwd 上抬后，cwd 下有克隆缓存与并发
  需求的工作树，展示给用户会误导；用户关心的是「这个需求/会话的代码」。
- **与 RemoteDiffService 的关系**：互补不替代——本能力看「工作区未提交改动」，
  RemoteDiffService 看「分支与基线的累积差异」；会话页两者并存（本能力是实时面板，
  远程 diff 保留在收口入口旁）。

## 4. 插件化接口

- common：`AgentProtocol.WORKSPACE_VIEW`（v11）；`WorkspaceStatusListener` SPI
  （session/chat bridge 实现，ObjectProvider 广播）；`WorkspaceSnapshot` DTO（帧契约）。
- runner：`WorkspaceWatcher`（触发器挂 CLI 事件出口 + 兜底轮询调度）、
  `GitStatusCollector`（采集 + 哈希去重）、`WorkspaceQueryHandler`（tree/file/diff 应答）。
- 服务端：`WorkspaceViewCache`（sessionId→最新快照，内存）、`WorkspaceQueryService`
  （REST → 帧 → ack 透传）。

## 5. API 概要

| 端点/帧 | 方向 | 说明 |
|---|---|---|
| `workspace_status` | runner→服务端 | git 变更快照（FR-01/02） |
| `{type:"workspace", snapshot}` | 服务端→浏览器 | 复用 /ws/sessions/{id}、/ws/chats/{id} |
| `workspace_query` / `workspace_query_ack` | 双向 | tree/file/diff 请求应答（FR-03） |
| `GET /api/sessions/{id}/workspace/tree|file|diff` | REST | 会话工作区拉取 |
| `GET /api/chats/{id}/workspace/tree|file` | REST | 问答沙箱拉取（无 diff） |

## 6. 验收标准

1. repo 会话中 agent 用 Edit 改一个文件：~1s 内页面变更 Tab 出现该行（M 徽标 + 正确
   增删行数）；连续 10 次 Edit 只推 1~2 轮（去抖生效）；变更消失（agent 自己改回）行消失；
2. 点击变更行 → Drawer 显示与 `git diff` 一致的单文件 diff；staged/unstaged/untracked 分段正确；
3. 文件 Tab 懒加载展开目录、点文件看内容；超 256KB/二进制给提示；`../`、绝对路径、
  符号链接逃逸一律 400；
4. 会话进行中刷新页面：变更 Tab 立即显示最新快照（旁路缓存补发），不等下一次推送；
5. chat 问答会话：变更 Tab 显示「非 git 仓库」，文件 Tab 可浏览沙箱目录；MODEL 执行体
   问答无面板；
6. 老 runner（v10）：面板显示「版本过低」，REST 端点 409（非超时）；老前端 + 新服务端
   无异常（未知帧忽略）；
7. 多库会话：变更按库分组；会话终态+释放工作区后拉取端点 410、面板显示已释放；
8. 高频推送不丢帧：连续编辑风暴下 exit 帧/事件帧不受影响（复用 CAP-50 出口队列）。

## 7. 落地状态

- **M1 —— 需求定稿**：本文档。
- **M2 —— 全链路实现（2026-09-22）**：协议 v11 + `workspace_status` 旁路推送 +
  `workspace_query` 拉取（tree/file/diff/status）+ 服务端旁路缓存与 REST + 前端右栏面板
  （变更/文件双 tab，diff 与文件内容抽屉；MODEL 执行体无面板）。单测：runner 采集/查询
  纯函数面（porcelain/numstat/路径限定）、agent 帧链路面（组帧/ack 路由/v11 门控/断连清理）。
  E2E：`tests/cap54_e2e.py` 已实跑通过（fake runner + 独立 :8090 实例）——status/tree/file/diff、
  路径逃逸 409、问答沙箱非 git、终态经 recentDirs 可读。
