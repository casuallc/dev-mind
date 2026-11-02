# CAP-105 需求 AI 扩展（关键点 → 表单草稿）

> 能力 ID：CAP-105 ｜ 分类：流程层 ｜ 状态：已实现 ｜ 日期：2026-11-02
> 决策记录：
> 1. **复用 CAP-28 `OneShotAgentRunner` 同步一次性会话**，不走 CAP-93 的异步场景会话 + 草稿表：
>    本能力的产物直接落在打开中的新建/编辑表单里（不落库、无确认流），同步等待一次模型往返
>    （fake ~秒级、真机通常 30~90s）比「触发→轮询→回填」链路简单一个量级，且阻塞的只是发起
>    用户的这一次请求；执行痕迹仍留在会话列表（sessionId 随响应返回可回溯）。
> 2. **产物契约 = 单一 JSON 对象**（title/type/description），解析失败即 fail-visible 报错，
>    不做「拿正文片段凑合填」的静默降级——填进表单的必须是结构化可信的内容。
> 3. **AI 只起草稿作用**：结果只回填表单字段（标题/类型/描述），需求仍由人检查后点保存才落库；
>    Jira 托管需求不提供入口（托管字段本地只读）。
> 4. **节点路由沿用会话层既有链路**（显式 → 项目默认 → 平台默认 → 409 不回落），one-shot 无
>    projectId 语境，实际走平台默认节点；无可用节点/节点离线按既有 409 语义透出。

## 1. 目的

新建需求时，标题与「描述（业务目标）」是后续 CAP-52/99 规划会话的直接输入，写得太薄会
连带拉低分析与拆单质量；但现场往往只有几句口头关键点。本能力让用户在新建/编辑需求抽屉里
粘贴关键点，一键让 AI 扩展成结构化草稿（标题 + 类型 + Markdown 描述）回填进表单，人工
修改后再保存——把「写需求」从空白页写作变成审稿，与 CAP-93 的 OKR 草稿同哲学但更轻量。

## 2. 功能需求

### M1 后端：同步扩展端点

- **FR-01 扩展端点**：`POST /api/projects/{projectId}/requirements/ai-expand`，
  请求 `{points, title?, description?}`（points 必填 ≤2000 字；title/description 为表单
  当前值，仅作上下文让 AI 在其基础上改，不是必须）。同步阻塞至 one-shot 会话结束，
  响应 `{title, type, description, sessionId}`。
- **FR-02 prompt 组装**：服务端组 prompt（模型不查库、不读文件）：项目名 + 表单现有
  标题/描述（有则）+ 用户关键点；明确「不读写任何文件，只输出一个 JSON 对象」与字段契约
  （type 四选一 FEATURE/BUG/IMPROVEMENT/TASK；description 为 Markdown，含背景与目标/
  需求描述/验收标准小节；不得编造具体数据指标）。
- **FR-03 结果解析**：从 one-shot summary 提取 JSON（容忍首尾散文与 ```json 围栏，取首个
  平衡大括号对象）；title/description 非空校验、title 截 256、type 不在词表置 null
  （前端保留原值）；解析失败 500 并附 summary 片段，便于排查模型跑偏。
- **FR-04 可用性与超时**：`OneShotAgentRunner` 未装配（devmind-session 不在依赖图）→ 409
  「AI 执行体不可用」；节点路由失败沿用会话层 409；超时（默认 120s，配置项
  `devmind.project.ai-expand.timeout-seconds`）杀会话并抛错，错误原文透传给前端。

### M2 前端：表单内 AI 扩展入口

- **FR-05 入口**：新建/编辑需求抽屉（Jira 托管除外）顶部加「AI 扩展」按钮 → 弹窗一个
  多行输入框填关键点（必填），确认后按钮 loading 等待同步返回。
- **FR-06 回填**：成功后把 title/type/description 写入表单字段（覆盖式，用户继续改）；
  type 为空（词表外）时保留表单原值；失败弹窗不关、错误原文透出，可修正关键点重试。

## 3. 否决方案

- **异步场景会话 + 草稿表 + 轮询回填（CAP-93 同构）**：否决——产物生命周期就是「这次表单
  编辑」，不需要持久草稿与确认流；引入草稿表/通知/轮询对一个表单填充动作过重。
- **前端直调模型端点（CAP-48 CHAT 端点）**：否决——绕过会话层丢掉节点路由、执行痕迹与
  统一审计，且把 prompt 组装散到前端。
- **AI 直接创建需求**：否决——与全平台「AI 产物人工确认才落库」红线一致（CAP-90/93 同口径）。

## 4. 架构与模块归属

| 模块 | 改造责任 |
|---|---|
| devmind-project | `RequirementAiExpandService`（prompt 组装 + `ObjectProvider<OneShotAgentRunner>` 探测注入 + 解析）+ `RequirementController` 端点 + DTO |
| devmind-common | fake-agent.js 增加测试钩子 `__FAKE_EOF_RESULT__=`（stdin 文本中该指令行覆盖 EOF 时的 result 正文），供 E2E 驱动 one-shot 产出契约 JSON |
| frontend requirements | `RequirementFormDrawer` 加 AI 扩展弹窗 + api.ts 封装 |

不新增表、不新增会话类型；one-shot 会话本身就是执行痕迹。

## 5. 数据模型

无（不落地任何持久状态）。

## 6. API 概要

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/projects/{pid}/requirements/ai-expand` | 同步 AI 扩展：`{points, title?, description?}` → `{title, type, description, sessionId}`；项目不存在 404、points 空 400、执行体不可用/节点离线 409、解析失败/超时 500 |

## 7. 安全

端点走 `/api/projects/**` 既有认证与项目归属校验（requireProject）；关键点长度上限 2000
防 prompt 膨胀；prompt 明确只读、one-shot 会话默认 plan 权限模式（不授权写操作）；
CLI 凭据不出节点，不进入 prompt 与日志；sessionId 仅作追溯，不暴露 runner 内部信息。

## 8. 里程碑

- **M1 后端**（FR-01~FR-04）：端点 + prompt + 解析 + 单测。✅ 2026-11-02
- **M2 前端**（FR-05/FR-06）：抽屉入口 + 回填 + E2E。✅ 2026-11-02

## 9. 验收标准

- M1：`RequirementAiExpandServiceTest`——prompt 含项目名/关键点/既有草稿上下文；裸 JSON /
  ```json 围栏 / 首尾散文三形态解析；title 超 256 截断；type 词表外置 null；缺 title 或
  description 报错；summary 非 JSON 报解析错误；执行体未装配 409。
- M2：`tests/e2e` 用例 `regression.requirement-ai-expand`（隔离实例 + fake runner 默认节点）：
  fake 经 `__FAKE_EOF_RESULT__` 产出契约 JSON → 端点 200 且三字段逐字对账、sessionId 可查
  会话；points 空白 400；项目不存在 404；`npx tsc -b` 通过。

## 10. 风险与开放问题

| 编号 | 问题 | 口径 |
|---|---|---|
| R1 | 同步阻塞受反向代理读超时影响（如 nginx 默认 60s） | 默认超时 120s 可下调；真机模型往返通常 <90s；被代理截断时前端按失败透出，可重试 |
| R2 | 模型不遵守 JSON 契约 | 解析 fail-visible 报错附 summary 片段；prompt 已强约束，实测漂移再迭代 |
| O1 | 扩展优先级/标签等更多字段 | 后置：先验证 title/type/description 三字段的可用性 |
| O2 | 流式回填（边生成边显示） | 后置：one-shot 是同步收口语义，流式需换会话事件通道，收益不足以抵消复杂度 |
