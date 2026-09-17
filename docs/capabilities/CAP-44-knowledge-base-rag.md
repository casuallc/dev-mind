# CAP-44 知识库容器化与向量检索（Knowledge Base + RAG）

> 新增能力（CAP-04 升级）。缘起：现有知识库只是扁平「经验条目」（scope=global/project + contentMd + tags），
> 无知识库容器概念、无检索能力，只能全量注入 CLAUDE.md。调研 WeKnora 后确立方向：
> 多知识库容器 + 条目重构归属 + 分块向量检索（RAG），为 CAP-45 飞书对接、CAP-46 知识库会话打底。

## 1. 目的

- 知识库（KnowledgeBase）成为一等容器：多库并存，条目归属库；库带归属语义（global/project）与消费方式
  （FULL=全量注入 CLAUDE.md，即原经验库语义｜RAG=仅检索）。
- 条目内容分块 + embedding 向量化，提供语义检索 API（topK + 阈值），支撑检索测试页与后续 KB 会话。
- 借鉴 WeKnora 前端编辑器，沉淀 shared MarkdownEditor 组件（工具栏 + 编辑/预览）。

**关键约束**：本机 H2 / 线上 PG，不依赖 pgvector——向量存 JSON CLOB，Java 内存余弦（KB 规模小）。
平台无模型管理体系，embedding 走 OpenAI 兼容端点配置（application-local.yml，密钥禁入库）。

## 2. 功能需求

- **FR-01 知识库容器**：新表 `knowledge_bases`（name/description/scope[global|project]/project_id/
  inject_mode[FULL|RAG]/embedding_model/status）。条目加 `kb_id` 归属；原条目 scope/project_id 语义上移到库
  （旧列保留停写，防 rollback 丢数据）。
- **FR-02 存量迁移（启动期，幂等）**：建「全局经验库」（global+FULL）；按存量条目 project_id 各建
  「XX 项目经验库」（project+FULL）；回填 kb_id。参照 `UserPlatformAccountMigration` 先例。
- **FR-03 注入零回归**：`KnowledgeContextProvider` 改为查 FULL 库条目，选择口径不变
  （global FULL 库按项目 tags 过滤 + 本项目 FULL 库全量）；现有 knowledge 测试全绿。
- **FR-04 摄入管线**：条目保存/内容变更 → 事件 + @Async（禁 @Transactional）→ 递归分隔符分块
  （chunkSize 800/overlap 100 默认）→ 批量 embedding → 删旧写新 `knowledge_chunks` →
  `index_status` ready/failed + error_message，可重试。FULL 库同样索引（inject_mode 只控制注入）。
- **FR-05 embedding 配置**：`knowledge.embedding.*`（baseUrl/apiKey/model/dimensions/chunkSize/chunkOverlap/
  topK/threshold）走 application-local.yml；`provider=mock` 用确定性哈希向量（测试/E2E，仿 runner
  executor=fake）。**未配置 = 降级 LIKE 关键词检索**，UI 明示。
- **FR-06 检索 API**：`KnowledgeRetriever` SPI 入 devmind-common
  （`retrieve(kbIds, query, topK) → List<RetrievedChunk{entryId, entryName, content, score}>`），
  knowledge 实现：查询向量化一次 → 按库过滤加载 chunks → Java 余弦 → 阈值 + topK。
  `POST /api/knowledge/search {kbIds, query, topK}` 供检索测试页。chunk 加载分批 + 上限截断防内存膨胀。
- **FR-07 库/条目管理 API**：`/api/knowledge/bases` CRUD、`/bases/{id}/entries` 条目 CRUD（沿用既有
  条目字段 tags/status/contentMd，新增 source/external_id/content_hash/index_status 列，
  飞书字段 CAP-45 才写值）。
- **FR-08 前端**：shared `MarkdownEditor`（工具栏包裹选区 + 编辑/预览 Segmented，预览复用
  `shared/components/Markdown.tsx`）；知识库页重构为库列表（含提案 inbox 视图）→ 库详情
  （条目｜检索测试｜设置；飞书导入页签 CAP-45 加）。

## 3. 关键设计

- **一个模型（已定）**：scope 语义迁移为库的归属属性，条目不再带 scope/project_id 语义；
  inject_mode 区分「经验库（注入）」与「文档库（检索）」两类消费，避免两套并行模型。
- **向量不落专用引擎（已定）**：JSON CLOB + Java 余弦，H2/PG 双库通用，无 pgvector 部署负担；
  规模假设 = 单库数千 chunk 内，超上限截断保护。
- **降级链（已定）**：embedding 未配置 → LIKE 检索；embedding 调用失败 → index_status=failed 可重试，
  检索跳过 failed 条目（退化 LIKE）。绝不让检索 5xx。
- **异步摄入（已定）**：保存不阻塞；@Async 方法禁 @Transactional（红线），靠 save 自身事务提交。
- **迁移幂等（已定）**：按 (name, scope, project_id) 查重建库，kb_id 已回填的条目跳过；重启安全。

## 4. 插件化接口

- `KnowledgeRetriever`（devmind-common SPI）：检索契约，knowledge 实现，chat（CAP-46）经 ObjectProvider 消费。
- embedding 无 SPI：`EmbeddingClient` 模块内接口（openai-compat / mock 两实现，配置切换）。

## 5. 数据模型

```
knowledge_bases(id, name, description, scope[global|project], project_id?,
                inject_mode[FULL|RAG], embedding_model?, status[active|archived],
                created_at, updated_at)
knowledge_entries + kb_id NOT NULL（迁移回填）
                + source[manual|feishu] DEFAULT 'manual'
                + external_id VARCHAR(256)?  + content_hash VARCHAR(64)?
                + index_status[pending|ready|failed] DEFAULT 'pending'  + index_error?
knowledge_chunks(id, kb_id, entry_id, chunk_index, content CLOB, embedding CLOB(JSON float[]),
                 token_count, UNIQUE(kb_id, entry_id, chunk_index), INDEX(kb_id))
```

红线：@ColumnDefault 字符串带引号；@Lob 必带 @JdbcTypeCode(SqlTypes.LONGVARCHAR)；
H2 保留字禁作列名；Boolean 列禁 @ColumnDefault。

## 6. API 概要

```
CRUD   /api/knowledge/bases                       库管理
GET    /api/knowledge/bases/{id}                  详情（含条目数/索引统计）
CRUD   /api/knowledge/bases/{id}/entries          库内条目
POST   /api/knowledge/entries/{id}/reindex        索引重试
POST   /api/knowledge/search {kbIds, query, topK} 检索测试（降级时返回 degraded=true）
兼容   /api/knowledge/entries /proposals /preview  旧端点保留（内部改走库模型），前端切换后下线
```

## 7. 验收标准

- 存量经验条目迁移后，会话注入预览内容逐字不变（E2E 断言）；
- 建库 → 建条目 → index_status=ready → search 语义命中且 score 有序；
- embedding 未配置时 search 降级 LIKE 且 degraded 标记，全程无 5xx；
- MarkdownEditor 在条目编辑/提案两处复用，工具栏语法插入与预览渲染正确。

## 8. 非目标

版本历史/diff（后续迭代）；pgvector/OpenSearch 等专用向量引擎；自动同步飞书（CAP-45 手动）；
KB 会话（CAP-46）；embedding 模型管理 UI（先配置文件）。
