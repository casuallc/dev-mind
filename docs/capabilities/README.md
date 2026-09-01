# Dev-Mind 基础能力清单（积木式组装）

> 版本：v0.1 ｜ 日期：2026-08-30
>
> 本文档定义平台的第一批**基础能力**（Building Blocks）。每个能力是独立可用的插件化模块，对外暴露稳定接口，后续由**流程层**（需求流程引擎、任务编排 Orchestrator 等，另行设计）像积木一样组装成全自动流水线。

## 通用技术基线

- **单体仓库，前后端一体**：一个项目 `dev-mind/`。后端 Spring Boot 4.2.x（REST + WebSocket + 静态托管前端构建产物）；前端 React + Ant Design 5（Vite 构建后由后端托管，浏览器访问）。
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
- CAP-18 平台集成依赖 CAP-01/02/13：出站单向为主——WI 分支 push、建 MR、发版后 push tag + 建平台 Release（CAP-11 可选钩子）；入站 webhook 与 Jira/GitHub 属后续阶段。

## 组装方式（后续流程层）

流程层（如「需求 → 方案 → 开发 → 构建 → 部署 → 验证 → 发版」）将通过编排这些能力实现：

- **需求流程引擎**：需求状态机 + 每阶段"产出就绪 → 等确认"语义，调用各执行器；
- **任务编排 Orchestrator**：方案（Design）→ Work Item DAG（CAP-13 depends_on 边）→ 并发调度 CAP-05 会话，门禁把关；
- **需求对话打磨**：AI 对话面板 + CAP-03 文档生成。

这些属流程层设计，不在此批基础能力文档内，待基础能力稳定后另行编写。

## 文档模板说明

每份能力文档统一结构：目的 / 功能需求（FR-xx 编号）/ 插件化接口 / 依赖关系 / 数据模型 / API 概要 / 验收标准 / MVP 范围。
