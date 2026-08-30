# CAP-02 项目管理

> 能力 ID：CAP-02 ｜ 分类：管理 ｜ 状态：草案 ｜ 日期：2026-08-30

## 1. 目的

注册与维护「项目」：本地 git 仓库路径、关联服务器、构建/发版/测试配置。项目是会话、构建、部署、测试、发版能力的**挂载点**——其余能力都以 projectId 为上下文工作。

## 2. 功能需求

- **FR-01 项目 CRUD**：name、本地仓库路径 `path`、defaultBranch、描述、状态（active/archived）。
- **FR-02 标签体系**：`tags`（如 `java/backend/frontend/pnpm/spring`），用于知识库注入筛选（CAP-04）。
- **FR-03 关联服务器**：项目下维护服务器列表（引用 CAP-07 的 server 实体），含 env 标注（test/staging/prod）。
- **FR-04 构建配置**：有序构建步骤列表 + 执行位置（local/remote），委托 CAP-08。
- **FR-05 发版配置**：Nexus 推送脚本模板引用 + 目标仓库 + 版本规则，委托 CAP-11。
- **FR-06 API 文档源**：`apiDocSource` 指向 OpenAPI 文件（项目内路径或文档库），供 CAP-10 生成测试套件。
- **FR-07 项目上下文摘要**：启动 Agent 扫描仓库结构生成 `context-summary`（目录结构、关键模块、技术栈、既有 API），可人工修正，供需求对话/方案/会话注入使用。
- **FR-08 worktree 规范**：约定 `path/.devmind/worktrees/<sessionId>` 作为会话隔离工作区（CAP-05 使用）。
- **FR-09 项目锁定**：并发控制基础——同一项目可配置最大并发写任务数（供 Orchestrator 使用，本能力只存储）。

## 3. 插件化接口

- 对外提供 `ProjectContext(projectId, path, tags, servers, buildConfig, releaseConfig, contextSummary)` 聚合对象，供所有能力取项目配置。
- 仓库操作服务 SPI：`RepoService`（read 结构摘要、worktree 创建/删除、打 tag），默认实现=本地 git（JGit 或 git CLI）。

## 4. 依赖关系

- 依赖：CAP-01（鉴权 + actor）。
- 被依赖：CAP-03/04/05/07/08/09/10/11。

## 5. 数据模型

```
projects(id, name, path, default_branch, tags, description,
         status, api_doc_source, context_summary, owner_id, created_at)
servers(id, project_id, name, env, access_type, access_config_enc, capabilities)
project_lock(project_id, active_writes, max_concurrent)
```

## 6. API 概要

```
CRUD   /projects
GET    /projects/{id}/summary         项目上下文摘要（触发 Agent 扫描/返回缓存）
POST   /projects/{id}/summary/refresh 重新扫描生成
GET    /projects/{id}/servers         项目下服务器列表
GET    /projects/{id}/worktrees       查看该项目的活跃 worktree
```

## 7. 验收标准

- 可注册一个本地 git 仓库项目，标签、构建配置、发版配置可维护；
- 可生成/刷新项目上下文摘要；
- 会话能力能基于该项目创建 worktree 并隔离；
- 项目列表对未授权角色隐藏/只读。

## 8. MVP 范围（暂不做）

多仓库聚合项目、CI 平台集成、项目级成员管理（多人版再做）。
