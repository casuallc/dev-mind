# Dev-Mind 基础能力清单（积木式组装）

> 版本：v0.1 ｜ 日期：2026-08-30
>
> 本文档定义平台的第一批**基础能力**（Building Blocks）。每个能力是独立可用的插件化模块，对外暴露稳定接口，后续由**流程层**（需求流程引擎、任务编排 Orchestrator 等，另行设计）像积木一样组装成全自动流水线。

## 通用技术基线

- **单体仓库，前后端一体**：一个项目 `dev-mind/`。后端 Spring Boot 4.1.1（REST + WebSocket + 静态托管前端构建产物；公司 Nexus 无 4.2.x 正式版，4.2 正式版上线后可升级）；前端 React + Ant Design 5（Vite 构建后由后端托管，浏览器访问）。
- **本地优先**：跑在个人开发机（Windows），单用户起步，为多人协作预留扩展。
- **结构化存储**：H2 文件模式（后续可平滑切 PostgreSQL），存流程/会话/事件等结构化数据。
- **文档与代码分离**：文档库（CAP-03）与知识库（CAP-04）内容均存 DB，平台不存代码，只引用项目 git 仓库。
- **Agent 执行**：headless Claude Code（`claude -p` / Agent SDK），由会话管理能力拉起本地子进程。
- **插件化原则**：每个能力通过「SPI 接口 + 具体实现」注册，配置可切换；能力之间只依赖接口，不依赖实现。

## 能力清单

| ID | 能力 | 分类 | 一句话职责 |
|---|---|---|---|
| [CAP-01](CAP-01-auth.md) | 用户认证与权限 | 管理 | 登录鉴权与角色，所有操作的 actor 基础 |
| [CAP-02](CAP-02-project-management.md) | 项目管理 | 管理 | 注册项目与服务器，其余能力的挂载点 |
| [CAP-03](CAP-03-document-management.md) | 文档管理 | 管理 | 版本化文档库（DB），需求/方案/报告跟需求走 |
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
| [CAP-38](CAP-38-requirement-flow-simplify.md) | 需求流程简化：阶段 Tab 与自动拆分固化 | 流程层 | 分析/方案独立 Tab 且可跳过（不可逆引导），方案产出后自动拆分+自动固化 WI，WI 状态中文+跳会话，会话关联需求自动建 WI（调整 CAP-37 FR-04、取代 CAP-14 FR-06/07 草稿人工确认） |
| [CAP-39](CAP-39-session-output-publish.md) | 会话产出手动推送与按需回传 | 流程层 | collect_output 帧（协议 v4）进行中会话即时回传产出，outputs 读取/同步端点，手动推送为关联需求文档（新建/更新版本，design 落 Design 记录），会话详情页裁撤并入工作台 |
| [CAP-40](CAP-40-requirement-attachment-context.md) | 需求附件上下文投送 | 流程层 | 需求描述引用的本地附件 + Jira 内嵌图打进 ContextPackage（schema v2 inputs），runner 物化 .devmind/input/，流程会话 agent 用 Read 读图 |
| [CAP-41](CAP-41-worklog-workspace.md) | 工作日志空间 | 组装层 | 工作日志重构为 WORKLOG 特殊项目 + runner 按用户隔离的持久 git 工作区（{user.home}/worklog/<user>），skill/格式模板管控台可配，报告生成走真实会话写文件、复用 CAP-37 产出回传落 DB 镜像（重构 CAP-28 报告链路） |
| [CAP-42](CAP-42-per-user-workspace.md) | 每用户固定工作区与手动收口 | 底座 | runner 代码工作区固定到 {项目}/{登录用户}（克隆缓存+固定 worktree，结束不 push 不删），页面手动收口合并基线+push+删 worktree（重构 CAP-25/31 布局与生命周期） |
| [CAP-51](CAP-51-requirement-scoped-workspace.md) | 需求粒度工作区与并行开发 | 底座 | runner 代码工作区粒度从「(项目,用户)」改为「需求」：一需求一工作树一分支（`<owner>/worktrees/req-<id>` + `feature/req-<id>`），需求内会话串行共用、跨需求零冲突并行；收口上移为需求级（合并+push+**保留工作树**），新增 worktrees 对账/超龄 GC（重构 CAP-42 FR-01~06/09，兼容存量旧布局会话） |
| [CAP-53](CAP-53-project-user-claude-cwd.md) | claude 工作目录上抬与代码目录分离 | 底座 | claude cwd 从需求工作树上抬到「项目+用户」粒度（`<proj>/<owner>/`），代码仍在需求子目录：memory/transcript 跨需求共享；上下文物化拆分（settings/skills 落 cwd、会话特定注入落代码目录）+ 路由注入（恒定文件 + 首条消息【代码目录】前缀）+ resume transcript 一次性迁移（修订 CAP-51 的 cwd 设定） |
| [CAP-54](CAP-54-workspace-live-view.md) | 会话工作区实时视图 | 底座 | 会话/问答页右侧工作区面板：git 变更实时推送（tool_result 事件触发采集+去抖+哈希去重，旁路内存快照不入事件流）+ 文件树/内容/单文件 diff 拉取（workspace_query 帧，协议 v11），补 RemoteDiffService 看不到未提交改动的盲区 |
| [CAP-55](CAP-55-decision-engine.md) | 决策引擎接入与知识库提案分诊 | 底座 | Laya 非自回归决策模型（choice/score/noul，33ms 级）以 Python 边车服务接入：端点复用 CAP-48 体系放开 kind=DECISION，DecisionEngine SPI 入 common；首场景 inbox 提案 AI 分诊（采纳层级+重复判定+质量分，建议先行不自动执行），人工裁决落 decision_records 沉淀微调训练集（数据飞轮），边车不可用全链降级现状 |
| [CAP-58](CAP-58-session-terminal.md) | 会话工作区远程终端 | 底座 | 会话/问答页工作区面板加终端 tab：单条命令（ls/cd/cat/git 等）经 terminal_exec 帧下发 runner 在会话代码目录执行并回显（协议 v16 门控），cwd 前端持有随命令下发，runner 侧可配置白名单缺省只读档 |
| [CAP-59](CAP-59-terminal-persistent-shell.md) | 远程终端增强（持久 shell/补全/取消） | 底座 | runner 侧每会话长驻 shell（env/cd 跨命令保持、cwd 权威移到 runner、空闲回收）+ Tab 补全（compgen 一次性子进程，前端候选弹层）+ Ctrl+C 取消（整树杀 shell exit 130，Windows MSYS 断树 ps 补杀），terminal_complete/terminal_cancel 帧（协议 v17 门控） |
| [CAP-60](CAP-60-terminal-frontend-polish.md) | 远程终端前端打磨 | 前端体验 | 纯前端：ANSI SGR 颜色渲染（自写解析器，16/256/truecolor，控制序列吞掉）、编程等宽字体栈（JetBrains Mono/Cascadia Code 优先回落 Consolas）、工具栏字号调节 10–20px localStorage 持久化 |
| [CAP-61](CAP-61-menu-management.md) | 菜单管理与角色菜单权限 | 管理 | 菜单目录注册表（top/project/admin 三区 menuKey）+ 角色×菜单可见性矩阵（白名单授权、ADMIN 恒见、种子即现状），/api/auth/menus 下发驱动三个菜单位渲染，前端路由弱守卫（展示层管控，安全边界仍后端角色链） |
| [CAP-62](CAP-62-data-permission.md) | 数据权限：项目成员与个人归属 | 管理 | 项目可见性（PUBLIC/PRIVATE）+ project_members（OWNER/MEMBER）+ common SPI ProjectAccessChecker 一处收口查询过滤与 403/404 判定；个人数据（问答/工作日志/PAT/通知）接口层 owner 强制；默认 PUBLIC 存量零变化（CAP-63 硬前置） |
| [CAP-56](CAP-56-decision-eval-finetune.md) | 决策模型评测与微调闭环 | 底座 | 先证明"这套判断准不准"再接模块：评测集（人工基准集+decision_records 回流，**强制含空召回/逐字重复/不相关对照组**）+ 官方口径指标（accuracy/ECE/Brier/score_mae，**必报随机与多数类基线**）+ 温度校准 + runner 编排微调（官方 RLCD recipe，2×T4 分钟级；权重留节点只回传指标指纹）+ **准入闸门**（只有验证通过的 checkpoint 才允许消费方启用）；同时更正 CAP-55「首发用 typed-decisions」口径 |
| [CAP-43](CAP-43-agent-node-proxy.md) | Agent 节点外网代理 | 底座 | 节点级 HTTP 代理配置（URL+按功能勾选 scope git/claude/exec），随 launch/worklog_push/exec/workspace_finalize 帧下发（协议 v8 门控），runner 进程级 holder 注入 git 命令行与子进程 env |
| [CAP-63](CAP-63-multi-tenant.md) | 多租户隔离 | 平台层 | 共享库共享表 + tenant_id 判别列（定稿策略，否决 schema/db-per-tenant 与 @Filter）；M1 租户登记+用户绑定+TenantContext（JWT tenant claim）+超管/租户管理员分层，M2 业务表分批铺 tenant_code 叠加进 CAP-62 过滤切面（硬前置），平台级资源（节点/模型端点）豁免共享 |
| [CAP-44](CAP-44-knowledge-base-rag.md) | 知识库容器化与向量检索 | 管理 | 知识库成为一等容器（scope 归属+inject_mode FULL/RAG），条目重构归属并分块向量化（JSON CLOB+Java 余弦，无 pgvector），检索 SPI+降级 LIKE，shared Markdown/MarkdownEditor（高亮复制预览 / 分栏全屏快捷键 / 极简编辑预览，升级 CAP-04，打底 CAP-45/46） |
| [CAP-45](CAP-45-feishu-knowledge-import.md) | 飞书文档对接 | 管理 | integrations 增 FEISHU（appId/appSecret 双行密文），wiki/docx/doc 拉取+blocks→markdown，手动选文档导入知识库（externalId 判重+contentHash 变更检测+手动重同步），条目走 CAP-44 摄入管线自动索引 |
| [CAP-46](CAP-46-kb-chat.md) | 知识库 AI 会话 | 会话 | 通用问答绑定知识库：启动注入库概览、每轮提问经 KnowledgeRetriever 检索包 `<knowledge-context>` 前缀注入（无命中/异常原样降级），前端新问答库选择器+库详情发起会话入口 |
| [CAP-47](CAP-47-requirement-push-to-jira.md) | 自建需求手动推送到 Jira | 底座 | 需求详情页手动推送 LOCAL 需求建 Jira issue（实例/项目/任务类型/优先级/标签/经办人/截止日期可选，描述附平台回链），登记 External Link 并转 JIRA 托管；选完任务类型按 createmeta 动态渲染该类型要求的必填字段（FR-08，渲染不了的提前列出并禁用提交）；SPI 补 createIssue，零表结构变更 |
| [CAP-48](CAP-48-model-endpoint-management.md) | 模型接入管理（Embedding 端点） | 底座 | 模型端点升为一等资源：独立模块+表 + 密文凭据（SecretCipher 抽取）+ 连接测试**实测探测维度** + 平台默认端点 + 库级覆盖；索引血缘落库（indexed_endpoint/model/dimensions）使**换模型维度失配可诊断**（degradedReason=DIMENSION_MISMATCH，不再静默空结果），补全库重建/定向重建立即修复；`devmind.knowledge.embedding.*` 迁成端点种子（cap44/45/46 E2E 零改动） |
| [CAP-49](CAP-49-chat-model-executor.md) | 问答模型执行体 | 底座 | 通用问答新增 MODEL 执行体：服务端直连已接入 CHAT 端点（流式 SSE + 可中断），**零节点依赖**；多轮上下文每轮从事件表重建（重启可续），是 CAP-34「服务端零执行」的收敛性例外 |
| [CAP-50](CAP-50-session-streaming.md) | CLI 会话流式输出 | 底座 | 给 CAP-49 的逐字打字机补上 CLI 执行体这一半：开 `--include-partial-messages` 让 `stream_event` 增量活起来（解析层命名空间白名单），每会话聚合器按 24字/120ms 节流保序收口，**前端零改动**（增量打底 + 全量覆盖收口复用既有渲染路径）；同批硬化链路：runner 出口改单写者队列（修并发 sendText 静默丢帧）、回放环形缓冲排除增量（否则刷屏冲掉历史）、`/ws/sessions` 加慢客户端装饰、`/sessions/{id}/events` 补 limit |
| [CAP-64](CAP-64-bookmark-favorites.md) | 收藏夹 | 组装层 | 绑定到人的网址收藏：分组树/标签/批量转移分组/浏览器打开、HTTP 可用性探测（状态码/耗时落库）、关联账号速查（密码 SecretCipher 密文+按次取明文）、平台内用户只读分享（密码恒不分享），个人数据归属复用 CAP-62 owner 强制 |
| [CAP-65](CAP-65-agent-node-file-browser.md) | Agent 节点文件浏览器 | 底座 | 节点级根目录白名单（服务端 DB 权威）下的文件浏览/预览/编辑/上传/下载/重命名/删除：小操作走 WS file 帧（协议 v18），≤100MB 大文件走 runner 主动 HTTP 中转（WS 零阻塞），全端点 ADMIN |
| [CAP-66](CAP-66-security-scan.md) | 安全漏洞扫描 | 执行器 | 多引擎（SCA=dependency-check / SAST=Semgrep / 密钥=Gitleaks）统一扫描底座：exec 帧下发节点执行+工具链标签调度，原始报告归一化统一发现模型（服务端解析），fingerprint 去重+跨任务基线 diff+误报四层漏斗（内部构件白名单/scope 降权/CVE 抑制库/KEV·EPSS 情报排序），修复指导一等输出（引入链/最低安全版本/AI 修复会话），密钥命中脱敏落库 |
| [CAP-67](CAP-67-usage-statistics.md) | 用量统计 | 组装层 | 会话+问答 token/成本只读聚合：零新表直接 SUM 现有累计六列，总体汇总 + 需求/项目/模型/用户四维分组 + 每日趋势（Java 分桶避方言）+ 成本 Top 明细，/usage 一级导航页；个人数据 owner 强制（非 admin 强制本人、dim=user 仅 admin），时段按创建时间归属为已知近似 |
| [CAP-68](CAP-68-session-attachments.md) | 会话附件注入与附件生命周期 | 底座 | 附件加自由标签/expiresAt 到期硬删（定时任务不做引用检查）/批量删除；/sessions 链路补齐图片（images 帧直读）与文件（input 帧 files 内联 → runner 落盘 .devmind/incoming/ 附路径 agent 自读）；创建会话即带附件随 ContextPackage inputs 物化（CAP-40 先例） |
| [CAP-69](CAP-69-script-test-suite.md) | 脚本测试套件 | 执行器 | 独立于项目的 kind=script 套件：自带 git 源+命令+env（脱敏）+超时，exec 帧下发 runner 整包跑（工作区按 workspaceKey 复用、`.state` 跨运行保留），JUnit XML 走 stdout marker（CAP-56 先例 gzip+base64 单行）回收解析进 test_case_results，WS 流/报告/失败转缺陷零改动复用；Playwright/pytest 等整包脚本统一落点 |
| [CAP-71](CAP-71-agent-audit-log.md) | Agent 节点审计日志 | 底座 | 连接日志升级为节点域全量审计（agent_audit_logs 九分类：连接/隧道/指令/代理逐条含字节耗时/上下文包/文件中转/规则/包/节点运维），旁路落库不阻塞主流程，服务端真分页+分类/节点/结果/关键词过滤，保留期 devmind.agent.audit-retention-days 默认 180 天可配；ServerTunnelStream 补统一终结点顺带修正常收尾不摘表缺口 |
| [CAP-72](CAP-72-pipeline-project-switches.md) | 执行链自动化项目级开关 | 底座 | CAP-17 恒开的「WI DONE→自动构建→自动部署 TEST」加项目级开关（projects.auto_build_on_wi_done / auto_deploy_test_on_build，默认关、getter 兜底 false、不加 @ColumnDefault），PipelineOrchestrator 两处入口静默闸门；生产部署/发版仍永远人工；与已有 auto_regression_on_deploy 同处项目表单 |
| [CAP-73](CAP-73-requirement-indev-verification.md) | 需求开发中验证 | 流程层 | 验证前移到开发中且走增量补丁（非全量流水线）：agent 自主触发（限额/互斥兜底）「推送 feature/req-<id> HEAD → 增量构建 → 变更文件补丁覆盖既有环境部署目录（前后钩子夹住停启服）→ 冒烟」，PROD 禁绑；结果回灌进行中会话供 agent 前置迭代；需求详情页验证 Tab + 人工同链触发，收口弹窗展示最近验证状态（不门禁） |
| [CAP-74](CAP-74-worklog-server-git-repo.md) | 工作日志事实源服务端化 | 组装层 | 消除「runner 文件⇄DB 镜像」单向同步的结构性分叉：事实源收敛为服务端本地 git 仓（JGit 单写者收口，不碰 CAP-34 红线），DB 报告表与 DRAFT/CONFIRMED 状态机退役（防覆盖=自动路径不覆盖已有文件+force 显式+git log 回溯）；按仓建模（个人仓=成员 1 的团队仓，per-user 子目录路径不相交零冲突），远端备份改服务端 push（CAP-70 出口规则接入），平台外编辑合法输入（写前 fetch+merge、冲突 fail-visible）；否决 index.json 派生索引（文件名+git log+commit trailer 全覆盖） |
| [CAP-75](CAP-75-feishu-integration.md) | 飞书集成（通知通道 + 双向机器人网关） | 组装层 | M1 补 feishu 自定义机器人通知通道（可选加签）+ 通道种子逐 code 兜底修存量库不补插 + 前端声明式 config 表单；M2 机器人网关底座接口化（devmind-bot：BotAdapter SPI/绑定表/指令路由/回答回推，零飞书代码）+ 飞书适配器（长连接收事件免公网域名证书，SDK 封装鉴权解密），群内 @机器人 问答（CAP-30 MODEL 执行体）+ /bind /session /build /deploy 项目操作 |
| [CAP-76](CAP-76-agent-gateway.md) | 平台智能体网关（飞书自然语言管理） | 组装层 | 飞书裸文本 → 智能体网关 → LLM 工具选择 → 平台工具受控执行 → 卡片回复：工具契约先行（名称/schema/风险/角色，可导出 OpenAPI 3.0），工具随能力模块交付并在进程内调本模块 Service（权限/事务/归属复用），读直查、写必确认（DB 确认单 + 一次性消费 + 确认时重校验），白名单无任意 HTTP/SQL/shell；新增 devmind-copilot 编排模块 + common 工具/网关 SPI + bot 卡片扩展，CAP-62 数据权限以 ToolAccessPolicy 预留接入 |
| [CAP-77](CAP-77-self-feedback-loop.md) | 平台自举反馈闭环（/issue 快捷登记） | 组装层 | Dev-Mind 自身注册为普通项目 + `/issue [类型] <描述>` 任意飞书会话快捷登记（固定投自项目，不受聊天绑定影响），自动附渠道/会话/发送者/登记人/时间，回执给 REQ 编号与 `/dev` 修复入口；复用需求主线与 CAP-34 会话，不新增存储；自动自更新（deploy_platform）列为后续能力 |
| [CAP-78](CAP-78-feishu-requirement-dev-flow.md) | 飞书对话式需求开发全流程 | 组装层 | 需求开发全流程搬进飞书对话：copilot 工具化 startPlan/startDev/WI 起会话（devmind-flow）+ finish/suspend/resume/会话级·需求级收口（devmind-session，需求级收口 DANGEROUS 文本短码），斜杠快速路径 /plan /dev WI /finish /resume /finalize；只调 CAP-52/42/51 既有 Service，零新表零新端点零前端改动 |
| [CAP-79](CAP-79-agent-memory-loop.md) | Agent 记忆闭环增强 | 流程层 | 三里程碑补记忆/知识/规划短板：M1 会话经验自动回流（devSpec 收尾增 lessons.md 可选产出→自动登记知识提案 inbox，FAILED/反复拒授权会话产【待回顾】占位提案，人工闸门从「发起」收敛到「采纳」）；M2 建 runner→平台会话作用域知识检索通道（节点 token+sessionId 反查归属，只读）+ search 调用统计表与聚合报表（不改瘦上下文与 copilot 侧工具行为）；M3 verify 连续失败→人触发增量重规划（[flow:replan] 会话产 replan.json diff，add 带 type、dependsOn 用 WI-<seq>/new:<i> 标识空间，applySplitPlan 增 diff 模式+合并全图环检测，旧会话活动态才挂起、起新 dev 会话不 resume） |
| [CAP-80](CAP-80-async-task-framework.md) | 通用异步任务框架 | 底座 | 新模块 devmind-task 收敛「私有线程池+自有记录表+散点 @Scheduled」多份同构拷贝：`async_tasks`/`async_task_runs` 两表 + AsyncTaskHandler SPI（按 type 注册分发）+ 主 tick 乐观认领调度（IMMEDIATE/DELAY/FIXED_INTERVAL/CRON 四形态，max_runs 次数上限，SKIP/QUEUE 重叠策略）；重试策略化（FIXED/EXPONENTIAL 退避 + NonRetryable 分类）；日志复用 CAP-12 枢纽（topic=task-run:{runId}）；僵尸收割 + 重启续跑；后台系统组管理页；四大执行器与存量 @Scheduled 不迁移 |
| [CAP-81](CAP-81-computer-use.md) | 远程桌面（节点 GUI 观测与控制） | Agent 域 | runner 补 GUI 原语（computer_req/ack 帧，Robot 截屏/键鼠/剪贴板），截图走 HTTP 中转不挤 WS；Windows Session 0 服务截不到用户桌面 → 计划任务拉起用户态 desktop-helper（loopback token），Linux X11 直做；三里程碑：M1 截屏只读观看 / M2 交互键鼠 / M3 审计限频运维；缺省关闭 + 只读档 + 动作全量审计脱敏 |
| [CAP-82](CAP-82-llm-computer-use.md) | LLM Computer-Use 循环 | Agent 域 | 复用 CAP-81 computer 原语，服务端编排视觉模型循环操作 GUI：computer_use_tasks/steps、动作 JSON 协议、dryRun、人工接管；前置=视觉模型接入（扩 common 多模态 + 模型端点视觉位）+ 明确驱动场景 |
| [CAP-83](CAP-83-binary-session-outputs.md) | 会话产出二进制文件回传 | Agent 域 | CAP-37 产出链路补二进制（xlsx 等曾 UTF-8 解码失败被静默跳过）：严格 UTF-8 解码判定文本/二进制，二进制走独立裸字节 POST 通道进附件管理（CAP-32：归属会话创建者+PRIVATE+tags=session-output，下载/图片预览复用 attachments raw），session_outputs 加 attachment_id 引用列（文本通道与消费零改动），同名覆盖删旧附件，上限沿用附件 20MB；前端弹窗附件项下载/禁推送，publish 兜底 400；部署先服务端后 runner |
| [CAP-84](CAP-84-knowledge-management-v2.md) | 知识管理 v2（万级文档摄入 + 混合检索 + 方案输出） | 管理/底座 | 本地上传（文件/文件夹/ZIP + Tika 解析 + 目录分类 + 哈希去重/版本）走 devmind-task 异步摄入；嵌入式 Lucene 10 BM25+HNSW 混合检索（RRF）+ 文档级聚合/高亮 + 全局知识中心（H2/PG 同构，否决 2GB 场景的 pgvector）；chat 扩展多库/多轮改写/引用快照；SOLUTION 结构化方案 + 引用校验 + 一键沉淀 CAP-03 文档 |
| [CAP-85](CAP-85-knowledge-governance-sync.md) | 知识治理与增量同步（M5） | 管理/底座 | Git 本地副本 + 飞书空间目录增量同步（水位 + 删除标记，外部删只 deprecated 不物理删）；CHAT 端点可选自动分类/摘要/标签（人工修正锁定、失败不阻断摄入）；复核/到期/久未复核提醒 + SimHash 近重复报告；固定评测集输出 Recall@K/MRR/引用准确率/拒答率；知识中心 ADMIN 治理控制台 |
| [CAP-86](CAP-86-knowledge-workspace.md) | 知识库管理台（菜单组化信息架构） | 管理/前端体验 | 消费归 AI 问答、知识页面纯管理员工具；后台「知识库」升级为菜单组：库总览/文档导入/召回验证/治理报告四子菜单；库详情恢复独立路由四页签（文档/摄入/同步/设置），同步源与飞书手动导入按库下沉，生命周期/近重复/反馈/评测聚合为治理报告；库内条目轻量摘要服务端分页，旧 `/knowledge` 与 v1 链接全量重定向并保留聊天引用深链 |

## 文档模板说明

每份能力文档**必须按 CAP-75 定稿格式书写**（[CAP-75](CAP-75-feishu-integration.md) 为范式参考）：

- **头部元信息块**：`能力 ID ｜ 分类 ｜ 状态 ｜ 日期`，接「决策记录」段写明关键选型结论与理由。
- **1. 目的** —— 要解决什么问题、为什么是现在。
- **2. 功能需求** —— FR-xx 连续编号，按里程碑（M1/M2…）分组小节。
- **3. 否决方案** —— 被否方案 + 否决理由（避免后人重提）。
- **4. 架构与模块归属** —— 模块划分、SPI 接口、依赖树（谁依赖谁）。
- **5. 数据模型** —— 新表 DDL 示意 + 复用既有表的说明；手工 ALTER 等存量库动作也写这里。
- **6. API 概要** —— 新增端点清单；复用既有端点也要点名。
- **7. 安全** —— 凭证/权限边界/暴露面。
- **8. 里程碑** —— M1…Mn 各自圈定 FR 范围，交付后标注「（已交付）」。
- **9. 验收标准** —— 按里程碑给出可执行验收项（含单测/E2E 脚本名）。
- **10. 风险与开放问题** —— R#/O# 编号表格，每项给口径（接受/缓解/待决）。
- **附录（可选）** —— 联调 checklist、操作手册等。

章节顺序与编号固定；某节确实无内容可省略该节，但不得自创新章节编号。1/2/5/8/9 为必备节。
