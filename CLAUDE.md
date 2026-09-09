# CLAUDE.md
Guidance for Claude Code when working in this repository.

**Project**: Dev-Mind 0.1.0-SNAPSHOT — 积木式研发能力平台（本地优先：Agent 会话管理 + 构建/部署/测试/发版执行器 + 需求主线）
**Stack**: 后端 Java 21 · Spring Boot 4.1.1（REST + WebSocket + JPA/H2 文件库）｜ 前端 React 19 + Vite 6 + Ant Design 5 + TS 5.7（构建产物由后端托管）

## 详细规范索引

实施对应领域任务前，先读对应文档。

| 领域 | 文档 |
|------|------|
| 能力需求（CAP-01~20）；新能力先在此立 CAP 文档 | [docs/capabilities/](docs/capabilities/README.md) |
| 实现方案（定稿） | [docs/design/](docs/design/) |
| 使用/排错指南 | [docs/guides/](docs/guides/) |
| 开发注意事项（Windows 环境 / H2 / Jackson / SSH 单点坑） | [docs/core/开发注意事项.md](docs/core/开发注意事项.md) |
| 前端内容区布局约定（列表/管理页） | [docs/core/前端内容区布局约定.md](docs/core/前端内容区布局约定.md) |

文档治理：capabilities 只放能力需求、design 放定稿方案、guides 放使用说明、core 放开发规范与踩坑记录；方案草稿与 E2E 脚本放 `tmp/`（已 gitignore，禁 commit）。

## Quick Commands（已验证，Windows Git Bash）

| 目的 | 命令 |
|------|------|
| 编译后端 | `mvn -q -DskipTests compile` |
| 后端测试 | `mvn -q test` |
| 起后端 :8080 | 先 `mvn -q install -DskipTests`，再 `mvn -pl devmind-app spring-boot:run`（**禁带 -am**，聚合器报 no main class） |
| 起 runner | `java -jar devmind-agent-runner/target/devmind-agent-runner.jar tmp/runner/agent.properties`（cwd 任意；CAP-34 起会话/问答全靠它，无 runner 创建 409） |
| 起前端 :5173 | `cd frontend && npm run dev`（/api、/ws 已代理 8080） |
| 前端类型检查 | `cd frontend && npx tsc -b` |
| 前端构建 | `cd frontend && npm run build`（产物输出到 `frontend/dist/`，不进 jar） |
| 构建分发包 | `scripts/build-dist.sh`（→ `devmind-dist/target/devmind-<version>.tar.gz`） |
| 一键起停 | `scripts\dev.ps1`（PowerShell）/ `scripts/dev.sh`（Git Bash）（后端+前端+runner 三进程；runner 配置 tmp/runner/agent.properties 缺失自动生成模板） |

健康检查 `GET /api/health`；H2 控制台 `/h2-console`（`jdbc:h2:file:./data/devmind`，sa/空）。起停与乱码等环境坑见上方「开发注意事项」。

## Module Structure

平铺 Maven 多模块：每能力一个模块，依赖图编码在各模块 pom（能力间只依赖 SPI，不依赖实现）。

| 模块 | 职责 |
|------|------|
| devmind-common | 公共契约（错误码、SPI、DomainEvent、`agent.runtime` 会话内核——状态机/事件流/CLI 协议，CAP-30 起 session/chat 共享） |
| devmind-auth | CAP-01 认证/RBAC（JWT HS256） |
| devmind-project | CAP-02 项目管理 + CAP-13 研发主线（Requirement/Design/WorkItem） |
| devmind-docs / knowledge / skill | CAP-03 文档库 / CAP-04 知识库 / Skill 管理 |
| devmind-session | CAP-05 项目开发会话（headless claude + worktree；CAP-31 多库聚合目录 + 远程 diff；CAP-34 起零执行、纯调度 runner） |
| devmind-chat | CAP-30 通用问答（无项目纯问答，个人组 /chats，复用 common 会话内核） |
| devmind-agent / agent-runner | CAP-21 节点注册/指令下发（WS）/ CAP-34 上下文包端点 ｜ runner 执行体（瘦 jar 无 Spring，拉包物化 + 起 claude） |
| devmind-notification | CAP-06 通知中心（WS 站内/bark/企微） |
| devmind-server-adapter | CAP-07 服务器适配（SSH/HTTP + 命令模板白名单 + 凭证加密） |
| devmind-execution | CAP-12 统一执行底座（StepRunner/日志 Hub/WS，**无统一 Job 表**） |
| devmind-build / deploy / test / release | CAP-08~11 执行器（各自实体与状态机，共用执行底座） |
| devmind-flow / integration / open-api | CAP-14 需求流程 / CAP-18·19 集成（GitLab/Jira）/ CAP-20 开放 API（HMAC） |
| devmind-app | 组装入口（主类 + application.yml；瘦 jar，不含前端静态） |
| devmind-dist | 分发包组装（bin/config/libs/ui/data → tar.gz，仅 `-Pdist` 触发） |
| frontend/ | `src/app`（壳/路由/当前项目设施）+ `src/features/<能力>`（自包含）+ `src/shared` |

## Architecture

- 积木式：新能力 = 一个新 Maven 模块 + `frontend/src/features/<能力>` 自包含目录 + `App.tsx` 注册路由。
- 跨模块调用走 `devmind-common` 的 SPI 接口；实现方由调用方以 `ObjectProvider<T>` 探测注入（防启动期循环依赖，禁反向依赖）。
- 数据约定：归属用外键（project_id/requirement_id/work_item_id 层级），追溯用 relations 表（稀疏边）；schema 靠 `ddl-auto=update` 自动演进，不写迁移脚本。
- 时间格式全局统一 `yyyy-MM-dd HH:mm:ss`：后端 `JacksonConfig` 一个 ObjectMapper（REST/WS 同生效），前端 `shared/utils/format.ts` 的 `fmtTime`。
- **claude 执行体 = runner 节点（CAP-34，无本机会话）**：服务端零执行（纯调度），会话/问答/one-shot 一律下发 runner 执行。路由链：`CreateSessionRequest.agentNodeId`（显式指定）→ 项目默认节点 → 平台默认节点（`agent_nodes.is_default`）→ 皆无命中 409 不回落；CAP-28 的 `"local"` 保留值已废除（传了报 400），历史本机会话（agent_node_id 为空）不可 resume。节点离线 launch 抛 409 不静默起失败进程。claude 二进制解析在 runner 侧 `agent.properties`：`claudePath` 优先，空按平台探测（Windows=`where` / Linux·macOS=`which`），探测/启动失败报 error=2 查此项；`executor=fake` 用内置假进程自测。上下文（知识注入 CLAUDE.md 块 + settings 白名单）由服务端装配 ContextPackage、launch 帧挂 manifest、runner 经 `GET /api/agent/context/{sessionId}?token=` 拉取物化（失败即 launch 失败，不降级）。

## 红线速览（MUST）

### 全局
- 本机路径/密钥禁入库 → 写 `application-local.yml`（已 gitignore）；commit 前 `git status --short` 检查。
- 源码与脚本一律 UTF-8；**含中文的 .ps1 必须存 UTF-8 with BOM**（PowerShell 5.1 无 BOM 按 GBK 解析 → 乱码 + 语法错误）。
- 构建要求 JDK 21（`mvn -version` 确认 Java version；报 "不支持发行版本 21" = JAVA_HOME 指到旧版）。

### 后端
- `@ColumnDefault` 字符串默认值必带引号 → `@ColumnDefault("'ACTIVE'")`。裸常量 H2 建表失败（已两次事故）。
- 异步触发方法（trigger/execute/rollback/run）**禁 @Transactional** → 靠 save 自身事务即时提交；否则异步线程看不到未提交行，任务卡 QUEUED。
- H2 保留字禁作列名（commit/version/…）→ 用 `commit_sha`/`release_version` 这类名。
- `@Lob` 必带 `@JdbcTypeCode`：String 用 `SqlTypes.LONGVARCHAR`、byte[] 用 `SqlTypes.LONGVARBINARY`（配 `@Column(length = 16_777_216)`）。裸 @Lob（CLOB/BLOB）在 PG 落成 oid 大对象 → auto-commit 读炸 "Large Objects may not be used in auto-commit mode"、lower() 渲染 bytea；在 MySQL 靠 length 防 tinytext。回归网：`devmind-common` 的 `LobColumnTypeTest`（钉死 PG→text/bytea、MySQL→longtext/longblob）。
- `@Lob` CLOB 禁直接 `lower()` → `lower(cast(e.contentMd as string))`（否则 Hibernate 启动期报错）。
- Jackson 3：请求 DTO 的布尔字段必用 `Boolean` 包装（null→primitive 直接抛错）；`ObjectNode` 迭代用 `properties()`；`Map.of` 禁 null 值。
- 时间序列化禁散点定制（@JsonFormat/自写格式化）→ 统一走 `JacksonConfig`。

### 前端
- 时间渲染禁 `toLocaleString`/散落 dayjs 格式化 → 一律 `fmtTime`。
- 新能力在 `features/<能力>` 内自包含（页面/api/types）；被多能力引用的平台设施放 `src/app/` 或 `src/shared/`，禁跨 feature 私引内部文件。
- 内容区（列表/管理页）布局必须遵循 [docs/core/前端内容区布局约定.md](docs/core/前端内容区布局约定.md)（Card+Segmented/extra 按钮/表格默认密度，含骨架示例）。

## Mandatory Workflow Rules

### Commit Rule (MUST)
1. 每完成一个功能/修复**单独 commit**，禁多功能打包。
2. `git status --short` 确认无误纳文件（本机路径、密钥、tmp/）→ 按功能分批 `git add` → `git commit`。
3. 格式：`<type>(<scope>): <中文描述>`。type ∈ feat/fix/refactor/docs/style；scope = 模块或能力名（如 skill、frontend、integration、open-api）。
4. **Do NOT ask for permission. Do NOT skip.**

### 提交前验证 (MUST)
- 后端改动：`mvn -q test` 通过。前端改动：`npx tsc -b` 通过。
- 端到端验证脚本写 `tmp/`（gitignored），不起服务跑一遍不算完成；起停姿势见「开发注意事项」。

### 文档规则 (MUST)
- 新能力先在 `docs/capabilities/` 立 CAP 需求文档，再动手实现。
