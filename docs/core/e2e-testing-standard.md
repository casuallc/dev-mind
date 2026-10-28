# E2E 测试规范 v1

适用：所有新增/修改的端到端测试，以及平台脚本套件。统一入口与平台报告契约即日起执行；历史脚本按清单逐项迁移，不要求一次重写。

## 入口与分层

| 层 | 内容 | 执行方式 |
|---|---|---|
| 单元/模块测试 | 业务逻辑、边界、模拟依赖 | `mvn test`，留在各模块 |
| smoke | 共享实例只读健康与关键 API；认证之外不得写业务数据 | `python tests/e2e/run.py --suite smoke --base <API地址>` |
| regression | 独立 H2、唯一工作目录、可清理的数据写入 | `python tests/e2e/run.py --suite regression --isolated` |
| UI / 外部集成 / 运维 | 浏览器、GPU、真实 Jira、升级、服务安装等专属前置 | 仍按历史清单手动运行，审核后另建套件，不能混入 smoke |

先执行 `mvn -q install -DskipTests` 构建当前代码，隔离入口通过 Maven 启动应用，JDK 21 / Python 3.10+ / Git / Maven 必备。无 Python 第三方依赖。入口可从任意目录执行；平台用 `bash scripts/e2e.sh` 调用同一入口。

## 用例与代码约束（MUST）

1. 正式用例写在 `tests/e2e/cases.py`，按业务域组织 TestCase；增长后拆模块时同步修改发现规则。方法名表达行为，登记 `catalog.json` 中稳定 id、suite、owner（负责模块）、CAP。CAP 编号仅做追溯，不再作为新脚本命名主轴。
2. 用例独立、可重复；测试数据名称带 `e2e-`，路径带本次唯一 run id；不得依赖上一用例或数据库第一条数据。断言 HTTP 状态和业务字段；禁止仅打印 FAIL 后退出 0。不得用 `--keep` 或条件跳过掩盖正式回归失败。
3. 写操作只允许 `--isolated`。共享实例禁止建删项目、修改平台默认值、轮换密钥或升级节点。隔离实例只读 classpath 默认配置，显式激活 e2e，独立 H2/仓库/附件/工作树，关闭工时自动任务，生成随机测试密码和 JWT secret。
4. 创建资源成功后立即 `addCleanup`，创建进程后立即进入 `try/finally`；清理失败必须失败。只停止本次创建的进程树，端口被占即失败，禁止按端口杀已有服务。
5. 网络请求必须设超时（当前统一 15 秒）；异步状态使用 deadline 轮询，不用固定长 sleep 猜结果。实例启动上限 180 秒；平台整套上限 300 秒。失败不自动重试掩盖不稳定性。
6. `E2E_BASE` 是带 `/api` 的 API 根地址，必须显式提供；`E2E_TOKEN` 或 `E2E_USER` + `E2E_PASSWORD` 由环境注入。共享环境无默认密码。不硬编码凭据/本机路径；自签证书仅显式 `--insecure` 放行。
7. 新 UI 用例必须走真实浏览器与用户操作路径，留失败截图和控制台日志；API 通过不能代替 UI 验收。UI 专用环境与用例迁移须单独审核，当前 smoke 不声称覆盖 UI 或全部业务。

## 报告与门禁

- `tmp/e2e/<run-id>/` 保存隔离数据与应用日志；`tmp/e2e/junit.xml` 是固定平台回传文件，每轮覆盖，不能提交。默认保留运行证据，定期按保留期清理；不要把报告或日志放进 `tests/`。
- 每个正式用例单独一个 JUnit testcase。测试失败、清理失败、配置错误、连接失败、启动失败都返回非零；基础设施故障写为 `e2e.infrastructure` error，不允许空跑绿灯。
- 每次提交 E2E 修改至少运行 `python tests/e2e/run.py --check`、`python -m unittest discover -s tests/e2e -p "test_*.py"` 及实际服务测试。统一入口的实现测试不属于业务 E2E，不入 catalog。
- 变更所涉业务必须增加对应回归，先隔离验证；发布后 smoke 验证实际部署。smoke 绿灯仅代表已登记检查通过，不能等价于“全量 E2E 通过”。
- 评审检查 catalog、隔离/清理、断言/退出码、报告、实际执行证据。新增未登记脚本会令 `--check` 失败。仓库 `AGENTS.md` 指向本规范；开发者/Agent 都遵循同一约定。

## 历史迁移

`tests/e2e/legacy.json` 登记 tests 顶层所有历史脚本，`legacy`=待审核，`helper`=辅助探针，`manual-only`=不可自动化的运维操作。保留原位置，避免破坏已有调用。

迁移时逐一核对端点与认证、独立前置、数据清理、失败退出码、硬编码路径/凭据；提炼断言到正式 TestCase，并更新清单 reason 为替代用例 id。优先迁移项目/需求/会话/测试套件关键链路，再迁移 UI 与外部依赖。未经审核不得直接批量执行 124 个历史脚本。
