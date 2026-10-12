# CAP-74 工作日志事实源服务端化（服务端 git 仓 + 去镜像去状态机 + 团队共享仓）

> 能力 ID：CAP-74 ｜ 分类：组装层 ｜ 状态：**需求定稿（待实现）** ｜ 日期：2026-10-12
> （2026-10-12 评审修订：技术选型定稿 git CLI 否决 JGit、M1 纳入最小服务端 push、
> 补 active_repo_key 写入路由、迁移幂等判据与冲突解决 API、周报文件名定稿 yyyy-Www）
> 重构 CAP-41 的存储与同步链路；条目管理/git 扫描导入（CAP-28 FR-03/04）、模板（CAP-41 FR-05）、
> 生成会话链路（CAP-41 FR-03/04）保留为素材源与生成通道不动。

## 1. 目的

CAP-41 定了「runner 工作区文件 = 事实源、DB = 镜像」，但实际写路径有两条
（claude 写文件、人手改 DB），同步只有单向（文件 → DB），分叉是结构性的：

- 手动编辑只落 DB，**不回写 runner 工作区文件**，因此远端备份 push（推的是工作区
  文件）永远推不上去手改内容——「平台里看到的最终稿 ≠ git 仓里的稿」；
- 反向地，手改的 DRAFT 会被下一次镜像 upsert 冲掉（`CONFIRMED 不覆盖` 是用状态机
  打的补丁），用户得靠「点确认」保护自己的修改；
- 事实源在 runner 侧 ⇒ 亲和节点离线 = 整个工作日志页不可读不可改（实际部署中
  runner 离线并不少见），可用性被执行体绑架。

根因是**双数据源 + 单向同步**。本能力把事实源收敛为**服务端本地 git 仓**
（唯一真相），DB 报告表退役，DRAFT/CONFIRMED 状态机删除，并顺带把「个人仓」
泛化为「团队共享仓」（成员为 1 的仓即个人仓）：

```
写入路径①：AI 成稿 —— 会话 outputs 回传（CAP-37 既有链路）→ 服务端写文件 + git commit
写入路径②：手动编辑 —— PUT 直接写文件 + git commit（保存即生效，无确认态）
事实源：data 下服务端本地 git 仓（git CLI 子进程，无 claude 执行，不碰 CAP-34 红线）
远端备份：服务端 GitRemoteOps push（直连优先，命中 CAP-70 egress_rules 走隧道）
历史/审计：git log + commit message trailer（取代状态机与镜像）
```

git 操作不是 claude 执行——integration 模块的服务端 git 链路（CAP-29 克隆、
GitRemoteOps、CAP-70 出口）本来就在服务端跑，CAP-34「服务端零执行」红线不受影响。
**技术选型（评审定稿）：沿用 git CLI 子进程**——GitRemoteOps 扩展 `add/commit/log/merge`
四个方法（现状仅读侧操作 + ffOnly），与 CAP-29 同栈零新依赖，egress 代理注入
（`-c http.<host>.proxy=socks5h://…`，GitRemoteOpsEgressTest 已覆盖）现成；
**否决引入 JGit**——全仓无 JGit 依赖，引入即第二套 git 栈，且其 push 走隧道需另辟
HTTP CONNECT 代理挂载路径，相对 CLI 方案无收益。

## 2. 功能需求

- **FR-01 仓级建模（个人仓 = 团队仓的特例）**：平台统一管理「工作日志仓」，
  新表 `worklog_repos` + `worklog_repo_members`（见 §5）。每个仓 = 服务端本地一个
  git 工作仓 + 可选远端绑定；仓内成员各写各的 `<username>/` 子目录。首次进
  /worklog 或首次生成时按当前用户懒创建个人仓（成员=本人，remote_url=null 纯本地）。
  存量 `worklog_user_settings.remote_url/remote_branch` 迁移进 repos 表后列废弃。
  **写入路由**：用户可同时是多个仓的成员（个人仓 + 团队仓），所有写入路径
  （AI 成稿/手动 PUT/生成）一律写该用户**活动仓**下的本人子目录——活动仓 =
  `worklog_user_settings.active_repo_key`（null = 个人仓）；MVP 每人仅个人仓路由
  无歧义，团队仓绑定（M2）后由设置页切换。**本机路径不入库**：本地仓根目录走配置
  `devmind.worklog.repo-root`（默认 `./data/worklog/repos`），DB 只存 `repo_key`，
  物理路径由根目录 + key 派生。仓目录是服务端本地磁盘状态，随 `data/` 一起备份；
  平台按单实例语义运行（多实例共库部署会产生分叉仓，属非支持形态）。
- **FR-02 目录契约**：仓内布局
  `<username>/daily/yyyy-MM-dd.md`、`<username>/weekly/yyyy-Www.md`
  （周文件名**定稿 `yyyy-Www.md`**：落 weekly/ 子目录后既有 `weekly-` 前缀省略，
  迁移时统一改写为该形式，并写进仓 README 骨架）。首次建仓/成员加入时写骨架
  commit（子目录 + README 说明契约）。**否决 index.json 派生索引**（见 §3）。
- **FR-03 写入路径收口（单写者）**：仓的所有平台写入只经服务端 `WorklogRepoService`
  一处：仓级锁（按 repo_key striped lock）串行化 → 写文件 → git add/commit
  （GitRemoteOps 扩展方法）。写入目标 = 操作人/会话归属用户的活动仓（FR-01）。
  - AI 成稿：`WorklogOutputMirror` 改造——outputs 命中 daily/weekly 成稿后不再
    upsert DB，改为写入**会话归属用户**活动仓的本人子目录并 commit；
  - 手动编辑：报告 PUT 直接写文件并 commit；
  - commit message 规范：`worklog: daily 2026-10-12` 一行摘要 + trailer
    `DevMind-Source: ai|manual` / `DevMind-Session: <sessionId>`（AI 生成时），
    署名经 `GitIdentityProvider` 按远端 host 解析（无远端/无绑定回退
    displayName/username），AI 生成的 commit 署名仍归属报告主人（报告是人的产出）。
- **FR-04 去状态机：保存即生效**：删除 DRAFT/CONFIRMED 语义——
  - 报告 PUT 不再有 status 字段；前端去掉确认按钮/状态徽标；
  - 防自动覆盖不靠状态位，靠一条规则：**自动生成（定时/手动非 force）在目标文件
    已存在时跳过**；force 重新生成必须显式触发（force 覆盖前的旧内容由 git 历史
    保全）。「CONFIRMED 不覆盖」补丁逻辑整体删除；
  - 历史与审计 = `git log`（更新时间、每次改动、trailer 来源），平台不再自建。
- **FR-05 读路径**：日报/周报列表与详情**从服务端仓读**——目录扫描（文件名编码
  日期，天然有序）+ 限定路径的 git log 取最近提交时间与 trailer + 按需读文件内容
  （列表 preview 取前 N 字；周报 summary/nextPlan 分节读时解析，沿用
  `ReportService.splitWeekly`）。git log 取时间/trailer 用**一次调用批量解析多路径**
  （`git log --format` 全路径），禁 per-file 逐条 fork 进程。数据量级为一人一年数百
  小文件，不做缓存；将来成瓶颈只准加服务端内存缓存，**禁止落盘索引/回建 DB 镜像**。
- **FR-06 远端备份与同步（服务端 push）**：「推送远端」改为服务端
  GitRemoteOps push：凭证按 remote host 经 CAP-35 `resolveToken` 解析（缺失 409
  引导），署名 FR-03；出口按 CAP-70 `egress_rules` 逐请求选择（直连/socks5h 隧道，
  命中规则但隧道离线快速失败），公网部署够不到内网 git 时配规则即可，零新协议。
  - **写前 fetch + merge**：任何 push 前先 fetch；远端领先/分叉自动
    `merge --no-edit`（GitRemoteOps 新增 merge 方法，现状仅有 ffOnly；成员子目录
    路径不相交，跨人恒干净）；merge 冲突=**同一人
    多端改同一文件**，fail-visible 报给用户并提供「以平台为准 / 以远端为准」
    两个显式动作（`POST /api/worklog/repos/{key}/resolve?strategy=ours|theirs`），
    不静默覆盖任何一侧，仓不留 MERGING 状态；
  - **平台外编辑是合法输入**：用户可在平台外 clone 团队仓直改 markdown。读路径
    带 TTL 节流的后台 fetch（如 60s），fetch 失败用本地内容降级并可见提示
    （fail-visible），不阻塞渲染。**fetch 凭证 = 仓级 fetch 身份**：仓主
    （created_by）的 PAT 优先，任一持有效 PAT 的成员兜底；均无凭证则跳过 fetch
    仅用本地内容。
- **FR-07 生成链路适配**：生成会话仍在 runner 上跑（CAP-34），WORKLOG 项目保留为
  会话调度载体，但：
  - **节点亲和红线松绑**：runner 侧目录降级为纯会话草稿区（skill/上下文物化与
    claude 写 `.devmind/output` 的 cwd），不再 git init/版本化/未提交告警；亲和
    节点离线只影响「生成/对话」，日志页读/编辑/推送全部不受影响（直接收益）；
  - worklog skill 种子文案改：删「claude 自己 commit 持久工作区」约定，保留
    「成稿写 `.devmind/output/`」回传约定；存量环境 skill 行「不存在才建」不覆盖，
    需管控台手工同步（同 CAP-41 FR-09 的口径）；
  - 周报素材：skill 删「回读工作区 daily/ 文件」约定（草稿区无历史），周报 prompt
    已携带该周日报全文（`ReportPrompts.weekly` 现成），以此为唯一素材口径；
  - runner 侧 `worklog_push` 帧与远端备份逻辑退役（协议字段保留不删，老 runner
    收到即 no-op；新版服务端不再下发）。
- **FR-08 迁移**：启动迁移任务（幂等）——以 **DB 镜像内容为准**（它含镜像+手改的
  并集，是最新真相）把 daily_reports/weekly_reports 逐条写入本人个人仓并 commit
  （message 标 `DevMind-Source: migration`）；已确认/草稿不加区分（状态机废除）。
  **幂等判据**：按 `(用户, 日期/周)` 映射目标文件，内容一致即跳过、不一致才
  写+commit——重复启动不产生新 commit。迁移 commit 的 author/committer date 回填
  报告的 `updated_at`，保证 git log 时间列（FR-05 的 updatedAt 来源）迁移后不失真。
  迁移完成后 daily_reports/weekly_reports 变死表（手工 DROP，同 agent_conn_logs
  先例）；runner 侧旧工作区文件留现场不删，不再消费。
- **FR-09 前端**：/worklog 页日报/周报 Tab 去确认态交互；「推送远端」按钮改调
  服务端 push（不再依赖亲和节点在线）；空间信息条去掉「节点离线=空间不可用」
  提示口径（改为仅影响生成）；「推送工作日志」手动同步保留（语义=collect outputs
  → 写仓）。**MVP 不做团队只读视图**（读同仓他人子目录的「团队成员」Tab 留演进）。

## 3. 关键设计

- **否决 index.json 派生索引**（已定）：曾设计仓内 `index.json`（更新时间/来源/
  preview 索引），因 FR-06 承认平台外编辑为合法输入而作废——索引在共享仓里必然
  永远滞后，还要维护「HEAD 变更检测 + 重建」机器，而收益在该数据量级上约等于零。
  它承担的字段全部由 git 自身提供：日期=文件名、列表=目录扫描、updatedAt=路径
  git log、preview=读文件前几行、source/sessionId=commit message trailer。
  **仓内禁止任何派生态文件**：文件 + git 历史即全部真相，平台外任何工具改完自洽。
- **按仓建模而非按人**（已定）：个人仓 = 成员为 1 的仓，代码只有一条路径；跨人
  冲突由「per-user 子目录路径不相交」结构性消除；推送用各人自己的 PAT + 署名，
  远端仓写权限交给 git 平台自己的成员管理，平台不重复造权限。
- **单写者收口**（已定）：平台对仓的写入只有 `WorklogRepoService` 一个入口 +
  仓级锁，这是「写文件与 commit 原子」「不产生半提交」的全部保证；平台外写入
  靠 git 自身 merge 语义收敛。
- **状态机删除的等价物**（已定）：CONFIRMED 的保护语义由「自动路径不覆盖已有
  文件 + force 显式 + git 历史可回溯」三元组承接，不靠任何标志位。

## 4. 插件化接口

- 无新 SPI。git 操作走 integration 的 GitRemoteOps/CAP-35 凭证链（ObjectProvider
  探测，缺席时推送 409 引导，本地仓读写不受影响）；出口选择走 CAP-70 既有规则
  解析；成稿消费仍是 `session.completed` 事件监听（`WorklogOutputMirror` 改造）。

## 5. 数据模型与协议

```sql
worklog_repos        ── id, repo_key UK, remote_url NULL, remote_branch default 'main',
                         created_at, created_by
worklog_repo_members ── id, repo_id FK, user_id, subdir（=username，UK(repo_id,subdir)），
                         UK(repo_id,user_id)
daily_reports / weekly_reports   迁移后变死表（手工 DROP）
worklog_user_settings ── remote_url / remote_branch 列废弃（迁入 worklog_repos）；
                         新增 active_repo_key（null=个人仓，M2 团队仓后切换写入目标）
```

WS 协议：`worklog_push` 帧退役（不下发；runner 侧保留处理做 no-op 兼容）；launch
帧 `kind:"worklog"` 保留（草稿区路由仍需要）。协议版本不强制 +1（无新帧），runner
侧草稿区去 git 化随 runner 版本自然生效，服务端不门控。

## 6. API 概要

```
GET/PUT /api/worklog/daily|weekly ...      对外契约基本不变（读改写仓；PUT 去 status 字段）
POST    /api/worklog/daily|weekly/generate 不变（已存在跳过；force 显式）
POST    /api/worklog/reports/sync          保留（语义=collect outputs → 写仓）
POST    /api/worklog/workspace/push        改服务端 push（不再下发 runner；入参出参不变）
POST    /api/worklog/repos/{key}/resolve   push 冲突显式解决（strategy=ours|theirs，M2）
GET     /api/worklog/workspace             空间信息改为仓信息（repo_key/remote/最近提交）
POST    /api/worklog/repos/{key}/remotes   团队仓绑定/成员管理（M2，MVP 仅个人仓自动建）
条目/repos/git preview/import、settings GET/PUT（模板部分）  全部不变
```

## 7. 验收标准

1. 新用户首次进 /worklog 自动建好个人仓（`repo-root/<key>/<username>/` 有骨架
   commit）；生成日报后仓内出现 `daily/yyyy-MM-dd.md` 且 commit 带
   `DevMind-Source: ai` + sessionId trailer；
2. 手动编辑日报保存后：仓文件内容更新 + 新 commit（`DevMind-Source: manual`）；
   推送远端后远端仓可见该内容（手改进 git，本能力的立项动机）；
3. 定时自动生成在文件已存在时跳过（不覆盖手改）；force 重新生成覆盖后旧版本可从
   git log 找回；
4. 亲和节点离线时：日志页读/编辑/推送远端全部正常，仅生成/对话 409 提示（M1 起可验）；
5. 两人绑定同一远端仓：各自推送后双方子目录齐全、merge 无冲突；同一人平台外改
   文件 push 后，平台读路径 TTL 内看到新内容；同一人双侧改同一文件产生冲突时
   报错并提供两个显式解决动作，仓不留 MERGING；
6. 出口规则命中（host glob → 隧道节点）时 push 走隧道成功；隧道离线快速失败；
7. 迁移：存量 DB 报告（含手改过的）全部落仓且内容一致；重复启动不产生新 commit
   （内容一致即跳过），迁移 commit 时间回填报告 updated_at；
8. 无 DRAFT/CONFIRMED 残留：前端无确认按钮/徽标，PUT 传 status 被忽略或 400。

## 8. 分期与 MVP 边界

- **M1 服务端仓 + 去镜像 + 最小 push**：repos/members 建模 + WorklogRepoService 单写者 +
  读改写仓 + 去状态机 + 输出镜像改造 + 迁移任务 + 前端去确认态 + **服务端最小 push**
  （fetch + ff-only + push，走 egress；推不动即 409 fail-visible，不做 merge）——
  push 必须进 M1：否则 M1→M2 缝隙期「推送远端」推的是 runner 侧彻底停滞的旧稿，
  比现状更坏；
- **M2 远端完整化与团队**：写前 fetch + `merge --no-edit` + 冲突显式解决（resolve 端点）
  + 团队仓绑定与成员管理（active_repo_key 切换）+ 平台外编辑 TTL fetch；
- **M3 演进**：团队成员只读视图、条目文件化（`entries/` 由 claude 维护，素材口径
  从 DB 条目切到文件）、runner 草稿区清理代码下线。

MVP 明确不做：index.json 或任何落盘索引（否决，见 §3）、团队仓的细粒度权限
（交给远端 git 平台）、报告全文搜索（成瓶颈时另立 CAP 评估内存索引）。
