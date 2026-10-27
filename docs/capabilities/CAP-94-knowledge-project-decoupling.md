# CAP-94 知识库去项目化（独立知识库 + 显式绑定消费）

> 能力 ID：CAP-94 ｜ 分类：管理/底座 ｜ 状态：已实现（2026-10-27，M1+M2 后端、M3 前端全量落地） ｜ 日期：2026-10-27
> 决策记录：**彻底废除 scope 概念**（`knowledge_bases.scope/project_id` 停写，所有库平等
> 独立，不再区分 global/project；存量 project 库原样保留为普通库，列保留不删防 rollback）；
> **消费 = 显式绑定**（检索与注入一律以消费方显式选择的 kbIds 为范围，删除一切按项目
> 自动圈范围的逻辑：`scopedKbIds`/`searchScopeKbIds`/检索 Filter.projectId/索引 projectId
> 字段）；**智能体工具无绑定不检索**（`search_knowledge` 删 projectId 参数、加可选 kbIds，
> 未指定时不检索、返回可用库清单让模型显式选择后重试）；**沉淀显式选目标库**（提案采纳
> 由 target=project/global 改为 targetKbId，项目仅作 sourceProject 溯源信息保留）。

## 1. 目的

知识库现行语义是「服务于项目」的：库分 global/project 两层、智能体工具按当前项目自动
圈定检索范围（全局库 + 本项目库）、会话产出沉淀按 target=project/global 分流进「项目
经验库」。这把知识资产绑死在项目维度上——跨项目的通用技术资产没有一等归属，项目库
也无法被其他项目或场景复用，且「自动圈范围」让知识流向不受控（任何项目会话都能隐式
摸到全局库 + 本项目库）。

CAP-44 已把知识库升格为一等容器、CAP-92 已把消费统一为检索命中注入，本能力走完最后
一步：知识库是完全独立的平台资产，谁要用知识（场景装配、知识问答、智能体工具、提案
沉淀）谁显式绑定/选择库，平台不再按项目做任何隐式分发。

## 2. 功能需求

### M1 后端：模型与检索去项目化

- **FR-01 scope 语义废除**：`KnowledgeBaseEntity.scope/projectId` 停写（列与常量保留，
  实体注释标注「CAP-94 停写，仅为兼容存量表结构保留」）；建/改库请求
  `KnowledgeBaseRequest.scope/projectId` 不再接受（传了 400 或静默忽略——取忽略，避免
  旧前端 422 炸街）；库视图 `KnowledgeBaseView` 不再输出 scope/projectId/projectName。
- **FR-02 条目归属简化**：条目读取不再经 KB 派生 scope/projectId（`derivedScope`/
  `derivedProjectId`/`EntryViews` 的 scope/projectId 输出删除）；`sourceProject`（沉淀
  来源项目）保留作纯溯源展示。条目历史列 `scope/project_id` 本就停写，不动。
- **FR-03 检索范围一律显式**：
  - `KnowledgeSearchService.scopedKbIds(projectId)` 删除；
  - `KnowledgeBaseService.searchScopeKbIds(projectId)` 删除，`search(q, projectId)` 改
    `search(q)`（范围 = 全部 active 库，管理台检索语义不变）；
  - `KnowledgeSearchIndex.Filter` 去掉 projectId 维度，`KnowledgeIndexService` 不再向
    Lucene 写 projectId 字段（存量索引段含该字段无害，随重建自然消亡，不强制重建）；
  - `KnowledgeSearchV2Request.projectId` 字段移除。
- **FR-04 条目列表 API 收敛**：`GET /api/knowledge/entries` 去掉 scope/projectId 参数
  （条目管理一律走 `/bases/{id}/entries` 库内视角；跨库搜索走 `/search/v2`）。

### M2 后端：消费侧显式绑定

- **FR-05 智能体工具显式选库**：`search_knowledge` 工具参数删 `projectId`、增
  `kbIds`（int 数组，可选，≤5）。未提供 kbIds → 不检索，返回「请指定知识库」+ 当前
  active 库清单（id+name+description，截断描述）让模型自选重试；提供后按显式列表检索。
  工具描述同步改写（不再出现「全局库 + 当前项目库」）。CAP-76 契约快照同步更新。
- **FR-06 提案显式选目标库**：
  - `ProposalRequest` 去 `targetScope/targetProjectId`，保留 `sourceSessionId`；新增可
    选 `sourceProject`（溯源标注，由发起方传）；
  - 采纳 `POST /proposals/{id}/adopt?targetKbId=`：必选目标库（404 不存在 / 409 已归档），
    直接建条目进该库；`resolveExperienceKb`（按 scope 找/建「项目经验库」「全局经验库」）
    删除；`adoptedTo/adoptedProjectId` 改记 `adoptedKbId`（旧列保留停写）；
  - 条目 `sourceProject` 由提案的 sourceProject 透传（不再由 target 推导）。
- **FR-07 装配链路确认无隐式项目语义**：`KnowledgeContextProvider` 本就只吃
  `scenarioKnowledgeBaseIds`（场景显式绑定），本次仅清理其 `baseScopes` 展示依赖
  （manifest item 的 scope 字段改输出库名或移除）；chat 多库绑定（CAP-84 FR-16）本就
  显式，不动。

### M3 前端：去项目维度

- **FR-08 库管理**：`KnowledgeBaseFormModal` 去掉 scope 选择与项目选择器；库列表/详情/
  设置抽屉去掉 scope 标签与项目名列（types/api 同步删字段）。
- **FR-09 提案页**：创建提案去「去向」选择；采纳操作改为弹选目标库（active 库下拉，
  必选项）；列表「去向」列改显示来源项目（溯源）。
- **FR-10 条目/检索视图**：条目列表去 scope/projectId 过滤参数；其余消费 UI（场景绑库、
  chat 绑库）本就显式，不动。

## 3. 否决方案

- **保留 projectId 仅作分组标签**：标签可以用 tags/命名约定承载，留一个半活的归属字段
  只会诱使后续需求重新长回隐式分发，违背本能力目的。
- **存量 project 库迁移合并进全局经验库**：库是内容容器，合并丢归属与索引血缘；原样
  保留为普通库零迁移成本，用户自行归档/改名即可。
- **智能体工具无绑定时检索全部 active 库**：等于把隐式分发放到最大（所有库对所有会话
  可见），比现状更失控；让模型显式选库多一轮往返，但边界清晰且工具返回的库清单本身
  就是可发现的。

## 4. 架构与模块归属

全部改动收敛在 `devmind-knowledge`（模型/检索/工具/提案）+ `frontend/src/features/knowledge`
+ CAP-76 契约快照测试。`devmind-common` 的 `KnowledgeRetriever` SPI 本就只收显式 kbIds，
不动；`ContextAssemblyRequest`/`KnowledgeContextProvider` 链路不动（仅 manifest scope 展示
微调）。无新模块。

## 5. 数据模型

无新表、无删列（ddl-auto 不删列，全部停写保留）：

| 表 | 变更 |
|----|------|
| knowledge_bases | `scope`/`project_id` 停写；存量行原样（scope='project' 的库即普通库） |
| knowledge_entries | 历史 `scope`/`project_id` 列本就停写，不动；`source_project` 继续作溯源 |
| knowledge_proposals | 新增 `adopted_kb_id`；`target_scope`/`target_project_id`/`adopted_to`/`adopted_project_id` 停写保留 |

## 6. API 概要

| 端点 | 变更 |
|------|------|
| `POST/PUT /api/knowledge/bases` | 请求体去 scope/projectId（忽略不报错） |
| `GET /api/knowledge/bases*` | 视图去 scope/projectId/projectName |
| `GET /api/knowledge/entries` | 去 scope/projectId 参数（保留 status） |
| `GET /api/knowledge/entries/search` | 去 projectId 参数 |
| `POST /api/knowledge/search/v2` | 请求体去 projectId |
| `POST /api/knowledge/proposals` | 请求体去 targetScope/targetProjectId，增 sourceProject |
| `POST /api/knowledge/proposals/{id}/adopt` | `target`+`projectId` → `targetKbId`（必填） |
| 工具 `search_knowledge` | 参数 projectId → kbIds（可选）；未指定返回库清单不检索 |

## 7. 安全

知识内容不再存在「项目库仅本项目可见」的隔离语义：所有 active 库对平台登录用户可检索
（管理台、显式绑定后的会话/问答/工具）。这与 global 库现状一致，但 scope=project 的存量
库可见性实质扩大——上线前需人工过一遍存量 project 库，敏感内容先归档。工具侧仍受
CAP-76 角色/风险门控（READ/VIEWER 不变）。

## 8. 里程碑

| 里程碑 | 内容 |
|--------|------|
| M1 | FR-01~FR-04 模型与检索去项目化 + 单测 |
| M2 | FR-05~FR-07 工具/提案显式绑定 + 契约快照与单测 |
| M3 | FR-08~FR-10 前端去项目维度 + tsc + 页面冒烟 |

## 9. 验收标准

1. 全库检索/装配/问答/工具链路中 grep 不到任何按 projectId 圈定知识范围的代码路径。
2. 建库表单无项目选择；存量 scope=project 库在列表/检索/绑定中表现与普通库一致。
3. `search_knowledge` 不传 kbIds 时返回库清单且不检索；传 kbIds 命中对应库内容。
4. 提案采纳必须显式选库，采纳后条目落在所选库且 sourceProject 溯源正确。
5. `mvn -q test` 全绿（含 CAP-76 契约快照更新）；`npx tsc -b` 通过。

## 10. 风险与开放问题

- **存量 project 库可见性扩大**：见 §7，需上线前人工审查（194 环境现存库数量少，可控）。
- **旧前端/外部调用传 scope/projectId**：写路径忽略不报错；读路径参数消失后旧链接
  query 无效但不报错（Spring 忽略多余参数）。
- **开放问题**：bot/网关侧（CAP-76）是否后续加「bot 绑定知识库」配置，让工具免选库——
  本 CAP 不做，待实际使用反馈再立。
