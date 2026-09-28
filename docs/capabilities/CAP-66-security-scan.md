# CAP-66 安全漏洞扫描（Security Scanning）

> 能力 ID：CAP-66 ｜ 分类：执行器 ｜ 状态：**需求定稿** ｜ 日期：2026-09-28
>
> 依赖 CAP-12（统一执行底座/日志 Hub）、CAP-21/34/36（exec 帧与工具链标签调度）、
> CAP-29（服务端克隆/仓库登记）、CAP-02/62（项目归属与数据权限）、CAP-06（通知）、
> CAP-32（报告附件）、CAP-33/34（AI 复核会话装配）。
>
> 新增能力。缘起：平台已有构建/部署/测试/发版四个执行器，唯独缺安全一环；而「安全扫描」
> 不等于跑一遍 dependency-check 出张报告——单引擎 SCA 只覆盖第三方依赖 CVE 一个维度，
> 看不到自研代码缺陷（SAST）、硬编码密钥泄漏，也没有基线 diff、误报抑制、修复跟踪这些
> 让报告「可运营」的治理能力。本能力建一个**多引擎、可归一化、可持续运营**的安全扫描模块。
>
> **增补（2026-09-28）**：针对 SCA 两大实测痛点补 FR-10/FR-11——①dependency-check 误报多
> （CPE 启发式匹配错配 + test scope 噪声 + 「有 CVE 但用不上」），对策是「过滤→降权→抑制→排序」
> 四层漏斗而非单点 suppression file；②报告只告知名单不告知修法，对策是把修复路径做成一等输出
> （引入链 + 最低安全版本 + 修复动作分类 + AI 修复会话）。

## 1. 目的

- 建立**统一扫描底座**：一次扫描任务编排多个引擎（MVP 三引擎：SCA / SAST / 密钥泄漏），
  各引擎原始输出归一化为统一发现模型（Finding），跨引擎一个列表、一套严重度、一套状态机。
- **可运营**：fingerprint 去重 + 跨任务基线 diff（本次新增 / 存量 / 已修复）+ 误报/风险接受
  抑制管理，让第二次、第 N 次扫描只盯增量，而不是每次面对几千条原始告警。
- **复用既有链路零新造**：扫描执行走 CAP-36 exec 帧下发 agent 节点（工具链标签调度），
  日志走 ExecutionLogHub，报告原文落 CAP-32 附件，高危发现走 CAP-06 通知。
- **AI 复核**：发现详情可一键起复核会话（claude 在对应仓库工作区读代码给出判定与修复建议），
  把平台最强的 agent 能力接到安全场景上。

**关键约束**：引擎二进制不进 Maven/dist，由节点预装（与 CAP-34 的 claude 二进制同策略：
`agent.properties` 配路径 + 平台探测 + 自检）；平台不托管 CVE 库（dependency-check 的 NVD
缓存留节点本地）；扫描结果永远只是「输入」，是否放行/拦截的门禁编排不在本能力。

## 2. 功能需求

### FR-01 扫描任务模型

- 新表 `security_scans`：一次扫描 = 项目 + 单仓库 + 基线 commit（创建时快照 `origin/<branch>`
  引用，CAP-26 先例）+ 勾选引擎集合（`sca/sast/secrets` 至少一项）。
- 状态机：`QUEUED → RUNNING → SUCCESS / FAILED / CANCELED`（沿用执行器家族口径）；
  每引擎一个子步骤状态（engine_status JSON：每引擎 status/耗时/原始发现数/归一化数/错误摘要），
  单引擎失败不拖垮整任务——任务 SUCCESS 且该引擎标 FAILED（部分成功），错误摘要可见。
- 触发方式 v1 仅**手动 + Open API**（同项目同分支有 RUNNING/QUEUED 任务时 409 防重）；
  定时扫描、CI 挂钩见非目标。
- 异步触发方法禁 @Transactional（红线，异步线程看不到未提交行会卡 QUEUED）。

### FR-02 引擎体系与适配器 SPI（归一化是核心）

- `ScanEngineAdapter` SPI（devmind-security 内定义，引擎按 Spring Bean 注册）：
  `engineId()` / `displayName()` / `detectCommand()`（自检命令）/ `execSpec(scanCtx)`（拼
  exec 帧步骤：命令、超时、环境）/ `parse(reportFile) → List<NormalizedFinding>`。
- MVP 三引擎实现：

  | engineId | 引擎 | 覆盖维度 | 原始输出 | 归一化要点 |
  |---|---|---|---|---|
  | `sca` | OWASP dependency-check | 第三方依赖 CVE | JSON | dependency → artifact 坐标 + Maven scope 记录；CVE/CVSS 取 NVD 分；CPE 匹配证据（identifiers/evidence）与 suppress 标记透传 |
  | `sast` | Semgrep | 自研代码缺陷/危险 API | SARIF（`--sarif`） | ruleId + 代码片段；严重度映射 semgrep level（ERROR→HIGH…） |
  | `secrets` | Gitleaks | 硬编码密钥/Token | SARIF（`--report-format sarif`） | 命中行内容**脱敏落库**（只留前后各 4 字符，中间打码），规则名即密钥类型 |

- 统一严重度：`CRITICAL / HIGH / MEDIUM / LOW / INFO`；SCA 按 CVSS v3 分段
  （≥9.0 / 7.0 / 4.0 / 0.1 / 0），SAST/secrets 按引擎 level 映射表（适配器内常量，可配覆盖）。
- 引擎扩展（trivy 镜像/IaC、checkov、代码许可合规）后续只加适配器 Bean，不动主链路。

### FR-03 节点执行（exec 帧，工具链标签调度）

- 扫描一律下发 runner 执行（服务端零执行红线），复用 CAP-36 exec 帧链路：repo 块随帧下发
  （CloneTokenResolver 解析 token，runner 内存持有不落盘），runner clone/切到基线 commit 后
  逐引擎执行，日志实时进 ExecutionLogHub（任务详情页可逐引擎看实时日志）。
- **工具链标签**：节点登记标签（`sca`/`sast`/`secrets`，即 `agent_nodes.toolchain_tags` 既有
  机制），调度 = 勾选引擎 ∩ 节点标签命中才可选；无命中节点创建任务 409 并列出缺哪些引擎标签。
- **引擎路径解析**（runner 侧 `agent.properties`，claudePath 同策略）：`scan.sca.path` /
  `scan.sast.path` / `scan.secrets.path` 优先，空按平台探测（Windows=`where` /
  Linux·macOS=`which`）；探测失败 = 该引擎子步骤 FAILED（错误摘要写明查哪项配置），不拖垮任务。
- **节点工具自检**：节点详情页按标签逐引擎发一条轻量 exec（`--version` 级命令），
  回显版本号；装没装、版本对不对不用登机器确认。
- 产物回传：各引擎原始报告文件（dependency-check JSON+HTML、SARIF）经 exec 产物通道
  回传服务端落附件（CAP-32），finding 归一化在**服务端**做（解析器升级随平台发版，
  不强依赖 runner 版本）。
- dependency-check 的 NVD 缓存留节点本地（`~/.dependency-check` 默认），平台不托管；
  首次跑慢（拉库）属预期，日志可见进度。

### FR-04 统一发现模型与基线 diff

- 新表 `security_findings`（字段见 §5）。**fingerprint** =
  `sha1(engineId | ruleId/cve | 仓库相对路径 | 归一化代码行哈希)`；行哈希对上下文 ±1 行
  漂移容忍（取目标行去空白哈希），同一次扫描内同 fingerprint 只留一条。
- 每次任务 SUCCESS 后做**基线 diff**（同 项目+仓库+引擎 维度与上一次 SUCCESS 任务比对）：
  - 本次有、上次无 → `NEW`（增量告警，运营主战场）；
  - 两次都有 → `EXISTING`；
  - 上次有、本次无 → 旧 finding 置 `FIXED`（记录修复于哪次扫描），不物理删除。
- 被抑制规则命中的 fingerprint 直接落 `SUPPRESSED`（不进 NEW，列表默认折叠，见 FR-05）。
- 任务详情页头部就是 diff 摘要：`新增 X（严重度分布）｜ 存量 Y ｜ 修复 Z`，
  默认视图只列 NEW。

### FR-05 抑制与误报管理

- 新表 `security_suppressions`：规则 = 项目 + 仓库 + 引擎 + fingerprint + 类型
  （`FALSE_POSITIVE 误报` / `ACCEPTED_RISK 风险接受` / `THIRD_PARTY 上游问题`）+
  理由（必填）+ 操作人 + 过期时间（可空，过期自动失效回 OPEN）。
- 单条发现页可一键建抑制（fingerprint 带出）；抑制列表页集中管理/解除。
- 解除抑制后下一次扫描同 fingerprint 重新按 NEW 出现（历史 finding 不回访改状态）。
- 抑制与引擎原生 suppress（dependency-check suppression file 等）**不互通**：平台层抑制
  是唯一权威，引擎层 suppress 透传落库仅作展示。

### FR-06 报告与导出

- 原始报告（JSON/SARIF/HTML）全量留存附件，任务详情页可逐个下载。
- 汇总导出：`GET .../export?format=csv|sarif`——CSV 面向汇报（全字段扁平），
  SARIF 面向工具链对接（多引擎结果合并为一个 SARIF run 数组）。
- 任务维度统计随列表返回（各严重度计数、NEW/EXISTING/FIXED 计数），项目维度
  最近一次扫描状态卡片（项目详情页挂一个 Security Tab，见 FR-07）。

### FR-07 前端（`features/security` 自包含）

- **任务列表页**（项目内 Security Tab + 全局「安全扫描」菜单项两个入口，遵循内容区布局约定）：
  项目/分支/状态筛选， diff 摘要列，发起扫描对话框（仓库/分支/引擎勾选/节点标签命中预览）。
- **任务详情页**：引擎子步骤状态条 + 逐引擎实时日志抽屉 + diff 摘要头 + 发现列表。
- **发现列表**：严重度/引擎/状态（NEW/EXISTING/SUPPRESSED/FIXED）/规则/文件筛选，
  默认只看 NEW；行内一键抑制。
- **发现详情抽屉**：引擎原始字段（CVE 描述/CVSS 向量/SARIF message/密钥脱敏命中）+
  代码定位（文件:行 + 片段）+ 历史轨迹（首次出现于哪次扫描、状态变迁）+ 抑制/解除 +
  「AI 复核」「AI 修复」按钮（FR-08/FR-11）；SCA 发现另有专属区块——依赖坐标与 scope、
  CPE 匹配证据链、引入链、KEV/EPSS 徽标、推荐修复版本（附版本区间原文）与修复动作分类。

### FR-08 AI 复核（可选消费，不阻塞主链路）

- 发现详情一键「AI 复核」：装配复核场景上下文（finding 全字段 + 代码片段 + 引擎依据）
  起项目会话（CAP-33 场景模板内置一个 `security-review` 场景），claude 在该仓库工作区
  读真实代码给出判定（真/误报、可利用性、修复建议），产出经 CAP-37 回传登记为 finding
  的复核记录（`security_findings.review_note/reviewed_at/review_session_id`）。
- 复核只是记录与建议，不自动改 finding 状态，人工仍可推翻。

### FR-09 通知

- 任务 SUCCESS 且 NEW 中存在 HIGH/CRITICAL → CAP-06 通知（级别 WARN，标题含项目/新增数/
  最高严重度，深链到任务详情默认视图）；任务 FAILED → 通知发起人（INFO 级）。
- 不发逐条发现通知（防轰炸），只有任务级一条。

### FR-10 SCA 误报治理（过滤 → 降权 → 抑制 → 排序，四层漏斗）

dependency-check 误报的三大来源：CPE 启发式匹配错配（内部包/同名构件被匹配到无关产品）、
test/provided scope 噪声、「CVE 真实存在但与本项目无关」。单点 suppression file 扛不住，
本能力按四层漏斗治理，**逐层递减噪音、每层留痕可查**：

1. **过滤·内部构件白名单**：平台默认 + 项目可追加的 groupId 前缀列表（如 `com.apusic`、
   `com.kingdee`），命中的构件其 CPE 匹配全是噪声 → 归一化层直接不入库，任务统计里记
   「白名单过滤 N 条」（可展开看明细，防误杀内部真引用的 OSS  shaded 包——shaded 场景
   按 embedded 证据放行不过滤）。
2. **降权·依赖作用域**：归一化记录 Maven scope（dependency-check JSON 自带）；`test`/
   `provided` 的 finding 打降权标记（severity 不改写、另存 `dep_scope`），发现列表默认
   排除、计数单列——不删但不与运行时依赖混排。
3. **抑制·CVE 抑制库（构件 × CVE 粒度，平台级复用）**：新表 `security_cve_suppressions`
   ——与 FR-05 的 fingerprint 抑制（单代码位置、单仓库）互补：CVE 抑制裁定的是
   「这个 CVE 对这个构件不成立」，**一处裁定、按 scope（全局/单项目）复用到所有扫描**，
   同一误报不用每个项目、每次扫描各点一遍。理由必填，`ACCEPTED_RISK` 类型强制过期时间，
   过期后同 fingerprint 重新按 NEW 出现。
4. **排序·可利用性情报富化**：服务端定时同步 EPSS（FIRST.org 公开 CSV）与 CISA KEV
   目录落 `vuln_intel` 表（离线可配关闭），归一化时按 CVE 回填 detail_json
   （epss 分/百分位、kev 标记/修复截止日）；发现列表支持「KEV 在榜优先 / EPSS ≥ 阈值」
   排序过滤——误报绝对数降不完时，用真实威胁情报决定先看哪几条。数据源不可达降级为
   字段 null，不阻塞扫描。

AI 复核（FR-08）是漏斗之上的兜底：人工对拿不准的条目发起复核，claude 读真实代码与
CPE 证据链（detail_json 已透传）给真/误报判定，判定结果一键转 FR-05/FR-10-3 抑制。

### FR-11 修复指导（「怎么修」作为一等输出）

报告只给「哪个 jar 有哪个 CVE」等于没给修法。本能力把修复路径做成 finding 的标配字段：

1. **引入链（谁带进来的）**：SCA 步骤在 runner 上附带采集依赖树（Maven 执行
   `dependency:tree -DoutputFile`；Gradle 执行 `dependencies --configuration runtimeClasspath`），
   输出随报告回传，服务端解析出每个脆弱构件的引入路径（直接依赖，或传递链顶端的那个
   直接依赖），finding 详情与 CSV 导出均携带。采集失败不阻塞任务（字段为空 + 缺失原因标注）。
2. **修复版本推荐**：归一化时从 NVD CPE 版本范围推导**最低安全版本**（同 major 内优先，
   无则跨 major 最低修复版），KEV 在榜的附修复截止日。**纯启发式**：推荐旁恒附来源版本
   区间原文，推导不出时明示「需人工查证」，不编造版本号。
3. **修复动作分类**（detail_json.remediation）：
   - `UPGRADE_DIRECT`——直接依赖，升版本即可；
   - `PIN_TRANSITIVE`——传递依赖，给出「dependencyManagement 钉版 / 对顶端直接依赖
     排包（exclusion）」两种改法与目标坐标；
   - `REPLACE`——构件已废弃且无修复版，建议替换构件；
   - `NONE_AVAILABLE`——暂无修复版：给缓解措施建议文本 + 直达「风险接受」抑制入口。
4. **AI 修复会话**（FR-08 的修复向扩展）：发现详情除「AI 复核」外提供「AI 修复」——
   装配修复场景上下文（finding + 引入链 + 推荐版本 + remediation 分类），会话在仓库工作区
   直接改 pom.xml/build.gradle 并跑编译（或项目构建脚本的快速验证步）验证改动不破坏构建，
   产出经 CAP-37 回传登记 `fix_session_id` / `fix_diff_summary`；**本能力不自动合入**，
   人工确认后走既有收口链路（CAP-51）。

## 3. 关键设计

- **归一化在服务端（已定）**：runner 只负责跑引擎 + 回传原始报告，parse 与 fingerprint
  在服务端做。理由：①引擎输出版本漂移时改解析器随平台发版即可，不强依赖全节点升级 runner；
  ②fingerprint 算法单点演进，历史 finding 状态机不被节点版本分叉污染。
- **全量扫描 + diff，不做增量扫描（已定）**：引擎本身大多不支持可靠的增量语义，
  增量在「比对层」做（FR-04），扫描层永远全量，语义简单可靠；大仓库靠节点本地缓存
  （克隆缓存/NVD 缓存）摊薄成本。
- **引擎不进包、节点预装（已定）**：与 claude 二进制同策略（配置路径 + 平台探测 + 自检），
  dist 不背几百 MB 的引擎与漏洞库；后续若要平台化分发，走 CAP-57 安装包链路另立 CAP。
- **只出报告与增量，不做门禁（已定）**：是否拦截构建/发版的编排属流程层
  （挂 CAP-17 执行链或 DecisionGate），另立 CAP；本能力数据模型已留足
  （每任务 diff 摘要 + 机器可读 SARIF 导出），门禁消费方届时只读不写。
- **密钥脱敏落库（已定）**：gitleaks 命中原文永不入库不脱敏版本（原始 SARIF 附件也仅
  ADMIN 可下载），列表/详情/API 出参一律脱敏形态。
- **误报治理走漏斗不走单点（已定）**：四层顺序不可颠倒——白名单过滤（确定性噪声）→
  scope 降权（可疑噪声）→ 抑制（裁定过的个案）→ 情报排序（剩余条目的阅读优先级）。
  把 EPSS/KEV 排在最后而非最前，是因为情报解决「先看哪条」而非「这条是不是真的」，
  拿它当过滤器会把「暂无 exploit 情报的真漏洞」误杀。
- **CVE 抑制与 fingerprint 抑制双轨（已定）**：fingerprint 抑制（FR-05）钉「代码位置」，
  适用 SAST/secrets；CVE 抑制（FR-10-3）钉「构件 × 漏洞」，适用 SCA 跨项目复用。
  不做统一抽象——两者粒度、过期语义、复用范围都不同，硬合并只会两头别扭。
- **修复推荐是启发式不是承诺（已定）**：NVD 版本区间本身常有错漏，推荐版本只作
  「建议起点」并附区间原文；真正可信的验证是 AI 修复会话里跑一次编译/构建，
  平台不在服务端模拟依赖求解（不重建 Maven/Gradle resolver）。
- **单仓库粒度（已定）**：多库项目逐库建任务（一次发起可多选仓库 → 拆多个任务），
  不做单任务跨库聚合——fingerprint 与基线 diff 都依赖「仓库相对路径」稳定。

## 4. 插件化接口

- `ScanEngineAdapter`（本模块内 SPI）：引擎注册点，FR-02。
- 消费既有：`AgentNodeRouter`/`AgentNodeStepRunner`/`ExecutionLogHub`（执行链路）、
  `CloneTokenResolver`（repo 凭据）、CAP-32 附件 SPI、CAP-06 通知 SPI、
  CAP-33 场景装配（FR-08）。
- 对外预留（本 CAP 不实现，供后续门禁 CAP 消费）：`SecurityGateQuery` SPI 草约——
  「项目+仓库 最新 SUCCESS 任务的 NEW HIGH/CRITICAL 计数」一个只读方法，闸门模块经
  ObjectProvider 探测。

## 5. 数据模型

```
security_scans
  id, project_id, repo_id, branch, baseline_commit_sha VARCHAR(64),
  engines VARCHAR(64)（逗号分隔勾选集）, agent_node_id?,
  status VARCHAR(16), engine_status CLOB?（JSON：引擎→状态/耗时/计数/错误摘要）,
  new_count INT, existing_count INT, fixed_count INT（diff 摘要冗余，列表免聚合）,
  error_message VARCHAR(512)?, created_by, started_at?, finished_at?, created_at

security_findings（唯一键 uk(scan_id, fingerprint)）
  id, scan_id, project_id, repo_id（冗余便于跨任务查询）, engine VARCHAR(16),
  fingerprint VARCHAR(64), rule_id VARCHAR(128), severity VARCHAR(16),
  title VARCHAR(512), file_path VARCHAR(512)?, line_no INT?,
  code_hash VARCHAR(64)?, detail_json CLOB?（引擎原始字段：CVE/CVSS 向量/CWE/依赖坐标/CPE 证据/
    EPSS·KEV 富化/引入链 dep_path/remediation 分类等，密钥已脱敏）,
  dep_scope VARCHAR(16)?（SCA：compile/test/provided…，降权标记依据）,
  direct_dep BOOLEAN?（SCA：是否直接依赖，列表过滤用；初始值 null 语义=非 SCA，
    禁 @ColumnDefault，getter 兜底）,
  status VARCHAR(16)（NEW/EXISTING/SUPPRESSED/FIXED）,
  first_seen_scan_id, fixed_in_scan_id?,
  review_note CLOB?, reviewed_at?, review_session_id?,
  fix_session_id?, fix_diff_summary VARCHAR(512)?,
  created_at, updated_at

security_suppressions（唯一键 uk(project_id, repo_id, engine, fingerprint)）
  id, project_id, repo_id, engine VARCHAR(16), fingerprint VARCHAR(64),
  type VARCHAR(16), reason VARCHAR(512), created_by, expires_at?, created_at

security_cve_suppressions（唯一键 uk(group_id, artifact_id, cve, project_id)）
  id, group_id VARCHAR(256), artifact_id VARCHAR(256)（'*'=该 group 整组）,
  cve VARCHAR(32), project_id?（null=平台级全局生效）,
  type VARCHAR(16)（FALSE_POSITIVE/ACCEPTED_RISK，ACCEPTED_RISK 强制 expires_at）,
  reason VARCHAR(512), created_by, expires_at?, created_at

vuln_intel（EPSS/KEV 情报快照，服务端定时同步，CVE 主键 upsert）
  cve VARCHAR(32) PK, epss_score DOUBLE?, epss_percentile DOUBLE?,
  kev BOOLEAN（实体初始值 false，禁 @ColumnDefault）, kev_due_date VARCHAR(16)?, updated_at

附件（CAP-32）：每任务 N 条原始报告（meta 记 scanId+engine+format）+ SCA 依赖树输出。
```

红线：状态/严重度等字符串默认值若用 `@ColumnDefault` 必带引号；`detail_json`/`engine_status`/
`review_note` 用 `@Lob` 必带 `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`；Boolean 列禁 `@ColumnDefault`；
异步触发禁 @Transactional；H2 保留字避让（`version`/`commit` 等禁作列名，已用 `baseline_commit_sha`）。

## 6. API 概要

```
POST   /api/security/scans                       发起扫描（projectId/repoId/branch/engines，409 防重）
GET    /api/security/scans                       任务列表（projectId/status 筛选，分页）
GET    /api/security/scans/{id}                  任务详情（engine_status + diff 摘要 + 附件清单）
POST   /api/security/scans/{id}/cancel           取消（QUEUED/RUNNING）
GET    /api/security/scans/{id}/findings         发现列表（status/severity/engine/rule/file/dep_scope/
                                                 direct_dep/kev/epssMin 筛选，默认 status=NEW + 排除 test scope）
GET    /api/security/findings/{id}               发现详情（detail_json + 引入链 + 修复推荐 + 历史轨迹）
GET    /api/security/scans/{id}/export           导出 CSV / SARIF（attachment 下载）
POST   /api/security/suppressions                建抑制（findingId 带出 fingerprint，理由必填）
DELETE /api/security/suppressions/{id}           解除抑制
GET    /api/security/suppressions                抑制列表（projectId 筛选）
POST   /api/security/cve-suppressions            建 CVE 抑制（构件×CVE，项目级/平台级，理由必填）
DELETE /api/security/cve-suppressions/{id}       解除 CVE 抑制
GET    /api/security/cve-suppressions            CVE 抑制列表（projectId/cve 筛选）
POST   /api/security/findings/{id}/review        AI 复核（异步起会话，202）
POST   /api/security/findings/{id}/fix           AI 修复（异步起会话改依赖并验证构建，202）
POST   /api/agent-nodes/{id}/scan-toolcheck      节点工具自检（ADMIN，按标签逐引擎 --version）
```

权限：发起/抑制/解除/复核 = 项目成员（CAP-62 判定）；导出与列表/详情 = 项目可见即可读；
原始 SARIF/HTML 附件下载 = ADMIN（密钥引擎原文不脱敏）；工具自检 = ADMIN。

## 7. 验收标准

- 三引擎在配置好标签与路径的节点上各跑通一次：原始报告落附件、发现归一化入库、
  严重度映射正确、密钥命中脱敏（库与 API 出参均无原文）；
- 同一仓库连续两次扫描：第二次 diff 出 EXISTING/FIXED/NEW 三类计数与明细正确；
  抑制一条后第三次扫描该 fingerprint 落 SUPPRESSED 且不进默认视图；
- 单引擎失败（路径配错）→ 任务部分成功，该引擎错误摘要可见，其余引擎正常；
- 节点标签不命中 → 创建 409 且提示缺哪个引擎标签；工具自检回显版本号；
- 高危 NEW 触发一条任务级通知，深链可达；
- AI 复核产出回传落 finding 复核记录；
- 误报治理四层各自生效：白名单 groupId 命中的构件不入库且计数可见；test scope 发现
  默认不进列表；CVE 抑制库裁定后同 CVE 在新扫描中不再出现（过期后复现）；EPSS/KEV
  富化字段随归一化落库且列表可按其排序；
- 修复指导：引入链正确指出传递依赖顶端的直接依赖；推荐版本附 NVD 区间原文且推导不出时
  明示不编造；AI 修复会话改动经编译验证并回传登记，平台未自动合入任何代码；
- E2E（脚本随 git，产物进 tmp/）：mock 引擎脚本（产出固定 JSON/SARIF 样例）走
  「发起→exec→归一化→diff→抑制→导出」全链；密钥样例断言脱敏；SCA 样例断言
  白名单过滤/scope 降权/CVE 抑制复现/引入链解析四类断言。

## 8. 非目标

扫描门禁/执行链挂钩（另立 CAP，消费 SecurityGateQuery）；定时扫描与 CI webhook 触发；
引擎与漏洞库的平台化分发（走 CAP-57 另立 CAP）；镜像/IaC/许可合规引擎（加适配器即可，
不在 MVP）；DAST/运行时扫描；**可达性分析**（call-graph 级「漏洞代码路径是否真实可达」
判定，依赖语言级调用图工具链，另立 CAP 评估）；服务端依赖求解/自动提修复 MR
（AI 修复会话产出永远人工确认合入）；跨项目安全大盘（组装层首页能力，另立 CAP）；
引擎原生 suppression file 与平台抑制的双向同步。
