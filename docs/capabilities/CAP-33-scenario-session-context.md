# CAP-33 场景化会话与上下文装配（Scenario Session & Context Assembly）

> 能力 ID：CAP-33 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-08

## 1. 目的

docs（CAP-03）/ knowledge（CAP-04）/ skills 三类资产目前挂在 /admin 管理区，
与会话消费链路断裂：skills 设计了 `.claude/skills/` 落盘出口（`exportPackages`）
但无调用方；docs 完全不进会话；knowledge 仅靠项目 tags 自动命中且仅本机会话生效。
项目工作区内用户感知不到这些资产的存在。

本能力引入**场景（Scenario）= 命名模板 + 预装配上下文包**，把资产绑定到意图，
并以统一的**上下文装配管线**让项目会话（CAP-05）与通用问答（CAP-30）一键带齐
上下文。装配产物的传输与物化由 CAP-34（执行内核）承担，本能力定义**装什么**。

## 2. 功能需求

- **FR-01 场景实体**：新表 `session_scenarios`（现有 `session_templates` 的升级版）——
  code / name / description / promptSkeleton（占位符沿用 `{{task}}/{{project}}/{{branch}}`，
  新增 `{{requirement}}`）/ skillIds / docIds / knowledgeTags / extraContextMd
  （业务背景、口径约定等自由文本）/ model / permissionMode / agentNodeId 预设 /
  scope（GLOBAL | PROJECT + projectId）/ enabled / sortOrder。
  兼容：旧 `templateCode` 视为无资产绑定的场景继续可用（数据迁移：模板行转场景行）。
- **FR-02 上下文装配管线（服务端数据侧）**：创建会话/问答时三层合并——
  ① 场景显式绑定（skills/docs/knowledgeTags/extraContext）
  ② 项目自动命中（CAP-04 global 条目按项目 tags 匹配 + scope=project 条目全量，现状沿用）
  ③ 请求级追加（创建表单临时加选）
  产出 `ContextPackage { claudeMdSections[], skills[SkillPackage], settingsJson, docs[] }`；
  知识条目 hitCount 累计沿用 CAP-04 FR-07。
- **FR-03 docs 进上下文**：绑定文档两级投递——摘要（标题 + 正文截断 N 字）进
  CLAUDE.md「项目文档」节；全文由 CAP-34 内核物化为 worktree `.devmind/docs/<docId>.md`，
  CLAUDE.md 中只留路径索引，大文档不全量塞 prompt（claude 需要时自行 Read）。
- **FR-04 skills 物化**：场景显式绑定 + 项目私有 skill（scope=PROJECT 且 projectId 命中）
  默认全带，GLOBAL 按场景选择；复用 `SkillService.exportPackages` 产出包，
  经 CAP-34 通道落 `.claude/skills/<name>/` 被 Claude Code 原生识别。
- **FR-05 场景问答（chat）**：`/chats` 创建问答可挂场景；上下文包物化到
  `_chat/<sid>` 沙箱（CLAUDE.md + .claude/skills/，无仓库语义），问答同样吃资产积累。
- **FR-06 前端集成**：
  - 项目工作区新增「上下文」页签：按 projectId 过滤展示 docs/skills/knowledge
    三资产（只读视图 + 跳转 /admin 对应管理页），消除资产与项目的割裂感；
  - 会话/问答创建表单加「场景」下拉（按当前项目过滤 scope）；
  - 场景管理页 `/admin/scenarios`（遵循内容区布局约定：Card + extra 按钮 + 表格，
    编辑器含资产多选与 prompt 骨架预览）。
- **FR-07 注入可追溯**：会话详情页展示「已注入上下文」清单（场景名/知识条目/skills/docs，
  来源 = 装配时的 ContextPackage manifest 快照落库），本机/远程口径一致。

## 3. 插件化接口

- `ContextAssembler`（devmind-session 内聚，装配管线本体）+ `ContextProvider` SPI
  （common 定义）：knowledge/docs/skill 模块各出一个 Provider，
  装配管线按 SPI 收集，不反向依赖实现。
- skill 包产出沿用 `SkillService.exportPackages` 的 `SkillPackageView` 形态
  （`<worktree>/.claude/skills/<name>/` 落盘契约不变）。

## 4. 依赖关系

- 依赖：CAP-03（docs 资产源）、CAP-04（knowledge 资产源与命中逻辑）、Skill 管理、
  CAP-05/30（两类会话的创建链路）、CAP-34（ContextPackage 的传输与物化机制）。
- 被依赖：CAP-15/17 编排层（自动派发会话时按 WI 类型选场景）。

## 5. 数据模型

```
session_scenarios(id, code unique, name, description, prompt_skeleton,
                  skill_ids JSON, doc_ids JSON, knowledge_tags, extra_context_md,
                  model, permission_mode, agent_node_id,
                  scope[GLOBAL|PROJECT], project_id, enabled, sort_order)
sessions      ── + scenario_code + context_manifest (JSON 快照，FR-07)
chat_sessions ── + scenario_code + context_manifest (JSON 快照)
session_templates  数据迁入 session_scenarios 后废弃（ddl-auto 不管删表，代码层停用即可）
```

## 6. API 概要

```
GET/POST/PUT/DELETE /api/scenarios               场景 CRUD（沿用模板管理权限）
GET    /api/scenarios/{code}/preview             装配预览（渲染骨架 + 命中资产清单）
POST   /api/sessions        现有端点 + scenarioCode（取代/兼容 templateCode）
POST   /api/chats           现有端点 + scenarioCode
GET    /api/sessions/{id}/context                已注入上下文清单（FR-07）
GET    /api/projects/{id}/context-assets         项目「上下文」页签聚合（docs/skills/knowledge 按 projectId）
```

## 7. 验收标准

- 建「代码评审」场景绑定 1 skill + 2 docs + knowledgeTags，创建会话后 worktree 内
  `.claude/skills/` 与 `.devmind/docs/` 就位，CLAUDE.md 含场景 extraContext 与文档索引；
  本机与远程节点产物一致；
- 项目工作区「上下文」页签正确按 projectId 过滤三资产；
- 挂场景的 /chats 问答在沙箱内获得 CLAUDE.md 与 skills，回答体现注入内容；
- 旧 templateCode 创建的会话行为不变（兼容）；
- 会话详情「已注入上下文」清单与装配预览一致。

## 8. MVP 范围（暂不做）

向量检索/语义命中（仍是显式绑定 + tags 匹配）、场景版本化与 diff、
场景导入导出/市场、会话中途热追加上下文（重建会话替代）、docs 全文的增量同步。
