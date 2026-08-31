# CAP-13 研发主线（Requirement / Design / Work Item）

> 能力 ID：CAP-13 ｜ 分类：管理 ｜ 状态：草案 ｜ 日期：2026-08-31

## 1. 目的

承载「项目有了新需求 → AI 分析、设计、拆分、执行 → 验收」的主线模型。替代原 Task 主线
（Task 内嵌 Requirement，目标与工作单元压成一层，复杂需求表达不了）。只做**身份 + 状态 + 关联**，
不含流程引擎（编排属上层 Orchestrator，后续作为组合层叠加）。

术语约定：

| 术语 | 角色 | 说明 |
|---|---|---|
| Requirement | 业务目标 | 一条新需求，主线关系的根 |
| Design | 解决方案（产物） | 复杂需求的方案实体（指向 CAP-03 文档 + 版本 + 状态），是拆 Work Item 的输入；简单需求可跳过 |
| Work Item | 工作单元 | 可派发给 agent/人执行的最小单位，类型：DESIGN / DEVELOPMENT / TEST / DOCUMENT / REVIEW |
| Session | 执行过程 | Work Item 的执行（CAP-05）；**分析型会话直挂 Requirement，不算 Work Item** |
| Artifact | 工作产物 | 一等实体（CAP-13 扩展 artifacts 表）：PACKAGE / DOC / CODE_DIFF / TEST_REPORT / REVIEW / ANALYSIS |
| Relation | 横向关系 | 通用边表，把研发过程串成可追溯的网 |

结构原则：**归属用外键（主干层级），追溯用 Relation（横向稀疏边）**。
「design 类型的 Work Item」是做设计这件事，其产出 Artifact/文档即 Design 方案。

## 2. 功能需求

- **FR-01 Requirement CRUD**：title/description（即需求内容）、ownerId、关联需求文档 docId；
  seq 项目内自增（展示 REQ-\<seq\>，(project_id, seq) 唯一）。
- **FR-02 Requirement 状态**：DRAFT / ANALYZING / DESIGNING / IN_PROGRESS / ACCEPTANCE / DONE / CANCELLED。
  状态**派生聚合为主**：Work Item 状态变化时自动重算——有未完成的 DESIGN 项→DESIGNING；
  有执行中项→IN_PROGRESS；全部 DONE→ACCEPTANCE。仅 `ACCEPTANCE→DONE`（人工验收）与
  CANCELLED 为人工翻转，rollup 不覆盖这两个人工终态。
- **FR-03 Design CRUD**：挂在 Requirement 下（requirementId），docId 指向 CAP-03 方案文档，
  version 递增，status = DRAFT / CONFIRMED / DISCARDED。复杂需求一份 CONFIRMED 方案是拆
  Work Item 的依据；可多次迭代（老版本 DISCARDED）。
- **FR-04 Work Item CRUD**：挂在 Requirement 下（requirementId，可挂 designId）；type 五类；
  spec 为执行输入（起 Session 时作为 taskSpec 注入，拆分时由 AI 生成、人可编辑）；
  seq 项目内自增（WI-\<seq\>）；DEVELOPMENT 型有 branchSlug（分支约定 `wi/<seq>-<slug>`）。
- **FR-05 Work Item 状态**：TODO / IN_PROGRESS / BLOCKED / DONE / CANCELLED，
  人工/API 驱动，转换路径不写死；状态变化触发所属 Requirement 的 rollup 重算。
- **FR-06 Relation 通用边**：(fromType, fromId) → (toType, toId) + relationType，预置
  `depends_on`（Work Item 间，编排调度依据）、`implements`（WI→Design）、
  `verifies`（test/review WI → development WI）、`fixes`（返工 WI → 未通过的 WI）、
  `produced_by`（Artifact→Session）。类型可扩展，不为新关系加表。
- **FR-07 Artifact 登记**：扩展 artifacts 表为全类工作产物登记表：type 扩为
  PACKAGE / DOC / CODE_DIFF / TEST_REPORT / REVIEW / ANALYSIS；storage/path 可空
  （信息类产物无存储实体，path 存引用如 docId/sessionId）；producer_type 扩为
  BUILD / SESSION / TEST_RUN / DOC / MANUAL；归属挂 work_item_id 或 requirement_id（分析产物）。
- **FR-08 关联约定**：各执行器/会话/文档记录的关联字段统一为 `workItemId`
  （替代原 taskId）；写关联时校验与 projectId 一致，projectId 空时经
  workItem→requirement 两级反推。

## 3. 插件化接口

- 对外提供 `MainlineContext(requirementId) → {requirement, designs, workItems, relations}`
  聚合查询，供 Orchestrator/看板/概览层使用。
- Requirement rollup 规则集中在 RequirementService.recomputeStatus()，上层可替换。

## 4. 依赖关系

- 依赖：CAP-01（actor）、CAP-02（项目归属）、CAP-03（docId 引用）。
- 被依赖：CAP-05（会话挂 work_item_id/requirement_id）、CAP-08/09/10/11（执行记录挂
  work_item_id）、流程层 Orchestrator（消费 depends_on DAG 调度）。

## 5. 数据模型

```
requirements(id, project_id, seq, title, description, status,
             owner_id, doc_id, created_by, created_at, updated_at)
designs(id, project_id, requirement_id, doc_id, version, status,
        created_by, created_at, updated_at)
work_items(id, project_id, requirement_id, design_id?, seq, type, title, spec,
           status, owner_id, branch_slug, created_by, created_at, updated_at)
relations(id, project_id, from_type, from_id, to_type, to_id, relation_type, created_at)
artifacts(id, project_id, requirement_id?, work_item_id?,    -- 扩展既有表
          type[PACKAGE|DOC|CODE_DIFF|TEST_REPORT|REVIEW|ANALYSIS],
          name, version?, checksum?, storage?, path?,
          producer_type[BUILD|SESSION|TEST_RUN|DOC|MANUAL], producer_id?,
          created_by, created_at)
```

## 6. API 概要

```
CRUD   /projects/{pid}/requirements
PUT    /projects/{pid}/requirements/{id}/status     人工翻转（DONE/CANCELLED）
GET    /projects/{pid}/requirements/{id}/overview   主线聚合（app 组装层）
CRUD   /projects/{pid}/requirements/{id}/designs
PUT    /projects/{pid}/requirements/{id}/designs/{did}/status
CRUD   /projects/{pid}/requirements/{id}/work-items
PUT    /projects/{pid}/requirements/{id}/work-items/{wid}/status
GET    /projects/{pid}/relations?fromType=&fromId=
POST   /projects/{pid}/relations        {fromType,fromId,toType,toId,relationType}
DELETE /projects/{pid}/relations/{id}
```

## 7. 主流程（需求 → 验收）

```
① 录入   创建 Requirement（DRAFT）
② 分析   起分析型 Session（挂 requirementId，非 Work Item）→ Artifact(ANALYSIS)：影响面/复杂度
③ 设计   复杂：design 型 Work Item → Session 产出 Design 方案文档，人确认（CONFIRMED）；简单跳过
④ 拆分   AI 依据 Requirement(+Design) 生成 Work Item 清单 {type,title,spec,depends_on}，人确认固化
⑤ 执行   Orchestrator 按 depends_on DAG 调度：依赖就绪的 Work Item 起 Session（worktree 隔离）
         test/review 型消费 development 型产物（verifies 边）；不通过挂 fixes 边出返工项
⑥ 验收   全部 Work Item DONE → Requirement ACCEPTANCE，聚合 Artifact 展示，人验收 → DONE
```

## 8. 验收标准

- 可建 Requirement，挂 Design（CONFIRMED 流转），拆 Work Item（五型），推进状态；
- Work Item 状态变化正确触发 Requirement rollup；人工验收后不再被 rollup 覆盖；
- depends_on 边可建可删，会话/构建/部署/测试/发版记录可挂 workItemId 且一致性校验生效；
- 需求概览页能聚合展示其全部 Work Item 的会话与执行记录。

## 9. MVP 范围（暂不做）

自动编排调度（Orchestrator 属流程层）、Requirement 级集成分支、Relation 的环检测与可视化图谱、
多人评审会签。
