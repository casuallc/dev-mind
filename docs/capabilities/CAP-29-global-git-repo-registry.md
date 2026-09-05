# CAP-29 全局代码仓库登记（服务端克隆 + 项目关联 + 定时同步）

> 能力 ID：CAP-29 ｜ 分类：平台层 ｜ 状态：已实现（2026-09-06）｜ 日期：2026-09-05
> 关联：CAP-23（项目仓库克隆，本能力复用其执行器并把仓库本体提升为全局）、CAP-28（个人工时，订阅源切换为本能力的服务端克隆）

## 1. 目的

仓库从「项目私有记录 / 用户本机路径」提升为**平台级独立资源**：

- 全局登记、后台统一管理（仅 ADMIN）；服务端统一克隆保存，不依赖任何人的本机检出；
- 项目使用仓库只是**关联**，不复制数据（全局表 = 唯一数据源）；
- 定时（默认 30 分钟）+ 手动拉取远端，同步分支列表与默认分支；
- CAP-28 个人工时扫描基于服务端克隆（用户只勾选订阅，不再填本机路径）。

```
integrations（CAP-18 凭证，管理员选定）
        │ clone/fetch（GitRemoteOps，token 仅出现在进程参数）
        ▼
git_repositories（全局登记表：remote_url_key 唯一，CLONE 行路径=<workspace>/_global/<slug>-<sha8>）
        │ git_repo_id 弱引用                │ GitRepoCatalog SPI（common）
        ▼                                   ▼
project_repos（项目关联行：role/primary/sortOrder，克隆状态由全局行镜像）   CAP-28 订阅/扫描
        │
        ▼
会话 worktree / 构建 / 发版（消费方零改动，仍读 project_repos.path）
```

## 2. 功能需求

### FR-01 全局仓库登记（仅 ADMIN）

- 表 `git_repositories`（沿用 CAP-28 表名，实体迁入 devmind-project），新增列：
  `source_type`(LOCAL/CLONE，默认 `'LOCAL'`)、`integration_id`（弱引用 integrations）、
  `clone_status`(NONE/CLONING/READY/FAILED，默认 `'NONE'`)、`clone_error`(1024)、
  `remote_url_key`(512，unique+nullable，规范化 URL 用于 upsert 匹配)、
  `branches`(@Lob 16MB，换行分隔远程分支)、`last_fetch_at`、`last_fetch_error`(1024)。
- 规范化：`remote_url_key` = 小写 scheme+host、去默认端口/尾斜杠/尾部 `.git`、
  `git@host:org/repo` 归一为 `host/org/repo`；无 remoteUrl 的 LOCAL 行 key 为 NULL。
- CLONE 行路径登记前确定：`<workspace-root>/_global/<slug>-<sha8>`
  （slug=URL 末段，sha8=规范化 key 的 SHA-1 前 8 位；同 URL 同路径，upsert 与路径分配一致）。
- LOCAL 行沿用 `git rev-parse` 校验；CLONE 行沿用 CAP-23 远端校验（仅 http/https，ssh 拒绝）。

### FR-02 项目仓库关联化

- `project_repos` 新增 `git_repo_id`（nullable 弱引用，存量行不回填，继续走旧 per-project 克隆）。
- 项目添加仓库：
  - CLONE：按 `remote_url_key` upsert 全局行（不存在则建、置 CLONING、发
    `gitrepo.clone-requested` 事件）；项目行 `path` = 全局克隆路径、`git_repo_id` 回填；
  - LOCAL：有 remoteUrl 按 key upsert，无则按 localPath 匹配，回填 `git_repo_id`；
  - updateRepo 对未链接行惰性链接。
- 克隆状态**全局行为主**：integration 侧状态迁移扇出镜像到所有关联项目行
  （cloneStatus/cloneError/clonedAt + 逐项目 syncPrimaryMirror），既有消费方
  （WorktreeManager/Build/Release/RepoGitGateway）零改动。
- 项目删仓库只删关联行；全局仓库被引用时 DELETE 返回 409。

### FR-03 服务端克隆与定时/手动同步

- 克隆执行放 devmind-integration（复用 GitRemoteOps；token 按 `integration_id` 解析，
  host 必须匹配、集成须 ENABLED；token 只出现在进程参数，日志脱敏）。
- 定时 fetch：`devmind.integration.repo-sync.enabled`（默认 true）、
  `fixed-delay`（默认 `PT30M`）、`initial-delay`（默认 `PT1M`）；遍历
  `CLONE + READY + ACTIVE` 行，单库失败记 `last_fetch_error` 不中断；AtomicBoolean 防重入。
- fetch 内容：`git fetch +refs/heads/*:refs/remotes/origin/* --prune` →
  `remote set-head`/remoteHeadBranch 更新默认分支 → `for-each-ref` 写 branches →
  记 `last_fetch_at`；默认分支变化镜像回关联项目行。
- 手动端点：`POST /api/repos/{id}/fetch`（立即抓取）、`POST /api/repos/{id}/clone`
  （FAILED 重克隆），均 ADMIN。

### FR-04 个人工时订阅切换

- `worklog_repo_subscriptions` 不动；GitLogScanner 改经 `GitRepoCatalog` SPI 取
  服务端克隆路径扫描，`CLONE 且非 READY` 的行跳过（warn）。
- worklog 模块仅保留：合并列表 `GET /api/worklog/repos`（registry + subscribed 标志）、
  勾选 `PUT /api/worklog/repos/{id}/subscription`；登记 CRUD 移到 `/api/repos`。

### FR-05 前端

- 后台新页 `/admin/repos`（features/repos 自包含）：列表（来源/远端/默认分支/分支数/
  克隆状态/最近抓取/状态）+ 登记编辑 Modal（sourceType 条件渲染 localPath 或
  remoteUrl+集成凭证下拉）+ 行内「立即抓取」。
- worklog `/worklog/repos` 瘦身成订阅页（只剩勾选列，提示管理入口在后台）。
- 项目 ReposPage 不改（镜像保证克隆状态轮询照常）。

## 3. SPI（devmind-common）

```java
public interface GitRepoCatalog {
    List<RepoRef> listAll();                       // 订阅页展示（含 DISABLED）
    List<RepoRef> listByIds(Collection<Long> ids); // 订阅校验 + 扫描取库
    record RepoRef(long id, String name, String localPath, String remoteUrl,
                   String defaultBranch, String status, String cloneStatus) {}
}
```

devmind-project `GitRepoService` 实现；worklog 以 `ObjectProvider<GitRepoCatalog>` 探测降级。

## 4. API 概要

```
GET    /api/repos                     列表（全认证用户）
GET    /api/repos/{id}
POST   /api/repos                     登记（ADMIN；CLONE 即发克隆事件）
PUT    /api/repos/{id}                改名/分支/凭证/启停（ADMIN；改 remoteUrl 不自动重克隆）
DELETE /api/repos/{id}                ADMIN；被项目引用 409
POST   /api/repos/{id}/fetch          立即抓取（ADMIN）
POST   /api/repos/{id}/clone          失败重克隆（ADMIN）

GET    /api/worklog/repos             合并视图（registry + subscribed）
PUT    /api/worklog/repos/{id}/subscription  本人勾选
```

配置（`devmind.integration.repo-sync.*`）：`enabled` / `fixed-delay` / `initial-delay`。

## 5. 验收标准

1. ADMIN 在 /admin/repos 登记 CLONE 仓库 → 服务端克隆落 `_global/<slug>-<sha8>`，
   cloneStatus READY、branches/defaultBranch 填充；非 ADMIN 写操作 403。
2. 项目添加同 remoteUrl 的 CLONE 仓库 → 复用全局行（不二次克隆），
   `project_repos.git_repo_id` 回填，path 指向全局克隆；两个项目关联同一仓库互不干扰。
3. 手动 fetch 后 `last_fetch_at` 更新、分支列表刷新；定时任务按配置周期执行。
4. 用户勾选订阅后，git preview 扫描服务端克隆、按本人署名过滤，中文提交不乱码；
   克隆未 READY 的仓库不出现在扫描结果。
5. 存量 LOCAL 行（CAP-28 数据）与存量 project_repos 行（无 git_repo_id）行为不变。

## 6. MVP 范围（暂不做）

- 全局克隆的完整日志流（clone_logs/WS，仅 clone_error 摘要）；
- 存量 `project_repos` 的 `git_repo_id` 启动回填；
- 删除全局仓库时清理磁盘克隆目录（保留目录，手工清理）；
- 多仓库（DOCS/CONFIG 角色）推送到 CAP-21 远程节点（RepoSpec 仍只覆盖主库）。

## 7. 排错

| 现象 | 根因 | 处置 |
|---|---|---|
| 本地验证克隆报「remote_url 协议仅支持 http/https」 | file:// 仅在匿名通道（integrationId 为空）放行；CloneTokenResolver 对带 integrationId 的行会先做集成校验 | 本地 E2E 用 file:// 且**不传** integrationId |
| fetch 成功但工时扫描/构建读到的还是旧代码 | `git fetch` 只更新 `refs/remotes/origin/*`，不移动已检出的本地分支 | doFetch 已内置 `merge --ff-only origin/<默认分支>`（GitRemoteOps.ffOnly）；分叉导致非 ff 时记 info 日志跳过，不影响抓取结果 |
| 分支列表出现 `origin`、`origin/HEAD` 脏行 | for-each-ref 短名含 HEAD 指针与裸 origin 行 | refreshBranches 已剥 `origin/` 前缀并过滤；老数据重新 fetch 一次即可 |
| E2E 起 8081 报「Unable to determine Dialect」 | `application-local.yml`（local 为默认 profile）指向共享 MySQL 172.20.140.156 | 必须显式 `--spring.profiles.active=e2e` 并覆盖 `spring.datasource.url` 到 H2 |
| git preview 空、但克隆 READY 且当天有提交 | 扫描按平台用户署名过滤，UserGitCredentialService 无配置时**回退 displayName** | 种子提交的 `user.name` 要与登录用户 displayName 一致（如 admin=「管理员」）；日期参数也要匹配提交日期 |
| 非 ADMIN 调 /api/repos 写接口 403 但读也 403 | SecurityConfig 顺序错——写 matcher 必须放在 `GET /api/**` 认证兜底**之后**、其余写规则之前 | 现有顺序已验证：GET 全认证可读，POST/PUT/DELETE 仅 ADMIN |
