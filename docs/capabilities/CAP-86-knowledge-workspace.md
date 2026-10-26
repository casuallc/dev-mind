# CAP-86 知识库管理台（内容 / 运营两区模型）

> 能力 ID：CAP-86 ｜ 分类：管理/前端体验 ｜ 状态：**v3 已交付**（v1 单工作台、v2 四子菜单菜单组均已否决/取代） ｜ 日期：2026-10-24（v3 重构定稿与实施 2026-10-25）
> 上游能力：CAP-84（知识管理 v2）、CAP-85（知识治理与增量同步）、CAP-44（库容器化）、CAP-46（知识库 AI 会话）
>
> 后续修订（CAP-92）：库设置不再含「注入方式 / 注入预览」，归档语义统一为「退出检索（含场景绑定）」；
> 下文 v3 决策记录中涉及注入预览的描述以 CAP-92 为准。
> 决策记录（v3，替代 v1/v2 全部导航决策）：
> 1. **消费归 AI 问答**：知识的消费（搜索答案、多轮追问、方案沉淀）已由 CAP-46/84 M3 在
>    `/chats` 闭环。知识库页面是**纯管理员工具**，页内检索只保留为**召回验证**调试手段。
> 2. **两区模型 + 质量报告独立**：后台新增独立菜单组「知识库」，组内三项「知识库」「知识运营」「质量报告」——
>    知识库回答「这个库喂什么」，知识运营回答「现在该处理什么」，质量报告集中看跨库质量。
>    自动增强是运营动作而非测量，归「知识运营」第三页签，使质量报告只保留可评测的四类指标
>    （生命周期 / 近重复 / 答案反馈 / 检索评测）。撤销 v2 按系统拆分的四个子菜单
>    （知识库总览 / 文档导入 / 召回验证 / 治理报告）。
> 3. **待办是运营默认页，且只收能闭环的动作**：跨库聚合待复核、增强失败与负面反馈，页面只保留
>    类型筛选与队列；已过期 / 临期 / 缺负责人没有补录入口，不进入队列，到生命周期报告查看。
> 4. **库详情三页签，库设置上移到列表**：库详情按内容来源拆为文档 / 手动上传 / 飞书同步——
>    手动上传只看本库上传批次，飞书同步放同步源（Git / 飞书增量）与飞书文档手动导入。
>    库设置（含归档、注入预览、删除库）改为库列表操作列「设置」抽屉，撤销 v2 的「摄入 / 同步」
>    双页签、详情设置页签、内容来源内层 Segmented 与多处重复上传入口。
> 5. **动作按任务折叠**：库详情顶部状态条异常时给出直达动作（失败 → 筛选失败条目、需重建 →
>    一键重建）；正常态只留「添加内容」下拉（上传 / 新建 Markdown / 飞书导入）。所有更新类
>    操作统一居中 `Modal.confirm` 二次确认。
> 6. **深链与权限边界不变**：仍仅 ADMIN 可达；v1/v2 旧链接全量重定向；聊天引用
>    `/knowledge?entryId=&chunkId=` 继续由库列表页打开详情抽屉。

## 1. 目的

v2 把工作台拆成四个子菜单后，试行中仍暴露结构性问题：

- **按系统分组，而非按任务**：管理员每次先判断「这属于哪个页面」（总览 / 导入 / 检索 / 治理），
  而真实任务是「看库状态 / 喂内容 / 处理质量问题 / 验证召回」；
- **入口重复**：上传入口同时出现在总览行、库详情、导入页；飞书导入又在库详情与治理页各有一份；
- **治理只解决「看」**：待复核、增强失败、负反馈、失败批次分散在报告和库详情，缺少一个
  「现在该做什么」的收件箱，管理员要自己巡检；
- **库详情页签按数据源拆**：上传批次与同步源同属「内容从哪来」，却被拆成「摄入 / 同步」两个
  页签，还要和顶部「添加内容」重复对照；
- **术语仍有工程腔**：摄入、治理、索引失配、未启用等词直接暴露。

v3 的根因修正：把管理员心智从「系统分层」改为两问——**这个库喂什么（知识库）**、
**现在有哪些质量问题要处理（知识运营）**。

## 2. 功能需求

### M1 两区菜单与路由

- **FR-01 菜单与路由**：后台侧边栏新增独立菜单组「知识库」（antd `type: 'group'`，
  组名常驻展示、三项直挂组下，仅 ADMIN 可见）：

  | 菜单组 | 子菜单 | 路由 | 内容 |
  |---|---|---|---|
  | 知识库 | 知识库 | `/admin/knowledge` | 库列表（默认页）+ 库详情 `/admin/knowledge/bases/:id` |
  | 知识库 | 知识运营 | `/admin/knowledge/ops` | 待办（`?tab=tasks`，默认）/ 召回验证（`?tab=search`）/ 自动增强（`?tab=enrich`） |
  | 知识库 | 质量报告 | `/admin/knowledge/reports` | 跨库质量报告（`?view=lifecycle|duplicates|feedback|eval`） |

  所有路由沿用 `RequireAdmin`；`menuSelectedKey` 将 `/admin/knowledge/ops` 及其子态映射到
  「知识运营」，`/admin/knowledge/reports` 映射到「质量报告」，其余 `/admin/knowledge*`（含库详情）映射到「知识库」。
- **FR-01a 页面骨架统一**：所有页面遵循《前端内容区布局约定》骨架——Card 默认密度、`title` =
  页名（多视图在 title 内放 Segmented）、动作与过滤收进 `extra`、body 顶部一行 `type="secondary"`
  说明、表格默认密度 + `FitTable`、行操作为纯文字按钮。

### M2 知识运营中心

- **FR-02 待办队列**（`/admin/knowledge/ops?tab=tasks`，默认视图）：
  - 三路并行聚合（`Promise.allSettled`，部分失败只提示不阻塞）：生命周期报告、
    增强状态、负面反馈 Top；页内不设统计卡/入口卡，队列即全部内容；
  - 类型筛选 Segmented：全部 / 待复核 / 增强失败 / 负面反馈（带计数）；
  - 待办表格：类型 / 对象（开文档详情抽屉）/ 知识库 / 待办说明 / 时间 / 操作；
    操作分别是「登记复核」「重试增强」「查看文档」，前两者居中 `Modal.confirm`；
  - 支持 `?kbId=` 按库过滤，页头展示可关闭的「仅看：库名」标签；无待办时显示健康态空态；
  - 队列**只收能闭环的动作**：已过期 / 临期 / 缺失负责人不进入队列（无补录入口），
    到生命周期报告查看。
- **FR-03 召回验证**（`?tab=search`）：复用跨库检索视图（库/类型/项目/标签过滤、文档级聚合、
  高亮片段、降级原因告警、命中开详情抽屉）；`?kbId=` 预选单库，替代原库内「检索测试」。
  文案明示：日常问知识走 AI 问答，本页只做召回调试。
- **FR-03a 自动增强**（`?tab=enrich`）：从质量报告页并入运营页（属动作而非测量）。展示增强配置
  （自动排队 / CHAT 端点 / 单批上限 / 正文截断）、按库与条数触发「立即增强」、增强状态表
  （已增强 / 待增强 / 人工锁定 / 失败原因）与逐条「补全」；批量与单条均居中 `Modal.confirm`。
  旧 `?view=enrich` 与 `?tab=reports&view=enrich` 均重定向到本页。
- **FR-04 质量报告独立页**（`/admin/knowledge/reports`）：独立菜单入口，聚合跨库质量测量结果，
  Card title 内 Segmented 四视图（生命周期 / 近重复 / 答案反馈 / 检索评测），URL `?view=` 可深链；
  一次只渲染当前视图。自动增强归知识运营；同步源与上传批次在库详情「手动上传 / 飞书同步」维护。
  运营页的到期 / 近重复 / 评测入口直接跳本页对应视图；旧 `?tab=reports` 自动重定向到本页。

### M3 库详情与库设置抽屉

- **FR-05 库详情页** `/admin/knowledge/bases/:id`：Card `title` = 库名 + Segmented
  （文档 / 手动上传 / 飞书同步，`?tab=upload|feishu`）；`extra` = 用此库问答 / 刷新 /「添加内容」下拉
  （上传文件·文件夹·ZIP / 新建 Markdown 文档 / 从飞书导入）/ 返回列表。
  body 顶部状态条：注入方式（全量注入 / RAG 检索）、范围（全局 / 所属项目）、向量端点与维度、
  `已索引 x/y`、待索引数、异常入口（`失败 N · 去处理`、`需重建 N · 一键重建`、
  `未配置端点 N`）、`召回验证` 与 `库待办` 深链、库描述。
  - **文档**：服务端分页条目表 + 关键字/类型/来源/索引状态过滤；行点击开详情抽屉；
    行操作编辑、更多（重建索引 / 重同步飞书内容 / 删除）；索引失败行内提供「重试」。
  - **手动上传**：沿用 `KnowledgeImportsView` 展示本库上传批次（固定库过滤，失败单条/整批重试）；
    上传入口在顶部「添加内容」下拉（文件 / 文件夹 / ZIP、新建 Markdown）。
  - **飞书同步**：同步源含 Git / 飞书增量同步 CRUD 与运行，以及飞书文档手动导入（选集成 → 粘贴
    URL → 按内容哈希判重入库）。
- **FR-05a 库设置抽屉**：库列表操作列「设置」打开抽屉（深链 `?settings=<id>`），含名称、
  描述、注入方式（全量注入 / RAG 按需检索）、向量端点覆盖、状态（启用 / 归档停用）、
  注入预览与危险区删除库；范围与所属项目创建后不可改。保存与删除均为居中 `Modal.confirm`，
  保存后列表行即时更新。
- **FR-06 状态条即操作入口**：只有异常存在时才出现动作链接；正常库不堆按钮。
  归档库在文档页顶部给出 Alert，指引到库列表「设置」把状态改回启用。

### M4 术语、确认与兼容收口

- **FR-07 路由兼容**：
  - `/knowledge` → `/admin/knowledge`（保留 `entryId/chunkId` 深链）；
  - `/admin/knowledge?view=imports` → `/admin/knowledge/ops?tab=tasks`；
    `?view=governance` → `/admin/knowledge/reports?view=lifecycle`；
  - `?baseId=:id&tab=test` → `/admin/knowledge/ops?tab=search&kbId=:id`；
    `?baseId=:id&tab=import` → `/admin/knowledge/bases/:id?tab=upload`；
    `?baseId=:id&tab=sync` → `/admin/knowledge/bases/:id?tab=feishu`；
    `?baseId=:id&tab=settings` 与库详情 `?tab=settings` → `/admin/knowledge?settings=:id`（列表抽屉）；
  - `/admin/knowledge/imports` → `/ops?tab=tasks`；`/admin/knowledge/search` → `/ops?tab=search`；
    `/admin/knowledge/health` → `/admin/knowledge/reports?view=lifecycle`；
    `/admin/knowledge/ops?tab=reports&view=` → `/admin/knowledge/reports?view=`；
    其中 `view=enrich` 与 `/admin/knowledge/reports?view=enrich` → `/admin/knowledge/ops?tab=enrich`。
- **FR-08 术语人话化**：摄入 → 手动上传 / 飞书同步；治理报告 → 质量报告；未启用 → 启用 / 归档停用；
  索引失配 / 维度未探测 → 「需重建」+ 一键重建；`FULL/RAG` → 全量注入 / RAG 检索；
  健康点与失败标签统一「状态 + 下一步动作」。
- **FR-09 更新操作二次确认**：知识库与知识运营范围内所有更新动作（导入、重试、重建、
  重同步、登记复核、补全增强、保存设置、归档、删除、同步源增删改/启停）统一居中
  `Modal.confirm`，文案说明影响范围与可逆性。

## 3. 否决方案

| 方案 | 否决理由 |
|---|---|
| v2 四子菜单（总览 / 导入 / 检索 / 治理） | 仍按系统分层：管理员先找页面而非做任务；上传与飞书入口重复；治理只解决「看」，缺待办收件箱 |
| 只保留质量报告、不设待办 | 管理员需要自己巡检生命周期/增强/反馈/批次四处，质量问题无人认领 |
| 待办包含已过期 / 临期 / 缺负责人 | 这些项没有补录入口，点「登记复核」也不会离队，违背「处理完即消失」；改为只在生命周期报告查看 |
| 库详情保留「摄入 / 同步」双页签 | 工程术语、与顶部添加内容重复；改为按管理员动作命名的「手动上传 / 飞书同步」双页签 |
| 新增后端待办聚合接口 | 三路数据已有现成接口，前端 `allSettled` 聚合足够；纯前端改造，后端零改动 |
| 恢复 `/knowledge` 前台只读检索 | 消费归 AI 问答；两入口的过滤/权限会再次漂移 |

## 4. 架构与模块归属

纯前端重排 + 组件下沉复用，不新增表、不改后端接口：

- 前端 `frontend/src/features/knowledge`：
  - `pages/KnowledgeOpsPage.tsx`（新增）：知识运营中心，含待办队列、类型筛选、召回验证、自动增强与
    文档详情抽屉；
  - `pages/KnowledgeReportsPage.tsx`（新增）：质量报告独立页，Card title 内四视图 Segmented + `?view=`；
  - `components/KnowledgeReportsPanel.tsx`（由 `pages/KnowledgeHealthPage.tsx` 下沉）：
    四视图受控面板（仅接收 `view`），不再自带 Card 与视图切换；
  - `components/KnowledgeEnrichView.tsx`（由质量报告面板拆出）：自动增强视图（配置 / 批量触发 / 状态表 /
    逐条补全），供运营页第三页签复用；
  - `pages/KnowledgeBaseListPage.tsx`：库列表 + 深链宿主 + 操作列「设置」抽屉（`?settings=`）；
    索引健康列「失败」链到库详情 `?tab=entries&indexStatus=failed`，「需重建」链到库详情状态条一键重建；
  - `pages/KnowledgeBaseDetailPage.tsx`：库详情三页签（文档 / 手动上传 / 飞书同步）+ 状态条 + 添加内容下拉；
  - `components/KnowledgeBaseSettingsDrawer.tsx`（新增）：库设置 + 注入预览 + 危险区删除；
  - 删除 `pages/KnowledgeImportsPage.tsx`、`pages/KnowledgeSearchPage.tsx`（整页化不再需要）；
  - 复用：`KnowledgeImportsView`、`KnowledgeSearchView`、`KnowledgeUploadDrawer`、
    `KnowledgeEntryDrawer`、`KnowledgeEntryEditorDrawer`、`SyncSourcesPanel`、`InjectionPreview`
    （注入预览由设置抽屉内嵌二级抽屉承载）。
- 路由与菜单：`app/App.tsx`（两区路由 + 旧链重定向）、`app/AdminLayout.tsx`（知识库菜单组）、
  `app/menuSelectedKey.ts`（前缀映射）。
- 后端 `devmind-knowledge`：零改动。

## 5. 数据模型

无表结构变更。库内文档分页继续复用 `EntrySummaryView` 摘要投影（不含正文 LOB）。

## 6. API 概要

全部复用既有接口：

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/knowledge/bases` | 库列表与统计 |
| GET | `/api/knowledge/bases/{id}` | 库详情头部 / 索引统计 |
| GET | `/api/knowledge/bases/{id}/entries/page` | 库内文档摘要分页 |
| GET | `/api/knowledge/entries/{id}` | 文档详情 / 编辑前加载正文 |
| POST | `/api/knowledge/search/v2` | 召回验证 |
| GET | `/api/knowledge/imports?kbId=` | 库内上传批次（手动上传页签） |
| GET | `/api/knowledge/admin/lifecycle?expiringDays=&staleDays=` | 待办复核队列 + 到期/负责人计数 |
| GET | `/api/knowledge/admin/enrich/entries?kbId=&limit=` | 增强失败队列 |
| GET | `/api/knowledge/admin/feedback/negative?days=&limit=` | 负面反馈队列 |
| POST | `/api/knowledge/entries/{id}/review` | 登记复核 |
| POST | `/api/knowledge/admin/enrich/entries/{id}` | 单条补全增强 |
| GET/POST | `/api/knowledge/admin/sync-sources...` | 同步源（库详情「飞书同步」按 kbId 过滤） |
| GET | `/api/knowledge/admin/...` | 质量报告其余区块 |

## 7. 安全

- 全部路由沿用 `RequireAdmin`；菜单组仅 ADMIN 渲染。
- 摘要投影不含正文 LOB 的暴露面控制保持不变。
- 同步源 / 端点凭证仍在既有密文与权限体系内，入口收敛不新增暴露面。

## 8. 里程碑

- **M1 两区骨架**：FR-01、FR-01a（菜单与路由收敛、页面骨架、旧链重定向）。
- **M2 运营中心与质量报告**：FR-02~FR-04（待办收件箱、召回验证、自动增强、质量报告独立页四视图）。
- **M3 库详情与设置抽屉**：FR-05、FR-05a、FR-06（三页签、手动上传与飞书同步分离、状态条动作、设置上移列表）。
- **M4 收口**：FR-07~FR-09（术语、确认弹窗、`tsc -b` 与浏览器主流程验证）。

## 9. 验收标准

> 验收记录（2026-10-25 v3 实施交付）：`tsc -b` 通过；后端零改动。
> 浏览器实测：侧边栏「知识库」菜单组下含知识库 / 知识运营 / 质量报告三项且高亮正确；库列表索引异常可直达库详情处理；
> 运营三视图（待办/召回验证/自动增强）与质量报告独立页四子视图可切换、`?tab=&view=&kbId=` 深链生效；
> 待办三路数据聚合、类型筛选、库过滤「仅看」标签、文档详情抽屉正常；自动增强批量与逐条操作均居中二次确认；
> 「登记复核」居中二次确认并成功提交；库详情三页签、库列表「设置」抽屉、手动上传批次与飞书同步、
> 状态条「去处理 / 一键重建」入口正常；`?view=`、`?baseId=&tab=`、`/imports|/search|/health`
> 旧链均落到新位置，`?view=enrich` 落到运营页自动增强。

- `tsc -b` 通过；后端 `devmind-knowledge` 零改动（回归不破）。
- 手工验收：
  1. 后台「知识库」菜单组下含「知识库」「知识运营」「质量报告」三项，任一子路径刷新/直达保持正确高亮；
  2. 知识运营默认落待办，页面只有类型筛选与待办表格（无统计卡/入口卡）；处理完
     待复核/增强/反馈后刷新队列即消失；
  3. 待办按 `?kbId=` 过滤时队列同步收窄，质量报告页明示跨库；
  4. 召回验证 `?kbId=` 预选生效，降级告警与高亮片段正常；
  5. 自动增强页签可查看增强配置、按库批量触发、逐条补全，操作均二次确认；
  6. 质量报告独立页四视图可用，`?view=` 深链可刷新保持；同步源/批次与自动增强不在质量报告页出现；
  7. 库详情三页签齐备，手动上传可看批次与重试，飞书同步可维护同步源并手动导入；列表「设置」抽屉可改注入方式 /
     向量端点 / 状态、预览注入并删除库，保存与删除均二次确认；`?settings=` 与旧 `?tab=settings` 直达抽屉；
  8. v1/v2 旧链（`?view=`、`?baseId=&tab=`、`/imports|/search|/health|ops?tab=reports`）全部重定向正确，
     `?view=enrich` 落到 `/admin/knowledge/ops?tab=enrich`。

## 10. 风险与开放问题

| ID | 风险/问题 | 口径 |
|---|---|---|
| R1 | 待办首屏并发三个聚合接口 | `Promise.allSettled`；单路失败仅提示不阻塞，其余队列照常展示 |
| R2 | 队列只收可闭环项，可能让人忽略过期/缺负责人 | 运营页不设入口卡，页头说明指向生命周期报告；质量报告入口在「质量报告」菜单 |
| R3 | 待办可按库过滤，而质量报告是跨库的 | 质量报告页不展示「仅看」标签，页头文案明示跨库，避免误读 |
| R4 | 菜单从四个减到三个，召回验证发现性下降 | 库详情状态条、运营页内 Segmented 双入口；`?kbId=` 深链保留 |
| R5 | v1/v2 外链与书签失效 | FR-07 全量重定向覆盖；`tab=import|sync` 映射到手动上传 / 飞书同步，`tab=reports` 映射到质量报告页，`view=enrich` 映射到运营页自动增强 |
| O1 | 待办刷新实时性 | 首屏与手动刷新拉取；不做常驻轮询，避免三路聚合请求常开 |
| O2 | 普通用户检索诉求 | 维持 CAP-84/86 口径：AI 问答绑库满足；引用标签非 ADMIN 不可点 |
