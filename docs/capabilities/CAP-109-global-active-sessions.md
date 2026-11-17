# CAP-109 全局活跃会话页

> 能力 ID：CAP-109 ｜ 分类：会话 ｜ 状态：已实现（2026-11-17）｜ 日期：2026-11-17
>
> 决策记录：
> - 全局列表走 `GET /api/sessions` 新增 `statuses`（CSV 多值）过滤，不为全局页新开专用端点——会话清单的唯一出口收敛在 SessionController，ETag 指纹逻辑原样复用。
> - 顶栏菜单复用 CAP-103 目录 + CAP-108 `seedFromKeys`：动作 `top.sessions.action.manage` 默认授权派生自 `project.sessions.action.manage`，不再另写种子授权。
> - 页面不做跨项目需求/工作单元引用解析（`useSessionRefs` 是单项目的），引用标签退化为原始 id；进入项目上下文用「在项目工作台打开」跳转收口。

## 1. 目的

多项目并行时，活跃会话分散在各项目的会话页里，用户要逐个点进项目才能看到「哪些会话在跑 / 在等人」。本能力提供一个**跨项目的全局活跃会话视图**：顶栏一级入口列出所有项目的活跃会话，选中后即可就地对话、结束、挂起、恢复、终止、删除，无需先切换到对应项目。

## 2. 功能需求

### M1（本次交付）

- **FR-01 顶栏入口**：顶部导航在「工作台」「项目」与「AI 问答」之间新增「活跃会话」（icon robot，排序 25），指向 `/active-sessions`；路由注册在项目上下文门（ProjectContextGate）之外，不依赖当前项目。
- **FR-02 多状态过滤接口**：`GET /api/sessions` 新增可选参数 `statuses`（CSV，如 `RUNNING,WAITING_INPUT`）。与既有参数的组合规则：
  - `statuses` 非空且未指定 workItemId/requirementId 时走多状态过滤；
  - 同时带 `keyword`：沿用 `search(null, kw)` 结果做内存状态过滤；
  - 同时带 `projectId`：沿用项目清单查询后内存过滤；
  - `statuses` 与 `status` 同时出现时 `statuses` 优先（`status` 被忽略）。
- **FR-03 全局列表**：左栏列出命中状态的会话（创建时间倒序），行内含状态 Tag、等待人输入徽标、任务摘要预览、所属项目名 Tag、执行节点、创建时间与用量；选中高亮。状态范围分段切换：「活跃（默认 RUNNING/WAITING_INPUT/WAITING_AUTH）/ 含挂起 / 全部」。
- **FR-04 轮询**：沿用项目会话页的 ETag 条件轮询——有活跃会话 3s、全终结 15s、页签隐藏暂停、回前台立即补拉。
- **FR-05 就地操作**：右侧操作条 + ChatPanel，支持发送消息、结束、挂起、恢复·继续对话、终止、删除（删除沿用 CAP-42 固定工作区释放警示文案）；动作按钮受 `top.sessions.action.manage` 授权控制，无权限账号只读（对话流可见，按钮隐藏）。不提供新建会话入口。
- **FR-06 深链与跳转**：支持 `?sid=` 深链定位会话；操作条提供「在项目工作台打开」——切当前项目并跳转 `/sessions?sid=`。
- **FR-07 首页联动**：首页「进行中的会话」卡片列表项点击跳 `/active-sessions?sid=`，「全部」链接跳 `/active-sessions`。

## 3. 否决方案

- **前端聚合 N 个项目各自拉会话再合并**：N 次请求 + N 套 ETag，分页/排序口径混乱；否决，改为后端一次多状态查询。
- **新开 `/api/sessions/active` 专用端点**：清单出口分裂、ETag 逻辑复制两份；否决，在既有端点加 `statuses` 参数。
- **全局页内解析需求/工作单元引用**：需跨项目批量引用解析接口，收益低（全局页重点是状态与操作）；否决，标签退化为原始 id，跳转项目工作台看完整上下文。

## 4. 架构与模块归属

| 层 | 位置 | 内容 |
|----|------|------|
| 后端 | devmind-session | `SessionController.list` 透传 `statuses`；`SessionManagerService.list` 多状态分支；`SessionRepository.findByStatusInOrderByCreatedAtDesc` |
| 后端 | devmind-auth | `MenuCatalog` 注册 `top.sessions` 菜单 + `manage` 动作（seedFromKeys 派生默认授权）；`ApiPermissionCatalog` 会话操作规则追加 `top.sessions.action.manage` |
| 前端 | features/sessions | `api.ts` 增 `statuses` 参数；新页面 `pages/ActiveSessionsPage.tsx`（自包含） |
| 前端 | src/app | `App.tsx` 注册路由（ProjectContextGate 外）；`menuSelectedKey.ts` 选中态前缀 |
| 前端 | features/home | 首页进行中会话卡片跳转改造 |

## 5. 数据模型

无新增表/字段。多状态过滤仅使用 `sessions.status` 既有列。

## 6. API 概要

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/sessions?statuses=RUNNING,WAITING_INPUT` | 新增可选 `statuses`（CSV）；与 status/projectId/keyword 的组合规则见 FR-02；ETag 语义不变 |

会话操作接口（input/authorize/finish/suspend/resume/kill/terminal/DELETE）路径不变，仅 OR 权限集合追加 `top.sessions.action.manage`。

## 7. 安全

- 菜单/动作注册走 CAP-103 目录静态校验（编码不存在即启动失败）。
- 默认授权由 CAP-108 `seedFromKeys` 机制从 `project.sessions.action.manage` 派生，与项目内会话管理权限口径一致。
- 创建会话（POST /api/sessions）与 finalize/outputs/worktree 等规则不追加新权限码，维持项目内口径。
- 无 `manage` 权限的账号：前端隐藏操作按钮只读展示；后端规则保证越权调用仍被 403。

## 8. 里程碑

- **M1（2026-11-17 已交付）**：FR-01 ~ FR-07 全量。

## 9. 验收标准

1. `mvn -q test` 通过；devmind-session 新增单测覆盖：多状态过滤、statuses+keyword 组合、statuses 优先于 status。
2. `cd frontend && npx tsc -b` 通过。
3. E2E（tests/e2e 正式用例并登记 catalog.json）：隔离环境起 app + fake runner，两个项目各建一个会话，断言 `GET /api/sessions?statuses=RUNNING,WAITING_INPUT,WAITING_AUTH` 同时含两者且不含终结态；对其中一个 suspend→resume 状态翻转。
4. 手工冒烟：普通角色登录 → 顶栏出现「活跃会话」→ 列表同时展示两个项目的会话 → 选中后可发消息/挂起/恢复/终止 → 首页卡片点击带 sid 深链 → 无 manage 权限账号按钮隐藏、只读。

## 10. 风险与开放问题

- 全局列表不做需求/工作单元引用解析，标签退化为原始 id——若后续需要，另立跨项目引用批量解析接口。
- 「全部」分段在多项目大盘下可能返回较长清单，暂无分页（与项目会话页同口径，依赖 ETag 304 控制传输量）。
- 数据权限（CAP-62）生效后，全局列表的项目可见性过滤需在 `SessionManagerService.list` 多状态分支同步收口，属后续授权收敛工作。
