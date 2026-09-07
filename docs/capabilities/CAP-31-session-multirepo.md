# CAP-31 项目会话多仓库与会话归属拆分

> 能力 ID：CAP-31 ｜ 分类：底座 ｜ 状态：草案 ｜ 日期：2026-09-07

## 1. 目的

配合 CAP-30（问答拆出），把 CAP-05 会话收敛为**纯项目开发会话**，并补齐两块缺口：

1. **归属当前项目**：会话从「协作」组移入「当前项目」组（进 ProjectContextGate），创建时
   项目固定为当前项目，不再每次手选；会话必须显式关联项目的 git 仓库，列表/详情展示
   针对哪些 repo。
2. **多仓库关联**：P0-4 数据模型（project_repos）已支持一项目多库，但会话全链路仍是单主库
   假设（CreateSessionRequest 无 repo 选择、sessions.worktree_path 单值、launch 帧 RepoSpec
   单库、runner 工作区按 `<projectId>/main` 单缓存）。本能力打通多库：会话可选中项目的
   多个仓库，每库独立 worktree/分支/push/diff。

同 commit 顺带修复两个存量问题：**resume 丢 permissionMode**（创建时的权限模式未持久化，
resume 回落全局默认）与**存量无项目会话清理**（问答拆出后 sessions 表 projectId 恒非空，
启动时删除 `project_id IS NULL` 的历史会话及其事件）。

## 2. 产品决策（已定稿）

1. **聚合目录**：多库会话的工作区 = 一个聚合根目录，各仓库 worktree 为其下子目录
   `<agg>/<repoName>`，claude cwd = 聚合根（类 VSCode multi-root）。**单库会话保持现状**
   （cwd = 该库 worktree 根），不动存量行为与目录。
   - 使用约定：cwd 非 git 仓库，`git status` 等需在子目录内执行（CLAUDE.md 注入提示
     「各仓库在子目录 `<name>/`」）。
2. **session_repos 快照表**：会话-仓库关联在创建时从 project_repos **拷值快照**
   （name/remoteUrl/localPath/baseBranch），之后项目仓库变更/改名不影响历史会话的
   resume/diff/清理（目录名稳定）。
3. **协议向后兼容**：launch 帧新增 `repos` 数组（含 name），保留旧 `repo` 单字段（填主库）。
   旧 runner 只读 `repo` → 降级为只拉主库，其余库表现为「目录不存在」，CAP 文档注明；
   新 runner 优先读 `repos`。
4. **远程 diff 走服务端克隆**：远程会话结束 runner 已 push `feature/<sid>`（CAP-25）。
   查看 diff 时服务端对 CAP-29 全局克隆缓存 fetch 会话分支后
   `git diff <base>...<branch>`，不再依赖读节点文件系统（CAP-25「暂不做」条目随之落地）。
5. **存量清理**：问答拆出后项目会话恒有项目。启动时一次性删除 `project_id IS NULL` 的
   sessions + session_events（含 CAP-28 one-shot 历史——one-shot 运行期即建即取 summary
   即弃，清历史无影响；日志记条数）。

## 3. 功能需求

- **FR-01 仓库选择**：`CreateSessionRequest` 加 `repoIds: List<Long>`（project_repos 主键）。
  null/空 = 主库（现状兼容）；非空校验全部属于该项目且至少一个。前端创建表单固定当前项目 +
  仓库多选（默认勾主库）。
- **FR-02 会话-仓库快照**：新表 `session_repos`（见 §5），创建时写入；会话视图
  `SessionView` 加 `repoNames` 供列表展示。
- **FR-03 本地聚合工作区**：`WorkspaceService.prepareSessionWorkspace(project, sid, repos)`——
  单库返回该库 worktree（现状路径 `<repo>/.devmind/worktrees/<sid>`）；多库建聚合根
  （主库 `.devmind/worktrees/<sid>`）+ 各库 worktree 子目录（`WorktreeManager.create(
  repoPath, baseBranch, branch, childDir)` 显式入参已就绪），分支统一 `feature/<sid>`。
  新增 `MultiWorktreeWorkspace`：cleanup 倒序 remove 各 worktree 后删聚合根。
  知识注入多库时写聚合根（`KnowledgeInjector.apply` 签名不变，传 aggRoot）。
- **FR-04 launch 协议多库**：`AgentLaunchCommand` 加 `repos: List<RepoSpec>`，RepoSpec 加
  `name` 字段（兼容构造器保留旧四参）；下发时 `repo`=主库 + `repos`=全量。token 逐库按
  CAP-24/25 优先级解析，安全红线不变（仅内存、URL 内嵌、日志脱敏）。
- **FR-05 runner 多库工作区**：`repos` 数组 >1 时：克隆缓存
  `<workspaceRoot>/<projectId>/<name>/main`，会话 worktree
  `<workspaceRoot>/<projectId>/sessions/<sid>/<name>`，cwd = 聚合根 `sessions/<sid>`；
  结束逐库 push + worktree remove（best-effort，system 事件上报带 `[<name>]` 前缀）。
  单库路径保持现状（不动存量节点目录）。
- **FR-06 diff 每库一组**：`GET /api/sessions/{id}/diff` 返回 `List<RepoDiffView>{
  repoName, primary, stat, files, hasChanges, error}`：
  - 本地：逐库 `WorktreeManager.diff(baseBranch, 子目录)`；
  - 远程：经 CAP-29 服务端克隆缓存 `GitRepoService.deriveClonePath/upsertCloneByRemoteUrl`
    取 localPath → fetch 会话分支（token 经 `RepoGitGateway.resolveToken` 显式 URL 内嵌注入，
    输出脱敏；按 gitRepoId 加锁防并发 fetch 撞车）→ `git diff <base>...<branch> --stat` +
    `--name-only`。单库失败只填该库 `error`，不拖垮整组。
- **FR-07 permission_mode 持久化**：`sessions` 表加 `permission_mode` 列，create 存值，
  resume 读实体（不再回落全局默认）。
- **FR-08 存量清理**：`restoreOnStartup` 追加删除 `projectId IS NULL` 会话 + 事件（决策 5）。
- **FR-09 前端归属**：`/sessions` 与 `/sessions/:id` 移入 ProjectContextGate；「协作」组移除
  会话入口，「当前项目」组加「会话」；列表/详情展示仓库徽标（repoNames）；Diff 弹窗按库分组；
  `listSessions` 补传 projectId。

## 4. 数据模型

```
session_repos(id 自增, session_id, project_repo_id NULL(溯源弱引用), name,
              remote_url NULL, local_path NULL, base_branch, branch,
              is_primary, sort_order)
              索引 idx_session_repos_sid(session_id)
sessions 加列：permission_mode(32)
sessions.worktree_path 语义变为「会话工作区根」（单库=该库 worktree，多库=聚合根）
```

## 5. 校验规则与红线

- repoIds 必须全部属于会话项目（400）；`is_primary` 等布尔 DTO 字段一律 `Boolean` 包装；
  列名避开 H2 保留字。
- runner 侧仓库名参与路径拼接：沿用 `SAFE_ID` 白名单 `[a-zA-Z0-9._-]` + 路径越界防护。
- token 红线同 CAP-25：仅存内存、URL 内嵌注入、clone 后清 origin、日志/事件脱敏。
- 异步触发方法禁 @Transactional（沿用现状）。

## 6. 模块归属与依赖

- devmind-common：`AgentLaunchCommand.RepoSpec` 加 name + `repos` 数组。
- devmind-project：WorkspaceService 多库方法 + MultiWorktreeWorkspace（WorktreeManager 零改动）。
- devmind-session：session_repos 实体/快照、create/resume/cleanup 多库化、RemoteDiffService、
  permission_mode 修复、存量清理。
- devmind-agent：launch 帧序列化 repos 数组。
- devmind-agent-runner：多库 prepare/finish、聚合 cwd。
- 前端：features/sessions 改造 + shared/chat 复用面板。
- 依赖：CAP-05（会话底座）、CAP-21/25（远程通道与工作区）、CAP-29（服务端克隆，远程 diff
  取数）、CAP-24（逐库 token 解析）。

## 7. 验收标准

- 当前项目建会话默认勾主库，行为与现状完全一致（目录、知识注入、diff）；
- 多库会话：聚合根下各库子目录就位、claude cwd=聚合根、各库分支均为 feature/<sid>；
  结束后远程各库分支均 push 到远端；
- 远程会话（单库/多库）结束后在服务端可看每库 diff；某库 fetch 失败只影响该库卡片；
- 列表/详情显式展示会话针对的仓库名；
- 挂起→恢复后权限模式与创建时一致；重启服务后无项目历史会话被清理；
- 旧 runner + 新服务端多库帧：降级只拉主库，其余库目录不存在有明确事件提示。

## 8. 暂不做

会话进行中动态加挂仓库、跨项目仓库混选（仓库选择限定当前项目内）、runner 工作区磁盘配额、
多库会话的自动 MR 拆分（各库 MR 由 CAP-18 消费方另行编排）。
