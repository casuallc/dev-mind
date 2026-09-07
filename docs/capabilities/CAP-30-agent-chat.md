# CAP-30 通用问答（Agent Chat）

> 能力 ID：CAP-30 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-07

## 1. 目的

把「无关联纯问答」从 CAP-05 会话管理里拆出来成为一等能力。CAP-05 的模型是项目中心的
（worktree/diff/分支 push 生命周期），问答此前只是 `projectId` 可空的隐式补丁（代码注释自称
"裸跑/fake 模式"），导致问答会话误建 worktree、cwd 落到 runner/服务端安装目录、UI 字段互相
干扰。本能力为问答建独立的表、REST/WS 端点与前端入口（「个人」菜单组），与项目会话
（「当前项目」组）从底层到交互完全分开。

**共享不复制**：headless claude 进程托管内核（状态机/事件流/CLI 协议解析/远程运行时）上移到
`devmind-common` 的 `agent.runtime` 包，session 与 chat 两能力复用同一内核，各自只有
实体/落库/REST/WS 薄壳。runner（瘦 jar）改依赖 devmind-common（原为复用 CLI 接触点而依赖
devmind-session， exclusions 一堆，依赖关系本就不干净）。

## 2. 功能需求

- **FR-01 起问答**：入参 `{message, model?, permissionMode?, agentNodeId?}`；`title` = 首条消息
  前 30 字符（去换行）；首条消息即初始 prompt 写入 claude stdin（stream-json 协议）。
- **FR-02 节点路由**：显式 `agentNodeId` > 平台默认节点（`agent_nodes.is_default`）> 本机；
  保留值 `"local"` = 强制本机。无项目默认层（问答无项目）。节点离线 launch 409，不静默回落。
- **FR-03 干净沙箱 cwd**：
  - 本地：`<devmind.chat.work-dir>/<chatId>`（默认 `${user.dir}/data/chats/<chatId>`）空目录，
    会话结束（DONE/FAILED/TERMINATED）后 best-effort 递归删除，删除会话时再兜底；
  - 远程：launch 帧带 `kind:"chat"`，runner 用 `<workspaceRoot>/_chat/<chatId>`（幂等创建，
    resume 复用），进程退出 finalizer 递归删除。问答无 clone/push 语义。
- **FR-04 状态机与交互**：复用共享内核——RUNNING/WAITING_INPUT/WAITING_AUTH/DONE/FAILED/
  SUSPENDED/TERMINATED；stdin 注入、授权（permission_request 中转）、挂起/恢复、优雅结束
  （关 stdin）、强杀；空闲超时自动结束。
- **FR-05 事件流**：`chat_events` 表批量落库（复刻 SessionEventSaver 模式）；WS
  `/ws/chats/{id}` 帧协议与 `/ws/sessions/{id}` 完全一致（snapshot/event/error/pong +
  input/authorize/ping 上行），前端共享同一套流组件。
- **FR-06 列表/删除**：按创建人 + 时间倒序；删除 = 杀进程（若在跑）+ 清沙箱目录 + 删事件与记录。
- **FR-07 启动恢复**：服务重启后遗留 RUNNING/WAITING_* → TERMINATED（同 CAP-05 口径）。

## 3. 插件化接口

- `RuntimeEventSink`（devmind-common）：事件出口 SPI，chat 实现 `ChatEventSaver` 落 chat_events。
- 远程通道复用 CAP-21 `AgentNodeConnector` / `AgentLaunchCommand`（新增 `kind` 字段，
  `"session"` 缺省 / `"chat"`）。
- `AgentEventListener` 改为**广播**（原单实现 `getIfAvailable()`，chat 加入会抛
  NoUniqueBeanDefinitionException）：session 与 chat 各自 Bridge 按「是否持有该 sessionId
  的运行时」自行忽略未命中帧。

## 4. 协议与兼容性

launch 帧新增 `kind` 字段：

| 组合 | 行为 |
|---|---|
| 新服务端 → 新 runner | `kind:"chat"` → `_chat/<sid>` 沙箱；`kind:"session"`/缺省 → 现状 |
| 旧服务端 → 新 runner | 无 kind，按 repo/projectId 判定，行为不变 |
| 新服务端（chat）→ 旧 runner | kind 被忽略，无 repo 无 projectId → 落兜底 workDir（默认 `.`，即 runner 安装目录）。功能可用但目录不干净——**chat 远程要求 runner ≥ 本期版本**，节点列表已展示 runner 版本供人工核对，不强制拦截 |

## 5. 数据模型

```
chat_sessions(id, title, status, agent_node_id NULL=本机, pid, model,
              permission_mode, summary, created_by, created_at, updated_at, finished_at)
chat_events(id 自增, chat_id, seq, type, content CLOB, source, payload CLOB, created_at)
            索引 idx_chat_seq(chat_id, seq)
```

无 project_id / worktree_path / base_branch 列——问答与项目资产零耦合。

## 6. API 概要

```
POST   /api/chats                  起问答 {message, model?, permissionMode?, agentNodeId?}
GET    /api/chats?status=          列表（当前用户，时间倒序）
GET    /api/chats/{id}             详情
GET    /api/chats/{id}/events?afterSeq=   事件回放
POST   /api/chats/{id}/input | /authorize | /suspend | /resume | /kill | /finish
DELETE /api/chats/{id}             删除（杀进程+清目录+删记录）
WS     /ws/chats/{id}              实时事件流（协议同 /ws/sessions/{id}）
```

无 diff / worktree / template 端点。

## 7. 前端

- `features/chat/` 自包含（api/types/pages），页面 = 左列表右对话（复用 `src/shared/chat/`
  的 ChatPanel/useChatStream/ChatStream——由 sessions 上移并 apiBase 参数化）。
- 菜单：「个人」组加「AI 问答」`/chats`，路由挂 ProjectContextGate 外（与 /worklog 同级）。
- 新建问答：输入框即建会话，高级选项只留 执行节点/模型/权限模式。

## 8. 依赖关系

- 依赖：CAP-01（鉴权/createdBy）、CAP-06（等待输入/授权通知）、CAP-21（远程节点通道）。
- 被依赖：暂无；CAP-28 one-shot 总结会话（当前借道 sessions 表 projectId=null）未来应迁移到
  chat 通道（本期不动，TODO）。

## 9. 验收标准

- 「个人 → AI 问答」发起问答，浏览器实时看到输出流，可多轮对话、授权、挂起/恢复、结束；
- 远程问答在 runner 机 `<workspaceRoot>/_chat/<sid>` 跑，结束后目录被清理；
- 本地问答 cwd 为 `data/chats/<sid>` 空目录，不出现落在服务端安装目录的情况；
- 删除问答后事件与记录清除、目录无残留；
- 与项目会话并存互不影响（两套表/端点/WS/页面）。

## 10. 暂不做

问答挂知识库检索、问答模板、对话导出/分享、多轮上下文压缩、one-shot（CAP-28）迁移。
