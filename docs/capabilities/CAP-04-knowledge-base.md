# CAP-04 知识库管理

> 能力 ID：CAP-04 ｜ 分类：管理 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

管理**经验知识**：全局通用经验 + 项目特有经验 + 待审核提案。核心价值是**一次维护、处处注入**——起 Agent 会话时自动组装有效 CLAUDE.md，解决"通用规范每个项目重复配置"的痛点。经验库为独立 git 仓库（knowledge-repo）。

## 2. 功能需求

- **FR-01 三层结构**：`global/`（通用：样式、提交规范、构建脚本模板、踩坑）、`projects/<项目>/`（项目特有：架构说明、gotchas、约定）、`inbox/`（待审核提案）。
- **FR-02 条目管理**：条目的增删改；条目带元数据（来源项目、日期、命中计数、标签）。
- **FR-03 标签筛选**：global 条目可打标签（如 `frontend`、`spring`），注入时按项目 tags 匹配，防上下文膨胀。
- **FR-04 注入器**：起会话时组装有效 CLAUDE.md = `global/*（按项目标签筛选） + projects/<本项目>/* + 任务说明`，写入会话 worktree；提供"预览将注入的内容"。
- **FR-05 经验捕获**：会话结束/过程中可「沉淀经验」→ agent 总结生成提案进 inbox；agent 也可主动提议（P2 通知，不打扰）。
- **FR-06 提案流转**：inbox 提案可「采纳到项目层」「采纳并晋升全局」「丢弃」；采纳时可选去重/合并相似条目。
- **FR-07 清理**：低命中计数（长时间未被注入使用）的条目可标记清理。
- **FR-08 全文检索**：跨三层检索经验条目。

## 3. 插件化接口

- 注入 SPI：`KnowledgeInjector.preview(projectContext, taskSpec) → claudeMdContent`，供 CAP-05 会话启动时调用。
- 知识存储 SPI：`KnowledgeStore`，默认实现=git 仓库（knowledge-repo）。

## 4. 依赖关系

- 依赖：CAP-01、CAP-02（项目标签）；本身也可独立使用。
- 被依赖：CAP-05（注入 CLAUDE.md）、流程层。

## 5. 数据模型

```
knowledge_entries(scope[global|project], project_id?, path, content_md,
                  tags, source_project, hit_count, status[active|deprecated], updated_at)
knowledge_proposals(id, title, content_md, target_scope, source_session_id,
                    status[open|adopted|rejected], adopted_to[project|global], created_at)
  # knowledge-repo 文件结构：
  #   global/…  projects/<project>/…  inbox/<yyyymmdd>-<title>.md
```

## 6. API 概要

```
CRUD   /knowledge/{scope}              scope=global | projects/<projectId>
GET    /knowledge/preview?projectId=&taskSpec=   预览注入内容
POST   /knowledge/proposals            提交经验提案（来自会话）
GET    /knowledge/proposals            待审核列表
POST   /knowledge/proposals/{id}/adopt?target=project|global&projectId=…
POST   /knowledge/proposals/{id}/reject
GET    /knowledge/search?q=…
```

## 7. 验收标准

- 能维护 global/项目 两层条目；
- 起会话预览的注入内容 = 全局(按标签) + 项目 + 任务说明；
- 会话中可一键生成经验提案进 inbox；
- 提案可采纳到项目层、晋升全局，晋升后新会话立即生效。

## 8. MVP 范围（暂不做）

知识图谱、自动去重合并（先人工评审）、跨会话自动沉淀的完整闭环。
