# CAP-49 问答模型执行体（通用问答直连已接入模型）

> 能力 ID：CAP-49 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-21
> 缘起：CAP-34 之后服务端零执行，`/chats` 的每一次问答都必须下发到 runner 节点、由节点上的 claude CLI 产出——
> **必须有一个在线节点**（无 runner 创建 409），**回答能力绑定 claude**（工具/文件是它的长处，纯问答用不上）。
> 与此同时 CAP-48 FR-11 已把 `kind=CHAT` 的通用模型端点做成一等资源（密文凭据 + 实测连接测试），
> 并写明「消费方另立 CAP，届时在 SPI 上补 `defaultEndpoint(kind)`」——本能力就是那个消费方。

## 1. 目的

通用问答（CAP-30）在新建时可选**执行体**：

| 执行体 | 实现 | 依赖 | 能力面 |
|---|---|---|---|
| `AGENT`（默认，现状） | 下发 runner 节点上的 claude CLI | 需在线节点 | 工具/文件/工作区（现状不变） |
| `MODEL`（新增） | 服务端直连一个已接入的 `kind=CHAT` 端点（OpenAI 兼容 `/chat/completions`） | **零节点依赖** | 纯文本问答，流式（打字机），可中断 |

**架构定性（必须写清，否则会被误读成倒退）**：这是 CAP-34「服务端零执行」的**收敛性例外**，不是"本机会话"的
复活——服务端不拉起任何子进程，只发出站 HTTP 到运维登记的端点。因此：

- `devmind-session`（项目开发会话）**不接入**：工具链/工作区/ContextPackage/产出回传对模型执行体无意义；
- 场景（CAP-33）、权限模式、执行节点对模型执行体**无意义**（见 FR-02 的冲突字段拒绝）；
- 图片附件（CAP-32）对模型执行体**不支持**（多数文本模型不吃图，且附件链路是"下发 runner 物化"的语义）。

**额外收益（CLI 路径做不到的）**：模型会话**没有内存态**——多轮上下文每轮从 `chat_events` 重建
（FR-04），因此服务端重启后仍可继续提问；CLI 会话历史在 runner 侧进程里，进程没了就没了。

## 2. 功能需求

### FR-01 执行体维度与会话级选择

- `chat_sessions` 增 `executor VARCHAR(16)`（可空，**null = AGENT**）与 `model_endpoint_id BIGINT?`（仅 MODEL 有值）。
  加列走 `ddl-auto=update`，**不写迁移脚本**（与仓库既有约定一致），且**不加 `@ColumnDefault`**：
  沿用 CAP-33/46 加列即可空的先例，可空 + getter 兜底同时绕开「NOT NULL DEFAULT 在 H2/PG/MySQL 加列」
  与「`@ColumnDefault` 字符串必须带引号」两个坑。
- 执行体**创建时定死，不支持会话内切换**（切换会让历史消息的执行体语义混杂，收益不抵复杂度）。
- `MODEL` 会话的 `agent_node_id` 恒空（不进节点路由）。
- `MODEL` 会话存**解析后的具体端点 id**，不存"跟随平台默认"：默认端点日后被换掉时，历史会话的模型身份
  不该漂移（可复现、可审计）。
- `CreateChatRequest` 增 `executor` / `modelEndpointId`（包装类型可空）；`ChatView` 增
  `executor` / `modelEndpointId` / `modelEndpointName`（端点名）/ `modelEndpointModel`（模型名）。

### FR-02 端点解析链与冲突字段拒绝（创建时）

解析链（顺序固定，**不回落 mock、不静默换端点**）：

```
显式 modelEndpointId（须 active 且 kind=CHAT）→ 平台默认 CHAT 端点（is_default 且 active）→ 皆无 → 409
```

- 显式 id 不存在 / 已停用 / **不是 CHAT** → 400（沿用 CAP-48 FR-11「消费方必须按 kind 过滤」的红线：
  把向量端点当对话端点用会拿 embedding 模型名去打 `/chat/completions`）。
- 未显式指定且无平台默认 CHAT 端点 → **409**「未配置通用模型端点或未设默认」（并给「后台 → 模型接入」引导）。
- `devmind-model` 模块未装配 → 409（chat 以 `ObjectProvider<ModelEndpointProvider>` 探测，
  **不新增模块依赖**，与 knowledge 侧同姿势）。
- **冲突字段一律 400 明确拒绝，不静默忽略**：`MODEL` 下 `scenarioCode`（场景资产靠 runner manifest 物化，
  模型执行体拿不到，静默丢上下文是比报错更坏的失败）、`agentNodeId`（节点路由）、`permissionMode`
  （CLI 工具授权）有非空值即 400 并说明原因。前端在模型模式下隐藏这三个控件，正常用户碰不到这条错误。
- 每轮**重新解析** `activeEndpoint(modelEndpointId)`（不回落默认）：端点被停用/删除/换 key 即时生效；
  端点不可用时**报错不静默切换**（静默换模型 = 答案质量与性格突变且用户无感）。

### FR-03 流式生成（SSE 客户端）

- 新 `devmind-common` 类 `OpenAiCompatChatStream`（在 `com.devmind.common.model` 包内，
  与探针 `OpenAiCompatChat` **分家**——探针的「压空白 + 截 200 字」只服务 `last_test_message`，
  绝不能用作正文）：支持 `system` + 多轮 `messages` + 增量回调，返回**完整正文（不压空白、不截断）**。
- 复用 `OpenAiCompatHttp`：脱敏正则、HTTP/1.1 钉死、`failure()` 诊断串**全仓只有一份**（复制即密钥泄漏路径）。
- **超时语义靠应用层，不赖 `HttpRequest.timeout()`**（本机 JDK 21.0.12 `src.zip` 钉死）：该超时在响应头到达时
  即被 `MultiExchange.cancelTimer()` 取消，**救不了卡住的流**；而 JDK-8370631 已把它扩到「响应体消费完成为止」
  （未来 JDK 会开始腰斩长流）。故流式路径：`connectTimeout` = 端点 `timeoutSeconds` + 三个看门狗
  （**首字节 60s / 分片停顿 120s / 整回合上限 600s**），超时与手动中断**统一走 `Thread.interrupt()` 读线程**
  （`BodyHandlers.ofInputStream()` 的 `@implNote` 契约：中断阻塞读会抛 `IOException`，并**同时取消请求、关闭流**）。
- 必须处理的边界：服务端忽略 `stream:true` 回普通 JSON → **回落非流式**（不抛错，打字机退化成一跳）；
  `[DONE]` 缺失 **不算失败**（EOF 亦正常结束）；`delta.content` 为数组 → **拼接全部 text part**
  （探针只取首个是探针语义）；同一事件多个 `data:` 行按 SSE 规范以 `\n` 先拼接再解析；跳过 `:` 注释行与空行、
  兼容 `\r\n`、**显式 UTF-8**（不用 `ofLines()`：它从响应头取字符集，网关声明 ISO-8859-1 就乱码）；
  `reasoning_content` 只累计不进正文（空正文失败时把"只返回了思考内容"写进错误消息）；
  2xx 但零正文算失败（同 FR-11 探针口径）；不发 `max_tokens` / `stream_options` / `Accept-Encoding`。

### FR-04 多轮上下文装配（无内存态）

- 上下文**每轮从 `chat_events` 重建**（`findByChatIdAndSeqGreaterThanOrderBySeqAsc(id, beforeSeq)`，
  `beforeSeq` = 本轮 user 事件的 seq，**排除本轮自己**）：最近 400 条 + 字符预算 24k（≈8–12k token）。
- 只取 `user` / `assistant`：跳过 `text_delta`（全量 assistant 才是真值）、`state`/`log`/`result`/`error`、
  内容为空或 `[图片]` 的遗留 user 事件。
- **剥掉历轮注入前缀**：`<knowledge-context>…</knowledge-context>`（每轮检索块）与
  `<knowledge-base>…</knowledge-base>`（首轮库概览）——它们在事件流里真实存在（CAP-46 E2E 正是据此断言），
  但作为历史上下文是纯噪音。**只剥历史，本轮的新检索块原样发给模型**。
- 装配顺序：`system`（模型执行体固定提示 + 绑库时的库概览节 + 一句「`<knowledge-context>` 是检索到的参考资料，
  不要照抄」）→ 历史（连续 assistant 以 `\n\n` 合并，与前端同规则；历史以 assistant 开头则丢弃到第一个 user，
  部分网关对开头非 user 直接 400）→ 本轮 user。
- 库概览文案从 `ChatManagerService.overviewSection` 抽成共享静态方法：CLI 首轮 prompt 与模型 system 提示
  **同源**，避免两份文案漂移。
- 用内核 `replay()` 环形缓冲与 DB 结果按 seq 归并，闭合 `ChatEventSaver` 200ms 批量落库的竞态缝
  （用户秒回时上一条 assistant 可能还没落库）。

### FR-05 增量事件与节流

- 事件序列（**前端 WS 协议零变更**，不新增事件类型）：`user` → `state:RUNNING` → `text_delta`×N →
  `assistant`（全量，`source=model`，`payload.model`）→ `result{isError,durationMs,truncated?}`。
  失败路径 `error`(已脱敏) → `result{isError:true}`（**必须落 result**，否则会话永远停在 RUNNING、输入框 disabled）；
  中断路径 `assistant`(已产出部分) → `result{isError:false, subtype:"interrupted"}`。
- 内核 `dispatch(result)` 会自动转 `WAITING_INPUT`（回合完成仍可继续提问），与 CLI 路径同语义；
  但 `kill()` 之后**不得再落 `result`**（内核无条件转换会把刚设的 TERMINATED 覆盖回 WAITING_INPUT，
  实时态与 DB 持久态劈叉）——回合线程终局先判 `exitHandled`。
- **`text_delta` 必须在发布前合并节流**：攒 120ms 或 24 字才发一条，结束时 flush 余量。
  不节流时一 token 一事件 ≈ 1500–3000 行/回合，会把 `chat_events` 撑爆（200ms 批量落库救不了表膨胀）。
  合并只在 runtime 侧做——放进 `ChatEventSaver` 会让 WS 也失去增量。
- 最终 `assistant` **不静默截断**（`content` 是 LONGVARCHAR）；超 200k 字上限时截断并在
  `result.payload.truncated=true` + 一条 `log` 说明；**不把一条回复拆成多条**（前端对连续 assistant 的
  合并规则会插多余空行）。
- `handleExit` 语义澄清：它是「会话收口」而非「进程退出回调」。模型执行体下触发点是 `doFinish`（= 取消流）
  与 kill 后的回合线程收口；端点不可用/鉴权失败等**不调 `handleExit`**，只落到 WAITING_INPUT 让用户修完重试
  ——模型会话是"只有人让它结束"的会话。

### FR-06 中断

- 进行中的生成可中断：只结束当前回合、**会话保留**（已产出的部分文本落成 `assistant` 事件）。
- `interrupt()` **只加在 `ModelSessionRuntime`**，不进 `SessionHandle`（否则 session 模块两个实现都得跟着改）；
  `ChatManagerService` 用 `instanceof` 分流，`AGENT` 执行体 → 409。
- 两个入口成对（与 input 一致）：WS 上行帧 `{"type":"interrupt"}`（主路径，用户正看着流）+
  `POST /api/chats/{id}/interrupt`（列表页/API 一致性）。
- 幂等：未在生成中调用是 no-op（前端连点安全）。

### FR-07 生命周期动作在模型执行体下的语义

| 动作 | 语义 | 理由 |
|---|---|---|
| `finish` | 取消流 + `handleExit(0)` → DONE | 与 CLI 的「优雅结束」对齐 |
| `kill` | 取消流 + TERMINATED（基类既有路径） | 同上 |
| `suspend` | **409** | 无进程、无工作区、无 `--resume` 语义，没有可挂起的东西 |
| `authorize` | **409** | 模型执行体无授权概念；no-op 会造出「操作成功了但什么都没发生」的错觉 |
| `resume` | **支持**，且比 CLI 简单：跳过 `cliSessionId` 校验与 `connector.launch`，重新 `activeEndpoint`
（空 → 409 端点已停用/删除）+ 重建 runtime | 等价于"继续对话" |
| 空闲超时 | 默认 0（不自动结束） | 内存里挂着的模型会话不占进程，无回收必要 |

- **懒重挂** `requireOrReattachRuntime(id, ent)`：`input`/`interrupt`/`subscribe`/`finish` 路径上 runtime 不在内存
  且 `executor=MODEL` 且状态可续 → 直接重建（历史在 DB，无需节点）——否则刷新页面打开一个模型问答会显示
  「会话已结束」。`resume` 复用同一段代码。
- **重启自处**：`restoreOnStartup` 现在的判据是 `agent_node_id 为空 → TERMINATED`，而 MODEL 会话恒空 ⇒ 会被误判。
  改为：MODEL + WAITING_INPUT → 保持不动（懒重挂）；MODEL + RUNNING → TERMINATED + summary
  「服务重启，进行中的生成已中断（可继续对话）」。
- **容量分账**：`ensureCapacity` 现在把所有 active runtime 等同计数。模型会话空闲（WAITING_INPUT）不占任何外部
  资源，不该挤占 runner 配额（4 个闲置模型问答会把平台卡到无法新建问答）→ 新增 `maxConcurrentModel`（默认 8），
  **只数 `generating()` 为真的 MODEL 会话**。

### FR-08 端点引用保护

- `devmind-chat` 新增 `ChatEndpointUsageProvider implements ModelEndpointUsageProvider`，**只报"进行中"的问答**
  （RUNNING/WAITING_INPUT/WAITING_AUTH）：否则一个历史问答会永久锁死端点的删除权。
  文案 `问答：<标题>（进行中）`；需 repo 方法 `findByModelEndpointIdAndStatusIn`。

### FR-09 前端

- **新建问答**：高级选项加「执行体」二选一（默认智能体）；选模型 ⇒ 显示对话端点 Select
  （只列 `kind==='CHAT' && status==='active'`，预选平台默认；**无 CHAT 端点则禁用该选项 + 引导「后台 → 模型接入」**），
  隐藏节点/场景/权限模式；CLI 那个自由文本「模型」输入框 label 改「CLI 模型名」以免与执行体混淆。
- **流式渲染**：`ChatStream.tsx` 的 `text_delta` 分支从 `break`（注释"assistant 全量消息为准"）改为累积到
  尾部 `streaming` 气泡（**忽略空增量**——CLI 的 `stream_event` 会产空 content）；随后到达的全量 `assistant`
  **直接覆盖 + 收口同一气泡**（"相等/前缀/网关重发不一致"三种情况收敛到一条路径，永不出两个气泡）；
  `user`/`result`/`error` 分支显式重置流状态。**AGENT 会话零影响**（CLI 未传 `--include-partial-messages`，
  实际不产 `text_delta`）。
- **停止生成**：发送按钮旁增「停止」（MODEL 且正在生成时）；`allowImages` 对模型会话强制 false 并提示
  「模型问答暂不支持图片」；生成中禁注入（会被后端拒）。
- **执行体可见**：问答列表项与对话头部显示徽标（`模型名 · 端点名` vs `Agent · 节点`）。
- 布局遵循 [docs/core/前端内容区布局约定.md](../core/前端内容区布局约定.md)。

### FR-10 权限与红线

- 通用问答是**个人资源**（CAP-30 语义：`requireOwned`，非本人 404），模型执行体不加权限门（不引入 ADMIN 要求）；
  端点的登记与启停仍归 ADMIN（CAP-48 FR-10 不变）。
- **凭据红线随类型走**：`apiKey` 只在 `ModelEndpointView` 内存里传递，禁进任何 HTTP 响应、禁日志、
  禁异常消息（含"端点解析失败"这类诊断，别打 `toString`）；所有错误/事件文本一律过 `OpenAiCompatHttp.sanitize`。
- 异步触发路径（`input`/`interrupt`）**禁 `@Transactional`**（仓库既有红线）。

## 3. 关键设计（已定）

- **模型执行体 = 服务端出站 HTTP，不是本机会话**（已定）：定性见 §1，决定 `session` 模块不接入。
- **上下文从事件表重建而非服务端自持**（已定）：重启可续、无内存态，代价是每轮一次按 seq 区间读（可接受）。
- **取消靠 `Thread.interrupt()` 而非关流**（已定）：JDK 的 `ofInputStream` 把「中断读线程」写成了契约，
  比"另起线程 close InputStream"可靠（后者是碰运气）。
- **超时靠应用层看门狗**（已定）：`HttpRequest.timeout()` 的语义随 JDK 版本漂移（JDK-8370631 会扩到响应体），
  不能作为设计依赖。
- **增量节流在 runtime 侧**（已定）：落库量与 WS 实时性是一次取舍，节流是唯一能两头兼顾的位置。
- **冲突字段报错而非静默忽略**（已定）：与 CAP-48 对 `top_k`/`threshold` 的"忽略 + 落 null"不同——那三个字段是
  「数值对模型无意义」，而场景/节点/权限模式是**用户以为生效了却没生效**的语义，必须报错。

## 4. 插件化接口（实现定稿）

- `devmind-common` 新增 `ModelEndpointProvider.defaultEndpoint(String kind)`（CAP-48 FR-11 预留的接入点）；
  无参 `defaultEndpoint()` 委托为 `kind=EMBEDDING`，行为不变。
- `ModelEndpointView` 增 `KIND_CHAT` 常量与 `chat()` 过滤器（与 `embedding()` 对称）；
  实体侧 `KIND_CHAT` 改为引用它（沿用 `MODEL_MOCK` 的"合同值只许一份"先例）。
- 新 `OpenAiCompatChatStream`（common，`model` 包内；`Options` / `Message` / 增量回调 /
  `ModelInterruptedException extends ModelCallException` 用于与"网络失败"区分）。
- 新 `ModelSessionRuntime`（common `agent.runtime`，与 `RemoteSessionRuntime` 并列，`AbstractSessionRuntime`
  的第三个实现）；多轮装配经 `TurnSupplier` 接口由 chat 模块实现（common 不认识 JPA）。
- `devmind-chat` 不依赖 `devmind-model`（也不依赖 knowledge）：端点/P 探测注入，保持模块单向依赖。

## 5. 数据模型

```
chat_sessions + executor VARCHAR(16)?        -- 可空；null = AGENT（读侧 getter 兜底）
              + model_endpoint_id BIGINT?    -- 仅 MODEL；创建时解析成具体端点
```

无新表。红线：可空加列不加 `@ColumnDefault`；无 Boolean 列；无新 `@Lob`（回复复用 `chat_events.content`）。

## 6. API 概要

```
POST   /api/chats                 请求体增 executor / modelEndpointId（可空）
GET    /api/chats[/{id}]          视图增 executor / modelEndpointId / modelEndpointName / modelEndpointModel
POST   /api/chats/{id}/interrupt  新增：中断进行中的生成（AGENT 执行体 409）
WS     /ws/chats/{id}             上行帧增 {"type":"interrupt"}；下行帧协议不变
POST   /api/chats/{id}/suspend    MODEL → 409
POST   /api/chats/{id}/authorize  MODEL → 409
POST   /api/chats/{id}/resume     MODEL 走"重建 runtime"分支（无需 cliSessionId / 节点）
模型侧                             复用 CAP-48 既有端点（无新 HTTP 面）；SPI 增 defaultEndpoint(kind)
```

## 7. 验收标准

- **无节点也能问答**：无任何在线 runner 节点时，建 `executor=MODEL` 问答并提问 ⇒ 流式回答、状态回
  `WAITING_INPUT`、`/events` 落库与 WS 一致；
- **流式可见**：WS 收到多条 `text_delta` + 一条全量 `assistant` + `result{isError:false}`；前端渲染无重复气泡
  （覆盖收口）；
- **多轮正确**：第二轮请求的 `messages` 含 system（库概览）+ 历轮 user/assistant，且历轮
  `<knowledge-context>` / `<knowledge-base>` 前缀已剥离；绑库时本轮正文带新 `<knowledge-context>`；
- **中断可用**：长流中 interrupt ⇒ 流停、部分文本保留、`result.subtype="interrupted"`、可继续提问；
- **失败不炸链路**：端点停用后提问 ⇒ `error` 事件 + 会话仍在 WAITING_INPUT（可修端点重试）；
  401 ⇒ 消息脱敏（`***`）不泄密；2xx 零正文算失败；服务端忽略 `stream:true` 时回落非流式仍可用；
- **零回归**：AGENT 问答链路不变；`tests/cap46_verify.py`（知识库问答）与 `cap48_verify.py` 不改一行仍全绿；
- **红线**：列表/详情视图无密文；日志与事件文本无 apiKey；`git status --short` 无本机路径与密钥。

## 8. 非目标

- 模型执行体的**工具/文件/联网能力**（无工具调用循环，纯文本问答；需要工具就用 AGENT 执行体）；
- **会话内切换执行体**、把模型执行体推广到项目会话（`/sessions`）/ 需求流程 / 工作日志报告
  （那些依赖 runner 的工作区与产出回传，另立能力）；
- 模型执行的**成本/用量统计与配额**、多端点负载均衡与故障转移（CAP-48 §8 已排除）、内容审核；
- `AGENT` 执行体的增量渲染（CLI 未开 partial messages，本题不涉及；前端收口规则已能承接）；
- 附件（图片/文件）投送：模型执行体不支持图片，也不做"文件转文本"通道。

## 9. 实现记录（与初稿的偏差）

| 项 | 初稿 | 实现 | 为什么 |
|---|---|---|---|
| （实现后回填） | | | |

**验收证据**：（实现后回填）
