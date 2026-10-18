# CAP-77 平台自举反馈闭环（/issue 快捷登记）

> 能力 ID：CAP-77 ｜ 分类：组装层 ｜ 状态：设计定稿 ｜ 日期：2026-10-19
> 背景：Dev-Mind 在自用过程中不断暴露问题与优化点（如「飞书查工作日志恒空」），
> 这些信息目前散落在飞书聊天/个人记忆里，无法自然沉淀成可跟踪、可派发、可验收的工作项。
> 本能力把 **Dev-Mind 自己**注册为平台上的普通项目，并补一个**不依赖当前聊天绑定**的
> 快捷登记入口，形成「用 → 记 → 修 → 发 → 复验」的自举闭环。

## 1. 目的

- **记录零摩擦**：在任意飞书会话（群/私聊）里 `/issue <描述>`，直接落成自项目的
  需求记录（BUG/IMPROVEMENT/FEATURE/TASK），不要求该聊天先 `/bind` 到自项目。
- **信息可追溯**：自动附带入站渠道、会话 id、发送者 id、登记人、登记时间，
  即使当时描述很粗糙，事后也能回查上下文。
- **修复可派发**：回执直接给出 `REQ-<seq>` 与 `/dev REQ-<seq>` 用法，复用 CAP-34
  runner 会话在 worktree 里改代码，进展自动推回聊天。
- **口径一致**：登记走 `RequirementService.create`，权限、状态机、序号分配与平台
  页面创建完全同源，不新增第二套存储。

## 2. 功能需求

### M1 自项目与快捷登记

- **FR-01 自项目配置**：`devmind.bot.self-project-id` 指定「平台自身」项目 id
  （部署时指向 194 上的 `dev-mind` 项目）。配置为空 = 能力未启用，`/issue` 明确
  提示如何配置，不猜项目。
- **FR-02 `/issue [类型] <描述>`**：在任意聊天登记一条自项目需求。
  - 类型前缀可选：`bug`/`缺陷` → BUG；`优化`/`改进`/`improvement` → IMPROVEMENT；
    `需求`/`feature` → FEATURE；`任务`/`task` → TASK；缺省 = BUG。
  - 描述首行（截断 120 字）作为需求标题，全文作为描述正文。
  - 优先级缺省 `Medium`，标签固定附 `bot-feedback`。
- **FR-03 来源元信息**：描述正文尾部自动附：
  `来源渠道 / 会话 id / 发送者 id / 登记人（actor）/ 登记时间`，便于从一行聊天
  还原现场；不写入任何凭证。
- **FR-04 回执**：成功回 `已登记 <类型> REQ-<seq>：<标题>` + 项目名 +
  `继续：/dev REQ-<seq> [补充说明]`；失败（未配置/项目不存在/平台拒绝）走
  现有 fail-visible 文案（`操作失败：…`），不静默。
- **FR-05 不改变 `/bind` 语义**：聊天绑定的项目仍是讨论/查询上下文；`/issue`
  只认 `self-project-id`，避免在客户项目群里误把平台缺陷记到客户项目。

### M2 门禁与运维口径

- **FR-06 身份门禁**：`/issue` 属敏感指令，与 `/reqs` `/dev` 同口径——支持身份映射
  的渠道（飞书）要求先 `/bindme` 绑定个人身份，未绑定回既有绑定指引并落 REJECTED；
  写操作以当前 actor 身份执行。
- **FR-07 交互日志**：沿用 `bot_interactions`，command=`issue`，OK/FAILED/REJECTED
  与其它指令一致；钉住「谁在哪个群登记了什么」。

## 3. 非目标（本期不做）

- **自动更新 194**：平台自更新需要由 JVM 之外执行（systemd / 宿主机脚本 / runner
  节点），且 CAP-76 v1 明确未开放 deploy/rollback 写工具；留待后续能力（候选：
  `deploy_platform` DANGEROUS + 短码确认 + 目标白名单，执行者走宿主机 sidecar 或
  runner SSH 调 `scripts/deploy-194.sh`）。
- **历史消息自动转需求**：`bot_interactions` 已全量落库，后续可做批量转换/标注，
  本期先保证「看到问题顺手 /issue」。
- **前端新页面**：需求在既有研发主线页面可见，`bot-feedback` 标签可用于筛选。

## 4. 使用闭环（目标形态）

1. 一次性：194 建 `dev-mind` 项目（仓库 `git@github.com:casuallc/dev-mind.git`，
   默认节点 `kingdee-win-194`），配置 `devmind.bot.self-project-id`。
2. 记录：飞书任意会话 `/issue bug 飞书查本周工作日志恒为空，194 有数据`
   → 回 `已登记 BUG REQ-1：…`。
3. 修复：`/dev REQ-1 根因在工具执行线程丢了身份上下文`
   → runner 在 worktree 起会话，结束时进展推回。
4. 发布：`trigger_build` 看构建，宿主机 `scripts/deploy-194.sh` 自更新。
5. 复验：部署后群里再问一次原问题，确认修复；`/flow REQ-1 完结` 收口。

## 5. 验收

- 未配置 `self-project-id` 时 `/issue` 回配置指引，不产生任何需求行。
- 配置后 `/issue bug xxx` 在自项目生成 BUG 需求，标题=首行、描述含来源元信息，
  `REQ-<seq>` 与页面展示一致。
- `/issue 优化 yyy` 生成 IMPROVEMENT；无类型前缀生成 BUG。
- 飞书未绑定个人身份时 `/issue` 被门禁拦截并落 REJECTED；绑定后登记人=个人身份。
- 聊天已绑定其它项目时，`/issue` 仍落自项目（不受绑定影响）。

## 6. 依赖与影响

- 依赖 CAP-75（bot 底座/四级身份链/交互日志）、CAP-76（敏感指令门禁与确认口径）、
  CAP-13（需求主线与 `REQ-<seq>`）、CAP-34（runner 会话修复链路）。
- 仅修改 `devmind-bot`：新增 `BotProperties.selfProjectId`、`/issue` 路由与帮助文案、
  单元测试；不改 API、不改表结构。
- 部署侧需在 194 外置配置（`config/application-local.yml`）增加
  `devmind.bot.self-project-id: <dev-mind 项目 id>`。
