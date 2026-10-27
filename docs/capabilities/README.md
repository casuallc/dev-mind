# Dev-Mind 能力索引（CAP 档案）

> 本目录是**能力档案**：每份 CAP 记录该能力立项时的需求、决策与里程碑（历史/演进视角）。
> 想看「系统**现在**有什么功能、整体怎么实现的」，看 [docs/design/system-overview.md](../design/system-overview.md)（现状视角活文档，随能力交付维护）。

## 状态口径（四档，禁止使用其他词汇）

| 状态 | 含义 |
|---|---|
| 已实现 | 已上线在用；括号内注明后续重构/演进它的 CAP |
| 部分交付 | 部分里程碑落地，注明已落地范围 |
| 定稿未实施 | 需求/设计定稿，代码未落地 |
| 已废弃 | 被后续 CAP 取代或整体下线，仅作决策档案留存，双方文档互链 |

**治理规则（MUST）**：能力交付或里程碑推进后，同一次提交内回写该 CAP 头部状态与本表；新立 CAP 时同提交登记本表。状态失真即索引失效。

## 能力索引

| ID | 能力 | 分类 | 状态 | 一句话职责 |
|---|---|---|---|---|
| [CAP-01](CAP-01-auth.md) | 用户认证与权限 | 管理 | 已实现 | 登录鉴权与角色，所有操作的 actor 基础 |
| [CAP-02](CAP-02-project-management.md) | 项目管理 | 管理 | 已实现 | 注册项目与服务器，其余能力的挂载点 |
| [CAP-03](CAP-03-document-management.md) | 文档管理 | 管理 | 已实现 | 版本化文档库（DB），需求/方案/报告跟需求走 |
| [CAP-04](CAP-04-knowledge-base.md) | 知识库管理 | 管理 | 已实现（经 CAP-44 容器化/RAG 化、CAP-84 升级混合检索、CAP-92 收敛为检索注入） | 经验分层（global/projects/inbox）+ 捕获；消费统一为检索命中注入 |
| [CAP-05](CAP-05-agent-session.md) | Agent 会话管理 | 底座 | 已实现（经 CAP-31/34/42/50/51/53/54 多轮演进） | 起/管/收 headless agent 会话，worktree 隔离，看板 |
| [CAP-06](CAP-06-notification.md) | 通知中心 | 底座 | 已实现 | 事件分级路由，多通道推送，远程快捷动作 |
| [CAP-07](CAP-07-server-adapter.md) | 服务器适配器 | 底座 | 已废弃 → CAP-36（模块已删除） | 原 SSH/HTTP 插件化服务器控制，远程执行统一切 runner exec 帧 |
| [CAP-08](CAP-08-build-executor.md) | 构建执行器 | 执行器 | 已实现（经 CAP-12 收敛、CAP-36 runner 化） | 按项目脚本步骤构建 |
| [CAP-09](CAP-09-deploy-executor.md) | 部署执行器 | 执行器 | 已实现（同 CAP-08） | 部署计划执行 + 失败自动回滚 |
| [CAP-10](CAP-10-test-executor.md) | 测试执行器 | 执行器 | 已实现（同 CAP-08） | 冒烟 + API 测试，套件沉淀自动回归 |
| [CAP-11](CAP-11-release-executor.md) | 发版执行器 | 执行器 | 已实现（同 CAP-08） | Nexus 脚本模板推送 + 打 tag |
| [CAP-12](CAP-12-execution-platform.md) | 统一执行底座 | 底座 | 已实现 | 步骤链引擎 + Runner + 统一 WS 日志枢纽（无统一 Job 表） |
| [CAP-13](CAP-13-requirement-workitem.md) | 研发主线 | 管理 | 已实现 | Requirement/Design/Work Item 模型 + Relation 追溯网 |
| [CAP-14](CAP-14-requirement-flow.md) | 需求流程引擎 | 流程层 | 已废弃 → CAP-38/52（主体 FR 被取代） | 原阶段动作+产出登记+人工确认门禁 |
| [CAP-15](CAP-15-orchestrator.md) | 自动编排器 | 流程层 | 已实现 | WI DONE 触发 depends_on 依赖就绪自动派发会话 |
| [CAP-16](CAP-16-command-center.md) | 指挥中心 | 组装层 | 已实现 | 全局聚合首页：状态分布/活跃会话/待办确认/最近失败 |
| [CAP-17](CAP-17-pipeline-orchestrator.md) | 执行链编排 | 流程层 | 已实现（CAP-72 加项目级开关） | WI DONE → 自动构建 → 测试环境自动部署；生产/发版人工门禁 |
| [CAP-18](CAP-18-platform-integration.md) | 第三方平台集成 | 底座 | 已实现（凭据链经 CAP-35 重构） | 外部平台连接器 SPI（GitLab 先行）：push/MR/Release/External Link |
| [CAP-19](CAP-19-jira-issue-sync.md) | Jira 任务/Bug 同步 | 底座 | 已实现 | Jira Server/DC 轮询拉取（JQL+水印增量）→ DRAFT 需求 |
| [CAP-20](CAP-20-open-api.md) | 开放 API + API Key 管理 | 底座 | 已实现 | `/open-api/v1/**` HMAC-SHA256 机器接入 + AK/SK 管控 + AI 项目接入助手 |
| [CAP-21](CAP-21-agent-node.md) | 远程 Agent 节点管理 | 底座 | 已实现 | 节点 runner 反向 WS 连服务端，远程拉起/交互 claude 会话 |
| [CAP-22](CAP-22-github-integration.md) | GitHub 集成 | 底座 | 已实现 | CAP-18 第二个 git 平台实现：PR/Release，github.com 与 GHE 分流 |
| [CAP-23](CAP-23-repo-clone.md) | 项目仓库从 Git 克隆 | 底座 | 已实现 | GitLab/GitHub 远端地址异步克隆到工作区，PAT 注入+状态机+实时日志 |
| [CAP-24](CAP-24-user-git-identity.md) | 用户级 Git 身份与凭证 | 底座 | 已废弃 → CAP-35 | 原用户自助 PAT 与署名，模型推广为统一第三方账号 |
| [CAP-25](CAP-25-runner-workspace.md) | 远程会话工作区编排 | 底座 | 已实现（布局/生命周期经 CAP-42、粒度经 CAP-51 重构） | launch 帧下发 repo+短期凭据，runner 自动 clone/worktree/push |
| [CAP-26](CAP-26-exec-fetch.md) | 执行前代码同步 | 底座 | 已实现 | 构建/发版/worktree 基准一律取 origin/ 远端引用（执行前 fetch） |
| [CAP-27](CAP-27-requirement-effort.md) | 需求工时 | 组装层 | 已实现 | AI 实际耗时会话时长汇总 + Jira 工时同步 + worklog 一键回写 |
| [CAP-28](CAP-28-personal-worklog.md) | 个人工作日志与工时管理 | 组装层 | 已实现（报告链路经 CAP-41 重构；存储经 CAP-74→CAP-89 回 DB） | git log + 手动补录成条目记工时，AI 日报/周报草稿人工确认 |
| [CAP-29](CAP-29-global-git-repo-registry.md) | 全局代码仓库登记 | 平台层 | 已实现 | 仓库平台级登记（ADMIN）+ 服务端克隆 + 定时 fetch，项目仓库改关联 |
| [CAP-30](CAP-30-agent-chat.md) | 通用问答 | 底座 | 已实现（CAP-49 增 MODEL 执行体、CAP-46 增知识库绑定） | 无项目纯问答（个人组 /chats），共享内核上移 common |
| [CAP-31](CAP-31-session-multirepo.md) | 项目会话多仓库与归属拆分 | 底座 | 已实现（经 CAP-42/51 重构布局） | 会话收敛纯项目开发会话 + 显式关联多 git 仓库 |
| [CAP-32](CAP-32-attachment.md) | 公共附件管理 | 底座 | 已实现（CAP-68 补标签/到期/生命周期） | 统一附件模型：上传即附件、字符串 id 引用、图片内联渲染 |
| [CAP-33](CAP-33-scenario-session-context.md) | 场景化会话与上下文装配 | 底座 | 已实现（CAP-92 场景改绑知识库并检索） | 场景=命名模板+预装配上下文包，session/chat 统一装配管线 |
| [CAP-34](CAP-34-agent-runner-executor.md) | Agent Runner 执行代理化 | 底座 | 已实现（FR-06 exec 帧随 CAP-36 落地） | 服务端零执行纯调度，一切执行收敛 runner 层 |
| [CAP-35](CAP-35-unified-platform-accounts.md) | 统一第三方账号体系 | 底座 | 已实现 | UserPlatformAccount 绑定实例，MR/PR、Jira、worklog 全走个人优先身份链 |
| [CAP-36](CAP-36-build-on-runner.md) | 构建执行 Runner 化 | 底座 | 已实现 | 构建/测试/部署/发版统一切 agent 节点 exec 帧执行 |
| [CAP-37](CAP-37-flow-output-pipeline.md) | 流程产出回传与阶段串联 | 流程层 | 已实现（CAP-83 补二进制通道） | runner 退出前回传产出落 session_outputs，需求详情页流程 Tab 串联 |
| [CAP-38](CAP-38-requirement-flow-simplify.md) | 需求流程简化 | 流程层 | 已实现 | 分析/方案独立 Tab 可跳过，方案产出后自动拆分+固化 WI |
| [CAP-39](CAP-39-session-output-publish.md) | 会话产出手动推送与按需回传 | 流程层 | 已实现 | collect_output 帧即时回传，手动推送为关联需求文档 |
| [CAP-40](CAP-40-requirement-attachment-context.md) | 需求附件上下文投送 | 流程层 | 已实现 | 需求附件+Jira 内嵌图打进 ContextPackage，runner 物化 .devmind/input/ |
| [CAP-41](CAP-41-worklog-workspace.md) | 工作日志空间 | 组装层 | 已实现（存储随 CAP-89 回 DB；远程备份退役） | WORKLOG 特殊项目 + 按用户持久工作区，报告走真实会话写文件 |
| [CAP-42](CAP-42-per-user-workspace.md) | 每用户固定工作区与手动收口 | 底座 | 已实现（粒度经 CAP-51 重构） | runner 工作区固定 {项目}/{用户}，页面手动收口合并+push+删 worktree |
| [CAP-43](CAP-43-agent-node-proxy.md) | Agent 节点外网代理 | 底座 | 已实现 | 节点级 HTTP 代理按功能勾选 scope，随帧下发 runner 进程级注入 |
| [CAP-44](CAP-44-knowledge-base-rag.md) | 知识库容器化与向量检索 | 管理 | 已实现（检索链路经 CAP-84 升级为 Lucene 混合检索；CAP-92 下线 FULL 注入） | 知识库一等容器（scope 分层），条目分块向量化，检索 SPI |
| [CAP-45](CAP-45-feishu-knowledge-import.md) | 飞书文档对接 | 管理 | 已实现（CAP-85 补增量同步） | wiki/docx 拉取转 markdown 导入知识库，变更检测+手动重同步 |
| [CAP-46](CAP-46-kb-chat.md) | 知识库 AI 会话 | 会话 | 已实现（CAP-84 扩多库/引用快照；检索口径经 CAP-92 统一） | 通用问答绑定知识库，每轮检索包注入，无命中降级 |
| [CAP-47](CAP-47-requirement-push-to-jira.md) | 自建需求手动推送到 Jira | 底座 | 已实现 | LOCAL 需求手动推送建 Jira issue，createmeta 动态渲染必填字段 |
| [CAP-48](CAP-48-model-endpoint-management.md) | 模型接入管理 | 底座 | 已实现 | 模型端点一等资源（密文凭据+连接测试+平台默认+库级覆盖+索引血缘） |
| [CAP-49](CAP-49-chat-model-executor.md) | 问答模型执行体 | 底座 | 已实现 | 问答 MODEL 执行体：服务端直连 CHAT 端点流式 SSE，零节点依赖 |
| [CAP-50](CAP-50-session-streaming.md) | CLI 会话流式输出 | 底座 | 已实现 | stream_event 增量解析+节流保序聚合，前端零改动 |
| [CAP-51](CAP-51-requirement-scoped-workspace.md) | 需求粒度工作区与并行开发 | 底座 | 已实现（cwd 口径经 CAP-53 修订） | 一需求一工作树一分支，跨需求零冲突并行，需求级收口 |
| [CAP-52](CAP-52-requirement-flow-lean.md) | 需求流程精简 | 流程层 | 已实现 | 取代 CAP-14 主体：阶段动作与会话分流精简，自动拆分固化派发 |
| [CAP-53](CAP-53-project-user-claude-cwd.md) | claude 工作目录上抬 | 底座 | 已实现 | claude cwd 上抬到「项目+用户」粒度，memory/transcript 跨需求共享 |
| [CAP-54](CAP-54-workspace-live-view.md) | 会话工作区实时视图 | 底座 | 已实现 | 工作区面板：git 变更实时推送 + 文件树/diff 拉取（workspace_query 帧） |
| [CAP-55](CAP-55-decision-engine.md) | 决策引擎接入与知识库提案分诊 | 底座 | 已实现（首发口径经 CAP-56 更正为 typed-decisions） | Laya 决策模型边车接入，inbox 提案 AI 分诊，decision_records 数据飞轮 |
| [CAP-56](CAP-56-decision-eval-finetune.md) | 决策模型评测与微调闭环 | 底座 | 已实现 | 评测集+官方口径指标+温度校准+runner 编排微调+checkpoint 准入闸门 |
| [CAP-57](CAP-57-classify-service.md) | 分类服务（Classify Service） | 底座 | 已实现 | laya 边车平台化管控：模型托管/槽位端点，与业务流程解耦 |
| [CAP-58](CAP-58-session-terminal.md) | 会话工作区远程终端 | 底座 | 已实现 | 工作区面板终端 tab：terminal_exec 帧下发 runner 执行回显 |
| [CAP-59](CAP-59-terminal-persistent-shell.md) | 远程终端增强 | 底座 | 已实现 | 每会话长驻 shell + Tab 补全 + Ctrl+C 取消（协议 v17） |
| [CAP-60](CAP-60-terminal-frontend-polish.md) | 远程终端前端打磨 | 前端体验 | 已实现 | ANSI SGR 颜色渲染、等宽字体栈、字号调节持久化 |
| [CAP-61](CAP-61-menu-management.md) | 菜单管理与角色菜单权限 | 管理 | 已实现 | 菜单目录注册表 + 角色×菜单可见性矩阵，/api/auth/menus 驱动渲染 |
| [CAP-62](CAP-62-data-permission.md) | 数据权限：项目成员与个人归属 | 管理 | 已实现 | 项目可见性+project_members+ProjectAccessChecker 收口，个人数据 owner 强制 |
| [CAP-63](CAP-63-multi-tenant.md) | 多租户隔离 | 平台层 | 定稿未实施 | 共享库共享表 + tenant_id 判别列，M1 租户登记/M2 业务表铺 tenant_code |
| [CAP-64](CAP-64-bookmark-favorites.md) | 收藏夹 | 组装层 | 已实现 | 绑定到人的网址收藏：分组树/标签/可用性探测/关联账号速查/只读分享 |
| [CAP-65](CAP-65-agent-node-file-browser.md) | Agent 节点文件浏览器 | 底座 | 已实现 | 节点根目录白名单下文件浏览/编辑/上传下载，大文件走 HTTP 中转 |
| [CAP-66](CAP-66-security-scan.md) | 安全漏洞扫描 | 执行器 | 定稿未实施 | 多引擎（SCA/SAST/密钥）统一扫描底座 + 统一发现模型 + 误报漏斗 |
| [CAP-67](CAP-67-usage-statistics.md) | 用量统计 | 组装层 | 已实现 | 会话+问答 token/成本只读聚合，四维分组+每日趋势+成本 Top |
| [CAP-68](CAP-68-session-attachments.md) | 会话附件注入与附件生命周期 | 底座 | 已实现 | 附件标签/到期硬删/批量删除，会话链路图片与文件注入 |
| [CAP-69](CAP-69-script-test-suite.md) | 脚本测试套件 | 执行器 | 已实现 | kind=script 套件自带 git 源+命令整包跑，JUnit XML 回收解析 |
| [CAP-70](CAP-70-egress-reverse-tunnel.md) | 服务端出口反向隧道 | 底座 | 已实现 | 服务端按域名规则经节点代理访问内网/外网（隧道流+出口规则） |
| [CAP-71](CAP-71-agent-audit-log.md) | Agent 节点审计日志 | 底座 | 已实现 | agent_audit_logs 九分类全量审计，旁路落库，保留期可配 |
| [CAP-72](CAP-72-pipeline-project-switches.md) | 执行链自动化项目级开关 | 底座 | 已实现 | auto_build_on_wi_done / auto_deploy_test_on_build 默认关，两处入口闸门 |
| [CAP-73](CAP-73-requirement-indev-verification.md) | 需求开发中验证 | 流程层 | 部分交付（MVP 后端+runner 全链落地，v4 差异口径收尾中） | 验证前移开发中：推送 HEAD→增量构建→补丁部署→冒烟，PROD 禁绑 |
| [CAP-74](CAP-74-worklog-server-git-repo.md) | 工作日志事实源服务端化 | 组装层 | 已废弃（2026-10-26 被 CAP-89 取代） | 事实源收敛服务端本地 git 仓（JGit 单写者），DB 状态机退役 |
| [CAP-75](CAP-75-feishu-integration.md) | 飞书集成（通知通道+机器人网关） | 组装层 | 已实现 | feishu 机器人通知通道 + BotAdapter SPI 网关（devmind-bot/feishu-bot），群内问答与项目操作 |
| [CAP-76](CAP-76-agent-gateway.md) | 平台智能体网关 | 组装层 | 已实现 | 飞书裸文本→LLM 工具选择→受控执行→卡片回复；工具契约先行，写必确认 |
| [CAP-77](CAP-77-self-feedback-loop.md) | 平台自举反馈闭环（/issue） | 组装层 | 已实现 | Dev-Mind 自注册为项目，/issue 快捷登记投自项目，回执 REQ 编号 |
| [CAP-78](CAP-78-feishu-requirement-dev-flow.md) | 飞书对话式需求开发全流程 | 组装层 | 已实现 | /plan /dev /finish /resume /finalize 搬进飞书，复用既有 Service 零新表 |
| [CAP-79](CAP-79-agent-memory-loop.md) | Agent 记忆闭环增强 | 流程层 | 定稿未实施 | M1 会话经验自动回流 / M2 runner→平台知识检索 / M3 增量重规划 |
| [CAP-80](CAP-80-async-task-framework.md) | 通用异步任务框架 | 底座 | 已实现 | devmind-task：async_tasks/runs 两表 + AsyncTaskHandler SPI + 主 tick 调度 |
| [CAP-81](CAP-81-computer-use.md) | 远程桌面（节点 GUI 观测与控制） | Agent 域 | 定稿未实施 | runner 补 GUI 原语（截屏/键鼠），三里程碑：观看/交互/审计运维 |
| [CAP-82](CAP-82-llm-computer-use.md) | LLM Computer-Use 循环 | Agent 域 | 定稿未实施 | 视觉模型循环操作 GUI：computer_use_tasks/steps、dryRun、人工接管 |
| [CAP-83](CAP-83-binary-session-outputs.md) | 会话产出二进制文件回传 | Agent 域 | 已实现 | 二进制产出走裸字节 POST 进附件管理，session_outputs 加 attachment_id |
| [CAP-84](CAP-84-knowledge-management-v2.md) | 知识管理 v2 | 管理/底座 | 已实现（M1~M4；CAP-92 下线全量注入） | 万级文档摄入（devmind-task 异步）+ Lucene 混合检索 + SOLUTION 方案输出 |
| [CAP-85](CAP-85-knowledge-governance-sync.md) | 知识治理与增量同步 | 管理/底座 | 已实现 | Git/飞书增量同步 + 自动分类摘要 + 复核提醒 + 近重复报告 + 评测 |
| [CAP-86](CAP-86-knowledge-workspace.md) | 知识库管理台（菜单组化信息架构） | 管理/前端体验 | 已实现（v3；CAP-92 移除注入方式/注入预览） | 后台「知识库」菜单组：库总览/文档导入/召回验证/治理报告 |
| [CAP-87](CAP-87-changelog.md) | 控制台更新日志 | 管理/前端体验 | 已实现 | CHANGELOG.md 随包分发，控制台只读解析渲染 |
| [CAP-88](CAP-88-worklog-plan-loop.md) | 工作日志计划闭环 | 组装层 | 已实现（M1+M2+M3；FR-11 提醒开放后置；日计划口径 2026-10-26 被 CAP-90 修订并入条目） | 日/周计划项一等数据 + 条目关联 + 数据驱动复盘，M3 AI 建议计划 |
| [CAP-89](CAP-89-worklog-storage-back-to-db.md) | 工作日志存储回 DB | 组装层 | 已实现（取代 CAP-74） | 去 git 仓化与远程推送：事实源回 DB，团队仓退役，幂等迁移 |
| [CAP-90](CAP-90-worklog-plan-entry-merge.md) | 工作日志计划-条目合并与周报投影 | 组装层 | 已实现（2026-10-26；WEEK 联动保留为实现修订） | 日计划并入条目（status=DONE 即事实），周计划唯一计划实体，周报下周计划单向投影 |
| [CAP-91](CAP-91-worklog-goals-okr.md) | 工作目标与 OKR 对齐 | 组装层 | 已实现（M1+M2+M3，2026-10-26） | 年度目标 + 季度 OKR 一等数据，周计划挂 KR 对齐，季度复盘数据驱动投影 |
| [CAP-92](CAP-92-knowledge-injection-retrieval.md) | 知识注入收敛为检索 | 管理/底座 | 已实现（M1+M2+M3，2026-10-26） | 下线 FULL 全量注入与注入预览；场景改绑知识库，装配时按任务检索命中片段 |
| [CAP-93](CAP-93-worklog-okr-suggest.md) | 工作日志 OKR AI 生成 | 组装层 | 已实现 | 会话链路生成年度/季度 OKR 草稿（worklog_okr_drafts），人工勾选编辑后采纳批量建目标 |
| [CAP-94](CAP-94-knowledge-project-decoupling.md) | 知识库去项目化 | 管理/底座 | 设计中 | 废除 scope/project 归属，库完全独立；检索/注入/工具/沉淀一律显式绑定 kbIds |
| [CAP-96](CAP-96-assistant-task-runtime.md) | 目标驱动的持续助手任务内核 | 底座/会话 | 定稿未实施 | 持久目标、动态推进、作业唤醒与重启对账，首个场景为受控构建失败修复 |
| [CAP-97](CAP-97-assistant-controlled-execution.md) | 助手任务授权、可靠动作与证据验收 | 底座/治理 | 定稿未实施 | 有限可撤销授权、工具适配与受理幂等、证据绑定和目标验收；CAP-96 首批前置 |
| [CAP-98](CAP-98-standing-missions-upstream-watch.md) | 长期委托与主动跟踪 | 组装层/流程层 | 定稿未实施 | 周期委托、观察台账和安静提醒，上游只读跟踪试点，漏洞闭环消费 CAP-66 |

## 持续助手实施主线（规划，均未实施）

- 首批联合交付：**CAP-97 M1 → CAP-96 M1/M2 + CAP-97 M2 → 双方 M3**，跑通“交代一次 → 诊断构建失败 → 授权内修复 → 指定提交验证 → 带证据收口”；仅建表/调高工具循环次数不算完成。
- 第二批：**CAP-98 M1/M2**，跑通“委托一次 → 周期检查上游 → 评估与去重提醒 → 记录处理结论”，保持不自动推代码。
- 后续领域扩展：REQ-5 由 CAP-95 提供知识方案检索/验收并接入 CAP-96；REQ-10 由 CAP-66 提供漏洞发现、CAP-98 串联后续任务。REQ-8 多人支持问答仍需独立领域规划。
- CAP-76 短对话/固定计划原语义保持；CAP-79 的经验提案/领域 WI 重规划、CAP-80 的调度分别复用。[整体边界与需求映射](CAP-96-assistant-task-runtime.md#附录整体规划与已有能力边界)见 CAP-96 附录。

## 文档模板说明

每份能力文档**必须按 CAP-75 定稿格式书写**（[CAP-75](CAP-75-feishu-integration.md) 为范式参考）：

- **头部元信息块**：`能力 ID ｜ 分类 ｜ 状态 ｜ 日期`，接「决策记录」段写明关键选型结论与理由。状态取值限于上方四档。
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
