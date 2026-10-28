# CAP-92 知识注入收敛为检索（下线全量注入，场景改绑知识库）

> 能力 ID：CAP-92 ｜ 分类：管理/底座 ｜ 状态：已实现（2026-10-26，M1+M2+M3 同批交付） ｜ 日期：2026-10-26
> 决策记录：**FULL 全量注入整体下线，知识消费统一走检索**。推翻 CAP-04 的「一次维护、
> 处处注入」整库注入口径：项目标签自动命中 + 场景知识标签 + 请求追加标签三路 FULL 选择
> 逻辑让每次会话上下文不可预期（库一大就顶满 CLAUDE.local.md），且与 CAP-46/CAP-84 已
> 验证的按问检索重复。**场景的知识绑定从「标签」改为「知识库」**：场景直接绑定若干库，
> 装配时以渲染后的任务说明（chat 为首条消息）检索，只注入命中片段；项目级自动命中与
> 请求级追加标签一并退役。**Chat 的 RAG 链路口径不变**（CAP-46 绑库 + 每轮检索）。
> 本能力修订 CAP-04（注入/预览）、CAP-33（场景三层来源中的 project-auto/request 知识层）、
> CAP-44（inject_mode 字段）、CAP-86（注入方式与注入预览 UI）。

## 1. 目的

知识库的消费路径此前有两条：

```
① FULL 全量注入（CAP-04）
   项目标签自动命中（global 无标签恒命中 + project 标签匹配）
   + 场景 knowledgeTags + 请求 extraKnowledgeTags
   → 选中条目整篇写进 CLAUDE.local.md（会话启动时一次性）
② RAG 按问检索（CAP-46/CAP-84）
   chat 绑定库 → 每轮提问检索分块 → <knowledge-context> 注入
```

实际使用中 ① 的问题：

1. **上下文不可预期**：命中的是整库整条，库越大注入越重；同名条目多时挤占任务说明与
   场景背景的空间，token 成本与噪声都高。
2. **两套消费语义并存**：同一批文档，会话走 FULL、问答走 RAG，命中逻辑与命中计数
   （hitCount）口径分裂；管理端还要维护「注入方式」配置与注入预览。
3. **标签选择器难维护**：项目标签、场景标签、请求标签三层叠加，命中顺序是隐式约定
   （②优先于③……），管理员无法从配置直观推出「这次会话会带哪些知识」。

收敛后只有检索一条路径：**知识库是检索范围，任务是查询**。场景负责「这次任务该参考
哪些库」，装配管线负责「按任务取命中的片段」。

## 2. 功能需求

### M1 下线 FULL 全量注入

- **FR-01 后端选择/预览逻辑退役**：删除 `KnowledgeBaseService.selectEntries(Project)`、
  `selectEntries(String, List)`、`selectByTags`、`preview` 与 `ClaudeMd` 组装器；
  删除 `GET /api/knowledge/preview` 与 `PreviewResult`；`KnowledgeContextProvider`
  不再实现 `ProjectAssetsProvider`（项目「上下文」页不再有知识条目视图），不再注入
  整库条目。
- **FR-02 模型/接口去注入方式**：`KnowledgeBaseRequest` / `KnowledgeBaseView` 去掉
  `injectMode`；创建/编辑库不再要求或校验注入方式。`KbOverview` 去掉 `injectMode`，
  chat 库概览不再展示「注入模式：…」。`KnowledgeBaseRepository` 的 FULL 查询方法删除，
  经验库解析改为按 scope/status 查询。
- **FR-03 场景知识标签退役**：`SessionScenarioEntity.knowledgeTags`（CSV）替换为
  `knowledgeBaseIdsJson`（JSON 数组，列 `knowledge_base_ids_json`）；`ScenarioRequest`/
  `ScenarioView` 的 `knowledgeTags` 字段改为 `List<Long> knowledgeBaseIds`；
  `CreateSessionRequest.extraKnowledgeTags` 与 `ContextAssemblyRequest.scenarioKnowledgeTags`/
  `extraKnowledgeTags` 删除，改为 `scenarioKnowledgeBaseIds` + `knowledgeQuery`。
  旧列 `knowledge_tags` 保留在存量库中但不再读取（不做标签→库的自动迁移，避免按标签
  猜库范围）。
- **FR-04 `inject_mode` 列兼容**：`knowledge_bases.inject_mode` 为 `nullable=false` 的
  存量列，保留 JPA 映射并恒写 `RAG`，避免 ddl-auto=update 不删列导致旧库插入失败；
  启动迁移把存量 `FULL`/`NULL` 归一为 `RAG`（幂等）。旧值仅作回滚兼容，不参与任何
  业务分支。
- **FR-05 前端注入面退役**：删除知识库设置抽屉的「注入方式」「预览注入效果」与二级
  抽屉、`InjectionPreview` 组件与 `previewInjection` API、库列表/详情/上传/新建问答
  下拉里的 FULL/RAG 标签；项目「上下文」页收为「文档 / Skills」两视图。

### M2 场景绑定知识库 + 装配时检索

- **FR-06 场景绑库**：场景编辑支持多选知识库（含已归档库可见但标注，归档库不参与
  检索）；列表「绑定资产」显示 `N 知识库`；场景介绍/表单说明改为检索口径。
- **FR-07 装配检索**：`KnowledgeContextProvider` 在每次上下文装配时：
  1. 入参为场景绑定的库 id 列表 + 检索 query（会话 = 渲染后的任务说明；chat = 首条
     消息；执行会话/瘦上下文传空库列表）；
  2. 调 `KnowledgeRetriever.retrieveDetailed(kbIds, query, topK)`（topK 取
     `devmind.knowledge.embedding.top-k`，默认 8），按 `entryId` 分组；
  3. 每个命中条目产出一条 manifest（`kind=knowledge`、`source=scenario`、
     `extra={kbId, kbName, chunks, sourceUri}`），并在 `CLAUDE.local.md` 渲染
     「相关知识（检索命中）」节：条目名 + `来源：path > headingPath（库：kbName）` +
     命中片段（多片段时间标 `**片段 N**`）；
  4. 非 dryRun 时对命中条目 `bumpHitsByEntryIds`（hitCount 语义从「被注入」改为
     「被检索命中」）；dryRun 预览不计数；
  5. 未绑库 / query 为空 / 检索器不可用 / 检索异常 / 零命中 → 只返回
     `SETTINGS_LOCAL_JSON`（权限设置照常物化），装配不失败。
- **FR-08 降级可观测**：检索异常 warn 后降级为 settings-only；库已归档时检索服务
  `resolveBases` 过滤，自然零命中。命中清单与原三层来源中的 `scenario` 语义一致，
  前端装配预览无需新增列。
- **FR-09 chat 链路不变**：CAP-46 的单库/多库绑定、每轮 `<knowledge-context>` 检索、
  库概览 `<knowledge-base>` 保持；仅概览文案去注入方式。Chat 的 `knowledgeTags` 是
  检索过滤标签（OR），与本次退役的「场景知识标签」不是同一概念，保留。

### M3 迁移与文档

- **FR-10 存量场景**：旧场景 `knowledge_tags` 不自动转换为库绑定；升级后这些场景
  不再注入知识，管理员按需在场景里重新绑定知识库（首版不做启发式迁移）。
- **FR-11 文档/索引**：新增本 CAP，回写 CAP-04/33/44/46/84/86 状态口径与能力索引；
  `tests/legacy/api/cap33_verify.py` 改为场景绑库检索断言，新增 `tests/legacy/api/cap92_e2e.py`；
  删除 `tests/e2e-injection-preview-drawer.mjs`，`tests/legacy/ui/e2e-context-asset-viewer.mjs`
  收为文档视图。

## 3. 否决方案

| 方案 | 否决原因 |
|---|---|
| 保留 FULL，仅限制条目数/字节 | 治标：仍有两套消费语义与两套命中计数；管理员继续维护注入方式与预览 |
| 保留项目自动命中，只删场景/请求标签 | 项目级命中仍是"猜范围"：项目标签与知识库没有稳定映射，命中不可预期 |
| 把场景 `knowledgeTags` 自动映射为「包含这些标签的条目所在库」 | 标签是条目级、库是范围级，自动映射会把无关库整库拉进检索范围；宁可让管理员显式重绑 |
| 连 RAG 一起删，知识库只做检索调试台 | 知识问答（CAP-46/CAP-84）是已验证的主要消费场景，删检索等于砍掉能力；本次只删全量注入 |
| 删除 `inject_mode` 列 | 存量表 `nullable=false`，ddl-auto 不删列；直接删映射会让旧库插入失败，保留恒 RAG + 迁移归一更安全 |

## 4. 架构与模块归属

- `devmind-common`
  - `ContextAssemblyRequest`：`scenarioKnowledgeBaseIds` + `knowledgeQuery` 取代两个标签列表。
  - `KnowledgeRetriever.KbOverview`：去 `injectMode`。
- `devmind-knowledge`
  - `KnowledgeContextProvider`：重写为「场景绑库 → 检索 → 分组渲染 + manifest + 计数」；
    保留 `SETTINGS_LOCAL_JSON` 契约（瘦上下文/执行会话仍拿到权限设置）。
  - `KnowledgeBaseService`：删全量选择/预览；新增 `baseScopes(kbIds)`（清单展示 scope）
    与 `bumpHitsByEntryIds(entryIds)`（仅命中条目计数）。
  - `KnowledgeBaseEntity` / DTO / Repository / Controller：去注入方式与 `/preview`。
  - `KnowledgeBaseMigration`：新库写 `RAG`，存量 `FULL→RAG` 归一；旧场景标签列不读。
- `devmind-session`
  - `SessionScenarioEntity` / `ScenarioService` / `ScenarioRequest` / `ScenarioView`：
    场景绑库 JSON 字段与读写。
  - `SessionContextService` / `ScenarioPreviewService` / `SessionManagerService`：
    装配请求组装检索参数；`CreateSessionRequest` 去 `extraKnowledgeTags`。
- `frontend`
  - 知识库：新建/设置/列表/详情/上传/新建问答去注入方式与预览；`InjectionPreview` 删除。
  - 场景：`knowledgeBaseIds` 多选与列表计数；装配预览沿用 manifest 表。
  - 项目上下文：知识条目视图移除，保留文档/Skills 与文档查看抽屉。

## 5. 数据模型

- `knowledge_bases.inject_mode`：保留列（`VARCHAR(8) NOT NULL`，实体默认 `"RAG"`），
  启动时 `WHERE inject_mode IS NULL OR inject_mode <> 'RAG'` 归一为 `RAG`。
- `session_scenarios.knowledge_base_ids_json`：`LONGVARCHAR`，JSON 数组文本
  （如 `[3,7]`）；旧列 `knowledge_tags` 保留但不映射。
- 无新表、无破坏性 DDL；H2/PostgreSQL 同构（沿用 `ddl-auto=update`）。

## 6. API 概要

| 端点 | 变化 |
|---|---|
| `GET/POST/PUT /api/knowledge/bases` | 请求/响应去掉 `injectMode` |
| `GET /api/knowledge/preview` | **删除**（全量注入预览下线） |
| `GET/POST/PUT /api/scenarios` | `knowledgeTags: string[]` → `knowledgeBaseIds: number[]` |
| `POST /api/sessions` | 去掉 `extraKnowledgeTags` |
| `GET /api/projects/{id}/context-assets` | 只聚合 docs/skills（knowledge provider 不再实现 SPI） |
| `GET /api/scenarios/{code}/preview` | 装配清单可出现 `kind=knowledge, source=scenario` 的检索命中项；`claudeMd` 含「相关知识（检索命中）」节 |

## 7. 安全

- 场景/会话/问答的既有权限与项目归属校验不变；检索范围仅限场景显式绑定的库。
- 检索 query 截断到 1000 字符（防止超长任务说明打爆检索）；命中内容按纯文本渲染，
  不引入 HTML 注入面。
- 归档库在检索服务端过滤，不能通过手工拼 kbIds 绕过归档状态。
- 检索异常只降级为 settings-only 并记录 warn，不向模型回传内部异常细节。

## 8. 里程碑

- **M1 下线全量注入**：FR-01~FR-05（后端选择/预览/模型字段 + 迁移归一 + 前端注入面退役）。
- **M2 场景绑库检索**：FR-06~FR-09（场景字段/表单 + Provider 检索渲染 + manifest/计数 +
  chat 口径保持）。
- **M3 迁移与文档**：FR-10~FR-11（存量场景说明 + CAP/索引/测试脚本同步）。

## 9. 验收标准

1. 知识库创建/编辑/列表/详情/上传/新问答不再出现「注入方式/FULL/RAG」，`/knowledge/preview` 404；
2. 场景可选择多个知识库并回显 `knowledgeBaseIds`；列表显示 `N 知识库`；
3. 场景绑定库后，dryRun 预览按任务检索命中：manifest `source=scenario`，
   `CLAUDE.local.md` 含「相关知识（检索命中）」与来源/库名，hitCount 不涨；
4. 真实会话/问答装配命中后 hitCount 上涨；未绑库、query 空、检索失败、零命中均正常
   装配（只落权限设置，不报错）；
5. 场景解绑后不再检索；绑定归档库不命中（归档退出检索）；
6. 存量 `inject_mode=FULL` 启动后归一为 `RAG`；旧场景不因缺少新字段报错，仅不再注入知识；
7. 全量 `mvn test` 绿、前端 `npx tsc -b` 绿；`tests/legacy/api/cap92_e2e.py` 与更新的
   `tests/legacy/api/cap33_verify.py` 覆盖预览/真实装配/不计数/解绑/归档。

## 10. 风险与开放问题

| # | 事项 | 口径 |
|---|------|------|
| R1 | 旧场景升级后知识不再自动注入（无标签迁移） | 接受：显式绑库更可预期；升级说明提示管理员重绑，不猜测范围 |
| R2 | 检索质量决定上下文质量（关键词/向量命中不到就没有知识） | 接受：CAP-84 已有混合检索与召回验证；后续按反馈调 topK/查询构造 |
| R3 | hitCount 语义从「注入」变为「检索命中」，历史数值不可直接对比 | 接受：字段名不变，UI 文案统一为「命中」；历史计数保留 |
| R4 | 每次装配同步检索增加创建会话延迟 | 观测：topK 默认 8、query 截断 1000 字；本地 Lucene 检索毫秒级，异常降级不阻塞 |
| O1 | 按任务检索对长任务说明的 query 构造（截断/关键词提取） | 开放：首版取渲染后任务尾部 1000 字，视命中质量再引入 query 改写 |
| O2 | 库级检索参数（topK/过滤）是否开放到场景 | 开放：首版沿用平台 topK，场景级覆盖待有诉求再立 |
