# CAP-05 Agent 会话管理（Session Manager）

> 能力 ID：CAP-05 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

**核心底座能力**。替代 PowerShell 多开：起、管、收 headless Agent 会话，git worktree 隔离，看板实时监控，stdin 交互与实时输出流。上层（Orchestrator）并发调度多个会话即在此之上实现。

## 2. 功能需求

- **FR-01 起会话**：入参 `(projectId, taskSpec, branch?)` →
  1. 创建 git worktree（`<project>/.devmind/worktrees/<sessionId>`，基于指定分支）；
  2. 调用 CAP-04 注入器组装 CLAUDE.md 写入 worktree；
  3. 拉起 headless Agent 进程（Claude Code `claude -p` / Agent SDK），接管 stdin/stdout/stderr 管道。
- **FR-02 会话状态机**：`RUNNING / WAITING_INPUT / WAITING_AUTH / DONE / FAILED / SUSPENDED`。
  - `WAITING_INPUT`（agent 在等回复）、`WAITING_AUTH`（agent 在等授权）是**最需要人关注的状态**，看板置顶 + 触发 P0 通知（CAP-06）。
- **FR-03 实时输出**：stdout/stderr 增量流经 WebSocket 推前端（终端式展示）。
- **FR-04 交互**：向会话 stdin 发送消息；支持快捷回复（"继续"/"是"/"按方案 A"）；可注入授权（自动允许指定工具权限）。
- **FR-05 worktree 隔离与回收**：会话完成→看板展示 diff→可发起合并；删除会话时清理 worktree。
- **FR-06 生命周期管理**：空闲超时自动挂起；一键强杀；进程退出码监控；异常进程回收。
- **FR-07 会话列表/详情**：看板（项目维度/任务维度分组），每会话卡片含状态、耗时、最近输出摘要、快捷操作。
- **FR-08 会话元数据**：关联 taskId（供 Orchestrator 使用），记录 token/耗时统计。

## 3. 插件化接口

- Agent Runner SPI：`AgentRunner`，默认实现=Claude Code headless；可扩展其它 Agent 后端。
- 会话执行器 SPI：`SessionExecutor`（起进程/管道/信号），默认=本机子进程；预留远程执行扩展。

## 4. 依赖关系

- 依赖：CAP-01（鉴权）、CAP-02（项目/worktree）、CAP-04（知识注入）、CAP-06（等待类事件通知）。
- 被依赖：任务编排 Orchestrator（流程层）、需求对话面板。

## 5. 数据模型

```
sessions(id, project_id, task_id?, agent_kind, status,
         branch, worktree_path, pid, waiting_reason?,
         started_at, updated_at, finished_at, token_cost, summary)
session_events(id, session_id, type[output|state|input], payload, created_at)
```

## 6. API 概要

```
POST   /sessions                      起会话 {projectId, taskSpec, branch?}
GET    /sessions?projectId=&taskId=&status=   看板列表
GET    /sessions/{id}                 详情（含摘要）
WS     /sessions/{id}/stream          实时输出 + 状态事件
POST   /sessions/{id}/input           发送输入 {text} | {quickReply}
POST   /sessions/{id}/authorize       授权（允许 pending 工具权限）
POST   /sessions/{id}/suspend | /resume | /kill
GET    /sessions/{id}/diff            完成后的代码 diff 摘要
POST   /sessions/{id}/merge           合并 worktree 到主分支（可选，含冲突提示）
```

## 7. 验收标准

- 对一个本地项目起会话，能在浏览器实时看到输出流；
- agent 等待输入/授权时会话状态正确翻转并触发 P0 通知，可远程回复；
- 同项目两个会话使用不同 worktree，互不影响；
- 会话完成/杀掉后 worktree 正确清理，无进程泄漏。

## 8. MVP 范围（暂不做）

会话录制/回放、多 Agent 后端切换 UI、远程 Agent 执行。
