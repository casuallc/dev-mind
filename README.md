# Dev-Mind · Agent 会话管理平台

从"多开 PowerShell 跑多个 agent"中解脱出来的浏览器工作台。起/管/收 headless Claude Code 会话，worktree 隔离，实时输出流，卡住时通知人、可远程回复。

> 当前阶段：**M0 骨架**（前后端联通）。规划见 `docs/design/CAP-05-agent-session-impl.md`，能力需求见 `docs/capabilities/`。

## 技术栈

- 后端：Spring Boot 4.1.1（Java 21）· REST + WebSocket · H2 文件库（公司 Nexus 暂无 4.2.x 正式版，先用 4.1.1 稳定版）
- 前端：React 19 + Vite 6 + Ant Design 5（构建产物由后端静态托管，前后端一体）

## 目录（积木式结构）

```
pom.xml          父 POM（聚合器，模块依赖图见各模块 pom）
devmind-common/  公共契约（错误码、SPI 定义）
devmind-auth/    CAP-01 用户认证
devmind-project/ CAP-02 项目管理
devmind-docs/    CAP-03 文档管理
devmind-knowledge/ CAP-04 知识库
devmind-session/ CAP-05 Agent 会话管理
devmind-notification/ CAP-06 通知中心
devmind-server-adapter/ CAP-07 服务器适配器
devmind-build/   CAP-08 构建执行器
devmind-deploy/  CAP-09 部署执行器
devmind-test/    CAP-10 测试执行器
devmind-release/ CAP-11 发版执行器
devmind-app/     组装入口（主类 + application.yml + 前端静态）
frontend/        React 前端（app 壳 + features/<能力> + shared）
docs/            需求文档(capabilities) + 实现方案(design)
```

- **后端**：每个能力一个 Maven 模块，依赖严格按 CAP 图；`devmind-app` 依赖所有模块组装成一个可运行 jar。
- **前端**：`src/app`（壳：路由/布局）、`src/features/<能力>`（自包含：页面/api/types/hooks）、`src/shared`（跨能力共享）。新能力在 `src/app/App.tsx` 注册路由即完成组装。

## 本地启动

### 后端（终端 1，从仓库根）

```bash
mvn -pl devmind-app -am spring-boot:run
# 后端: http://localhost:8080   health: http://localhost:8080/api/health
# H2 控制台: http://localhost:8080/h2-console (jdbc:h2:file:./data/devmind, sa/空)
```

### 前端（终端 2，开发热更新）

```bash
cd frontend
npm install
npm run dev
# 前端: http://localhost:5173  （/api 与 /ws 已代理到 8080）
```

### 生产构建（前端产物进 devmind-app jar）

```bash
cd frontend && npm run build   # 产物输出到 devmind-app/src/main/resources/static
cd .. && mvn -q -DskipTests package
java -jar devmind-app/target/devmind-app-0.1.0-SNAPSHOT.jar
```

## 里程碑

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 前后端骨架联通 | ✅ 已完成 |
| M1 | 进程生命周期（假进程验证状态机） | ⏳ |
| M2 | 接入 claude（stream-json spike） | ⏳ |
| M3 | WebSocket 实时流 + 回放 | ⏳ |
| M4 | 输入注入 + 授权处理 | ⏳ |
| M5 | worktree + 知识注入 + 收尾加固 | ⏳ |
| M6 | 打磨：历史/筛选/重启恢复 | ⏳ |

## 配置

后端配置 `devmind.*` 在 `backend/src/main/resources/application.yml`：

- `devmind.session.claude-path`：claude 可执行文件路径（默认探测全局命令）
- `devmind.session.permission-mode`：默认 `acceptEdits`（放手模式）
- `devmind.project.default-path`：MVP 阶段填一个本地 git 仓库路径即可起会话
- `devmind.knowledge.repo-path`：knowledge-repo 本地路径（知识注入，可后配）
