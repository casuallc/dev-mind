# CAP-48 模型接入管理（向量化 / 通用对话端点）

> 能力 ID：CAP-48 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-20
> 缘起：CAP-44 FR-05 把 embedding 做成 `application-local.yml` 里的 `devmind.knowledge.embedding.*`，
> 并明确把「embedding 模型管理 UI」列为非目标。落地后实测发现这层配置已成为多个缺陷的**共同瓶颈**，
> 本能力把「模型接入」提升为一等平台资源，并把 CAP-44 的索引/检索改为按端点解析 + 索引血缘落库。

## 1. 目的

现状问题（均已核实，附证据位置）：

| # | 问题 | 证据 |
|---|---|---|
| 1 | **配置游离**：随包 `application.yml` 只有 `knowledge.repo-path/enabled`；dist `config/application.yml` 连 `devmind.knowledge` 段都没有；`config/README` 未提 embedding；部署脚本无处理。全仓 `embedding` 字样只出现在 CAP-44/45/46 三份文档里 | `devmind-app/src/main/resources/application.yml:71` |
| 2 | **无连接测试**（对比平台集成有 `test` / `testDraft` 预检） | `IntegrationController.java:58-68` |
| 3 | **无维度记录 → 换模型静默全空**：余弦第一步校验维度，不等即返回 0，被 `threshold=0.15` 过滤成"无命中"；而 `available()` 硬编码 true，前端不显示降级 | `VectorJson.java:46`、`OpenAiCompatEmbeddingClient.java:36` |
| 4 | **库级 `embeddingModel` 是死字段**：UI 可填、DB 存、索引不读、改名也不触发重建 | `KnowledgeBaseService.java:162`、`KnowledgeIndexService.java:68` |
| 5 | **全局单端点**，无法按库选模型 | `EmbeddingConfig.java:17-27` |
| 6 | **`timeoutSeconds` 名不副实**：只作用于 connectTimeout，请求超时硬编码 60s | `OpenAiCompatEmbeddingClient.java:30,52` |
| 7 | 无分批、无重试，单次请求发全部 chunk，长文档整条 failed | `OpenAiCompatEmbeddingClient.java:45-54` |

本能力 = 端点登记（密文凭据）+ 连接测试（**实测探测维度**）+ 默认端点 + 库级覆盖 + 索引血缘与失配防护 + 配置迁移 + 失配修复入口。

**范围拍板**：首期只实现 `kind=EMBEDDING`、`kind` 列预留（CHAT/RERANK 留待有真实消费方）；端点实体放**新模块 `devmind-model` + 独立表**；绑定粒度为**平台默认 + 库级可覆盖**。

**范围修订（FR-11）**：`kind=CHAT`（通用模型/对话）**开放登记 + 连接测试**，`RERANK` 继续预留（传值 400）。
本次仍**不接任何 CHAT 消费方**（全仓无 `/chat/completions` 调用：CAP-46 知识库问答的答案是 runner 上
claude CLI 产的，不走 HTTP 模型），因此开放登记的同时补了「向量解析链只认 EMBEDDING」的守卫（见 FR-11），
否则一个 CHAT 端点会被知识库的向量链当成向量端点用。

## 2. 功能需求

### FR-01 端点实体与 CRUD

- 新模块 `devmind-model`，新表 `model_endpoints`（见 §5）。`kind` 接受 `EMBEDDING` / `CHAT`（FR-11），`RERANK` 传值 400（预留）。
- `provider` 本期两个值：`openai-compatible`（默认）、`mock`（确定性哈希向量，供测试/E2E，仿 runner `executor=fake` 先例）；未知值 400。
- 字段校验：`base_url` 必填（`mock` 除外）、`model` 必填（`mock` 除外）、`timeout_seconds` 1~600、`batch_size` 1~256、`dimensions` 只读（由 FR-03 探测写入，不接受人工提交）。`kind=CHAT` 时 `top_k` / `threshold` **被忽略并落 null**（不报错、不做范围校验，见 FR-11）。
- 删除：被知识库引用（`knowledge_bases.model_endpoint_id`）返回 409 并列出引用方；未被引用可直接删。
- 状态：`active` / `disabled`；停用后解析链跳过该端点（不等于删除，可恢复）。

### FR-02 凭据加密

- 把 `IntegrationCipher` 的加解密能力抽取为 `devmind-common` 的 `SecretCipher`（域分隔派生参数化）：
  - `SecretCipher.encrypt(domain, plain)` / `decrypt(domain, cipher)`，格式沿用 `enc1:base64(iv||ct||tag)`；
  - **兼容红线**：integration 侧域分隔串保持 `"devmind-integration"` 不变，`IntegrationCipher` 保留 bean 名与全部既有行为（改造后已有密文必须仍可解，加单测钉死）；
  - model 侧域分隔串 `"devmind-model"`，密钥来源同一套（`devmind.integration.crypto-key` → `data/auth.key` → 自动生成 `data/model-crypto.key`）。
- 视图只回 `hasApiKey: boolean`，**不回显密文或明文**；日志与异常消息不带 key（含 embedding 调用失败的响应体摘要，仍按现有 200 字符截断，但需过一遍脱敏：剔除响应体中形如 `sk-`/`Bearer` 的片段）。

### FR-03 连接测试（**探测维度**）

- 两个入口，与平台集成同构：
  - `POST /api/model-endpoints/test` 草稿预检（凭据不落库，用于新建/编辑表单内先测再存）；
  - `POST /api/model-endpoints/{id}/test` 已存端点测试：**把探测结果回写端点**（`last_test_at/last_test_ok/last_test_message`），**并把探测到的维度写入 `dimensions`**。
- 测试动作按 `kind` 分派：`EMBEDDING` 用探针文本（`"__devmind_probe__"`）实调一次 `/embeddings`，校验：HTTP 2xx、`data` 条数与输入一致、向量非空且**所有向量等长**；返回 `{ok, latencyMs, model, dimensions, message}`（CHAT 的探针见 FR-11；`testDraft` 也必须先按 `kind` 分派，否则预留类型的草稿会静默打到 `/embeddings`）。
- `mock` provider 走 `MockEmbeddingClient`，返回配置维度。
- 维度语义（关键）：**`dimensions` 是连接测试的产物，不是人工输入**——人工填错维度是 FR-06 要防的事故源，因此设计上不允许手填。
- 若已存端点重新测试得到**不同维度**，响应带 `dimensionChanged: {from, to}`，前端明确提示「维度已变化，该端点上已有的索引需重建」。

### FR-04 默认端点与解析链

解析链（顺序固定，**不回落 mock**）：

```
库级 model_endpoint_id（active）→ 平台默认端点（is_default=true 且 active）→ 皆无 → 降级（索引 disabled / 检索 LIKE）
```

- 「平台默认」唯一：`PUT /{id}/default` 在单事务内把旧默认置 false、新默认置 true（表上 `is_default` 加唯一部分索引或服务层保证）。
- 端点被停用/删除后，引用它的库自动回落到"平台默认"（不做数据回写，解析时判定）。
- 降级语义与 CAP-44 完全一致：无可用端点 → 条目 `index_status=disabled`、检索走 `searchInBases` LIKE 降级并标 `degraded=true`。**既有 E2E 的 `provider=mock` 启动方式经 FR-07 迁移后自动变成一条 mock 端点，cap44/45/46 脚本无需改动**。

### FR-05 客户端按端点解析

- `EmbeddingClient` 接口签名不变（`available/model/embed`），新增 `EmbeddingClientFactory`：
  - `clientFor(ModelEndpoint) → EmbeddingClient`，按 `(endpointId, updatedAt)` 缓存实例；端点更新后旧实例自动弃用；
  - 实现内承担 FR-01 的 `batch_size` / `timeout_seconds`（**请求超时读 `timeout_seconds`**，修掉硬编码 60s）与分批调用；单批失败按批重试 1 次（指数退避 500ms），仍失败抛 `EmbeddingException`，消息带批次区间（`chunks[120,180)`）。
- `KnowledgeIndexService` / `KnowledgeRetrieverImpl` 改为经工厂取客户端；`EmbeddingConfig` 的全局单例 bean 保留为「无端点时的降级实现」，不再承担真实调用。
- embedding HTTP 调用**移出 `@Transactional`**：`indexEntry` 拆成「读条目 → 事务外取向量 → 事务内删旧写新」三步，避免重建全库时长期占用数据库连接（当前 `KnowledgeIndexService.indexEntry` 整体 `@Transactional`）。

### FR-06 索引血缘与维度失配防护

- 索引成功时把「用了谁」落库到条目：`indexed_endpoint_id` / `indexed_model` / `indexed_dimensions`。
- 检索时：
  1. 解析当前端点，取其 `dimensions`；
  2. 逐 chunk 用向量实际长度校验（比 JSON 解析更廉价，先判长度再解析）；维度不符的 chunk **跳过并计数**；
  3. 若因维度失配导致**全部候选被跳过**，不再返回空列表了事，而是返回结构化降级原因：
     `degraded=true, degradedReason=DIMENSION_MISMATCH`，消息模板：
     「该库索引由模型 A（1024 维）生成，当前端点 B 为 768 维，需重建索引」。
- `KnowledgeSearchResponse` 增 `degradedReason`（枚举：`NONE | NO_EMBEDDING | DIMENSION_MISMATCH`）；知识库详情页「检索测试」与 CAP-46 会话侧按此提示（会话侧沿用"检索异常按无命中降级"，但日志需显式告警一次）。
- 这也顺带修掉 §1 第 4 条：`knowledge_bases.embedding_model` 死字段删除，替换为 `model_endpoint_id`（真正的解析入口）。

### FR-07 配置迁移（幂等，不阻断启动）

- 启动 `ApplicationRunner`（仿 `KnowledgeBaseMigration` 先例）：
  - 若表中已存在任何 `EMBEDDING` 端点 → 直接返回（幂等）；
  - `provider=mock` → 建端点「内置 Mock 向量（测试用）」，`is_default=true`；
  - `base_url` + `model` 齐备 → 建「平台默认向量端点」，`api_key` 经 FR-02 加密落库，`is_default=true`，`dimensions` 留空（待首次索引/测试探测）；
  - 皆不满足 → **不建端点**（保持 disabled/LIKE 降级，与现状一致，不静默造 mock）。
- 迁移**只读不写配置**：`devmind.knowledge.embedding.baseUrl/apiKey/model/provider` 迁移后降级为"仅首次种子"，之后用户在 UI 的修改不被重启覆盖。
- `chunkSize` / `chunkOverlap` / `topK` / `threshold` / `dimensions` 保留为平台级默认（不进端点）；端点可**可选覆盖** `topK` / `threshold`（空 = 用平台默认），因为不同模型的合理阈值差异很大（0.15 对哈希向量与对真实 embedding 不是一个量级）。

### FR-08 修复入口：全库重建索引

- `POST /api/knowledge/bases/{id}/reindex`：整库重建（异步，经既有单线程索引执行器排队），返回 `{queued: N}`，跳过 `status != active` 的条目。
- `POST /api/knowledge/bases/{id}/reindex?onlyMismatched=true`：只重建 `indexed_endpoint_id != 当前端点` 或 `indexed_dimensions != 当前维度` 的条目（FR-06 失配的定向修复）。
- 当前只有单条 `POST /entries/{id}/reindex`，换模型/调分块参数后只能逐条点，这是本 FR 的存在理由。
- `KnowledgeBaseView` 增 `indexStats{ready, pending, failed, disabled}`，让"这库到底能不能用"一眼可见。

### FR-09 前端

- 新页「模型接入」（后台管理区，路由 `/admin/models`，与 `/admin/integrations` 同级）：列表列 = 名称 / 类型 / 模型 / 维度 / 超时·批量 / 状态 / 默认 / 最近测试 / 操作（编辑·测试·设为默认·停用·删除）。
- 表单：`kind`（本期只有 Embedding 一个选项）· `provider` · 名称 · `baseUrl` · `apiKey`（密码框，编辑时留空表示不改）· `model` · 超时 · 批量 · 高级折叠里的 `topK`/`threshold`；表单内「测试连接」调 FR-03 draft 预检，成功展示延迟与探测维度。
- 知识库设置页：把「向量模型」文本框（`KnowledgeBaseDetail.tsx:632`）换成端点选择器（`跟随平台默认` / 指定端点），并展示该库当前解析到的端点 + 维度。
- 知识库详情页：FR-06 失配时顶部告警条 + 「重建全库索引」按钮；「检索测试」区显示当前端点/模型/维度。
- 列表页 `chunkCount` 旁增 `indexStats` 摘要（如 `128/130 已索引`）。

### FR-10 权限与红线

- 写操作（POST/PUT/DELETE `/api/model-endpoints/**`）在 `SecurityConfig` 收紧为 `hasRole("ADMIN")`，读列表对已登录用户开放（不含密文），与平台集成一致。
- 密钥禁入库：迁移与文档不写死任何 baseUrl/apiKey；`application-local.yml` 仍是本机密钥的落点（已 gitignore）。

### FR-11 通用模型（CHAT）端点登记与连接测试

**目标**：把「模型接入」从"只有向量模型"扩成多类型登记，首个新增类型 = 通用对话模型。本期**只做登记 + 连接测试**，
不接消费方（无消费方的类型放开登记是刻意决定：端点是平台资源，先让运维能把"公司有哪几个通用模型"登记进来并
验证可用；消费方接入时按 `kind` 取默认端点即可）。

- **类型放行**：`kind` 接受 `EMBEDDING` / `CHAT`；`RERANK` 仍 400。类型创建后不可变更（沿用 FR-01）。
- **连接测试（对话探针）**：`provider=openai-compatible` 实调一次 `{baseUrl}/chat/completions`，
  body 为 `{model, messages:[{role:"user",content:<探针文本>}]}`——**不发 `max_tokens`**（部分网关/推理模型对
  它的取值挑剔，而探针只需要"有回复"）；**不重试**（探针由人盯着，重试只双倍耗时与计费）。
  ok 判定 = HTTP 2xx **且** body 可解析为 JSON **且** `choices` 为非空数组 **且** `content` 非空白；
  `content` 允许是文本节点或数组里的首个文本元素（OneAPI/vLLM 兼容层会这么回）；**不要求**回显 `model`（网关会改写）。
  `provider=mock` 零网络返回成功（假回复说明）。
- **结果表达**：CHAT 的 `dimensions` 恒为 null、不产生 `dimensionChanged`（该类型无维度语义），
  模型回复的脱敏摘要放进既有的 `message`（连续空白压成单空格、截 80 字 + `…`），**不扩展 `EndpointTestResult`**；
  UI 在 `dimensions` 为空时不显示"探测维度"。
- **字段语义**：`top_k` / `threshold` 对 CHAT 无意义 → 忽略并落 null（**不报错、不做范围校验**，含 update 时清掉
  历史遗留值）；`batch_size` 列 NOT NULL 保留默认、不使用。
- **默认端点按类型各自唯一**：`setDefault` 已按 kind 清理旧默认（FR-04），CHAT 默认与 EMBEDDING 默认互不影响。
- **必须同时落的守卫（本 FR 的硬前置）**：向量解析链只认 EMBEDDING，三处收口——
  1. `KnowledgeBaseService` 绑定库级端点处：`kind != EMBEDDING` → 400（**源头拒绝**，不存在/已停用的 id 维持
     现状，解析时回落平台默认）；
  2. `KnowledgeEndpointResolver.resolve(...)` 与 `KnowledgeBaseService.endpointNameOf` 对解析结果
     `filter(ModelEndpointView::embedding)`（**解析防御**）；
  3. 知识库详情页的端点下拉与「当前生效」兜底只取 `kind === 'EMBEDDING'`（**UI 与实际行为一致**）。
  不做这三处的后果是：库级绑了 CHAT 端点后拿对话模型名打 `/embeddings`，条目 `index_status=failed`
  并把对话模型名写进 `indexed_model` 血缘；且 UI 会把 CHAT 默认端点显示成知识库的"当前生效向量端点"。
- **前端**：类型下拉新增「通用模型（对话，Chat Completions）」（新建可选、编辑禁用）；CHAT 表单只留
  服务地址 / API Key / 模型名 / 超时（隐藏单批条数、维度、高级 topK/threshold）；表格维度列对 CHAT 显示 `—`；
  provider 选项标签与页头/空态/抽屉/默认 tooltip 文案按类型走，并**明说 CHAT 端点本期无消费方**。
- **非目标**：CHAT 端点的消费方（问答生成、摘要等）另立能力，届时在 SPI 上补 `defaultEndpoint(kind)`；
  本 FR **不新增** SPI 方法。

## 3. 关键设计（已定）

- **维度是探测结果而非人工输入**（已定）：这是 FR-06 能成立的前提。人工填维度 = 埋雷。
- **索引血缘落条目级而非 chunk 级**（已定）：chunk 维度可由条目推导，条目级足以驱动"这条要不要重建"的决策；chunk 级校验用向量实际长度即时判定，无需额外宽列。
- **抽取 `SecretCipher` 而非模型模块自带一套密钥派生**（已定）：避免出现两套密钥来源导致"数据在但解不开"；integration 域分隔串必须保持兼容。
- **真实调用与事务解耦**（已定）：embedding 是网络 IO，不能留在 `@Transactional` 里。
- **降级链不变**（已定）：无端点 → disabled/LIKE，绝不 5xx；失配 → 结构化原因而非静默空结果。
- **kind 分两步走**（已定，FR-11 修订）：首期 CHAT/RERANK 皆 400，避免"配了没人用"；FR-11 放开 **CHAT 的登记与连接测试**（消费方仍不做，属于"先登记后接入"），`RERANK` 继续 400。放开登记的文件必须与向量链守卫**同批**落地（见 FR-11 末条）。

## 4. 插件化接口（实现定稿）

- `devmind-common` 新增 `ModelEndpointProvider` SPI，`devmind-model` 实现：
  - `Optional<ModelEndpointView> resolve(Long kbEndpointId)`——**入参是库级覆盖值而非 kbId**：
    「哪个库用哪个端点」存在 `knowledge_bases`（knowledge 模块自有列），SPI 只负责"取端点"这步，
    解析链由调用方传值驱动（原稿的 `resolveForKb(long kbId)` 会让 model 反向依赖 knowledge）；
  - `Optional<ModelEndpointView> defaultEndpoint()`；另有 `activeEndpoint(long id)`（库级覆盖取用）；
  - `record ModelEndpointView(id, kind, provider, name, baseUrl, apiKey, model, dimensions,
    timeoutSeconds, batchSize, topK, threshold)`——**携带解密后的 apiKey**（SPI 内存传递，
    连接器不接触持久层）：调用方要用它发 `Authorization`，不给就没法调。红线随类型走：
    禁序列化进 HTTP 响应、禁日志、禁异常消息；HTTP 侧视图只有 `hasApiKey`。
    另含 `MODEL_MOCK` 常量（跨模块合同值，索引血缘要比对，禁止两处各写一份）。
- `devmind-common` 新增 `ModelEndpointUsageProvider`（FR-01 删除保护）：引用方是各消费模块的自有数据，
  由消费模块实现、`devmind-model` 探测注入；未装配 = 无引用 = 删除放行。
- `devmind-knowledge` 以 `ObjectProvider<ModelEndpointProvider>` 探测注入（未装配 = 回退 CAP-44 配置化单例，
  装配了就**不回落**——否则 UI 里删了端点检索还在偷用配置，看到的降级提示与实际行为不符）。
- `devmind-common` 新增 `SecretCipher`（FR-02），另有 `OpenAiCompatEmbeddings`（FR-05）：
  两个消费方共用，且"报错不泄密"只有一套实现（`sanitize()` 抹 `sk-*`/`Bearer *`）。
- FR-11 另增（仍在 common）：`OpenAiCompatChat`（对话调用，`record Options(baseUrl, apiKey, model, timeoutSeconds)`，
  返模型回复文本）、`OpenAiCompatHttp`（**包内可见**：按超时缓存 `HttpClient` + `sanitize()` + `abbreviate()`；
  脱敏正则只许有一份，第二份就是密钥泄漏路径）、`ModelCallException`（模型调用失败基类，
  `EmbeddingCallException extends ModelCallException` 保持既有捕获点兼容）。
  `ModelEndpointProvider` 的 javadoc 明确：**返回视图含 kind，消费方必须按 kind 过滤**；
  本期**不新增** kind 版 `defaultEndpoint(kind)`（无消费方不加接口），它是未来 CHAT 消费方的接入点。

## 5. 数据模型

```
model_endpoints(
  id, kind[EMBEDDING|CHAT|RERANK], name, provider[openai-compatible|mock],
  base_url VARCHAR(512)?, api_key_enc VARCHAR(1024)?, model VARCHAR(128)?,
  dimensions INT?,                     -- 连接测试探测写入，禁人工提交
  timeout_seconds INT DEFAULT 30, batch_size INT DEFAULT 32,
  top_k INT?, threshold DOUBLE?,       -- 空 = 用 devmind.knowledge.embedding.* 平台默认
  status[active|disabled], is_default BOOLEAN,
  last_test_at, last_test_ok BOOLEAN, last_test_message VARCHAR(1000)?,
  created_at, updated_at)

knowledge_bases   + model_endpoint_id BIGINT?     -- 空 = 跟随平台默认；原 embedding_model 列删除（死字段）
knowledge_entries + indexed_endpoint_id BIGINT?   -- 索引血缘（FR-06）
                  + indexed_model VARCHAR(128)?
                  + indexed_dimensions INT?
```

红线：`@ColumnDefault` 字符串值必带引号；`is_default`/`last_test_ok` 是 Boolean 列 → **禁 `@ColumnDefault`**，走实体初始值 + getter 兜底（H2 测不出，MySQL bit 列建列失败）；`kind`/`status` 列名在 H2/PG/MySQL 均非保留字，但实现时仍需按红线过一遍保留字清单；无新增 `@Lob` 列。

FR-11 **无表结构变更**：`kind` 已是 `VARCHAR(16) NOT NULL`，CHAT 只是它的新取值；`dimensions`/`top_k` 本就可空，
`batch_size` 保持列默认 32。零 DDL ⇒ `@ColumnDefault` / Boolean 默认值 / `@Lob` 三条红线均不涉及。

## 6. API 概要

```
GET    /api/model-endpoints                 列表（admin 写入，登录可读；仅 hasApiKey）
POST   /api/model-endpoints                 ADMIN 新建
PUT    /api/model-endpoints/{id}            ADMIN 更新（apiKey 留空 = 不改）
DELETE /api/model-endpoints/{id}            ADMIN 删除（被库引用 409）
PUT    /api/model-endpoints/{id}/status     ADMIN 启停
PUT    /api/model-endpoints/{id}/default    ADMIN 设为平台默认（唯一）
POST   /api/model-endpoints/test            草稿预检（凭据不落库）
POST   /api/model-endpoints/{id}/test       已存端点测试（回写 last_test_* + 探测维度 + dimensionChanged）
POST   /api/knowledge/bases/{id}/reindex    全库重建（?onlyMismatched=true 定向修复），返回 {queued}
GET    /api/knowledge/bases/{id}            KnowledgeBaseView 增 modelEndpointId/modelEndpointName/indexStats
POST   /api/knowledge/search                响应增 degradedReason（NONE|NO_EMBEDDING|DIMENSION_MISMATCH）
兼容   application-local.yml 的 devmind.knowledge.embedding.* 仍可作首次迁移种子

FR-11 复用上面同一组端点（无新端点）：kind=CHAT 走同一 CRUD，仅连接测试分派到 /chat/completions；
kind=RERANK 仍 400。绑定守卫（FR-11）落在 knowledge 侧，不新增 HTTP 面。
```

## 7. 验收标准

- **零回归**：迁移后 cap44/45/46 三个 E2E 脚本**不改一行**仍全绿（`provider=mock` 自动迁成 mock 端点）；
- 未配置任何端点时行为等同现状：条目 `disabled`、检索 LIKE 降级 + `degraded=true`，全程无 5xx；
- 建端点 → 表单内「测试连接」返回延迟与探测维度 → 保存后 `dimensions` 已落库；
- **维度失配可诊断**：端点 A（如 mock 64 维）索引后，切到端点 B（不同维度）→ 检索返回 `degradedReason=DIMENSION_MISMATCH` 且消息含两个模型与维度，**不再静默空结果**；执行 `reindex?onlyMismatched=true` 后恢复命中；
- 全库重建：N 条条目一次触发，`indexStats` 各状态计数正确，失败条目带 `index_error`；
- 分批与超时生效：`batch_size=1` 时对 3 块的条目发出 3 次请求（测试内以 stub 端点断言请求次数）；`timeout_seconds=1` 对慢端点触发超时并落 `failed`，消息含批次区间；
- 凭据红线：列表/详情视图无密文；日志与异常消息不含 apiKey；`git status --short` 无本机路径与密钥。
- 单测：`SecretCipher` 兼容性（integration 旧密文可解）、解析链四级回落、维度探测、失配跳过与原因返回、分批切分、唯一默认互斥、迁移幂等（重启不重建、不覆盖 UI 修改）。
- **FR-11 追加**：可建 CHAT 端点（`RERANK` 仍 400）；CHAT 连接测试打进 `/chat/completions` 且带 model/messages、
  **不带 `max_tokens`**，成功回显模型回复摘要且 `dimensions` / `dimensionChanged` 皆 null、端点 `dimensions` 未被写；
  401 失败消息脱敏（`***`）且不覆盖维度；2xx 但回复为空算失败；非 JSON 的 2xx 算失败而非异常外泄；
  `mock` 对话端点零网络；CHAT 草稿预检按 kind 分派、RERANK 草稿 400 且零网络；CHAT 端点的
  `top_k`/`threshold` 落 null（含越界值不 400、update 清历史遗留值）；`setDefault` 只清同类型默认；
  **向量链守卫**：provider 返回 CHAT 视图 → 解析 `unavailable()`、绑定 CHAT 端点 → 400。

## 8. 非目标

- `RERANK` 类型端点的实现（仅预留 `kind` 列与 400 校验；`CHAT` 已由 FR-11 放开登记，但**消费方仍不做** ——
  知识库问答生成、摘要等改走 HTTP 模型属于消费方能力，需另立 CAP，届时在 SPI 上补 `defaultEndpoint(kind)`）；
- 用量统计 / 配额 / 限流 / 多端点负载均衡与故障转移；
- **分块策略改造**（Markdown heading 感知、代码块与表格保护、块 breadcrumb、最小块合并、真 tokenizer 口径）→ 另立 CAP；本能力只把 `chunkSize/chunkOverlap` 留在平台级默认，不改变**当前**分块行为，避免一个 CAP 同时动模型与分块两处导致 E2E 归因不清；
- 索引队列深度 / 批次进度可视化（本 CAP 只做到 `indexStats` 计数 + 触发）；
- 把 runner 侧 claude 的接入纳入（claude 走节点本地 CLI 进程，`agent.properties` 是它的配置面，非本 CAP 消费方）。

## 9. 实现记录（与初稿的偏差）

| 项 | 初稿 | 实现 | 为什么 |
|---|---|---|---|
| SPI 解析入参 | `resolveForKb(long kbId)` | `resolve(Long kbEndpointId)` | kbId → 端点映射在 knowledge 自有列，传 kbId 会让 model 反向依赖 knowledge（模块依赖红线） |
| `ModelEndpointView` 凭据 | 不含凭据 | 含解密后的 apiKey | 调用方要发 `Authorization`；红线改为"禁出 HTTP 响应/日志/异常消息"，HTTP 视图仅 `hasApiKey` |
| 端点引用查询 | 未提 | 新增 `ModelEndpointUsageProvider` SPI | 同上，引用方数据在消费模块，不能让 model 反查 `knowledge_bases` |
| 客户端缓存 | 计划 `EmbeddingClientFactory` | `HttpClient` 按超时值静态复用（common 内） | 每次新建客户端会连连接池一起废掉；端点参数已足够轻，不需要工厂层 |
| 加解密密钥来源 | 复用 `devmind.integration.crypto-key` | 独立 `devmind.model.crypto-key`（空则同主密钥派生，再空自动生成 `data/model-crypto.key`） | 两条凭据链各自轮换互不影响；仍是同一 `SecretCipher` 实现、不同域分隔串 |
| knowledge 侧降级 | 未提 | `devmind-model` 已装配 → 无端点即无（不回落 CAP-44 配置）；未装配 → 退回配置化单例 | 否则删了端点检索还偷用配置，降级提示与实际行为不符 |
| 降级可诊断 | 仅"不静默空结果" | `KnowledgeRetriever` SPI 增 `retrieveDetailed()` + `DegradedReason(NONE/NO_EMBEDDING/DIMENSION_MISMATCH)` | 需区分"没配端点"与"配了但维度对不上"，前者才能引导去配置、后者引导去重建 |
| 索引写入 | 未提 | `KnowledgeIndexListener`(AFTER_COMMIT) → `KnowledgeIndexService` → `KnowledgeIndexWriter`(短事务) | embedding 是网络 IO，必须落在事务外；写回用独立短事务，避免长事务占连接 |
| 失配计数 | 未提 | 端点未探测过维度 / 无可用端点时 `mismatched=0` | 算不出来时宁可不报警，也不误报把用户引向无意义的重建 |
| `kind=CHAT` | 「预留但不实现，传值 400」 | FR-11 放开**登记 + 连接测试**，消费方仍不做，`RERANK` 继续 400 | 多类型接入是用户诉求；端点是平台资源，先能登记与验证，消费方另立能力（避免一次动两处导致 E2E 归因不清） |
| 对话探针 ok 判定 | 未提 | 2xx **且** JSON 可解析 **且** `choices` 非空 **且** `content` 非空白（允许数组首元素）；不要求回显 `model`、不要求 `id`、不发 `max_tokens`、不重试 | OneAPI/vLLM 兼容层会把 `content` 回成数组、会改写 `model`；部分网关/推理模型对 `max_tokens` 直接 400；探针由人盯着，重试只双倍耗时与计费 |
| CHAT 的 `top_k`/`threshold` | 未提 | 忽略并落 null，含**越界值不报错**、update 清历史遗留值 | 该类型无检索语义，报错只增加失败面而不带来收益；但绝不能静默存下一个永远没人读的脏值 |
| 向量链守卫 | 未提 | knowledge 绑定处 `kind != EMBEDDING` → 400 + 解析结果 `filter(embedding())` + 前端只列 EMBEDDING | 解析链原本不看 kind：放开 CHAT 后一个对话端点会被当向量端点用（拿对话模型名打 `/embeddings`、写脏 `indexed_model` 血缘），且 UI 会把它显示成知识库"当前生效向量端点"——正是 §1/§9 反复收口的「UI 与实际行为不符」 |

**验收证据**：`tests/cap48_verify.py` 56 项全绿（含 `tests/fixtures/embedding-mock.py` 假 embedding 端）；
cap44/45/46 三个 E2E 脚本**未改一行**仍全绿（迁移种子端点生效）；知识库列表/详情页布局巡检通过
（`tests/e2e-layout-pages.mjs`，含详情页四个视图）。

**踩坑归档**：JPQL 批量 update 里用 `CURRENT_TIMESTAMP` 赋 `Instant` 字段会被 Hibernate 7 语义校验拒
（`Cannot assign expression of type 'java.sql.Timestamp' to target path ... of type 'java.time.Instant'`），
repository bean 创建期即失败、应用起不来；MySQL 方言下不触发（本地 dev 一直正常，H2 上必炸）。
时间戳一律由调用方传参。详见 [docs/core/开发注意事项.md](../core/开发注意事项.md)。
