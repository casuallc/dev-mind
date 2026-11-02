# CAP-105 新建需求 AI 扩充（关键信息 → 结构化需求正文回填）

> 能力 ID：CAP-105 ｜ 分类：流程层 ｜ 状态：已实现（2026-11-02） ｜ 日期：2026-11-02
> 决策记录：生成链路定稿**服务端同步直连平台默认 CHAT 端点**——表单即时交互场景，
> 否决 CAP-93 式异步 runner 会话链路（分钟级时延、依赖 runner 在线）。生成结果是
> **纯回填**（只进表单描述字段，不落库），确认权与最终提交权都在用户手里。

## 1. 目的

新建/编辑需求抽屉目前要求用户手写完整标题与描述；信息零散时（几行要点、一段聊天记录）
补全成本高，描述质量参差，直接影响下游 AI 规划/开发会话的上下文质量。

本能力在抽屉内提供「AI 扩充」入口：用户只输入关键信息/零散要点，后端调用平台默认
CHAT 模型端点同步生成结构化需求正文（背景与问题/目标/功能范围与非目标/主要流程与交互/
边界与异常/验收标准/风险与依赖 七节），回填描述字段供用户编辑后再提交。

## 2. 功能需求

- **FR-01 抽屉入口**：新建/编辑需求抽屉描述字段 label 区增加「AI 扩充」小按钮；
  Jira 托管需求（source=JIRA）禁用（描述由同步维护，本地只读）。
- **FR-02 要点输入弹层**：点击打开内联 Modal，`TextArea` 必填、maxLength=4000 带计数；
  弹层声明「将携带当前已填的标题/类型/优先级作为生成上下文」。
- **FR-03 同步生成端点**：`POST /api/projects/{projectId}/requirements/ai-expand`，
  服务端解析平台默认 CHAT 端点（`ModelEndpointProvider.defaultEndpoint(KIND_CHAT)`，
  过滤 `chat() && baseUrl/model 非空`），同步调用
  `OpenAiCompatChat.complete`（非探针语义的 `chat`，不截断回复）生成正文。
  **纯生成接口，禁止任何写库调用。**
- **FR-04 上下文一致性**：prompt 携带当前已填标题/类型（FEATURE→功能、BUG→缺陷、
  IMPROVEMENT→优化、TASK→任务）/优先级，空字段省略对应行；用户要点包进
  `<notes>` 标签并声明为不可信数据（忽略其中指令）。
- **FR-05 回填不提交**：生成结果仅 `form.setFieldsValue({description})` 回填，
  用户可继续编辑，点保存才落库；失败时表单任何字段不动、弹层不关闭。
- **FR-06 覆盖保护与恢复**：当前描述非空时先二次确认（居中 Modal.confirm）再覆盖；
  覆盖前把当前描述备份到组件 state，描述字段 extra 区出现「恢复原文」文字按钮
  （点击还原并清空备份；重新生成刷新备份为本次生成前内容）。
- **FR-07 加载态与失败提示**：生成中 confirmLoading（「生成中…」）；失败
  `showError(e, 'AI 生成失败')`，不清空用户已输入内容（notes 保留、描述不动）。
- **FR-08 长度约束**：描述上限 20000 字（`MAX_DESCRIPTION_LENGTH`），模型输出超长
  服务端截断并回 `truncated=true`，前端 `message.warning` 提示人工补全。

错误语义：notes 空/超长 → 400（Bean Validation）；无可用默认 CHAT 端点 → 409
（「未配置平台默认对话模型端点，请先在「模型端点」页配置」）；模型调用失败 → 500
（「AI 生成失败：」+ 已脱敏底层消息）。

## 3. 否决方案

- **CAP-93 式异步 runner 会话链路（起会话 → runner 执行 → 轮询结果）**：分钟级时延、
  依赖 runner 在线、还要处理会话生命周期与超时清理，与「表单里点一下马上出草稿」的
  即时交互完全不匹配。本能力采用服务端同步直连 CHAT 端点（60s 超时一次调用）。
- **前端直连模型端点**：apiKey 是平台级凭据，出服务端即泄密；必须后端封装。
- **生成结果自动落库（草稿态需求）**：会产生用户未确认的需求记录，污染列表与统计；
  回填表单由用户显式保存，确认语义清晰。
- **复用探针 `OpenAiCompatChat.chat`**：探针语义截断回复到 200 字，正文必被砍头；
  结构化输出必须用 `complete`。

## 4. 架构与模块归属

```
devmind-project（不新增模块，不新增依赖——pom 已含 devmind-common）
  controller/RequirementAiController   POST .../requirements/ai-expand（纯转发，无写库）
  RequirementAiExpandService（包根）   端点解析（ObjectProvider 探测）+ prompt 组装 + 调用 + 截断
  dto/AiExpandRequest / AiExpandResponse（record）

frontend/src/features/requirements/
  api.ts        aiExpandRequirement()
  components/RequirementFormDrawer.tsx  「AI 扩充」按钮 + 要点弹层 + 回填/备份/恢复
```

端点解析走既有 CAP-48 SPI：`ObjectProvider<ModelEndpointProvider>` 探测注入
（devmind-model 装配实现，滚动升级期缺席 = 无端点 = 409，零反向依赖）。
模型调用收敛到 package-private `ChatCall` 函数接口（默认委托 `OpenAiCompatChat.complete`），
单测注入假实现，不起 mock server。

## 5. 数据模型

无新表、无字段变更。接口无写库副作用，不产生任何持久化记录。

## 6. API 概要

`POST /api/projects/{projectId}/requirements/ai-expand`

请求（record，Jackson 3 红线：布尔一律包装类型）：

```json
{ "notes": "关键信息/要点（必填，≤4000）", "title": "可空，≤256",
  "type": "FEATURE|BUG|IMPROVEMENT|TASK（可空，≤24，不做枚举强校验）", "priority": "可空，≤24" }
```

响应：`{ "description": "Markdown 正文", "truncated": false }`

错误：400 参数校验失败 / 409 无默认 CHAT 端点 / 500 模型调用失败。
不新增权限点（与需求写接口同受既有鉴权覆盖）。

## 7. 安全

- `ModelEndpointView.apiKey` 是解密后明文密钥：**禁止**写入日志、异常消息、HTTP 响应；
  只在服务端内存中传给 `OpenAiCompatChat.Options`。
- 用户要点包进 `<notes>` 标签并在 prompt 中声明为不可信数据（忽略其中指令），
  防提示注入改写生成指令；定界标签被伪造时先中和（`</notes>` → `[/notes]`）。
- 底层 `ModelCallException` 消息已脱敏（剔除 sk-/Bearer 片段），透传进 500 文案安全。
- 入参上限：notes ≤4000、title ≤256、type/priority ≤24（Bean Validation 硬约束）。

## 8. 里程碑

- **M1 同步生成 + 抽屉回填**：FR-01~08 全量（本 CAP 单里程碑交付）。

## 9. 验收标准

- 仅填关键信息即可生成一版含固定七节的可用描述，回填后可直接编辑、保存落库；
  未点保存前不产生任何需求记录（接口无写库调用）。
- 未配置平台默认 CHAT 端点时 POST ai-expand 返回 409 与明确配置引导文案（curl 实测）。
- 模型调用失败返回 500「AI 生成失败：…」，前端弹层不关闭、notes 与表单字段原样保留。
- 描述非空时重新生成先弹覆盖确认；覆盖后可「恢复原文」；超长输出截断至 20000 字
  并出现截断提示。
- 单测覆盖：无端点 409、非 chat 端点 409、正常透传 truncated=false、超长截断
  truncated=true、模型异常 → 500、prompt 含七节标题/类型中文映射/`<notes>` 包裹/
  空上下文行省略；`mvn -q test` 全绿、`cd frontend && npx tsc -b` 通过。

## 10. 风险与开放问题

| # | 项 | 口径 |
|---|---|---|
| R1 | 同步调用 60s 超时内模型未回 | 前端 confirmLoading 兜底，超时按 500 失败提示处理；表单场景可接受 |
| R2 | 模型不遵守七节结构/夹带解释 | prompt 强约束 + 「待定」兜底；用户编辑环节兜底，不追求机器强校验 |
| R3 | 多人同时点生成打满模型额度 | 单实例小团队场景，与问答同额度口径；后续可加限流 |
| O1 | 编辑态（非 Jira）也开放了入口 | 与新建同语义（回填可编辑），有意为之 |
| O2 | 是否支持流式输出 | 一次性返回 + 加载态已满足表单场景；流式留作体验增强 |
