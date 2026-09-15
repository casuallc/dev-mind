# CAP-42 每用户固定工作区与手动收口（Per-user Workspace）

> 能力 ID：CAP-42 ｜ 分类：底座 ｜ 状态：**M1/M2 已实现（2026-09-15）** ｜ 日期：2026-09-15
> 重构 CAP-25/31 的 runner 侧代码会话工作区布局与生命周期；构建工作区（CAP-36）、问答沙箱（CAP-30）、worklog 空间（CAP-41）均不受影响。

## 1. 目的

CAP-25/31 的 runner 工作区是「每会话独立目录」：克隆缓存 `<workspaceRoot>/<projectId>/main`
共享，会话 worktree `<projectId>/sessions/<sessionId>` 随会话创建、结束即 push 并删除。
实际痛点：

- 每个会话/需求都新建目录，依赖产物（node_modules、.m2 缓存、构建中间产物）不沉淀，
  每个会话冷启动重新安装；
- 目录随 sessionId 漂移，用户想在节点上接着会话成果手工开发时找不到固定位置；
- 结束即 push+删除是「用完即弃」语义，与「在节点上持续开发一块固定地盘」的习惯不符。

本能力参照 CAP-41 worklog 持久空间的思路，把代码会话工作区改为**按 `{项目}/{界面登录用户}`
固定的持久 worktree + 手动收口**：

```
<workspaceRoot>/<projectId>/<username>/main          克隆缓存（每用户每库一份）
<workspaceRoot>/<projectId>/<username>/work          固定 worktree（claude cwd，单库）
<workspaceRoot>/<projectId>/<username>/<repo>/main   多库缓存（CAP-31）
<workspaceRoot>/<projectId>/<username>/work/<repo>   多库子 worktree（聚合根 work/ = cwd）
```

- **固定不删**：会话结束不再 push、不再删 worktree（仅上报未提交告警）；目录不参与 GC，
  依赖产物跨会话沉淀；`.runner-pid` 孤儿进程对账回收保留。pid 文件落在 worktree 根，
  runner 建 worktree 时把 `/.runner-pid` 写入克隆缓存 `info/exclude`（全 worktree 共享，
  幂等），防 agent「git add -A」把它提交进会话分支导致会话结束后工作区恒脏。
- **同 (项目, 用户) 唯一活跃工作区**：新会话 launch 时 worktree 已存在且检出分支不是
  `feature/<新sid>` → launch 失败报占用会话 sid，引导先收口；同 sid（resume）幂等复用。
- **手动收口**（页面触发，不自动执行）：合并会话分支到基线 → push 基线 + 顺带 push
  会话分支（供收口后 diff 查看）→ 删除 worktree。冲突/未提交改动失败透传、目录保留。

## 2. 功能需求

- **FR-01 每用户固定布局**：launch 帧新增 `workspaceOwner`（= 会话 createdBy，管控台登录
  用户名），runner 按 `<projectId>/<owner>/{main,work}` 组织克隆缓存与固定 worktree。
  owner 走既有 SAFE_ID 白名单（`[a-zA-Z0-9._-]+`）+ 越界防护；协议 v7，服务端
  `supports()` 门控，老 runner 409 提示升级；runner 侧 repo 会话缺 workspaceOwner
  直接报错（防老服务端静默落旧布局）——双向 fail-visible。
- **FR-02 占用互斥与 resume 幂等**：worktree 存在时 `rev-parse --abbrev-ref HEAD`：
  等于 `feature/<sid>` 复用（resume）；不等则失败并带占用分支名（可定位占用会话）。
  worktree 不存在时：本地分支在 → 挂回；本地无但 `origin/feature/<sid>` 在 → 从远端
  分支建本地分支挂回（老会话 resume 迁移到新缓存防分叉）；都没有 → 从基线 FETCH_HEAD 新建。
- **FR-03 结束降级为告警**：finalizer 不再 push、不再 `worktree remove`，仅
  `git status --porcelain` 非空时上报告警（引导收口前先提交或丢弃）。
- **FR-04 GC/对账适配**：固定目录 `<proj>/<owner>/{main,work}` 不在 `sessions/`、`_chat/`
  扫描桶下，天然不参与 GC（磁盘取舍见 §3）；Reconciler 新增第三类目录——扫
  `<proj>/<owner>/work` 的 `.runner-pid` 回收孤儿 claude 进程，但**不**登记 ownerless
  待删。存量旧布局 `sessions/<sid>` 目录继续按旧规则 GC/对账。
- **FR-05 手动收口（workspace_finalize）**：会话详情「更多 → 收口合并到基线」→
  `POST /api/sessions/{id}/finalize`（鉴权 = 会话创建者或管理员）→ 服务端下发
  `workspace_finalize` 帧（协议 v7，同步等 ack）→ runner 逐库执行：
  脏检查（`discardChanges=true` 时先 `reset --hard + clean -fd`）→ fetch 基线 →
  **临时 detached worktree 合并**（不动 main 缓存检出分支）`merge --no-ff feature/<sid>` →
  push 基线 → best-effort push 会话分支（供 diff）→ `worktree remove` + `branch -D`。
  合并冲突 `merge --abort` 并透传脱敏错误尾部，work/ 保留待人工/resume 处理。
  成功落 `sessions.workspace_state=FINALIZED`（新列，OPEN/FINALIZED，null=旧会话）。
- **FR-06 归属人解析（编排链路）**：无登录态的自动派发（CAP-15/17）工作区归属按
  **WI.ownerId → 需求.ownerId → WI.createdBy → 需求.createdBy** 回退链解析真实用户名，
  解析不到 409 fail-visible（不落 "local" 兜底，防多 WI 并发全撞 `<proj>/local/work`）。
- **FR-07 用户名字符约束**：`UserAdminService.create` 加 username 白名单
  `[a-zA-Z0-9._-]+` 校验，并排除保留名 `{main, sessions, builds, _chat, work}`
  （与 GC 扫描桶撞名风险）；存量非法用户名在会话 create/resume 前置 409 清晰报错。
- **FR-08 前端**：会话列表/看板加工作区状态 Tag（OPEN=占用中/FINALIZED=已收口）；
  「收口合并到基线」操作（Modal 说明 + 「丢弃未提交改动」勾选，注明只清脏文件不解
  合并冲突）；diff 提示文案引导先收口。全部在 `features/sessions` 内，projects 零改动。

## 3. 关键设计

- **合并放临时 detached worktree**（已定）：main 克隆缓存的检出分支是共享状态
  （并发 build worktree/其他操作会踩），绝不在缓存里 checkout 基线做合并；
  临时 `worktree add --detach .finalize-tmp FETCH_HEAD` 内合并 + `push HEAD:<base>`，
  finally 清理（remove + 递归删兜底 + prune）。
- **收口时顺带 push 会话分支**（已定）：不再自动 push 后 diff 链路（RemoteDiffService
  走远端分支）依赖分支在远端；finalize best-effort push `feature/<sid>` 使收口后
  仍可查看 diff。代价是远端多留 feature 分支。
- **冲突恢复路径**：合并冲突 → work/ 保留。恢复两条路：(a) resume 该会话让 agent
  `git rebase <baseBranch>` 解冲突并提交后再收口；(b) 节点上手工处理。
  `discardChanges` 只清未提交脏文件，不解提交级冲突。
- **磁盘成本取舍**：每用户每库一份全量克隆（换用户间零互踩与依赖沉淀）；构建缓存
  保持共享 `<proj>/main`（CAP-36 不动）。固定目录不 GC，超龄清理靠收口后目录自然
  消除 + 人工。
- **部署顺序约束**：runner v7 + 老服务端 → repo 会话 launch 明确报错；老 runner +
  新服务端 → create/resume 409 门控。双向 fail-visible，无静默降级。

## 4. 插件化接口

- 无新 SPI。`AgentNodeConnector` 增 `finalizeWorkspace(...)` default 方法（仿 pushWorklog）。

## 5. 数据模型与协议

```sql
sessions  ── + workspace_state VARCHAR(16) NULL   -- OPEN / FINALIZED（null=旧会话）
```

WS 协议 v7：launch 帧 +`workspaceOwner`；新下行帧 `workspace_finalize`
（requestId/sessionId/projectId/workspaceOwner/discardChanges/repos[]）与上行
`workspace_finalize_ack`（requestId/ok/detail|error）。

## 6. API 概要

```
POST /api/sessions/{id}/finalize   {discardChanges} → {ok, detail}   收口合并（创建者/admin）
GET  /api/sessions...              SessionView + workspaceState（按钮态/Tag）
```

## 7. 验收标准

1. 新会话在 runner 侧落 `<projectId>/<username>/{main,work}`；第二个同用户同项目会话
   launch 失败报占用分支；resume 同会话幂等复用；
2. 会话结束目录保留、远端无自动 push；未提交改动有告警事件；
3. 页面执行收口：远端基线含合并提交（--no-ff）、会话分支已推送、work 目录删除、
   workspace_state=FINALIZED；重复收口 409；
4. 未提交改动收口失败提示 discardChanges；合并冲突失败透传且 work/ 保留，可 resume
   解冲突后收口成功；
5. 老 runner（协议 < v7）派发会话 409 提示升级；固定目录超龄不被 GC；runner 重启
   孤儿进程照常回收且固定目录不登记待删；
6. 编排器自动派发落 WI/需求归属人目录；用户名非法的存量用户创建会话 409 清晰提示；
7. chat/worklog/构建链路零回归。

## 8. 分期

- **M1 布局切换**：CAP 文档 + 协议 v7 + workspaceOwner 全链路 + RunnerWorkspace 新布局/
  占用判定/finish 降级 + Reconciler 第三类目录 + 用户名白名单 + 编排归属人回退 + 测试。
- **M2 收口闭环**：workspace_finalize 帧 + 服务端端点/鉴权 + 前端按钮/Tag + diff 文案 +
  finalize 测试 + E2E。
