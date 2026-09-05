# CAP-26 执行前代码同步（服务端 clone 保鲜）

> 能力 ID：CAP-26 ｜ 分类：底座 ｜ 状态：已落地（2026-09-05：RepoGitGateway SPI +
> 构建双 fetch/checkout + 同项目串行锁 + 发版 tag 基准 + worktree FETCH_HEAD 基线，
> 全量测试通过） ｜ 日期：2026-09-05

## 1. 目的

「方案 A」定稿：服务端继续承担执行者角色（构建/发版/tag 在服务端本机跑），
服务端 clone（CAP-23）是这些执行器的工作副本。但**执行链路没有任何一环负责刷新它**：

- CAP-08 构建（LOCAL executor）：`commit` 缺省取 `git rev-parse HEAD`——HEAD 停在
  克隆那一刻，远程会话时代 agent 的 push 到远端后服务端完全不知道，构建跑的是过时代码；
- CAP-11 发版：`git tag -a` 打在本地 HEAD 上，同样可能指向过时提交；
- CAP-05 worktree：`WorktreeManager.create` 虽 `git fetch origin <base>`，但随后
  `worktree add -b <new> <base>` 用的是**本地分支引用**（fetch 只更新 FETCH_HEAD 与
  `origin/<base>`，不动本地分支）——基线同样可能滞后。

执行底座（devmind-execution）全模块无 fetch/checkout。本能力补上「执行前同步」这一环，
原则是：**有 remoteUrl 的库，执行基准一律取自 `origin/` 远端引用，不再信任本地 HEAD**。

## 2. 产品决策（已定稿）

1. **fetch 失败 = 执行失败**：既然声明了以远端为基准，fetch 失败（网络/凭据）必须
   fail-fast 报清晰错误，不允许悄悄用过时代码执行。匿名库（无 Integration）fetch
   用干净 URL；有绑定 Integration 的库 fetch 用 token 内嵌 URL（复用 GitRemoteOps）。
2. **构建基准解析顺序**：`req.commit` 显式指定 > `origin/<req.branch>` >
   `origin/<项目默认分支>`；不再使用本地 HEAD。checkout 到解析出的 commit（detached）
   后执行步骤链。
3. **同项目构建串行**：checkout 会改写共享 clone 的工作区，同项目并发构建互相踩踏——
   项目级锁串行化（全局并发上限不变，只约束同项目）。跨项目不受影响。
4. **发版 tag 基准**：tag 目标 = 发版记录关联构建的 commit > `origin/<baseBranch>`，
   打 tag 前先 fetch。
5. **worktree 基线修正**：`WorktreeManager.create` 的 `worktree add` 基准从本地分支名
   改为 `FETCH_HEAD`（紧接的 `git fetch origin <base>` 之后），本地分支滞后不再影响
   会话/工作单元基线。
6. **适用范围**：主库 `remoteUrl` 非空才执行同步；纯本地库（无 remoteUrl）保持现状
   零改动。多库项目的非主库构建同步暂不做（构建目前只面向主库）。

## 3. 功能需求

- **FR-01 构建前同步（CAP-08）**：LOCAL executor 触发构建时，主库有 remoteUrl：
  fetch（带凭据）→ 按决策 2 解析 commit → `git checkout --detach <commit>` →
  走既有步骤链。fetch/checkout 失败即触发失败（400/500 + 明确信息），不产生
  跑错代码的构建。REMOTE executor（CAP-07 SSH）不受影响。
- **FR-02 同项目构建串行（CAP-08）**：项目级锁（ConcurrentHashMap 分段），
  同项目第二个构建排队等锁；锁等待超全局并发上限语义不变。
- **FR-03 发版前同步（CAP-11）**：打 tag 前 fetch；tag 目标 commit 按决策 4 解析；
  回滚删 tag 逻辑不变（本地 tag 操作不需要远端同步）。
- **FR-04 worktree 基线修正（CAP-05/13）**：`WorktreeManager.create` fetch 后以
  `FETCH_HEAD` 为 `worktree add` 基准；`fetch` 失败保持现状（best-effort，留给 add
  报错）——离线/新仓库场景不回归。
- **FR-05 凭据与脱敏**：fetch 复用 GitRemoteOps 凭据注入与 sanitize 约束；
  构建/发版日志不得出现 token。

## 4. 依赖关系

- 依赖：CAP-08/11（执行器）、CAP-18（GitRemoteOps 凭据注入）、CAP-23（clone 模型）。
- 与 CAP-25 配套：CAP-25 让节点改动 push 回远端，CAP-26 让服务端执行器消费到这些改动，
  两者合起来闭环「节点开发 → 远端 → 服务端构建/发版」。

## 5. 验收标准

- CLONE 项目：节点 push 新提交到 `origin/main` 后（不经任何服务端本地会话），
  立即触发构建，构建内容包含该提交（日志可见 fetch 与 checkout 的 commit 前 8 位）；
- fetch 失败（停 GitLab/错凭据）构建触发即失败，错误信息明确，无残留中间态；
- 同项目两个构建并发触发：串行执行，无工作区互踩；跨项目并发不受影响；
- 发版 tag 打在关联构建 commit 上（而非本地滞后 HEAD）；
- 本地分支滞后远端时，新建会话 worktree 的基线 = 远端最新；
- 纯本地库（无 remoteUrl）构建/发版/会话行为零回归。

## 6. 暂不做

非主库构建同步、定时 fetch 巡检、构建在一次性 worktree 中执行（彻底隔离工作区，
依赖 artifact 路径语义调整，后续评估）、REMOTE executor 的远端拉码。
