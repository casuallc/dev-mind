# Dev-Mind 系统总览（架构与功能现状）

> 本文档是**现状视角**的活文档：回答「系统现在有什么功能、整体怎么实现的」。
> 与 [docs/capabilities/](../capabilities/README.md)（CAP 档案，历史/演进视角）分工：能力交付后回写 CAP 状态，**同时更新本文相应小节**。
> 与 CLAUDE.md 分工：CLAUDE.md 是开发红线与索引（给 AI/开发者），本文是系统全景（给所有人）。
>
> 最近全面核对：2026-10-26（对照代码模块逐一核实）。

## 1. 平台定位与技术栈

积木式研发能力平台，本地优先：Agent 会话管理 + 构建/部署/测试/发版执行器 + 需求主线（Requirement/Design/WorkItem）。

- 后端 Java 21 · Spring Boot 4.1.1（REST + WebSocket + JPA），单体仓库平铺 Maven 多模块，每能力一个模块；
- 前端 React 19 + Vite 6 + Ant Design 5 + TS，`src/features/<能力>` 自包含；
- 存储：H2 文件库（本机）/ PostgreSQL / MySQL（按部署环境外置配置），schema 靠 `ddl-auto=update` 演进；
- Agent 执行体 = headless claude，**服务端零执行**，一切经 runner 节点执行（WS 反向连接 + 帧协议）；
- 能力间只依赖 `devmind-common` 的 SPI，调用方 `ObjectProvider<T>` 探测注入。

## 2. 总体架构

```
浏览器(React SPA)
   │ REST /api · WS /ws（会话事件流、日志 Hub、通知）
   ▼
devmind-app（组装入口，瘦 jar）
   │  能力模块（下表）· 跨模块走 common SPI
   │  H2/PG/MySQL ── 结构化数据、附件元数据、知识/文档、审计
   │
   │ WS 帧协议（launch/exec/terminal/file/workspace_query/collect_output…，带版本门控）
   ▼
agent-runner 节点（瘦 jar 无 Spring，可 Windows 服务托管）
   │  拉起 claude 子进程 · 执行构建/部署/测试脚本 · 工作区 git 操作
   │  上下文包经 GET /api/agent/context/{sid}?token= 拉取物化
   ▼
外部：GitLab/GitHub（push/MR/PR/Release）· Jira（拉需求/回写工时）· 飞书（通知+机器人）· 模型端点（EMBEDDING/CHAT/DECISION）
```

关键路由：**会话/问答/one-shot 一律下发 runner**——`agentNodeId` 显式指定 → 项目默认节点 → 平台默认节点 → 皆无 409 不回落；节点离线 launch 抛 409。

## 3. 能力地图（当前生效）

### 3.1 会话与 Agent 执行

| 能力 | 现状 | 依据 |
|---|---|---|
| 项目开发会话 | headless claude + 需求粒度 worktree（`feature/req-<id>`），cwd 上抬「项目+用户」，多库聚合，流式输出，工作区实时视图+远程终端（持久 shell/补全/取消） | CAP-05/31/34/42/50/51/53/54/58/59/60 |
| 通用问答 | 无项目纯问答（/chats），CLI 或 MODEL 执行体（服务端直连 CHAT 端点 SSE），可绑定知识库 | CAP-30/49/46 |
| 场景化上下文 | 场景模板 + 上下文包（知识注入 CLAUDE.local.md + settings 白名单），服务端装配 runner 拉取 | CAP-33/34/40 |
| 节点管理 | 节点注册/心跳/指令下发，节点级外网代理，文件浏览器，九分类审计日志 | CAP-21/43/65/71 |
| 出口隧道 | 服务端按域名规则经节点代理访问内网（反向隧道流） | CAP-70 |
| 产出链路 | 退出前回传 + collect_output 即时回传 + 手动推送为需求文档；二进制走附件通道 | CAP-37/39/83 |
| 决策引擎 | laya 边车（分诊/打分），评测+微调闭环+checkpoint 准入闸门，classify 服务托管 | CAP-55/56/57 |

### 3.2 研发主线与流程

| 能力 | 现状 | 依据 |
|---|---|---|
| 研发主线 | Requirement/Design/WorkItem + Relation 追溯网 | CAP-13 |
| 需求流程 | 分析/方案 Tab 可跳过，方案后自动拆分+固化 WI，WI 中文状态跳会话 | CAP-38/52 |
| 自动编排 | WI DONE → depends_on 就绪自动派发；→ 自动构建 → 自动部署 TEST（项目级开关，默认关）；生产/发版永远人工 | CAP-15/17/72 |
| 开发中验证 | 增量补丁链：推 HEAD→增量构建→补丁部署→冒烟，PROD 禁绑（MVP 全链落地） | CAP-73 |
| 需求工时 | 会话时长自动汇总 + Jira 工时同步 + worklog 回写 | CAP-27 |
| 指挥中心 | 全局聚合首页（dashboard） | CAP-16 |

### 3.3 执行器（构建/部署/测试/发版）

统一执行底座（步骤链 + 日志 Hub，CAP-12），四大执行器各自实体与状态机，**全部 runner 化**（exec 帧下发节点执行，CAP-36）；脚本测试套件（kind=script，JUnit XML 回收，CAP-69）；制品登记存储（devmind-artifact）。安全扫描（CAP-66）定稿未实施。

### 3.4 知识

多库容器（FULL 注入 / RAG 检索），万级文档摄入（文件/文件夹/ZIP + Tika + devmind-task 异步），Lucene BM25+HNSW 混合检索，飞书 wiki/docx 导入与增量同步，Git 副本同步，自动分类/摘要/标签，近重复报告与复核提醒，全局知识中心 + 后台菜单组管理台（库总览/导入/召回验证/治理报告），inbox 提案 AI 分诊（决策引擎），知识库绑定问答（多库+引用快照），SOLUTION 方案一键沉淀文档。依据 CAP-04/44/45/46/55/84/85/86。

### 3.5 工作日志

条目管理 + git 扫描导入（CAP-28），WORKLOG 项目 + 真实会话生成日/周报（CAP-41），事实源为数据库（CAP-89，取代 CAP-74 的服务端 git 仓——远程推送与团队仓已退役），计划闭环 M1 已落地（CAP-88：日/周计划项一等数据 + 条目关联联动 + 滚转；M2 数据驱动复盘、M3 AI 建议计划待实施）。

### 3.6 集成与开放

| 能力 | 现状 | 依据 |
|---|---|---|
| Git 平台集成 | GitLab/GitHub 连接器：push/MR/PR/Release/External Link | CAP-18/22 |
| Jira | JQL+水印增量拉需求；LOCAL 需求手动推送建 issue（createmeta 动态表单） | CAP-19/47 |
| 统一账号 | UserPlatformAccount 个人优先身份链（git PAT/Jira PAT） | CAP-35 |
| 飞书 | 机器人通知通道 + 长连接机器人网关：群内问答、/bind /session /build /deploy；/issue 自反馈；/plan /dev /finish /finalize 对话式需求开发 | CAP-75/77/78 |
| 智能体网关 | 飞书裸文本→LLM 工具选择→受控执行→卡片回复（工具契约+写必确认） | CAP-76 |
| 开放 API | /open-api/v1/** HMAC AK/SK + AI 项目接入助手 | CAP-20 |

### 3.7 平台治理

认证/RBAC（CAP-01）、菜单管理与角色菜单权限（CAP-61）、数据权限（项目成员+个人归属，CAP-62）、用量统计（CAP-67）、收藏夹（CAP-64）、控制台更新日志（CAP-87）、通用异步任务框架（CAP-80）、通知中心（CAP-06）、全局仓库登记（CAP-29）、附件管理（CAP-32/68）。**多租户（CAP-63）定稿未实施。**

### 3.8 已定稿未实施清单

CAP-63 多租户 ｜ CAP-66 安全扫描 ｜ CAP-79 记忆闭环 ｜ CAP-81 远程桌面 ｜ CAP-82 LLM Computer-Use。（CAP-88 计划闭环 M1 已实现、M2/M3 待实施；CAP-89 工作日志回 DB 已实现，移出清单。）

## 4. 后端模块清单（以 pom 依赖图为准）

| 模块 | 职责 |
|---|---|
| devmind-common | 公共契约：错误码/SPI/DomainEvent/agent.runtime 会话内核/VerifyToken |
| devmind-auth / project / docs / knowledge / skill | 认证 RBAC / 项目+研发主线 / 文档库 / 知识库 / Skill 管理 |
| devmind-session / chat | 项目开发会话 / 通用问答 |
| devmind-agent / agent-runner | 节点注册与指令下发（WS）/ runner 执行体（瘦 jar） |
| devmind-execution / build / deploy / test / release | 执行底座 + 四大执行器 |
| devmind-artifact / attachment | 制品登记存储 / 公共附件 |
| devmind-flow | 需求流程 + 编排（DispatchPlanner/WorkItemOrchestrator/PipelineOrchestrator） |
| devmind-integration / open-api | GitLab/GitHub/Jira/飞书连接器 / HMAC 开放 API |
| devmind-model / decision / decision-lab / classify | 模型端点 / 决策引擎 / 评测微调实验室+闸门 / 分类边车管控 |
| devmind-notification / bot / feishu-bot / copilot | 通知中心 / 机器人网关 SPI / 飞书适配 / 智能体工具编排 |
| devmind-task / worklog / usage / bookmark / changelog | 异步任务框架 / 工作日志 / 用量统计 / 收藏夹 / 更新日志 |
| devmind-app / devmind-dist | 组装入口 / 分发包组装 |

## 5. 演进取代速查（读旧 CAP 前先查这里）

- CAP-07 服务器适配 → **CAP-36**（SSH/HTTP 整体下线，模块已删）
- CAP-14 需求流程 → **CAP-38/52**
- CAP-24 用户 Git 身份 → **CAP-35** 统一账号
- CAP-25/31 工作区布局 → **CAP-42** → 粒度 **CAP-51** → cwd **CAP-53**
- CAP-28 报告链路 → **CAP-41** → 存储服务端 git 仓 **CAP-74** → 回 DB **CAP-89**（已实现）
- CAP-04 知识库 → **CAP-44**（容器化/RAG）→ **CAP-84**（v2 混合检索/摄入）
- CAP-34 的 `"local"` 保留值已废除：无本机会话，一切走 runner
- CAP-55 首发分诊模型口径经 **CAP-56** 更正（multilingual 不可用，用 typed-decisions）

## 6. 部署环境现状

| 环境 | 要点 |
|---|---|
| 本机（Windows） | 开发机；后端 :8080 / 前端 :5173 / 本机 runner（WinSW 服务 devmind-agent，C:\Users\kingdee\devmind-runner） |
| 172.20.140.143 | /apusic/dev-mind 端口 8088，PG 156/devmind |
| 172.20.140.224 | 端口 8088，与 143 同库；节点 build-224 / gpu(140.88)；CAP-56 闸门在线 |
| 8.135.37.194 | 平台自举（构建/部署/自更新），admin 密码独立，节点 4 代理必备；runner-194 Windows 服务 |
| 172.20.140.88 | GPU 节点：laya 边车（LAYA_DEVICE=cpu 与 vLLM 共卡）+ DECISION 端点 8377 |

部署细节见 [docs/guides/](../guides/)（env-migration-runbook、platform-self-deploy-194 等）与 deploy 脚本（scripts/deploy-*.sh）。
