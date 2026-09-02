# Dev-Mind · 积木式研发能力平台

本地优先的个人研发工作台：起/管/收 headless Claude Code 会话（worktree 隔离、实时输出流、卡住通知人、可远程回复），并围绕「需求 → 设计 → 工作单元」主线组合构建/部署/测试/发版执行器与知识/文档沉淀。

> 能力需求见 `docs/capabilities/`（CAP-01~20），实现方案见 `docs/design/`，使用指南见 `docs/guides/`。

## 技术栈

- 后端：Spring Boot 4.1.1（Java 21）· REST + WebSocket · JPA + H2 文件库（公司 Nexus 暂无 4.2.x 正式版，先用 4.1.1 稳定版）
- 前端：React 19 + Vite 6 + Ant Design 5（开发走 Vite 热更新；生产由后端托管分发包里的外置 web/ 目录）

## 目录（积木式结构）

```
pom.xml            父 POM（聚合器，模块依赖图见各模块 pom）
devmind-common/    公共契约（错误码、SPI、DomainEvent）
devmind-auth/      CAP-01 认证/RBAC（JWT HS256）
devmind-project/   CAP-02 项目管理 + CAP-13 研发主线（Requirement/Design/WorkItem）
devmind-docs/      CAP-03 文档库（git 版本化）
devmind-knowledge/ CAP-04 知识库（经验沉淀与注入）
devmind-skill/     Skill 管理（附件/导出）
devmind-session/   CAP-05 Agent 会话（headless claude 子进程 + worktree）
devmind-notification/ CAP-06 通知中心（WS 站内/bark/企微）
devmind-server-adapter/ CAP-07 服务器适配（SSH/HTTP + 命令模板白名单 + 凭证加密）
devmind-execution/ CAP-12 统一执行底座（StepRunner/日志 Hub/WS）
devmind-build/     CAP-08 构建执行器
devmind-deploy/    CAP-09 部署执行器
devmind-test/      CAP-10 测试执行器
devmind-release/   CAP-11 发版执行器
devmind-artifact/  产物登记（ArtifactStorage SPI）
devmind-flow/      CAP-14 需求流程
devmind-integration/ CAP-18/19 平台集成（GitLab/Jira 同步）
devmind-open-api/  CAP-20 开放 API（HMAC 签名）
devmind-app/       组装入口（主类 + application.yml，瘦 jar）
devmind-dist/      分发包组装（bin/config/libs/web/data → tar.gz）
frontend/          React 前端（app 壳 + features/<能力> + shared）
docs/              需求文档(capabilities) + 实现方案(design) + 指南(guides) + 开发规范(core)
```

- **后端**：每个能力一个 Maven 模块，能力间只依赖 SPI（devmind-common），不依赖实现；`devmind-app` 依赖所有模块组装。
- **前端**：`src/app`（壳：路由/布局/当前项目设施）、`src/features/<能力>`（自包含：页面/api/types）、`src/shared`（跨能力共享）。新能力在 `src/app/App.tsx` 注册路由即完成组装。

## 本地开发启动

### 一键起停（推荐）

```powershell
scripts\dev.ps1            # Windows PowerShell：构建（跳测试）后起后端 :8080 + 前端 :5173
scripts\dev.ps1 -Executor fake -SkipBuild   # 假进程执行器 / 跳过后端构建
```

```bash
scripts/dev.sh             # Git Bash，参数：fake|claude、--skip-build
```

### 手动启动

```bash
# 后端（先 install 再跑，禁带 -am——聚合器报 no main class）
mvn -q install -DskipTests
mvn -pl devmind-app spring-boot:run
# 后端: http://localhost:8080   health: http://localhost:8080/api/health
# H2 控制台: http://localhost:8080/h2-console (jdbc:h2:file:./data/devmind, sa/空)

# 前端（开发热更新，/api 与 /ws 已代理到 8080）
cd frontend && npm install && npm run dev
# 前端: http://localhost:5173
```

首次登录：`admin / admin123`（`devmind.auth.admin-password` 可改，请登录后立即修改）。

## 分发包（生产部署）

```bash
scripts/build-dist.sh        # 前端 build + maven 组装（-Pdist），产出 devmind-dist/target/devmind-<version>.tar.gz
tar xzf devmind-dist/target/devmind-<version>.tar.gz
cd devmind-<version>
bin/dev-mind start           # 后台启动；status/stop/restart/run(前台)/install(装为 systemd 服务)
```

包布局：

| 目录 | 内容 |
|------|------|
| `bin/` | `dev-mind` 服务脚本（JDK 解析顺序 `APUSIC_JAVA_HOME` > `JAVA_HOME` > PATH，要求 21+） |
| `config/` | 精简外置配置（只列常用项，未列出的回落 jar 内置默认值）；本机覆盖放 `application-local.yml` 自动加载 |
| `libs/` | devmind-app 瘦 jar + 全部依赖散 jar（不打 fat jar，便于单 jar 替换更新） |
| `web/` | 前端构建产物（外置托管，替换即更新前端） |
| `data/` | H2 库、自动生成的密钥、pid 文件（运行时生成；删除=重置实例） |
| `logs/` | 运行日志（`dev-mind.log`） |

改 `config/application.yml` 的 `server.port` 后，`bin/dev-mind` 的健康探测/status 自动跟随（也可用 `DEVMIND_PORT` 环境变量指定）。JVM 参数默认 `-Xms512m -Xmx1024m -XX:+UseG1GC`，用 `JAVA_OPTS` 覆盖；业务参数用 `EXTRA_OPTS` 追加（如 `EXTRA_OPTS="--devmind.session.executor=fake" bin/dev-mind start`）。

## 配置

开发态配置在 `devmind-app/src/main/resources/application.yml`；本机路径/密钥写 `application-local.yml`（已 gitignore，profile 默认 local 自动加载）。常用项：

- `devmind.session.executor`：`claude`（真实 Claude Code）/ `fake`（内置假进程，自测）
- `devmind.session.claude-path`：claude 可执行文件路径（默认探测全局命令）
- `devmind.project.default-path`：本机默认项目仓库路径（写 application-local.yml）
- `devmind.docs.repo-path` / `devmind.knowledge.repo-path`：文档/知识 git 仓库路径（写 application-local.yml）
