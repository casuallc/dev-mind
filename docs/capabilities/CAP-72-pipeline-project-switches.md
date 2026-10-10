# CAP-72 执行链自动化项目级开关（WI DONE 自动构建 / 构建成功自动部署 TEST 可按项目关闭）

> 能力 ID：CAP-72 ｜ 分类：底座 ｜ 状态：定稿（2026-10-10）
> 演进自 CAP-17（执行链编排器）与 CAP-10 FR-05（`auto_regression_on_deploy` 项目级开关先例）。

## 1. 目的

CAP-17 的执行链是**恒开**的：WI 翻 DONE 必触发自动构建（项目配了构建步骤即触发）、
构建成功必自动部署 TEST 环境。并非所有项目都想要这套自动化——手工节奏的项目、
只拿平台做需求管理的项目，会被不想要的后台构建/部署打扰。

本能力给执行链的自动环节加**项目级开关**，默认关闭（显式开启才自动跑）：

| 环节 | 开关 | 默认 |
|---|---|---|
| WI → DONE 自动构建 | `projects.auto_build_on_wi_done` | 关 |
| 构建成功 → 自动部署 TEST | `projects.auto_deploy_test_on_build` | 关 |
| 部署成功 → 自动回归测试 | `projects.auto_regression_on_deploy`（CAP-10 已有） | 关 |

不变量（CAP-17 语义不松动）：**生产部署与发版永远人工触发**，不在开关范围内；
开关只管「安全区」（构建 + TEST 环境部署 + 回归）是否自动。

粒度决策：只做**项目级**。用户级开关（按 WI owner/创建者）本期不做——
执行链是项目基础设施行为，开关归项目设置，与 `auto_regression_on_deploy` 同处。

## 2. 功能需求

### FR-01 项目级开关字段

`projects` 表加两列（Boolean 可空，**不加 @ColumnDefault**——MySQL bit 列默认值建列失败
有事故先例；存量行 null 由 getter 兜底 `false`，即默认关闭）：

- `auto_build_on_wi_done`：WI 翻 DONE 后是否自动触发构建。
- `auto_deploy_test_on_build`：构建成功后是否自动部署 TEST 环境。

读写走 `ProjectRequest`/`ProjectView`（语义同 `autoRegressionOnDeploy`：
创建缺省 false；更新不传 = 保持不变）。

### FR-02 编排器闸门

`PipelineOrchestrator` 两个入口加闸门，关闭时**静默跳过**（log.info，不发降级通知——
是用户显式关的，不是前置不齐）：

- `tryTriggerBuild`（WI DONE 事件与 build.completed 补扫共用此入口，一处闸门两路都管住）；
- `autoDeployTestEnv`。

`TestRunService` 的部署成功自动回归已有 `auto_regression_on_deploy` 闸门，不动。

### FR-03 项目表单开关

项目新建/编辑抽屉（`ProjectFormDrawer`）在「部署成功后自动回归」旁加两个开关：
「WI 完成后自动构建」「构建成功后自动部署测试环境」，缺省关。

## 3. 非目标

- 用户级/用户×项目矩阵开关（本期不做，见 §1 粒度决策）。
- 生产部署、发版的自动化（CAP-17 红线：永远人工）。
- 存量项目行为迁移说明：存量行新列为 null = 关，**行为从恒开变为关闭**——
  想要旧行为的项目到项目设置里显式打开（升级公告告知）。

## 4. 验收

1. 新项目两开关默认关：WI 翻 DONE 不产生构建记录；构建成功不自动部署。
2. 打开「自动构建」：WI（DEVELOPMENT/TEST 类型）翻 DONE → 自动建构建单；关闭则不动。
3. 打开「自动部署 TEST」：构建成功 → 自动建 TEST 部署单；关闭则不动。
4. 补扫路径（构建并发满后 build.completed 重扫）同样受闸门管住。
5. 更新项目不传开关字段 = 保持原值（不被人误关）。
