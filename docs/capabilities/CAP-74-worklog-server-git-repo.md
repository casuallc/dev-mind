# CAP-74 工作日志事实源服务端化（服务端 git 仓 + 去镜像去状态机 + 团队共享仓）

> 能力 ID：CAP-74 ｜ 分类：组装层 ｜ 状态：**已实现（M1+M2 + runner 去 git 化）** ｜ 日期：2026-10-12
> （2026-10-12 评审修订：技术选型定稿 git CLI 否决 JGit、M1 纳入最小服务端 push、
> 补 active_repo_key 写入路由、迁移幂等判据与冲突解决 API、周报文件名定稿 yyyy-Www）
> （2026-10-12 实现完毕：M1+M2 同批落地 + runner 草稿区去 git 化；M3 未做。
> 实施偏差见文末 §9）
> （2026-10-19 修订：**写后自动推送**——每次写仓产生新 commit 且仓已绑定远端时，
> 服务端后台自动 fetch+merge+push 被写入的仓（含团队仓），见 FR-06 末段；
> §6 口径 4「推送只推个人仓」随之修订为「手动 push 仍只推个人仓，自动推送推被写入的仓」）
> （2026-10-19 修订②：**镜像「只推当前会话产生的」+ 手改保护**——镜像单位定稿为
> 当前会话的产出（每个日报/周报生成都单独开会话，天然一份）。runner 侧产出回传
> 按会话过滤：worklog 持久草稿区（{worklogRoot}/<owner>/ 跨会话共享、永不删除）只回传
> 本会话启动后写出的文件（mtime 过滤 + 10min 宽限防预写误滤），历史会话攒下的旧稿
> 不再混入 session_outputs——这是"一推好多天"的根因修复。`mirrorAll`（手工对话结束
> 自动镜像 + 「推送工作日志」手动同步）对本会话产出的每份成稿逐一过闸：内容与仓一致
> 不写不提示（重复推送零 commit 也不刷"已写入"）；仓内最后提交为 manual 且内容有差异
> 的文件跳过不覆盖、进 skipped 警示（仓是事实源，手改永不被成稿静默覆盖；要覆盖走
> 日志页 force 重新生成）。场景生成路径（mirrorDaily/mirrorWeekly）不受影响——force
> 是显式覆盖意图）
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
  - **写后自动推送（2026-10-19 修订）**：`WorklogRepoService` 每次写产生新 commit
    且该仓已绑定远端时，锁外触发后台自动 push（`WorklogRemoteBackupService.autoPushAsync`）
    ——AI 成稿回传、手动 PUT 保存、条目写入全部走同一收口，生成/保存完成即远端可见，
    不再依赖手动「推送远端」。语义：
    - 推的是**被写入的仓**（含团队仓；手动 push 入口仍只推个人仓）；
    - 复用同一条 fetch+merge+push 链与冲突处理：传输失败/无可用凭证 → 置同步异常位
      （空间视图 fail-visible 复用 C6 提示位）+ warn，不阻断写；merge 冲突**不自动
      选边**——abort + 置位 + P0 通知仓主，仍走显式 resolve；推送/resolve 成功复位异常位；
    - 凭证走「仓主优先、成员兜底」链（事件/调度线程无 SecurityContext，不能
      `currentActor()`）；
    - 按仓飞行中合并：推送期间又有新 commit 只记 pending，推送线程结束前补推一轮，
      连续编辑/批量迁移不打出一串 push；
    - 零变更写（committed=false）不触发；未绑定远端直接跳过。
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
  → 写仓；2026-10-19 修订②起「只推当前会话产生的」：runner 回传按会话过滤，
  镜像逐份过闸——内容一致不写不提示、manual 稿跳过进 skipped）。**MVP 不做团队只读视图**（读同仓他人子目录的「团队成员」Tab 留演进）。

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
   绑定远端后保存/生成**无需手动推送**即自动同步远端（2026-10-19 写后自动推送，
   传输失败置同步异常位、冲突 P0 通知仓主走显式 resolve）；
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

## 9. 实施偏差与运维说明（2026-10-12 实现后补记）

相对 §5/§6 定稿口径的实际落地差异：

1. **PUT 寻址由 id 改为日期/周**：`PUT /api/worklog/daily/{date}`、
   `PUT /api/worklog/weekly/{weekStart}`（无 DB 后 Long id 无载体）。视图 DTO 删
   `id`/`status`、增 `exists`；请求传 `status` 字段一律 400（防旧前端误以为确认生效）。
2. **WorkspaceView 形态**：`exists/repoKey/remoteUrl/remoteBranch/lastCommitAt/
   fetchStale/projectId/agentNodeId/nodeOnline`；remoteUrl/remoteBranch 恒展示个人仓
   口径（push 只推个人仓）；nodeOnline 仅用于「离线仅影响 AI 生成与对话」提示。
3. **settings 的 remote 两列废弃留存**：`worklog_user_settings.remote_url/remote_branch`
   停读写（读写改路由 worklog_repos），ddl-auto 不删列，存量库手工清理口径：
   `ALTER TABLE worklog_user_settings DROP COLUMN remote_url, DROP COLUMN remote_branch;`
   （不清理无害）。`daily_reports`/`weekly_reports` 迁移后变死表，手工 DROP 同理。
4. **active_repo_key 语义**：null=不变、空白串=回个人仓、其他=必须是本人所属仓
   （成员校验失败 400）；写入目标随之切换。推送口径（2026-10-19 修订）：手动
   「推送远端」仍只推个人仓；写后自动推送推被写入的仓（个人/团队均可），
   团队仓成员各写各的子目录、自动推送各推各的（凭证各自兜底解析），路径不相交
   结构性免冲突。
5. **冲突解决双入口**：`POST /api/worklog/repos/{key}/resolve?strategy=ours|theirs`
   之外，另有个人仓快捷端点 `POST /api/worklog/workspace/resolve?strategy=...`
   （key 由当前用户推导，免前端拼 `personal-<username>`）。resolve = 锁内重新 fetch
   + `merge -X ours|theirs --no-edit` + push，不留 MERGING。
6. **成员/仓列表端点**：`GET /api/worklog/repos/mine`（我所属的仓，供设置页切换）、
   `POST/DELETE /api/worklog/repos/{key}/members[/ {username}]`（仓主操作；§6 写的
   `POST .../remotes` 未做——远端绑定沿用 settings 入口，写在个人仓行上）。
7. **skill 种子不覆盖存量**：`WorklogAssetSeeder` 只在新装时落「不再自行 commit
   工作区」的新版 skill/场景内容；存量部署需在管控台 Skill 页手工同步一次
   （ seeds 幂等不覆盖是历史约定）。
8. **runner 去 git 化随 jar 生效**：`prepareWorklog` 不再 git init（存量 git 草稿区
   只补写 .gitignore）、`finishWorklog` 删除、`worklog_push` 帧 runner 侧改 no-op ack
   （协议帧保留兼容）。**runner jar 重建后必须重启所有 runner 节点**（本机 WinSW
   服务 devmind-agent / devmind-agent-194 及 140.88 等远程节点），否则运行中 jar
   被覆盖会新旧类混装。
9. **验收 6（egress 隧道）** 由 `GitRemoteOpsEgressTest` 单测覆盖（CAP-70 既有回归网），
   E2E 不起真实隧道；验收 7 的带行迁移幂等由 `WorklogReportMigrationTest` 覆盖，
   E2E（tests/e2e-cap74.py）钉「重启零新 commit」。

## 10. M3 条目文件化与 runner 清理（2026-10-12 落地）

1. **条目从 DB 迁仓内文件**：`<subdir>/entries/yyyy-MM-dd.md` 每日一文件，条目以
   `<!-- devmind:entry id=<32hex> type=.. hours=.. source=.. repo=.. sha=.. req=.. jira=.. created=.. -->`
   注释行定界 + `## 标题` + 正文；preamble（首个注释前内容）与未知 `k=v` 属性逐字节/原序
   无损往返，平台外手改不丢。条目 id 从 DB 自增 Long 改 uuid 字符串（破坏性变更，前后端同发布）。
2. **API 变更**：`EntryView.id` 为 String；删除改 `DELETE /entries/{date}/{id}`
   （date 定位 day 文件免全目录扫）；`updatedAt` 口径 = `createdAt`（条目粒度无独立
   更新时间，历史走 git log）。
3. **素材口径切换**：日报/周报生成预检与素材（ReportService/ReportPrompts/GitLogScanner
   判重）全部改读仓文件；`WorklogEntryEntity`/`WorklogEntryRepository` 已删，
   `worklog_entries` 表 ddl-auto 不删，手工清理口径：`DROP TABLE worklog_entries;`（不清理无害）。
4. **存量迁移幂等**：`WorklogEntryMigration`（ApplicationRunner）按 (user, date) 分组，
   幂等键 = 条目元数据 `legacy=<db id>`，commit 时间回填组内最新 updated_at；一律写
   **个人仓**（已切团队仓的用户其个人仓历史条目不跨仓聚合，与报告迁移同款已知限制）。
5. **skill 教追加格式**：worklog skill 种子增「追加工作条目」节（source=AGENT、hours
   0.25 步进、只尾部追加不动他人 marker/preamble）；存量部署需在管控台 Skill 页手工同步。
6. **runner push 链路整体下线**：`RunnerWorkspace.pushWorklog/ensureGitignore` 与存量
   .git 补写分支删除（骨架/.gitignore 初写保留）；服务端 `worklog_push` 下发链路删除
   （AgentConnectionRegistry/AgentNodeWsHandler/AgentNodeConnector），协议常量
   `WORKLOG_PUSH_FRAMES`/`WORKLOG_PUSH_IDENTITY` 标 @Deprecated，`CURRENT=23` 不回退；
   runner 侧 `worklog_push` no-op ack 分支按 §5 保留兼容旧服务端。**运维**：runner jar
   重建后重启所有节点（WinSW devmind-agent/devmind-agent-194、140.88），新旧两侧任意
   组合均安全。
7. **E2E**：`tests/e2e-cap74-m3.py`（32 断言）——H2 URL INIT 预植 legacy 表真迁移、
   条目文件字节/trailer、平台外手改存活、删除寻址、git 导入判重走文件、生成预检
   文件化通路、重启迁移幂等。
