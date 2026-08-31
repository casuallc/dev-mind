# CAP-03 文档管理

> 能力 ID：CAP-03 ｜ 分类：管理 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

提供 **git 管理的版本化文档库**（docs-repo）。承载一切「跟需求走」的文档：需求文档、技术方案、API 测试套件、测试报告等。与代码彻底分离——平台不存代码，只管理文档。

## 2. 功能需求

- **FR-01 文档分类与归属**：kind = `requirement | design | api-suite | report`；可关联 `requirementId` / `workItemId` / `projectId`（需求/方案文档挂 CAP-13 主线，设计文档按项目拆分，故同时挂 projectId）。
- **FR-02 版本化**：每次保存生成新版本（v1、v2…），保留全部历史与 diff，支持回退到任意历史版本。
- **FR-03 编辑与渲染**：前端 Markdown 编辑器（实时预览）；只读渲染模式。
- **FR-04 状态机**：`draft → pending_confirm → frozen`；`frozen` 为基线，变更须生成新版本并标注变更说明。
- **FR-05 git 同步**：文档内容即 docs-repo 中的文件；保存=提交到该仓库；支持 push 远端备份；平台外可直接编辑文件。
- **FR-06 全文检索**：按标题/内容/标签检索文档。
- **FR-07 文档模板**：按 kind 预置模板（如需求文档模板、方案模板），一键新建。

## 3. 插件化接口

- 文档存储 SPI：`DocStore`，默认实现=git 仓库（docs-repo）；可换其它存储（DB/对象存储）。
- 文档生成服务：`DocGenerator` 供上层（需求对话、方案 Agent、API 套件生成）调用生成结构化文档。

## 4. 依赖关系

- 依赖：CAP-01、CAP-02（文档可归属到项目）、CAP-13（需求/方案文档挂主线）。
- 被依赖：CAP-10（API 套件读写）、流程层（需求/方案文档）。

## 5. 数据模型

```
documents(id, kind, requirement_id, work_item_id, project_id, title, current_version,
          status, template, created_by, created_at)
document_versions(id, document_id, version_no, content_md, commit_sha,
                  change_note, created_by, created_at)
  # docs-repo 文件结构：
  #   requirements/<requirementId>/…          designs/<requirementId>/<project>/…
  #   api-suite/<project>/…     reports/…
```

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
- 文档可 push 到远端 git 仓库；
- 检索命中正确。

## 8. MVP 范围（暂不做）

多人会签审批流（属流程层）、富文本（先 Markdown）、图表编辑。
