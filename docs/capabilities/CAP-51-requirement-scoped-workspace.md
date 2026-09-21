# CAP-51 需求粒度工作区与并行开发（Requirement-scoped Workspace）

> 能力 ID：CAP-51 ｜ 分类：底座 ｜ 状态：**需求定稿待评审** ｜ 日期：2026-09-21
> 重构 CAP-42 的工作区归属粒度与生命周期（取代其 FR-01~FR-06、FR-09；FR-07/FR-08/FR-10 保留）。
> 构建工作区（CAP-36）、问答沙箱（CAP-30）、worklog 空间（CAP-41）不受影响。

## 1. 目的

CAP-42 把 runner 代码工作区固定到 `{项目}/{登录用户}`，解决了依赖沉淀与「节点上一块固定地盘」，
但把归属粒度绑死在「用户」上，实际使用时暴露三个痛点：

1. **同一人在同一项目只能有一个活跃工作区**：需求 A 的会话（哪怕只是跑分析）占用
   `<proj>/<user>/work` 后，需求 B 点分析直接 409「你在本项目的固定工作区仍被会话 X 占用」。
   两个需求无法并行，且必须先手动收口 A 才能开 B。
2. **纯读型流程会话占用代码地盘**：分析/方案/拆分三个 flow 会话的 taskSpec 明写
   「不要修改项目代码」，产出只落 `.devmind/output/*.md`（平台排除路径）并经 `session_outputs`
   回传（CAP-37/39）——它们只需要一份代码快照，却把开发用的固定工作区锁住，反向也一样：
   正在需求 A 上写代码时，A 的分析都起不来。
3. **占用键与用户心智不一致**：用户的心智是「每个需求一块工作区」，实际键是「(项目, 用户)」，
   报错文案里的会话 id 也帮不上定位（是哪个需求？）。

本能力把归属粒度从「用户」改为「需求」：

- **一个需求一块工作区、一条分支**：需求内多个会话（分析 → 方案 → 开发 → 测试）串行共用
  同一工作树与同一分支，改动天然累积在同一条线上；
- **跨需求零冲突**：不同需求各自目录、各自分支，可并行开发（上限只受节点 `maxConcurrent` 约束）；
- 占用判定从「同 (项目,用户) 是否有未收口会话」改为「同需求是否有进行中的会话」，
  报错直达需求，且不再波及无关需求。

## 2. 功能需求

### FR-01 需求粒度工作区布局

runner 侧目录从 `<proj>/<owner>/{main,work}` 改为：

```
<workspaceRoot>/<projectId>/<owner>/main                     克隆缓存（每用户每库一份，不变）
<workspaceRoot>/<projectId>/<owner>/<repo>/main              多库缓存（不变）
<workspaceRoot>/<projectId>/<owner>/worktrees/<key>/         工作树（claude cwd；多库时为聚合根）
<workspaceRoot>/<projectId>/<owner>/worktrees/<key>/<repo>/  多库子 worktree
```

`<key>` 由服务端在 launch 帧显式下发（**不由 runner 推导**，避免隐式约定）：

- 会话关联需求 → `req-<requirementId>`（需求 id 为 8 位 `[a-z0-9]`，天然落在 SAFE_ID 白名单内）；
- 无需求会话（项目级/临时）→ `sid-<sessionId>`（每会话独立目录，等价 CAP-42 之前的按会话隔离）。

保留目录名集合（`RunnerWorkspace.RESERVED_DIRS`、`WorkspaceReconciler.NON_OWNER_DIRS`、
`UserAdminService`、`SessionManagerService` 四处副本）增加 `worktrees`；owner/repo 名禁用。

### FR-02 需求级分支与需求内串行

- 分支名：有需求 → `feature/req-<requirementId>`；无需求 → `feature/<sessionId>`（不变）。
- `WorktreeManager.branchFor` 增加需求维度的解析（服务端唯一生成点，runner 不复制命名逻辑）。
- 需求内第二个会话直接复用已存在的工作树与分支（`ensureWorktree` 的「HEAD 与期望分支一致 →
  复用」分支命中），因此**上一个会话的改动与提交对下一个会话天然可见**，无需收口中转。
- `SessionRepoEntity.branch` 快照与 resume/finalize/release 现算值必须一致：命名规则变更后，
  存量会话快照里的 `feature/<sid>` 与新规则不符 → 收口/释放必须按**落库快照**优先，
  快照缺失才按新规则推导（否则 `finalizeOne` 的分支一致性校验必报「检出分支与会话分支不一致」）。

### FR-03 占用判定改需求级

- 服务端预检（替换 CAP-42 的 `findByProjectIdAndWorkspaceOwnerAndWorkspaceState`）：
  **同需求存在非终态（RUNNING/QUEUED/SUSPENDED）会话** → 409「该需求已有进行中的会话 X，
  请先结束它或等待完成」；resume 同一会话时跳过该预检。
- 无需求会话不预检（`sid-` 键天然独占；同 sid resume 幂等）。
- 跨需求不冲突：不同 `<key>` 目录，互不感知。
- runner 侧 `ensureWorktree` 的分支比对逻辑保留为**最终防线**（需求粒度下期望分支恒定，
  正常不触发；触发即说明磁盘上有历史残留，报错文案按目录名/key 描述而非反查会话 id）。

### FR-04 收口（需求级，手动）

- 收口从「会话级动作」上移为「**需求级动作**」：合并 `feature/req-<rid>` → 基线分支 → push。
- **收口后保留工作树与分支**（不再 `worktree remove` + `branch -D`）：需求可能还要继续开发，
  保留才能让后续会话接着用；收口成功后 best-effort 把需求分支前进到新基线
  （`merge --ff-only`，工作树有未提交改动时跳过并告警），避免下次收口重复合并同一批提交。
- 语义保持手动、失败可见：脏工作树 / 合并冲突 / push 失败 → 工作树原样保留可重试；
  `discardChanges=true` 仍只清未提交脏文件，不解提交级冲突。
- 无需求会话（`sid-` 键）收口语义不变：合并 + push + **删工作树与分支**（用完即弃，
  没有「后续会话接着用」的诉求）。
- 端点：
  - 新增 `POST /api/requirements/{projectId}/{requirementId}/workspace/finalize {discardChanges}`
    （鉴权 = 需求创建者/负责人或 admin）——前端主入口，挂在需求详情页；
  - 保留 `POST /api/sessions/{id}/finalize`：无需求会话用；有需求的会话调用时**转发到需求级语义**
    （便于存量前端与脚本平滑过渡）。
- 仓库描述来源：该需求下最近一条带 `session_repos` 快照的会话；全无快照 → 409「该需求无代码工作区」。

### FR-05 释放与定期清理（新增 GC 维度）

CAP-42 明确固定工作区「不参与 GC」，需求粒度下目录数会随需求线性增长，必须补策略。
`worktrees/<key>` 纳入对账与 GC（沿用既有「宁跳不错删」四条件）：

- **对账（WorkspaceReconciler）**：扫描 `<proj>/<owner>/worktrees/<key>` 的 `.runner-pid`，
  回收孤儿 claude 进程；进程已不在时**登记为无主目录**（与 `work/` 的「永不移交 GC」不同，
  工作树是可回收的临时产物）。
- **GC（WorkspaceGc）**：新增删除判定（全部满足才删）：
  1. 无存活 pid 文件（复用 `hasLivePidFile`）；
  2. 目录 mtime 距今 > `worktreeGcDays`（**新增配置，默认 30 天**，比会话目录 `gcDays=14` 长）；
  3. `git status --porcelain` 为空（有未提交改动**永不自动删**，只告警——防吞掉 agent 的活）；
  4. 检出分支已在远端（`git ls-remote origin refs/heads/<branch>` 命中，收口会 push 需求分支，
     故收口过的需求可回收；探测失败保守保留）。
  删除动作 = `worktree remove --force`（失败退化为递归删）+ `worktree prune` + `branch -D`。
- 分支推导不再拼字符串：GC 对 worktree 目录直接 `git rev-parse --abbrev-ref HEAD` 取当前分支。

### FR-06 需求删除/终态释放

- **需求删除**：project 模块发 `DomainEvent`，session 模块监听后向节点下发 `workspace_release`
  （丢弃语义，不合并不 push）。释放失败**不阻断删除**（需求已删，不能让用户卡住），
  记日志 + 通知；残留目录由 FR-05 的 GC 兜底（条件 4 能兜住已收口的需求）。
- **需求进终态（DONE/CANCELLED）**：不自动释放（用户可能还要看），页面提示可释放；
  超龄后由 GC 回收。

### FR-07 无需求会话回退

未关联需求的代码会话用 `sid-<sessionId>` 键，工作树按会话独立创建与释放，收口语义同 CAP-42
（合并 + push + 删）。这类会话不参与需求级预检，也不产生「地盘」语义。

### FR-08 共享基线检出 `work/`（M2，可选）

在需求粒度工作树之上，保留一份**共享基线检出** `<proj>/<owner>/work`（检出基线分支，
常驻不删），作为人工开发/构建的「地盘」：需求工作树是 agent 的隔离作业区，`work/` 是人的落脚点，
需求收口后 `work/` 前进到新基线（`merge --ff-only`）。

- 前提：克隆缓存 `main/` 必须让出基线分支的检出（改为 detached），否则 `worktree add work <base>`
  会因「分支已被检出」失败。该解耦在 M2 落地（M1 不动缓存检出行为）。
- 存量 `work/` 目录（CAP-42 布局，「检出的是某个 `feature/<sid>`」）**不自动迁移**：
  升级说明要求人工处理（在其内收口或丢弃未提交改动后删除），新代码不主动删该目录。

### FR-09 数据模型与协议 v10

```sql
sessions     ── + workspace_key VARCHAR(64) NULL   -- req-<rid> / sid-<sid>；null = 旧布局（key="work"）
requirements ── + workspace_owner VARCHAR(64) NULL -- 工作区归属用户名（首次建工作区时冻结，不随负责人漂移）
              ── + workspace_state VARCHAR(16) NULL -- OPEN=占用中 / FINALIZED=已收口；null=无工作区
```

- `requirements.workspace_state` 是新真源（会话行上的 `workspace_state` 保留为存量兼容，
  新逻辑不读；前端工作区 Tag 与收口按钮改读需求）。
- 协议 **v10**：launch 帧 +`workspaceKey`；`workspace_finalize` / `workspace_release` 帧
  +`workspaceKey`（收口/释放要定位目录）。三类帧都必须被 runner 认识——老 runner 忽略
  `workspaceKey` 会落回 `work/` 旧布局，把不同需求写进同一目录，故属「必须认识」，
  服务端 `supports(nodeId, 10)` 门控，老 runner 409 提示升级。
- 新增协议常量 `REQUIREMENT_WORKSPACE = 10`，`CURRENT` 提升到 10；版本史补一行。
- **落点红线**：新字段必须在 `AgentConnectionRegistry` 的帧组装处补 `put`
  （launch/finalize/release 三处）+ LaunchTest/帧形状测试断言，否则静默丢失。

### FR-10 前端

- **需求详情页新增「工作区」卡片**：状态 Tag（占用中/已收口/无工作区）+ 「收口合并到基线」按钮
  （弹窗说明 + 「丢弃未提交改动」勾选，文案写明保留工作树）+ 未提交/冲突失败原因展示。
  挂在需求详情页（需求是工作区的归属单位，用户在那儿看进度）。
- **会话列表撤掉收口入口**：`SessionMoreActions` 的收口项仅对**无需求会话**保留；
  有需求的会话在列表里显示所属需求的收口状态（只读 Tag），操作引导到需求详情页。
- 后端字段：`RequirementView` 增 `workspaceState` / `workspaceOwner`；`SessionView.workspaceState`
  保留（值改为所属需求的收口状态，读不到需求时回退会话行旧值）。
- 全部在 `features/requirements` 与 `features/sessions` 内自包含，遵守内容区布局约定。

### FR-11 存量兼容与迁移

- **旧会话**（`workspace_key IS NULL`，CAP-42 布局 `<owner>/work`）：收口/释放按旧 key `work` +
  旧分支规则（快照优先）执行，行为等价 CAP-42；不可 resume 到新布局（会新建需求工作树并从
  远端/本地分支挂回，提交不丢）。
- **旧 `work/` 目录**：新代码不再写入该路径；升级说明列出人工清理步骤（FR-08）。
- **`SessionRepoEntity.branch` 快照**：所有收口/释放/diff 一律**快照优先**，快照为 null 才按新规则推导。
- 需求表两列由 `ddl-auto=update` 自动加列，不写迁移脚本。

### FR-12 claude 本地状态目录与保留期

工作树粒度变细后，claude CLI 自身的本地状态会跟着 cwd 走，必须显式管住（实测 + 对节点 CLI bundle
2.1.278 的反编译级核实，结论见下）。

- **状态目录按 cwd 归属**：claude 把 transcript 写到 `<claudeConfigDir>/projects/<slug(cwd)>/`，
  slug = cwd 绝对路径里所有非 `[a-zA-Z0-9]` 字符替换为 `-`，超过 200 字符则截断为「前 200 + 短哈希」
  （截断后靠哈希区分，不可逆）。同目录内：每会话一个 `<cli-session-id>.jsonl` + 一个同名 sidecar
  目录（`subagents/`、`tool-results/`、`workflows/`、`remote-agents/`）+ `memory/`。
  → **一需求一目录**（需求内多会话共用），目录数与需求数同阶、单个仅 KB 级；吃盘的是「会话数 ×
  轮次」，与本能力选的粒度无关。
- **`cleanupPeriodDays` 必须显式设置**：该设置的**默认值是 30 天**（`0` 被拒绝，最小值 1），claude
  启动时静默清扫超过保留期的 transcript、sidecar、`file-history/`、`session-env/`、`tasks/` 等。
  静置超期的需求工作树会**失去 resume 能力**（提交仍在分支上，代码不丢，但会话上下文不可续）。
  清扫**遍历整机所有 `projects/`**，cutoff 取合并后的设置——**任一层 settings 写短值会波及整机**，
  因此统一在 runner 专属 config 的 user settings 里设，**不放项目级**。
- **runner 用专属 `claudeConfigDir`**（新配置项，默认 `{workspaceRoot}/../claude-config`，与
  `claudePath`/`claudeConfigDir` 同处 `agent.properties`）：不再写节点用户个人的 `~/.claude`，
  使平台会话的保留期策略、清理范围与用户的个人 Claude Code 记录彻底隔离（也避免清理误伤个人记录）。
  升级时需在该目录补一次登录态（`ANTHROPIC_*` env 与凭据），随节点升级一次性完成。
- **resume 需在原 cwd**：transcript 的归属键是**启动时的 cwd**，不是 git root。本机 2.1.278 构建里
  `--resume <id>` 另有两层兜底（枚举同仓库 `git worktree list`、以及全 `projects/*` 扫 `<id>.jsonl`，
  命中多个则放弃），故换路径后存量会话大概率仍可续——**但这两层无文档承诺，不得依赖**。
  设计口径：**一律在原 worktree 路径 resume**（`sessions.workspace_key` + repo 快照可推导路径）；
  跨路径续上算 bonus，续不上报清晰错误。
- **平台不主动清 transcript**：工作树被 GC/释放后其 `projects/<slug>` 变孤儿目录，但由 claude 自身
  保留期回收即可；留一个 `purgeClaudeStateOnWorktreeGc` 开关（**默认 false**）备极端场景。

## 3. 关键设计

- **占用与并行的边界**：服务端预检管「同需求互斥」，runner 的目录比对管「磁盘残留」。
  真正的并行天花板是节点 `maxConcurrent`（默认 4）与机器资源，不是本能力要解决的。
- **收口保留工作树**：与 CAP-42 的「收口即释放」相反。释放改为三个独立触发点（需求删除 /
  需求终态后超龄 GC / 无需求会话收口），互不耦合，避免「收口把还要用的工作区删了」。
- **需求内串行的代价**：同需求的多个 WI 不能同时改代码。这是粒度选择的固有取舍——
  需求内的 WI 通常有依赖关系，串行成本低于跨需求被迫串行。
- **依赖产物沉淀**：按需求沉淀（同需求的多个会话复用一个工作树），跨需求不共享
  （这是「并行」的对价）；人工地盘由 FR-08 的 `work/` 承担。
- **磁盘成本**：活跃需求数 × 工作树文件（worktree 不复制 `.git` 对象库），
  加上 agent 若跑构建则依赖各装一份；claude 侧额外一份按 cwd 归属的 transcript（FR-12）。
  GC 是唯一兜底，参数可调。
- **保留期与工作区寿命对齐（FR-12）**：需求工作树保留多久，transcript 保留期就得覆盖多久，
  否则「工作树还在、会话续不上」这种半可用状态最难排查。三者（`worktreeGcDays`、
  `cleanupPeriodDays`、需求实际生命周期）在部署时一并确认。
- **合并放临时 detached worktree**（沿用 CAP-42，已定）：绝不碰克隆缓存的检出分支，
  `.finalize-tmp` 内 merge 后 `push HEAD:<baseBranch>`。
- **部署顺序约束**：老 runner + 新服务端 → 409 门控；新 runner + 老服务端 → repo 会话缺
  `workspaceKey` 直接报错。双向 fail-visible，无静默降级。

## 4. 插件化接口

- 无新 SPI。`AgentNodeConnector` 的 `finalizeWorkspace` / `releaseWorkspace` 增加 `workspaceKey`
  参数（default 方法兼容旧签名）。

## 5. API 概要

```
POST /api/requirements/{projectId}/{requirementId}/workspace/finalize   {discardChanges} → {ok, detail}
POST /api/sessions/{id}/finalize                                        （无需求会话；有需求时转发需求级）
GET  /api/requirements/{projectId}/{requirementId}                      RequirementView + workspaceState
GET  /api/sessions...                                                   SessionView.workspaceState（= 所属需求状态）
```

## 6. 验收标准

1. 需求 A 分析中，需求 B 可同时点分析并成功建会（不同 `<key>` 目录，无 409）；需求 A 的第二个
   会话（如「生成方案」）在 A 结束后可直接复用 A 的工作树与分支，看得到上一会话的提交；
2. 需求 A 工作区占用时报错直达需求（「该需求已有进行中的会话 X」），不再提「本项目」；
3. 页面按需求收口：远端基线含 `--no-ff` 合并提交、需求分支已 push、**工作树保留**、
   `requirements.workspace_state=FINALIZED`；重复收口 409；收口后需求分支前进到新基线；
4. 无需求会话仍走 `sid-` 键、收口后工作树与分支被删除，远端不受影响；
5. 未提交改动收口失败（提示 discardChanges）；合并冲突失败透传且工作树保留，可 resume 解冲突；
6. 删除需求 → 节点工作树释放（丢弃语义）；节点离线时删除不阻断、记告警，目录由 GC 兜底；
7. GC：无存活 pid + 超龄 `worktreeGcDays` + 无未提交改动 + 分支已推远端 → 工作树与本地分支删除；
   任一条件不满足则跳过并记原因；有未提交改动的工作树永不自动删；
8. 老 runner（协议 < v10）派发会话/收口/释放 409 提示升级；新 runner 收到缺 `workspaceKey`
   的 repo 会话直接报错（不落旧布局）；
9. 存量 CAP-42 会话（`workspace_key IS NULL`）照常收口/释放/删除，行为与升级前一致；
10. chat / worklog / 构建链路零回归；`git status --porcelain` 在零改动会话下仍为空（FR-10 回归保持）；
11. claude 状态落 runner 专属 `claudeConfigDir`（节点用户个人 `~/.claude/projects` 不再新增平台目录）；
    该 config 的 `cleanupPeriodDays` 已显式设置（非默认 30）；静置超过 30 天的需求会话仍可 resume
    （在**原 worktree 路径**启动，FR-12）。

## 7. 分期

- **M1 需求粒度工作区（核心）**：CAP 文档 + 协议 v10（`workspaceKey` 三帧）+ 服务端
  分支/键解析与快照优先 + 需求级预检 + `requirements` 两列 + 收口需求级语义（保留工作树）+
  runner 布局改造（`worktrees/<key>`）+ Reconciler/GC 扩展 + claude 状态目录/保留期（FR-12，
  随节点升级一次性完成）+ Java 单测 + E2E 脚本同步。
- **M2 前端与需求页入口**：需求详情页工作区卡片 + 收口按钮/弹窗 + 会话列表撤收口入口 +
  SessionView 状态来源切换 + 前端类型检查/E2E。
- **M3 共享基线检出 `work/`（可选）**：克隆缓存检出解耦（detached）+ 地盘创建与收口后前进 +
  升级说明与存量 `work/` 清理指引。

## 8. 与 CAP-42 的关系

| CAP-42 条目 | 处置 |
|---|---|
| FR-01 每用户固定布局 | **取代**：改为 `<owner>/worktrees/<key>` 需求粒度布局 |
| FR-02 占用互斥与 resume 幂等 | **取代**：占用判定改需求级；resume 幂等保留（目录比对逻辑复用） |
| FR-03 结束降级为告警 | 保留（收口仍手动） |
| FR-04 GC/对账适配 | **取代**：固定目录不再「天然不参与 GC」，新增 `worktrees/<key>` 的 GC 维度 |
| FR-05 手动收口 | **取代**：上移为需求级，合并 + push 但**保留工作树** |
| FR-06 归属人解析 | 保留（回退链不变），结果冻结到 `requirements.workspace_owner` |
| FR-07 用户名字符约束 | 保留（保留名集合加 `worktrees`） |
| FR-08 前端 Tag/收口入口 | **取代**：入口移到需求详情页，会话列表仅保留无需求会话的入口 |
| FR-09 删除会话释放工作区 | **取代**：释放触发点改为需求删除/终态超龄/无需求会话收口 |
| FR-10 平台物化与版本控制隔离 | 保留（`excludePlatformPaths` 六条路径与 `CLAUDE.local.md` 不动） |
