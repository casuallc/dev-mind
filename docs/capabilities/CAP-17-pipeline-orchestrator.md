# CAP-17 执行链编排（Pipeline Orchestrator）

> 能力 ID：CAP-17 ｜ 分类：流程层 ｜ 状态：草案 ｜ 日期：2026-09-01

## 1. 目的

在 CAP-15（WI 派发自动化）之后接续执行链：人验收工作单元（置 DONE）后，
**构建 → 部署测试环境 → 回归测试** 自动推进，不用再人去各 Tab 手工点。语义边界沿用 CAP-15：

> **执行自动化，高风险决策归人。** 生产部署与发版永远由人触发（既有 UI/确认门），
> 编排器只负责"WI DONE → 构建 → 测试环境部署"这段安全区。

**分支假设**：WI 代码在 worktree 分支开发，人验收前先人工合并回主干；
自动构建走主库 HEAD（merge 自动化属后续能力）。

## 2. 功能需求

- **FR-01 DONE 触发构建**：订阅 `workitem.status.changed`，DONE 且类型为 DEVELOPMENT/TEST 的 WI
  自动触发构建（`BuildService.trigger`，branch 空=主库 HEAD）。幂等：该 WI 已有构建记录则不重复触发
  （返工重建由人工触发）。DESIGN/DOCUMENT/REVIEW 型不触发。
- **FR-02 构建成功自动部署测试环境**：订阅 `build.completed`，success 且前置齐备
  （artifactRef 非空、项目存在 TEST 类环境、deploy-config 有步骤）时自动创建并执行部署
  （confirmRequired=false）。前置不齐 → P1 降级通知「构建完成，请人工部署验证」。
- **FR-3 并发补扫**：构建并发满（409）时本轮跳过；每次 `build.completed` 后补扫该项目
  「DONE 且无构建记录」的 WI 补触发，不排队不丢失。
- **FR-04 部署确认门通知动作**：confirmRequired 部署创建时发 P0 通知（actions=[confirm, view]），
  通知中心一键 confirm（确认+执行）/rollback。生产部署由此实现"人工门禁但远程可操作"。
- **FR-05 链路完成通知**：测试环境部署成功 → P1「WI-x 构建+部署链路完成，可验收/发版」。
  回归测试由项目 `autoRegressionOnDeploy` 开关触发（test 模块既有能力，编排层不重复触发）。
- **FR-06 失败降级**：构建/部署失败走各执行器既有 P0 通知，编排器不做自动重试/补偿
  （部署侧已有自动回滚兜底）。

## 3. 插件化接口

- 就绪/幂等判定为纯函数 `PipelineEligibility`（buildable / pendingBuilds），可单测、可替换。
- 编排规则集中在 `PipelineOrchestrator` 事件订阅，执行器模块不感知编排层（只新增查询/通知动作）。

## 4. 依赖关系

- 依赖：CAP-13（workitem.status.changed）、CAP-08（触发构建/build.completed）、
  CAP-09（两段式部署/确认门/TEST 环境解析）、CAP-06（通知与动作 SPI）、devmind-common 事件总线。
- 被依赖：无（流程层叶子）。测试回归复用 CAP-10 既有 deploy→test 开关，不新增依赖。

## 5. 数据模型

**不新增表**。复用 builds.work_item_id（幂等查询）、environments（TEST 选取）、
deploy_configs（步骤）、deployments.confirm_required（门禁）。

## 6. API 概要

无新增业务端点。通知动作复用 `POST /api/notifications/{id}/action`（新增 DEPLOYMENT 域 handler）。

## 7. 验收标准

- DEVELOPMENT WI 置 DONE → 自动构建（有构建步骤时）；重复 DONE 翻转不重复构建；
- 构建 SUCCESS 且有产物+TEST 环境+部署步骤 → 自动部署执行；缺任一前置 → P1 降级通知；
- 构建并发满时第二个 WI 跳过，首个构建完成后自动补触发；
- confirmRequired 部署创建 → P0 通知带 confirm 动作 → 动作执行后部署进入执行；
- 生产部署/发版无任何自动触发路径。

## 8. MVP 范围（暂不做）

test.completed / release.completed 事件；WI 代码 merge 自动化；pipeline 每项目配置 UI；
生产环境自动部署；审批会签；安全扫描节点（蓝图 Security Check）。
