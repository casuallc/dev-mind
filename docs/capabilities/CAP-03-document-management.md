# CAP-03 文档管理

> 能力 ID：CAP-03 ｜ 分类：管理 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

提供**版本化的平台文档库**。承载一切「跟需求走」的文档：需求文档、技术方案、API 测试套件、测试报告等。正文与版本历史均存 DB，与代码彻底分离——平台不存代码，只管理文档。

## 2. 功能需求

- **FR-01 文档分类与归属**：kind = `requirement | design | api-suite | report`；可关联 `requirementId` / `workItemId` / `projectId`（需求/方案文档挂 CAP-13 主线，设计文档按项目拆分，故同时挂 projectId）。
- **FR-02 版本化**：每次保存生成新版本（v1、v2…），保留全部历史与 diff，支持回退到任意历史版本。
- **FR-03 编辑与渲染**：前端 Markdown 编辑器（实时预览）；只读渲染模式。
- **FR-04 状态机**：`draft → pending_confirm → frozen`；`frozen` 为基线，变更须生成新版本并标注变更说明。
- **FR-05 版本存储**：正文存 `document_versions.content_md`，DB 即唯一副本；保存/回退只写库，不落外部文件、不依赖 git，无任何本机路径配置。
- **FR-06 全文检索**：按标题/内容/标签检索文档。
- **FR-07 文档模板**：按 kind 预置模板（如需求文档模板、方案模板），一键新建。

## 3. 插件化接口

- 文档生成服务：`DocGenerator` 供上层（需求对话、方案 Agent、API 套件生成）调用生成结构化文档。
- 存储：无存储 SPI（原 `DocStore` + git 实现已删除）。如需对象存储/外部仓库，另行立能力设计，不再走本能力的配置开关。

## 4. 依赖关系

- 依赖：CAP-01、CAP-02（文档可归属到项目）、CAP-13（需求/方案文档挂主线）。
- 被依赖：CAP-10（API 套件读写）、流程层（需求/方案文档）。

## 5. 数据模型

```
documents(id, kind, requirement_id, work_item_id, project_id, title, current_version,
          status, template, created_by, created_at)
document_versions(id, document_id, version_no, content_md,
                  change_note, created_by, created_at)
```

建模约定：文档挂 `requirement_id`（需求主线，CAP-13）；`work_item_id` 可再细到工作单元，`project_id` 用于不挂需求的独立文档。每版本存**全量正文**（非增量），diff 在读取时计算。

## 6. API 概要

```
CRUD   /documents
GET    /documents/{id}?version=v2      按版本读
GET    /documents/{id}/versions        版本列表
GET    /documents/{id}/versions/{v}/diff   与指定版本 diff
POST   /documents/{id}/versions        保存新版本（content + change_note）
POST   /documents/{id}/status          流转（提交确认/冻结/解除）
GET    /documents/search?q=…
```

## 7. 验收标准

- 新建文档→保存多版本→查看 diff→回退，全流程可用；
- 文档冻结后仅能通过新版本变更；
- 检索命中正确；
- 不依赖任何本机路径配置即可完成上述全流程（无外部仓库、无文件落盘）。

## 8. MVP 范围（暂不做）

多人会签审批流（属流程层）、富文本（先 Markdown）、图表编辑。

## 9. 变更记录

- **2026-09-21 去 git 化**：FR-05 由「git 同步 docs-repo」改为「正文存 DB 单副本」，
  删除 `DocStore` SPI、git 实现、`DocPaths` 路径映射与 `/documents/push`、`/documents/repo`
  端点，`devmind.docs.repo-path` 配置废弃（连同 `docs.enabled`）。原因：git 层在平台内
  **只写不读**——列表/详情/diff/回退/检索/agent 上下文装配全部读 DB，`DocStore.read()`
  零调用方；而它要求每个部署方各配一个本机仓库路径，带来部署耦合、多机孤岛、无远端
  凭证，并把 git 子进程（超时 30/60s）压在 `@Transactional` 的建档/保存路径上。
  代价（平台外直接编辑文件、git 远端备份）已确认不需要；如未来要异地备份，另行设计。

