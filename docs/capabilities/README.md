# Dev-Mind 基础能力清单（积木式组装）

> 版本：v0.1 ｜ 日期：2026-08-30
>
> 本文档定义平台的第一批**基础能力**（Building Blocks）。每个能力是独立可用的插件化模块，对外暴露稳定接口，后续由**流程层**（需求流程引擎、任务编排 Orchestrator 等，另行设计）像积木一样组装成全自动流水线。

## 通用技术基线

- **单体仓库，前后端一体**：一个项目 `dev-mind/`。后端 Spring Boot 4.1.1（REST + WebSocket + 静态托管前端构建产物；公司 Nexus 无 4.2.x 正式版，4.2 正式版上线后可升级）；前端 React + Ant Design 5（Vite 构建后由后端托管，浏览器访问）。
- **本地优先**：跑在个人开发机（Windows），单用户起步，为多人协作预留扩展。
- **结构化存储**：H2 文件模式（后续可平滑切 PostgreSQL），存流程/会话/事件等结构化数据。
- **文档与代码分离**：文档库 `docs-repo/` 与经验库 `knowledge-repo/` 均为独立 git 仓库，平台不存代码，只引用项目 git 仓库。
- **Agent 执行**：headless Claude Code（`claude -p` / Agent SDK），由会话管理能力拉起本地子进程。
- **插件化原则**：每个能力通过「SPI 接口 + 具体实现」注册，配置可切换；能力之间只依赖接口，不依赖实现。

## 能力清单

| ID | 能力 | 分类 | 一句话职责 |
|---|---|---|---|
| [CAP-01](CAP-01-auth.md) | 用户认证与权限 | 管理 | 登录鉴权与角色，所有操作的 actor 基础 |
| [CAP-02](CAP-02-project-management.md) | 项目管理 | 管理 | 注册项目与服务器，其余能力的挂载点 |
| [CAP-03](CAP-03-document-management.md) | 文档管理 | 管理 | git 版本化文档库，需求/方案/报告跟需求走 |
| [CAP-04](CAP-04-knowledge-base.md) | 知识库管理 | 管理 | 经验分层（global/projects/inbox）+ 注入 + 捕获 |
| [CAP-05](CAP-05-agent-session.md) | Agent 会话管理 | 底座 | 起/管/收 headless agent 会话，worktree 隔离，看板 |
| [CAP-06](CAP-06-notification.md) | 通知中心 | 底座 | 事件分级路由，多通道推送，远程快捷动作 |
| [CAP-07](CAP-07-server-adapter.md) | 服务器适配器 | 底座 | SSH/HTTP 插件化服务器控制，命令白名单 |
| [CAP-08](CAP-08-build-executor.md) | 构建执行器 | 执行器 | 按项目脚本步骤构建，本机/远程可配 |
| [CAP-09](CAP-09-deploy-executor.md) | 部署执行器 | 执行器 | 部署计划执行 + 失败自动回滚 |
| [CAP-10](CAP-10-test-executor.md) | 测试执行器 | 执行器 | 冒烟 + API 测试，套件沉淀自动回归 |
| [CAP-11](CAP-11-release-executor.md) | 发版执行器 | 执行器 | Nexus 脚本模板推送 + 打 tag |
| [CAP-12](CAP-12-execution-platform.md) | 统一执行底座 | 底座 | 步骤链引擎 + 本地/远程 Runner + 统一 WS 日志枢纽 |
| [CAP-13](CAP-13-requirement-workitem.md) | 研发主线 | 管理 | Requirement/Design/Work Item 模型 + Relation 追溯网 |
| [CAP-14](CAP-14-requirement-flow.md) | 需求流程引擎 | 流程层 | 需求主流程半自动推进：阶段动作 + 产出登记 + 人工确认门禁 |
| [CAP-15](CAP-15-orchestrator.md) | 自动编排器 | 流程层 | WI DONE 触发 depends_on 依赖就绪自动派发会话 |
| [CAP-16](CAP-16-command-center.md) | 指挥中心 | 组装层 | 全局聚合首页：状态分布/活跃会话/待办确认/最近失败 |
| [CAP-17](CAP-17-pipeline-orchestrator.md) | 执行链编排 | 流程层 | WI DONE → 自动构建 → 测试环境自动部署；生产/发版人工门禁 |
| [CAP-18](CAP-18-platform-integration.md) | 第三方平台集成 | 底座 | 外部平台连接器 SPI（GitLab 先行）：push 分支/tag、建 MR/Release、External Link 追溯 |
| [CAP-19](CAP-19-jira-issue-sync.md) | Jira 任务/Bug 同步 | 底座 | Jira Server/DC 轮询拉取（JQL+水印增量）→ DRAFT 需求，单向只拉取，人工确认后进自动开发 |
| [CAP-21](CAP-21-agent-node.md) | 远程 Agent 节点管理 | 底座 | Windows 节点 runner 反向 WS 连服务端，远程拉起/交互 claude 会话，事件解析下沉 runner |
| [CAP-22](CAP-22-github-integration.md) | GitHub 集成 | 底座 | CAP-18 连接器第二个 git 平台实现：PR/Release，github.com 与 GHE（/api/v3）分流 |
| [CAP-23](CAP-23-repo-clone.md) | 项目仓库从 Git 克隆 | 底座 | 项目/多库支持 GitLab/GitHub 远端地址异步克隆到工作区（按项目分目录），PAT 注入+状态机+实时日志 |
| [CAP-24](CAP-24-user-git-identity.md) | 用户级 Git 身份与凭证 | 底座 | 用户自助维护各 git 平台 PAT 与署名，Agent 提交按会话发起人署名（env 注入），push 个人凭证优先（**已由 CAP-35 演进取代**） |
| [CAP-25](CAP-25-runner-workspace.md) | 远程会话工作区编排 | 底座 | launch 帧下发 repo+短期凭据，runner 自动 clone/fetch/会话 worktree/结束 push，节点零手工配码 |
| [CAP-26](CAP-26-exec-fetch.md) | 执行前代码同步 | 底座 | 构建/发版/worktree 基准一律取 origin/ 远端引用（执行前 fetch），服务端 clone 不再失鲜 |
| [CAP-27](CAP-27-requirement-effort.md) | 需求工时 | 组装层 | AI 实际耗时会话时长自动汇总 + Jira 预估/已用工时同步展示 + worklog 一键回写 |
| [CAP-28](CAP-28-personal-worklog.md) | 个人工作日志与工时管理 | 组装层 | git log + 手动补录成工作条目记工时，AI 定时生成日报/周报草稿人工确认，Jira 一键登记 |
| [CAP-29](CAP-29-global-git-repo-registry.md) | 全局代码仓库登记 | 平台层 | 仓库提升为平台级资源：后台统一登记（仅 ADMIN）+ 服务端克隆 + 定时/手动 fetch 同步分支，项目仓库改为关联、CAP-28 订阅扫描切到服务端克隆 |
| [CAP-30](CAP-30-agent-chat.md) | 通用问答 | 底座 | 无项目无仓库的纯 AI 问答独立成能力（个人组入口），共享内核上移 common，干净沙箱 cwd |
| [CAP-31](CAP-31-session-multirepo.md) | 项目会话多仓库与归属拆分 | 底座 | 会话收敛为纯项目开发会话（当前项目组）+ 显式关联多 git 仓库（聚合目录/逐库 push/diff），远程 diff 走服务端克隆 |
| [CAP-32](CAP-32-attachment.md) | 公共附件管理 | 底座 | 统一附件模型：二进制上传即附件、唯一字符串 id 对外引用，图片内联渲染（图床）/非图片下载，chat 图片下发 claude、docs 粘贴插图 |
| [CAP-33](CAP-33-scenario-session-context.md) | 场景化会话与上下文装配 | 底座 | 场景 = 命名模板 + 预装配上下文包（skills/docs/knowledge 绑定到意图），session/chat 统一装配管线一键带齐上下文 |
| [CAP-34](CAP-34-agent-runner-executor.md) | Agent Runner 执行代理化 | 底座 | 取消本机会话：服务端零执行纯分发调度，一切执行收敛 runner 层（执行内核上移 common）；上下文包传输、会话隔离强化、exec 帧利用节点本地工具链 |
| [CAP-35](CAP-35-unified-platform-accounts.md) | 统一第三方账号体系 | 底座 | Integration 收敛为实例登记+可选机器人凭证；用户账号统一为绑定实例的 UserPlatformAccount（git PAT+署名 / Jira PAT·BASIC）；MR·PR 创建、Jira transition、worklog 回写全部切个人优先身份链（取代 CAP-24） |
| [CAP-36](CAP-36-build-on-runner.md) | 构建执行 Runner 化 | 底座 | exec 帧落地：构建/测试/部署/发版统一切 agent 节点执行，GitLab 凭证随帧下发复用集成体系，SSH/HTTP server-adapter 整体下线（取代 CAP-07） |
| [CAP-37](CAP-37-flow-output-pipeline.md) | 流程产出回传与阶段串联 | 流程层 | runner 退出前 HTTP 回传 .devmind/output 产出落 session_outputs，分析落成 docs 文档，方案/拆分会话注入上游产出，需求详情页流程 Tab 串联四阶段 |

## 依赖关系

```
CAP-01 认证  ─┬─ CAP-02 项目 ─┬─ CAP-03 文档
              │               ├─ CAP-04 知识库 ─▶ CAP-05 会话
              │               ├─ CAP-07 服务器适配器 ─┬─ CAP-08 构建
              │               │                       ├─ CAP-09 部署 ─▶ CAP-10 测试
              │               │                       └─ CAP-11 发版 ─▶ CAP-18 平台集成
              │               └─ CAP-18 平台集成（GitLab 先行：push 分支/tag、MR/Release）
              ├─ CAP-06 通知（被所有"等待人"的场景依赖）
              ├─ CAP-14 流程层（消费 CAP-03/05/06/13，推进需求主流程）
              │               ├─ CAP-15 编排器（消费 workitem.status.changed，自动派发）
              │               └─ CAP-17 执行链（消费 build/deploy 事件，WI DONE→构建→部署）
              └─ CAP-16 指挥中心（只读聚合各能力仓库，组装层首页）
```

- CAP-01 无依赖，被全部能力依赖；
- CAP-02 被文档/知识库/会话/服务器/执行器依赖；
- CAP-13 研发主线定义 Requirement/Design/Work Item/Relation 模型，被会话与各执行器关联（workItemId）；
- CAP-05 依赖 CAP-04（知识注入）与 CAP-06（等待输入/授权通知）；
- CAP-08/09/10/11 依赖 CAP-07（远程执行时）与 CAP-02；执行层共用 CAP-12 统一执行底座（步骤链引擎/Runner/WS 日志枢纽）。
- CAP-14 流程层依赖 CAP-03/05/06/13：消费 `session.completed` 事件推进需求主流程，后续 CAP-15 自动编排器复用其阶段动作。
- CAP-15 编排器在 CAP-14 之上：订阅 CAP-13 发布的 `workitem.status.changed` 事件，depends_on 依赖就绪自动派发会话。
- CAP-16 指挥中心只读依赖各能力仓库（findAll 内存聚合），不改任何被聚合模块。
- CAP-17 执行链编排依赖 CAP-08/09/13：WI DONE 自动构建、build.completed 自动部署 TEST 环境；生产部署与发版保持人工（确认门通知动作远程可确认）。
- CAP-18 平台集成依赖 CAP-01/02/13：出站单向为主——WI 分支 push、建 MR、发版后 push tag + 建平台 Release（CAP-11 可选钩子）；入站 webhook 属后续阶段（Jira 已由 CAP-19 落地、GitHub 已由 CAP-22 落地）。
- CAP-19 Jira 同步依赖 CAP-18（Integration/凭据/SPI/审计/External Link）与 CAP-13（Requirement 落点）：轮询拉取 Jira issue → DRAFT 需求（单向只拉取不回写），人工确认后走 CAP-14/15 自动开发链。
- CAP-21 远程 Agent 节点依赖 CAP-01/05：节点 runner 反向 WS 注册，远程会话复用 CAP-05 会话模型/状态机/事件流（`sessions.agent_node_id` 区分本地/远程），事件解析下沉 runner（复用 CliProcessLauncher/CliEventParser）。
- CAP-22 GitHub 集成依赖 CAP-18（SPI/服务层/凭据/GitRemoteOps/External Link 全部复用，零表结构变更）：仅新增 GitHubConnector，覆盖 github.com 与 GHE。
- CAP-23 仓库克隆依赖 CAP-02/12/18：project 创建 CLONE 行发领域事件，integration 监听后异步克隆（PAT 注入 + ExecutionLogHub 实时日志），按项目分目录落 `data/repositories/<projectId>/`。
- CAP-24 用户 Git 身份依赖 CAP-01/05/18：用户级 PAT 与署名存 `user_git_credentials`（复用 IntegrationCipher 加密），会话拉起时经 env 注入提交身份，WI 分支 push 个人凭证优先于项目 Integration。
- CAP-25 远程工作区依赖 CAP-21/23/24：launch 帧携带 repo 块（remoteUrl/分支/token，CAP-24 优先级解析），runner 侧 clone 缓存 + 每会话 worktree + 结束 push，节点机零手工维护代码。
- CAP-26 执行前同步依赖 CAP-08/11/18/23：构建/发版前 fetch 并以 origin/ 远端引用为基准，与 CAP-25 闭环「节点开发 → 远端 → 服务端执行」。
- CAP-27 需求工时依赖 CAP-05/13/19：会话时长经 RequirementAgentTimeLookup 端口（project 定义、session 实现）汇总为 AI 实际耗时；Jira time tracking 字段走 CAP-19 同步链路落托管列，worklog 回写复用其 FR-08 通道。
- CAP-29 全局仓库依赖 CAP-18/23/26：登记表归 devmind-project（git_repositories 扩列），克隆/定时 fetch 归 devmind-integration（复用 GitRemoteOps + Integration 凭证），项目仓库经 git_repo_id 关联且克隆状态由全局行镜像，CAP-28 经 common 的 GitRepoCatalog SPI 读注册表。
- CAP-28 个人工时依赖 CAP-01/05/06/24：全局仓库登记 + 用户勾选，git log 按 CAP-24 署名过滤成工作条目，日报/周报经 OneShotAgentRunner 端口（common 定义、session 实现）跑 headless claude 生成草稿，事件走 CAP-06 通知；FR-07 Jira 一键操作另依赖 CAP-19/27。
- CAP-30 通用问答依赖 CAP-01/06/21：headless 会话内核（状态机/事件流/CLI 协议）上移至 devmind-common 被 session/chat 共享；问答独立 chat_sessions/chat_events 表与个人组入口，无项目资产耦合。
- CAP-31 会话多仓库依赖 CAP-05/21/24/25/29：会话收敛为当前项目开发会话，session_repos 快照表关联多库（聚合目录工作区），launch 帧 repos 数组逐库下发凭据，远程 diff 经服务端克隆 fetch 会话分支后比对。
- CAP-32 公共附件管理依赖 CAP-01/21/30：统一附件表 + 本地磁盘存储 + 唯一字符串 id 引用，scope 归属可见性；chat 图片消息经 AttachmentContentResolver SPI（common 定义）解析下发 claude，远程节点 input 帧内嵌 base64 送达 runner；docs 编辑器粘贴插图。
- CAP-33 场景化会话依赖 CAP-03/04/05/30/34 与 Skill 管理：场景实体绑定 skills/docs/knowledgeTags 产出 ContextPackage（装什么），传输与物化由 CAP-34 承担；session/chat 创建链路统一走装配管线，session_templates 迁入场景。
- CAP-34 runner 执行代理化依赖 CAP-21/25/30/31/12：取消本机会话，服务端不再拉起任何执行子进程，会话必有执行节点（皆无命中 409 不回落）；执行内核（工作区/上下文物化/进程拉起）上移 devmind-common 仅供 runner 使用；launch 帧 contextManifest + HTTP 拉包补掉远程注入缺口；exec 帧 + 工具链标签让 build/test 可在 agent 节点本地执行。
- CAP-35 统一第三方账号依赖 CAP-01/18/19/22/27：取代 CAP-24——Integration 收敛为实例登记 + 可选机器人凭证（自动化专用），用户个人账号绑定实例（user_platform_accounts），人触发的写操作（push/MR·PR/Jira transition/worklog）统一「个人 → 机器人 → 报错引导」身份链，Connector SPI 零变更。
- CAP-36 构建执行 Runner 化依赖 CAP-21/34/25/12/18/35：取代 CAP-07——exec 帧（CAP-34 FR-06 预留协议）落地，构建/测试/部署/发版远程执行统一切 agent 节点；repo 块照搬 launch 帧 RepoSpec 语义（CloneTokenResolver 解析 token 随帧下发、runner 内存持有不落盘），标签调度选构建机；SSH/HTTP server-adapter 模块整体下线，远程执行只余「服务端 → runner」一条路径。
- CAP-37 流程产出回传依赖 CAP-03/13/14/34：runner 退出前经 HTTP 旁路把 `.devmind/output/` 产出回传落 `session_outputs`（SessionOutputSink SPI），修复 CAP-34 后流程引擎读不到产出的断链；分析产出文档化（docs kind=analysis），方案/拆分会话 spec 注入上游产出，需求详情页新增流程 Tab 串联四阶段。

## 组装方式（后续流程层）

流程层（如「需求 → 方案 → 开发 → 构建 → 部署 → 验证 → 发版」）将通过编排这些能力实现：

- **需求流程引擎**：需求状态机 + 每阶段"产出就绪 → 等确认"语义，调用各执行器；
- **任务编排 Orchestrator**：方案（Design）→ Work Item DAG（CAP-13 depends_on 边）→ 并发调度 CAP-05 会话，门禁把关；
- **需求对话打磨**：AI 对话面板 + CAP-03 文档生成。

这些属流程层设计，不在此批基础能力文档内，待基础能力稳定后另行编写。

## 文档模板说明

每份能力文档统一结构：目的 / 功能需求（FR-xx 编号）/ 插件化接口 / 依赖关系 / 数据模型 / API 概要 / 验收标准 / MVP 范围。
