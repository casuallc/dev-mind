# CAP-109 后台项目管理与代码库增强（列表搜索 + 提交历史/分支操作）

> 能力 ID：CAP-109 ｜ 分类：管理 ｜ 状态：已实现 ｜ 日期：2026-11-17
> 决策记录：列表搜索走 **DB 层 JPQL lower like**（`cast(:kw as string)` 防 PG bytea 红线，
> 照 SkillRepository 范式），不做前端内存过滤——两处入口（业务列表页 + 后台 tab）共用同一
> 后端口径；git 信息**全仓无 JGit，一律走本机 CLI**（common 的 GitCli：UTF-8 强制解码 +
> 超时包装 + requireOk 非零退出翻 BAD_REQUEST），分支名一律以实体 branches 列做**白名单
> 校验**防注入；checkout 仅限 CLONE+READY 的服务端克隆——LOCAL 是用户自己的工作区，
> 平台不替用户切分支。

## 1. 目的

REQ-22 两个同源诉求：

- 项目管理列表、代码库列表数据量上来后无检索手段，需支持关键字搜索；
- 代码库（CAP-29 全局登记）目前只能看到同步状态，功能单一——需能查看提交历史，
  并支持常见 git 操作（分支切换 / 抓取，抓取复用既有端点）。

## 2. 功能需求

### 列表搜索（M1）

- **FR-01 项目列表关键字搜索**：`GET /api/projects` 增加可选参数 `keyword`，
  小写 like 匹配 name / description（摘要）/ path（仓库路径）三列（description 为
  CLOB，查询用 `lower(cast(p.description as string))`）；status 过滤下推 DB，
  kind 过滤与 CAP-108 可读范围过滤沿用现有内存逻辑；保持全量 List 返回，不引入分页。
  CAP-76 内部用的 `list(status, kind, keyword, limit)` 重载不动。
- **FR-02 代码库列表搜索 + 状态过滤**：`GET /api/repos` 增加可选参数
  `keyword`（匹配 name/remoteUrl/localPath）与 `status`（可选等值过滤 ACTIVE/DISABLED）。
- **FR-03 前端搜索框**：项目列表页（ProjectsPage）、后台管理「项目」tab
  （ProjectsView）、后台管理「代码库」tab（ReposView）表格上方放
  `Input.Search allowClear`（照 SkillsPage 范式），onSearch 存 state 重新请求；
  代码库 tab 另加状态下拉（全部/启用/停用）。Card + extra 按钮 + FitTable 布局不动。

### 代码库提交历史与 git 操作（M2）

- **FR-04 提交历史**：`GET /api/repos/{id}/commits?branch=&limit=` 返回
  `[{sha, authorName, authorEmail, time, subject}]`。校验链：仓库存在（404）→
  localPath 目录存在且含 .git，否则 400 可读错误（提示 LOCAL 路径失效或克隆未就绪，
  禁止 500）→ branch 若提供必须在实体 branches 列表内（白名单防注入），否则 400。
  执行 `git log <branch> --format=%H%x1f%an%x1f%ae%x1f%ad%x1f%s
  --date=format:%Y-%m-%d %H:%M:%S -n <limit>`（GitCli，30s 超时，%x1f 分隔照
  GitLogScanner 范式）；branch 缺省用实体 defaultBranch；limit 默认 30、硬上限 200
  （超出截断）。time 用 String（git 直接格式化），时间序列化不做散点定制。
- **FR-05 分支切换**：`POST /api/repos/{id}/checkout`，body `{branch}`。
  仅允许 sourceType=CLONE 且 cloneStatus=READY 的仓库（LOCAL 直接 400）；
  分支名白名单校验同 FR-04；执行 `git checkout -B <branch> origin/<branch>`
  （远端分支对齐），失败返回可读错误。
- **FR-06 前端提交抽屉**：ReposView 操作列加「提交」按钮打开
  RepoCommitsDrawer：顶部分支 Select（选项来自行数据 branches，默认 defaultBranch）
  +「抓取」按钮（调既有 POST /api/repos/{id}/fetch 后刷新）；主体 Table 列：
  短 sha（前 8 位）、提交时间（fmtTime）、作者、提交信息；「切换到此分支」调
  checkout，成功 message 提示并刷新。展示范式照 worklog 的 GitImportDrawer。

## 3. 否决方案

- **前端内存过滤**：列表全量下发后前端过滤，两处入口各抄一份、口径易漂；且项目列表
  未来接分页时前功尽弃。
- **引 JGit 读提交历史**：全仓 git 一律走本机 CLI（GitCli 的 UTF-8/超时/错误包装已
  多模块验证），为一个只读 log 引一套新栈得不偿失。
- **LOCAL 仓库也支持 checkout**：LOCAL 是用户本机工作区，可能有未提交改动，平台替
  用户切分支会丢工作现场——直接 400 拒绝。
- **checkout 用 `-f` 强制对齐**（GitRemoteOps.checkoutReset 同款）：后台管理是人工
  操作场景，失败应暴露可读错误由人处理，不静默丢弃。

## 4. 架构与模块归属

```
devmind-project
  ProjectRepository.search(status, kw)        —— JPQL 关键字搜索（FR-01）
  ProjectService.list(status, kind, keyword)  —— 重载；kind/可读范围内存过滤沿用
  ProjectController                           —— GET /api/projects 加 keyword
  GitRepositoryRepository.search(status, kw)  —— FR-02
  GitRepoService.list(keyword, status) / GitRepoController 加参

devmind-integration（已依赖 project，直接注入 GitRepositoryRepository 读实体）
  GitRepoOpsService    —— commits 查询 / checkout 执行（GitCli，白名单校验）
  GitRepoOpsController —— GET /api/repos/{id}/commits、POST /api/repos/{id}/checkout
  （fetch 复用既有 GitRepoSyncController，不新增）

frontend
  features/projects：ProjectsPage / ProjectsView / api.ts
  features/repos：ReposView / components/RepoCommitsDrawer.tsx / api.ts / types.ts
```

## 5. 数据模型

无变更（projects / git_repositories 均不增列；branches 列既有，fetch 维护）。

## 6. API 概要

- `GET /api/projects?status=&kind=&keyword=`（既有端点加参，全认证用户 + 可读范围过滤）
- `GET /api/repos?keyword=&status=`（既有端点加参，全认证用户可读）
- `GET /api/repos/{id}/commits?branch=&limit=`（全认证用户可读）
- `POST /api/repos/{id}/checkout` `{branch}`（ADMIN，SecurityConfig 既有
  `POST /api/repos/**` 规则覆盖）
- `POST /api/repos/{id}/fetch`（既有，ADMIN）

## 7. 安全

- 关键字搜索全部走 JPQL 参数绑定，无注入面；`cast(:kw as string)` 防 PG bytea。
- git 命令的分支参数以实体 branches 列做白名单（进库前已被 fetch 流程规范化），
  且 GitCli 走 ProcessBuilder 参数数组，不过 shell，双重防注入。
- checkout 为写操作，由 SecurityConfig 既有 `POST /api/repos/**` ADMIN 规则约束；
  LOCAL 仓库一律拒绝，不动用户工作区。
- 不触碰凭证：commits/checkout 均为本地 git 操作，无需 token。

## 8. 里程碑

- **M1 列表搜索**（已交付）：FR-01~03（项目/代码库列表关键字搜索 + 前端搜索框）。
- **M2 提交历史与 git 操作**（已交付）：FR-04~06（commits 查询、分支切换、提交抽屉）。

## 9. 验收标准

- M1：`mvn -q -pl devmind-project -am test` 通过（含 Repository keyword 查询的 H2
  内存库 JUnit：大小写不敏感、部分匹配、空 keyword 返回全部）；`npx tsc -b` 通过；
  起服务后项目 tab / 代码库 tab 输入关键字过滤生效、清空恢复全量。
- M2：`mvn -q -pl devmind-integration -am test` 通过（含：无 .git 目录 400、limit 超
  上限截断、分支名不在白名单 400、LOCAL 仓库 checkout 拒绝）；`npx tsc -b` 通过；
  起服务登记本地临时 git 仓库，「提交」抽屉可见提交列表，切换分支后
  `git branch --show-current` 确认，「抓取」后 lastFetchAt 更新。

## 10. 风险与开放问题

| # | 项 | 口径 |
|---|---|---|
| R1 | checkout 有副作用：服务端克隆被会话 diff、工时扫描（CAP-28/74）等共用，切分支会改变这些消费方看到的内容 | 记录在案；仅限 CLONE+READY 且 ADMIN 操作；消费方按 origin/ 引用或默认分支取数的链路不受影响（CAP-26 执行前 fetch 取 origin/） |
| R2 | 大仓库 git log 慢 | `-n` 截断（上限 200）+ 30s 超时，接受 |
| R3 | CLONE 行 branches 列滞后于远端 | 抽屉内提供「抓取」按钮刷新；白名单以登记列表为准，未抓回的新分支先抓取再切 |
| O1 | 提交 diff 详情 / 文件级变更查看 | 展望，本期不做 |
| O2 | 列表分页 | 项目/仓库数量级小（几十），全量返回，接受 |
