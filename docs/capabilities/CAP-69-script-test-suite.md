# CAP-69 脚本测试套件（项目内 UI E2E 套件接入测试中心）

> 能力 ID：CAP-69 ｜ 分类：执行器 ｜ 状态：已定稿 ｜ 日期：2026-10-09

## 1. 目的

CAP-10 测试执行器只有两类套件：`api`（OpenAPI 生成的 HTTP 用例，服务端自发请求）与
`smoke`（health 用例，command 型经 exec 帧下发节点）。**UI E2E（Playwright）这类
「整包脚本在节点上跑、产出 JUnit 报告」的套件没有落点**——CAP-10 §8 明确把 UI E2E 列为
暂不做。ADMQ Manager 的 `e2e/`（Playwright，12 个套件，serial 单 worker，跨套件
`.state/` 共享状态）是第一个真实需求方。

本能力新增套件 kind=`script`：**强制绑定项目的脚本测试套件**——套件自带 git 源与执行命令，
经 exec 帧下发 runner 节点跑，JUnit XML 结果回收解析成 test_case_results，测试中心
现有的运行流（WS 实时日志/报告/失败转缺陷线索）零改动复用。入口 = 项目「测试」页
新建套件选 script 类型；run 归属项目，进项目运行历史，报告沉淀 CAP-03 文档。
（初版曾设计为独立于项目的顶层页 /script-tests，评审后改为强制绑定——没有不绑项目的
真实场景，绑定后历史/报告/权限全部白拿。）

与 api/smoke 套件的差异（为什么不复用现有 case 模型）：脚本套件的用例由节点上的测试
框架（Playwright/pytest/go test）自己发现与执行，服务端不编排单个用例，只在事后把
JUnit XML 解析为用例结果行。

## 2. 功能需求

### 后端（devmind-test）

- **FR-01 脚本套件 CRUD（强制绑定项目）**：kind 增加 `script`；`projectId` 服务层必填
  （缺失 400、幽灵项目 404；DB 列保持可空——ddl-auto 不改存量约束，约束在服务层收口）。
  脚本套件新增字段（均可空列平滑加列）：
  `repo_url`/`branch`（git 源，必填）、`work_subdir`（仓库内子目录，如 `e2e`，可空=仓库根）、
  `command`（执行命令模板，多行脚本串，必填）、`junit_path`（JUnit XML 产出路径，相对
  workSubdir，必填）、`env_json`（`[{key,value,secret}]`，secret=true 的值在视图层掩码
  `******`，掩码原样回传=不变）、`agent_node_id`（套件默认节点，可空）、
  `timeout_sec`（默认 7200——Playwright 套件远超全局 30min 默认）、
  `workspace_key`（默认套件 id；有 `.state` 跨套件依赖的套件族（如 cluster-deploy →
  cluster-scaling）配同一个 key 共享 runner 工作区）。
  端点：`GET /api/script-suites?projectId=`（必填）、`POST /api/script-suites`、
  `GET/PUT/DELETE /api/script-suites/{id}`；变更类限 ADMIN（SecurityConfig URL 模式，
  同 AgentNodeController 口径），GET 登录即可。
- **FR-02 触发运行**：`POST /api/script-suites/{id}/run`，body `{agentNodeId?, env?,
  command?}` 全可选（env/命令覆盖仅本次生效，不落库）。路由链：显式 agentNodeId →
  套件默认节点 → **项目默认节点** → 平台默认 → 皆无 409（AgentNodeRouter +
  requireExecCapable 既有口径）。创建 test_run（projectId=套件项目、triggeredBy="user"）
  后 virtual thread 异步执行，与既有 run 同一状态机（RUNNING→SUCCESS/FAILED）。
  **防护**：`/api/tests/runs`（createInternal）拒收 script 套件（400——误混入会空跑
  SUCCESS 假象）；部署自动回归（CAP-10 FR-05）过滤 kind=script。
- **FR-03 节点执行**：经 `AgentNodeStepRunner.runStep` 下发 exec 帧（协议 v3+）：
  repo 块 = `Repo(repoUrl, branch, null, token=null)`——runner 克隆缓存 + worktree
  按 workspaceId=`stsuite-<workspaceKey ?: id>` 复用（CAP-36 既有语义，套件内 `.state/`
  因此跨运行保留）；workingDir=workSubdir（runner 相对 workspace 解析，自带 `..` 逃逸
  防护）；env = 套件 env（解掩码原值）+ 触发覆盖。workspaceId 用伪 projectId
  `script-tests` 过 RunnerWorkspace SAFE_ID（刻意与项目解耦，workspaceKey 跨项目可共享）。
  **命令包装**：服务端把套件命令渲染为
  `{ <command>; ec=$?; p=$(gzip -c <junitPath> 2>/dev/null | base64 -w0); echo "DEVMIND_JUNIT $p"; exit $ec; }`
  ——无论成败都尝试回收 JUnit，退出码原样保留（包装尾段贴在套件命令末行同行：runner
  execAllowlist 逐行校验首词，独立行的 `ec=$?` 会被拒，`{`/`}`/echo/exit 属内置豁免）。
- **FR-04 JUnit 回收与解析**：exec 链路无文件上行通道，沿用 CAP-56 `LabMarkers` 的
  marker 先例（stdout 单行 `DEVMIND_JUNIT <base64(gzip(xml))>`，gzip 防日志撑大、base64
  防换行撕裂、Tap 分流器把 marker 行从人读日志剔除）。服务端解析 JUnit XML
  （testsuite(s)/testcase 的 classname/name/time/failure/error/skipped；XML 解析禁
  DOCTYPE/外部实体）批量落 test_case_results（caseId=null、name=classname#name 快照、
  status=pass/fail/skip、error=failure/error 文本、duration=time 秒转毫秒），逐条
  `hub.publishEvent` 推 WS result 帧（既有帧格式，前端零改动）。
  收口：exit≠0 或 fail>0 → FAILED，否则 SUCCESS；**JUnit 缺失不炸**——results 为空 +
  errorSummary 注记「未回传 JUnit 报告」（空载荷 marker 与载荷打坏区分注记）；summary_json 正常聚合。
- **FR-05 复用运行资产**：`/ws/test-runs/**` WS 流、`GET /api/test-runs/{id}/report`、
  `POST /api/test-runs/{id}/issues`（失败转缺陷草稿）对脚本套件 run 直接可用；
  run 归属项目 → 进项目运行历史（`GET /api/test-runs?projectId=`），报告沉淀
  CAP-03 文档（reportDocId 非空）。

### 前端（features/test）

- **FR-06 项目「测试」页集成**（/tests，ProjectContextGate 内；2026-10-09 交互统一后口径）：
  三种套件类型（smoke/api/script）交互一致——新建走统一抽屉 **SuiteFormDrawer**
  （类型选择在表单内：smoke/api 填名称、openapi 由项目 apiDocSource 生成（名称服务端定，
  即原「从 OpenAPI 生成」入口收编）、选 script 展开 git 源、命令多行、junitPath、env 键值
  编辑器带 secret 开关与掩码回显、默认节点、超时、workspaceKey，字段与编辑页共用
  ScriptSuiteFields）；套件表格所有行操作统一 = 运行/编辑/删除，运行走统一
  **RunSuiteModal** 按类型渲染字段（script = 节点可空=套件默认→项目默认→平台默认、
  env/命令覆盖仅本次生效；api/smoke = 目标环境/执行节点/baseUrl），编辑统一跳内层页
  `/tests/suites/:id`（SuiteDetailPage 按 kind 分支：api/smoke 用例编排，script 脚本
  属性表单 updateScriptSuite；extra 统一带「运行」按钮，同一 RunSuiteModal）；顶部
  「新建运行」保留为多套件批量入口，排除 script 套件（误收会 400）。script run 自动
  出现在运行历史，详情复用 `RunDetailDrawer`（WS 流/报告/缺陷线索零改动）。
- **FR-07 类型与常量**：`TestSuiteKind` 加 `'script'`，SUITE_KIND_COLOR 补色；
  api.ts `listScriptSuites(projectId)` 必带项目参数。

## 3. 插件化接口

- 执行通道：复用 CAP-36 `AgentNodeStepRunner`（exec 帧 + repo 块 + env + 超时 + 审计），
  无新 SPI。
- 结果回传：复制 `LabMarkers` 精简版进 devmind-test（`ScriptMarkers`，marker 名
  `DEVMIND_JUNIT`，Tap 分流 + 解码 + 日志截断语义照抄）——LabMarkers 本体与
  decision-lab 的 Python 契约绑定，不上移 common、不改它。
- JUnit 解析：`JUnitXmlParser` 工具类（devmind-test 内），JDK DocumentBuilderFactory
  + XXE 加固；只依赖 JUnit 事实标准 schema，不限定 Playwright。

## 4. 协议与兼容性

| 组合 | 行为 |
|---|---|
| 脚本套件 → runner 协议 ≥v3 | 全功能（exec 帧 + repo 块） |
| 脚本套件 → runner 协议 <v3 | requireExecCapable 409，提示升级（既有口径） |
| 节点离线/无可用节点 | 触发即 409，不静默起失败运行 |
| 套件命令失败（exit≠0） | run FAILED，JUnit 能回则照解析（失败明细进 results） |
| JUnit 缺失/损坏 | run 按退出码定生死，results 空 + errorSummary 注记 |

## 5. 数据模型

```
test_suites：
  project_id   script 套件服务层强制非空（DB 列保持可空：ddl-auto 不改存量约束，
               api/smoke 行本就非空，约束收口在服务层 requireProject + 必填校验）
  kind         增加取值 'script'
  新增列（均可空，ddl-auto 平滑加列）：
    repo_url      VARCHAR(512) NULL
    branch        VARCHAR(128) NULL
    work_subdir   VARCHAR(256) NULL
    command       TEXT NULL        -- @JdbcTypeCode(LONGVARCHAR) + length 16_777_216
    junit_path    VARCHAR(256) NULL
    env_json      TEXT NULL        -- [{key,value,secret}]，同上 TEXT 口径
    agent_node_id VARCHAR(64) NULL
    timeout_sec   INT NULL         -- null=7200
    workspace_key VARCHAR(128) NULL

test_runs：
  project_id   脚本套件 run = 套件归属项目（强制绑定后恒非空）

test_case_results：
  case_id      脚本套件结果行 case_id=NULL（name 为 classname#name 快照）
```

## 6. API 概要

```
GET    /api/script-suites?projectId=       项目脚本套件列表（projectId 必填，登录）
POST   /api/script-suites                  新建（ADMIN；projectId 必填，幽灵项目 404）
GET    /api/script-suites/{id}             详情（env secret 值掩码）
PUT    /api/script-suites/{id}             更新（ADMIN；掩码原样回传=该 env 值不变）
DELETE /api/script-suites/{id}             删除（ADMIN；历史 run/results 保留）
POST   /api/script-suites/{id}/run         触发 {agentNodeId?, env?, command?}（登录）
（运行历史走项目口径 GET /api/test-runs?projectId=；WS /ws/test-runs/{id}/stream、
  report、issues、logs 端点不变）
```

## 7. 依赖关系

- 依赖：CAP-10（套件/运行/报告/缺陷模型与 WS 流）、CAP-36（AgentNodeStepRunner + repo
  块 + AgentNodeRouter）、CAP-56（LabMarkers marker 回传先例）、CAP-21（节点协议版本门）、
  CAP-02（项目归属与项目默认节点）。
- 被依赖：后续任何「整包脚本型测试」（pytest/go test/自定义）复用同一套件形态；
  脚本套件显式不进 CAP-10 FR-05 部署自动回归（无环境/baseUrl 语义），挂流程属后续能力。

## 8. 验收标准

- 脚本套件 CRUD：字段齐备、env secret 掩码与「掩码回传=不变」语义正确；
  projectId 缺失 400、幽灵项目 404、列表不带 projectId 400；
- file:// fixture 仓库 + 真 runner 全链：触发 → WS 流实时日志 → 终态 SUCCESS，
  results 逐条（pass/fail/skip 混合）断言 name/status/duration，summary 聚合正确；
  run.projectId 归属套件项目、报告沉淀 CAP-03 文档（reportDocId 非空）；
- 路由链：显式节点 → 套件默认 → 项目默认 → 平台默认逐层命中，离线节点 409；
- env 触发覆盖生效（覆盖后 fixture 脚本改判失败 → run FAILED）；失败 run 的 issues
  草稿端点返回失败用例行；
- JUnit 缺失：run 按退出码收口、results 空、errorSummary 有注记，不抛异常；
- runner 协议 <v3 触发 409；
- 同 workspaceKey 两套件串跑，第二套件读到第一套件在工作区留的文件（工作区复用成立）；
- /tests/runs 收 script 套件 400（误混入防护）；部署自动回归不扫 script 套件；
- ADMQ 真实场景（配套改动后）：build-224 节点跑 `npm run test:rabbitmq`，JUnit 进
  test_case_results，markdown 报告位置在日志可见。

## 9. 已知边界与暂不做

- **clone 认证**：v1 repo token 恒空，依赖节点 git 凭据（内网匿名读/节点
  credential helper）；需要认证私库时再加套件级 token 字段。
- **工作区 GC**：runner `buildGcHours` 默认 24h，`.state` 跨天保留需调大节点配置。
- **execAllowlist**：节点 `agent.properties` 需放行套件命令首词（npm/npx/git/cat 等），
  空名单=拒所有 exec（runner 既有语义）。
- 暂不做：截图/trace 等二进制产物回传（CAP-65 文件帧或 HTTP 中转是候选通道）；
  用例级趋势对比；脚本套件进 CAP-10 FR-05 部署自动回归；取消运行（exec 无 cancel 帧）。
- admq 仓库侧配套（另一仓库单独提交）：playwright.config.js 加 JUnit reporter、
  节点 Node 20+ 与 chromium 依赖。
- **存量库注记（历史）**：独立套件初版曾要求存量库手工 ALTER 放开
  `test_suites/project_id`、`test_runs/project_id` 的 NOT NULL（ddl-auto 不改存量
  约束）；改为强制绑定项目后 script 套件与 run 恒有 project_id，**该 ALTER 不再必要**
  ——已执行过的库无需回退（列可空无影响，约束在服务层收口）。
