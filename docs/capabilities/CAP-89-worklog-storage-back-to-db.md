# CAP-89 工作日志存储回 DB（去 git 仓化与远程推送，取代 CAP-74）

> 能力 ID：CAP-89 ｜ 分类：组装层 ｜ 状态：**已实现（M1 单批合拢，2026-10-26；E2E tests/legacy/api/cap89_e2e.py 26 项全绿）** ｜ 日期：2026-10-26
> 决策记录：**全部回 DB 唯一存储，git 仓与远程推送整体退役**（2026-10-26 用户定：
> 「远程没有意义」——CAP-74 的核心收益（远端备份/平台外编辑/团队共享）在实际使用中
> 没有场景，而 git 链路成本（单写者锁、marker 解析、merge/resolve、自动推送/egress、
> 迁移机器）全部兑现）；防覆盖语义定稿**自动不覆盖 + force 显式**（无状态机、不留
> 历史旧版）；**团队仓整体退役**，工作日志回纯个人域。本能力取代 CAP-74（其
> 「读写不依赖 runner」「自动不覆盖」两项真收益以 DB 等价物保留）；CAP-88 计划项
> 随之改为 DB 表。

## 1. 目的

CAP-74 把事实源从 DB 迁到服务端本地 git 仓，立项动机是治三个病：双写单向同步的
结构性分叉、手改保护靠 DRAFT/CONFIRMED 补丁、亲和节点离线绑架日志页可用性。
病治好了，但药方的主收益没有兑现：

- **远程推送无人用**——写后自动推送、冲突 resolve、egress 隧道、仓级凭证兜底链，
  一整条链路服务的是一个不存在的场景；
- **平台外编辑无人用**——没有人在平台外 clone 仓直改 markdown；
- **团队仓无人用**——共享语义本来就绑在远端 git 平台上。

留下的只有成本：仓级单写者锁、条目/报告 marker 文件解析器、两次迁移机器
（DB→仓）、磁盘状态随 data/ 备份的运维口径、以及「禁回建 DB 镜像」约束下所有
结构化需求（如 CAP-88 计划项的状态跟踪）都要绕着文件走。

回 DB 后：存储与平台其他能力同构（JPA + ddl-auto），状态/关联字段原生可查，
链路删掉一大半。CAP-74 的两项真收益用 DB 等价物保留：

1. **读写不依赖 runner**：DB 本来就在服务端，亲和节点只影响生成/对话（不变）；
2. **手改防覆盖**：「自动生成在已存在时跳过 + force 显式覆盖」这条规则不依赖
   git，DB 存在性判断即可成立（代价：force 覆盖不留旧版历史——已确认接受）。

## 2. 功能需求

### M1 存储回 DB + git 链路退役（单批合拢，删多于建）

- **FR-01 报告回 DB**：复活 `daily_reports` / `weekly_reports`（CAP-74 迁移后的
  死表，结构沿用 CAP-28 §5：daily = unique(user_id, work_date) + content_md；
  weekly = unique(user_id, week_start) + summary_md/next_plan_md 两列，
  `splitWeekly`/`assembleWeekly` 分节解析现成）。**status 列不映射不读不写**
  （物理列 ddl-auto 不删，留存无害）——防覆盖语义 = 自动路径（定时/手动非 force）
  在目标行已存在时跳过；force 显式覆盖（旧版不留，git 历史保护随之放弃）。
  `session_id` 列恢复记录来源会话。
- **FR-02 条目回 DB**：重建 `WorklogEntryEntity`/`WorklogEntryRepository`（CAP-74
  §10 已删）。**主键保持 String uuid（32hex）**——文件时代已做过一次 Long→String
  破坏性变更，回 DB 不再二次翻转前端契约；字段沿用 CAP-28 FR-03（user_id/
  work_date/title/content/entry_type/minutes/source/repo_id/commit_sha/
  requirement_id/jira_issue_key），**新增 `plan_item_id` 可空列**（CAP-88 关联
  点随本能力一并落）。git 导入幂等键 (user_id, repo_id, commit_sha) 恢复 DB
  唯一约束判重（CAP-28 原口径，文件判重逻辑退役）。
- **FR-03 存量迁移（仓 → DB）**：启动一次性迁移任务（ApplicationRunner，幂等）：
  扫 `devmind.worklog.repo-root` 下所有仓所有 `<subdir>/`，解析
  `daily/*.md` / `weekly/*.md` / `entries/*.md` 回 DB 行：
  - 报告幂等键 = (user_id, work_date / week_start)，已存在跳过；
  - 条目幂等键 = marker `id`（uuid 即主键，天然幂等）；条目 `created_at/updated_at`
    取文件 git log 该 marker 首/末次提交时间（最后一次走 git，兜底 mtime）；
  - 仓主映射：subdir = username 直接对应 user；多仓同 subdir（个人仓+团队仓）按
    个人仓优先、冲突跳过记 warn；
  - 迁移完成后仓目录**留现场不删**（不再消费），`repo-root` 配置废弃。
- **FR-04 生成与读写链路适配**：
  - `ReportService` 素材/预检改回读 DB（CAP-28 口径 + CAP-41 既有增强全保留：
    hint、提交正文/numstat 素材、`{{lastWeekPlan}}`——其来源回到上周
    `weekly_reports.next_plan_md` DB 文本，直到 CAP-88 M2 改计划项驱动）；
  - `WorklogOutputMirror` 消费 session outputs 改 upsert DB：目标行已存在跳过
    （自动不覆盖），force 重生成走显式覆盖；手工对话镜像（mirrorAll）同步改为
    DB upsert + 同样不覆盖规则（原「manual 稿跳过进 skipped」的仓保护语义由
    「已有行不覆盖」承接，skipped 提示保留）；
  - 报告 GET/PUT、条目 CRUD 全部回 DB 直读直写。
- **FR-05 git 仓与远程推送链路退役**（纯删除）：
  - 服务端：`WorklogRepoService`（单写者/仓锁/commit）、
    `WorklogRemoteBackupService`（自动推送/冲突检测）、`WorklogReportMigration`、
    `WorklogEntryMigration`（仓方向迁移）、仓文件解析器（条目 marker 解析
    ——**CAP-88 曾计划抽共用，DB 化后无此需求**）、`/api/worklog/repos/**` 与
    workspace push/resolve 端点；
  - 表：`worklog_repos` / `worklog_repo_members` 死表化（手工 DROP 口径）；
    `worklog_user_settings.active_repo_key` 列废弃停读写（留存无害）；
  - 前端：「推送远端」按钮、活动仓切换、同步异常位（C6 提示位）、resolve 冲突
    处理 UI 全部移除；空间信息条瘦身 = 项目状态 + 亲和节点在线态（仅影响生成
    提示，口径不变）；
  - runner **零改动**（CAP-74 已完成草稿区去 git 化，草稿区只是 cwd；
    `.devmind/output` 回传链路不动）；skill/场景种子文案不变（「成稿写
    `.devmind/output/`」约定与存储侧无关）。
- **FR-06 API 契约收敛**：对外端点尽量不动（CAP-74 实施时保住了契约，回 DB
  同样保）；唯一回收：`DELETE /api/worklog/entries/{date}/{id}` 回
  `/entries/{id}`（DB 主键直接定位，date 寻址是文件时代的限制），前端同步小改。

### M2 收尾

- **FR-07 文档与配置清理**：application.yml 删 `devmind.worklog.repo-root`；
  CAP-74 文档头标注「已被 CAP-89 取代」；CAP-41 中「runner 持久 git 工作区/
  事实源」相关段落标注演进（生成会话链路、模板、skill 种子仍有效）；本模块
  排错节按 DB 口径重写。

## 3. 否决方案

- **保留本地 git 仓、只删远程推送（半吊子）**：否决。远程被判无意义后，本地仓
  剩余的「收益」只有 git 历史，而成本（锁/解析器/迁移/磁盘运维/禁 DB 镜像约束
  卡住结构化需求）一分不少；要简化就简化到底。
- **恢复 DRAFT/CONFIRMED 状态机**：否决（已定）。CAP-74 已验证「自动不覆盖 +
  force 显式」足够，确认动作是纯负担。
- **报告修订历史表**：否决（已定）。首版不留旧版；真出现误覆盖诉求再立演进
  （revisions 表是自然扩展位）。
- **团队仓 DB 化保留**：否决（已定）。共享语义绑在远端 git 平台上，DB 化要自建
  权限模型且无使用场景；回纯个人域（owner 强制，CAP-62 口径）。
- **连 WORKLOG 特殊项目一并取消**（2026-10-26 复议）：否决。数据落库后项目的
  「数据载体」角色确实没了，但剩下的是**调度载体**，拆不掉：亲和节点固化在
  `project.agent_node_id`；会话内核两处（起会话/resume）靠 `project.kind=WORKLOG`
  识别无仓库/无分支/持久草稿区分支；runner 草稿区隔离键
  `<worklogRoot>/<owner>/` 取自 `project.ownerId`；前端 SessionsBoard 按
  projectId 锁定/过滤。取消它 = 会话内核新增「无项目个人会话」形态
  （节点路由/归属/resume/列表筛选全部绕开 project），收益只是概念干净、
  每用户少一行幻影项目；而现有特判（列表 kind 过滤/切换器兜底）已写好且稳定。
  若未来会话内核原生支持个人会话（如迁 CAP-30 无项目问答体系），届时顺手拆。

## 4. 架构与模块归属

- 后端：`devmind-worklog` 模块内重构——复活/重建实体与 repo、ReportService/
  WorklogOutputMirror/GitAutoImportService 素材判重回 DB、删 git 仓三件套与
  迁移机器。无新 SPI、无 WS 协议变更、runner 零改动。
- 前端：`features/worklog` 删仓/推送/冲突 UI，条目删除端点回收，其余不动。
- 依赖：CAP-28（原 DB 口径与表结构）、CAP-41（生成链路/模板，存储段被取代）、
  CAP-62（个人域 owner 强制）。**取代 CAP-74**；被依赖：CAP-88（计划项 DB 表
  与 `plan_item_id` 列随本能力定稿）。
- 附带收益：多实例共库部署的「分叉仓」非支持形态随之消失（DB 天然收敛）。

## 5. 数据模型

```sql
-- 复活（死表重新启用，结构不变；status 列不映射）
daily_reports(id PK, user_id, work_date, content_md clob, /*status 忽略*/,
              session_id?, created_at, updated_at, unique(user_id, work_date))
weekly_reports(id PK, user_id, week_start, summary_md clob, next_plan_md clob,
               /*status 忽略*/, session_id?, created_at, updated_at,
               unique(user_id, week_start))

-- 重建（id 保持 String uuid，避免二次破坏前端契约；plan_item_id 为 CAP-88 关联点）
worklog_entries(id varchar(32) PK, user_id, work_date, title, content clob,
                entry_type default 'DEV', minutes int, source,
                repo_id?, commit_sha?, requirement_id?, jira_issue_key?,
                plan_item_id?, created_at, updated_at,
                unique(user_id, repo_id, commit_sha))   -- GIT 来源判重（CAP-28 原口径）

-- 死表化（手工 DROP 口径，不清理无害）
worklog_repos / worklog_repo_members

-- 列废弃（停读写，ddl-auto 不删列，留存无害）
worklog_user_settings.active_repo_key
daily_reports.status / weekly_reports.status
```

存量库手工清理口径（可选）：`DROP TABLE worklog_repos, worklog_repo_members;`
`ALTER TABLE worklog_user_settings DROP COLUMN active_repo_key;`
`ALTER TABLE daily_reports DROP COLUMN status;`（weekly 同）。

**存量库必做（2026-10-26 194 事故补记）**：ddl-auto=update 不改存量列**类型**——
CAP-28 时代 `worklog_entries.id` 是 bigint IDENTITY，实体改 String uuid 后老库列型
不随动，PG 上读写即炸 `operator does not exist: bigint = character varying`（新库 E2E
全绿盖不到）。凡从 CAP-74 之前一路升级来的库须手工执行：

```sql
-- PG：先 DROP IDENTITY（IDENTITY 列不收 DROP DEFAULT）；MySQL：MODIFY id varchar(32)
ALTER TABLE worklog_entries ALTER COLUMN id DROP IDENTITY;
ALTER TABLE worklog_entries ALTER COLUMN id TYPE varchar(32) USING id::text;
-- 老数字 id 行（CAP-74 已全量迁 git 仓）建议删除，让启动回迁以 uuid 干净重导，
-- 否则 MANUAL 条目新旧双份、GIT 条目永留数字 id：
DELETE FROM worklog_entries WHERE id ~ '^[0-9]+$';   -- 删前先 CREATE TABLE z_bak_... AS SELECT 备份
```

194 已于 2026-10-26 执行（备份表 `z_bak_worklog_entries_pre_varchar_20261026`，
删 1310 行数字 id 老数据，重启后回迁 +1931 条 uuid 条目，API 建/删实测通过）。

## 6. API 概要

```
报告/条目/设置/生成/同步    全部端点与视图 DTO 不变（数据源换 DB）
DELETE /api/worklog/entries/{id}              回收 date 寻址（原 /entries/{date}/{id}）
GET    /api/worklog/workspace                 瘦身：去 repoKey/remote/fetchStale/同步异常位，
                                              保留 projectId/agentNodeId/nodeOnline（仅影响生成提示）
移除：POST /api/worklog/workspace/push|resolve、/api/worklog/repos/**（仓管理/成员/冲突解决）
```

生成语义变化：「已存在跳过 / force 覆盖」从文件存在性判断改为 DB 行存在性判断，
行为对用户不变；「推送工作日志」（collect outputs → 落存储）保留，目标改 DB。

## 7. 安全

- 个人域 owner 强制不变（CAP-62 口径，接口层强制本人）；团队仓退役后无跨人
  可读面。
- 删除远程推送 = 删掉仓级凭证解析链（CAP-35 resolveToken 在 worklog 域的消费）
  与 egress 出口的一个消费方，攻击面缩小。
- 迁移任务是服务端本地文件读 + DB 写，无新外部面；subdir→user 映射白名单校验
  （防路径注入，沿用 CAP-41 sanitize 口径）。

## 8. 里程碑

- **M1 回迁 + 退役**（单批）：FR-01~06——表复活/重建、迁移任务、读写与生成链路
  回 DB、git 仓与推送链路删除、前端瘦身。CAP-74 设施是现成的反向映射素材，
  删多于建，不分两期。
- **M2 清理**：FR-07 文档/配置/排错节收尾。

MVP 明确不做：修订历史表、DRAFT/CONFIRMED 任何形式的复活、团队共享、仓目录
物理删除（留现场，运维确认后可手工删）。

## 9. 验收标准

1. 迁移：存量仓内日报/周报/条目全部落 DB 且内容一致；重复启动零新行（幂等）；
   条目 created/updated 时间取自 git log 不失真；
2. 手动编辑日报保存 → DB 行更新；定时自动生成在已有行时跳过；force 重生成覆盖
   生效（旧版不留，符合定稿）；
3. AI 成稿回传 → DB upsert + 通知不变；「推送工作日志」手动同步落 DB；
4. 条目 CRUD / git 预览导入（DB 判重，重复导入不产生重复条目）/工时口径全部
   与 CAP-28 原行为一致；条目 id 仍是 String（前端零适配）；
5. 亲和节点离线：日志页读/编辑正常，仅生成/对话 409 提示（CAP-74 收益保留）；
6. 前端无「推送远端」/仓切换/冲突 resolve 残留入口；workspace 信息条瘦身生效；
7. 全量 `mvn test` 绿（CAP-74 的仓方向测试随代码删除，迁移测试改钉仓→DB 方向）；
8. E2E（tests/ 沉淀）：预植仓文件 → 启动迁移 → DB 行断言 → 重启幂等 → 生成/
   force/不覆盖规则行为断言。

## 10. 风险与开放问题

- **force 覆盖无历史**：已确认的代价。若误覆盖诉求真实出现，演进项 = 报告
  revisions 表（覆盖前快照旧行），不阻塞本能力。
- **迁移窗口的数据写竞争**：迁移任务与在线写（生成回传/手改）并发时，已存在
  跳过规则保证不覆盖新写；迁移只在启动期跑一轮，存量环境升级即完成。
- **仓目录留现场的磁盘占用**：量级极小（markdown 文件），运维可择机手工删；
  文档写明「平台不再消费」即可。
- **条目 id 保持 String 的例外性**：平台惯例主键 Long，此处为保前端契约的
  有意例外，写进模块开发注意（避免后来者「顺手」改回 Long 造成第二次破坏）。
- **CAP-41 文档多处「文件为事实源」段落**：M2 统一标注演进，不留相互矛盾的
  定稿文本。
