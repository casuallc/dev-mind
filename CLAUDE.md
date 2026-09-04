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

文档治理：capabilities 只放能力需求、design 放定稿方案、guides 放使用说明、core 放开发规范与踩坑记录；方案草稿与 E2E 脚本放 `tmp/`（已 gitignore，禁 commit）。

## Quick Commands（已验证，Windows Git Bash）

| 目的 | 命令 |
|------|------|
| 编译后端 | `mvn -q -DskipTests compile` |
| 后端测试 | `mvn -q test` |
| 起后端 :8080 | 先 `mvn -q install -DskipTests`，再 `mvn -pl devmind-app spring-boot:run`（**禁带 -am**，聚合器报 no main class） |
| 起前端 :5173 | `cd frontend && npm run dev`（/api、/ws 已代理 8080） |
| 前端类型检查 | `cd frontend && npx tsc -b` |
| 前端构建 | `cd frontend && npm run build`（产物输出到 `frontend/dist/`，不进 jar） |
| 构建分发包 | `scripts/build-dist.sh`（→ `devmind-dist/target/devmind-<version>.tar.gz`） |
| 一键起停 | `scripts\dev.ps1`（PowerShell）/ `scripts/dev.sh`（Git Bash） |

健康检查 `GET /api/health`；H2 控制台 `/h2-console`（`jdbc:h2:file:./data/devmind`，sa/空）。起停与乱码等环境坑见上方「开发注意事项」。

## Module Structure

平铺 Maven 多模块：每能力一个模块，依赖图编码在各模块 pom（能力间只依赖 SPI，不依赖实现）。

| 模块 | 职责 |
|------|------|
| devmind-common | 公共契约（错误码、SPI、DomainEvent） |
| devmind-auth | CAP-01 认证/RBAC（JWT HS256） |
| devmind-project | CAP-02 项目管理 + CAP-13 研发主线（Requirement/Design/WorkItem） |
| devmind-docs / knowledge / skill | CAP-03 文档库 / CAP-04 知识库 / Skill 管理 |
| devmind-session | CAP-05 Agent 会话（headless claude 子进程 + worktree） |
| devmind-notification | CAP-06 通知中心（WS 站内/bark/企微） |
| devmind-server-adapter | CAP-07 服务器适配（SSH/HTTP + 命令模板白名单 + 凭证加密） |
| devmind-execution | CAP-12 统一执行底座（StepRunner/日志 Hub/WS，**无统一 Job 表**） |
| devmind-build / deploy / test / release | CAP-08~11 执行器（各自实体与状态机，共用执行底座） |
| devmind-flow / integration / open-api | CAP-14 需求流程 / CAP-18·19 集成（GitLab/Jira）/ CAP-20 开放 API（HMAC） |
| devmind-app | 组装入口（主类 + application.yml；瘦 jar，不含前端静态） |
| devmind-dist | 分发包组装（bin/config/libs/web/data → tar.gz，仅 `-Pdist` 触发） |
| frontend/ | `src/app`（壳/路由/当前项目设施）+ `src/features/<能力>`（自包含）+ `src/shared` |

## Architecture

- 积木式：新能力 = 一个新 Maven 模块 + `frontend/src/features/<能力>` 自包含目录 + `App.tsx` 注册路由。
- 跨模块调用走 `devmind-common` 的 SPI 接口；实现方由调用方以 `ObjectProvider<T>` 探测注入（防启动期循环依赖，禁反向依赖）。
- 数据约定：归属用外键（project_id/requirement_id/work_item_id 层级），追溯用 relations 表（稀疏边）；schema 靠 `ddl-auto=update` 自动演进，不写迁移脚本。
- 时间格式全局统一 `yyyy-MM-dd HH:mm:ss`：后端 `JacksonConfig` 一个 ObjectMapper（REST/WS 同生效），前端 `shared/utils/format.ts` 的 `fmtTime`。

## 红线速览（MUST）

### 全局
- 本机路径/密钥禁入库 → 写 `application-local.yml`（已 gitignore）；commit 前 `git status --short` 检查。
- 源码与脚本一律 UTF-8；**含中文的 .ps1 必须存 UTF-8 with BOM**（PowerShell 5.1 无 BOM 按 GBK 解析 → 乱码 + 语法错误）。
- 构建要求 JDK 21（`mvn -version` 确认 Java version；报 "不支持发行版本 21" = JAVA_HOME 指到旧版）。

### 后端
- `@ColumnDefault` 字符串默认值必带引号 → `@ColumnDefault("'ACTIVE'")`。裸常量 H2 建表失败（已两次事故）。
- 异步触发方法（trigger/execute/rollback/run）**禁 @Transactional** → 靠 save 自身事务即时提交；否则异步线程看不到未提交行，任务卡 QUEUED。
- H2 保留字禁作列名（commit/version/…）→ 用 `commit_sha`/`release_version` 这类名。
- `@Lob` CLOB 禁直接 `lower()` → `lower(cast(e.contentMd as string))`（否则 Hibernate 启动期报错）。
- Jackson 3：请求 DTO 的布尔字段必用 `Boolean` 包装（null→primitive 直接抛错）；`ObjectNode` 迭代用 `properties()`；`Map.of` 禁 null 值。
- 时间序列化禁散点定制（@JsonFormat/自写格式化）→ 统一走 `JacksonConfig`。

### 前端
- 时间渲染禁 `toLocaleString`/散落 dayjs 格式化 → 一律 `fmtTime`。
- 新能力在 `features/<能力>` 内自包含（页面/api/types）；被多能力引用的平台设施放 `src/app/` 或 `src/shared/`，禁跨 feature 私引内部文件。
- 内容区（列表/管理页）布局约定（自包含规则，勿以某现有页面为参照）：
  - 外壳：`Card` 默认尺寸（禁 `size="small"`），`title` 放页面名；有多视图时 title 里加 `Segmented` 切换（禁 Card 内套 `Tabs`）。
  - 操作按钮一律放 Card `extra`：默认大小、图标+文字（`刷新` / `type="primary"` 的 `新建xx`）。
  - 页面说明文字：body 顶部 `Typography.Paragraph type="secondary"`，不进工具栏。
  - 表格：默认密度（禁 `size="small"`），`pagination={false}`（服务端真分页除外）；行内操作用小号普通按钮（查看/管理），`danger` 仅用于删除。
  - 骨架：

    ```tsx
    <Card
      title={<Space size={12}><span>页面名</span><Segmented value={view} onChange={setView} options={[...]} /></Space>}
      extra={<Space>
        <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>新建xx</Button>
      </Space>}
    >
      <Typography.Paragraph type="secondary">一句话说明这个页面管什么。</Typography.Paragraph>
      <Table rowKey="id" loading={loading} columns={columns} dataSource={rows} pagination={false} />
    </Card>
    ```

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
