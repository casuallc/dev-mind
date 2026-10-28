# CAP-101 验收通过联动 Jira 解决与工时登记

> 能力 ID：CAP-101 ｜ 分类：集成 ｜ 状态：定稿未实施 ｜ 日期：2026-10-28
> 决策记录：**弹窗内用户选择（默认勾选）而非全自动**——验收是人工动作，Jira 写操作
> （改状态 + 记工时）有外部副作用，留一键否决口；默认勾选保证常态零额外操作。
> 「解决」转换**不做实例级配置**，用语义词表从 Jira 动态返回的转换清单匹配
> （转换名/目标状态随实例工作流，硬编码或配置都会腐化）。Jira 步失败**不阻断验收**——
> 本地 DONE 已生效，失败仅提示，可由「Jira 操作」下拉手动补。

## 1. 目的

Jira 来源需求在平台验收通过（翻 DONE）后，关联的 Jira issue 常仍挂「开放/处理中」，
需要人肉去 Jira 点「解决」并登记工时——收尾动作割裂、容易漏。

在验收确认弹窗内一步联动：默认勾选「同步 Jira：置为解决并登记工时」（工时默认带出
AI 实际耗时），确认后本地 DONE + Jira 解决转换 + worklog 登记一次完成；用户可取消勾选
或改工时。

## 2. 功能需求

### M1 后端（devmind-integration）

- **FR-01 解决联动端点**：`POST /api/projects/{pid}/requirements/{rid}/jira/resolve`，
  body `{seconds?, comment?}`。前置解析复用 CAP-19 FR-08 的 resolve()（Jira 来源 +
  已关联 issue + 集成启用，个人写身份优先 CAP-35 FR-03）。
- **FR-02 解决转换匹配**：从 `listTransitions` 动态清单按语义词表匹配——toStatus 优先、
  name 兜底；词表「解决/Resolved/Resolve/已完成/完成/Done/已关闭/关闭/Closed/Close」，
  大小写不敏感、包含匹配，按 Jira 返回顺序取首个命中。匹配不到 → 跳过转换
  （`transitioned=false`，返回带提示），**不报错**——issue 可能已在终态或该工作流
  没有解决类转换。
- **FR-03 工时登记**：seconds 非空且 >0 时登记 worklog（复用 CAP-27 校验 1~360000 秒）；
  seconds 为空 → 只解决不登记。
- **FR-04 部分失败语义**：解决转换失败 → 抛错（远端什么都没发生）；转换成功但工时登记
  失败 → 返回 `transitioned=true` + `worklogError`，不抛。收尾统一做一次
  refreshAfterTransit（link.status + 托管字段回落），本地需求 status 绝不动。
- **FR-05 审计/事件/调用记录**：复用 recordCall（jira_transition / jira_worklog 两类各记）
  与 auditService；新增领域事件 `integration.jira.resolved`（通知文案含 issue key、
  转换名与登记工时）。

### M2 前端（features/requirements）

- **FR-06 验收弹窗联动区**：验收通过弹窗（accept 模式）在 JIRA 来源需求下追加联动区：
  Checkbox（**默认勾选**）「同步 Jira `<externalKey>`：置为解决并登记工时」+ 工时
  InputNumber（默认 = AI 实际耗时按 0.25h 取整，无耗时默认 1h，与 JiraActions.openWorklog
  同公式）+ 备注输入（可空，写入 worklog comment）。非 Jira 来源不渲染。
- **FR-07 执行序**：先 `PUT status=DONE`（409 未完结工作单元的 force 二次确认路径同样
  生效——勾选态在首次弹窗捕获，force 成功后沿用）；DONE 成功且勾选 → 调 resolve 端点；
  Jira 步失败仅 `message.warning`（文案带「Jira 操作」手动补救提示），不回滚 DONE。
- **FR-08 直接完成不联动**：direct 模式（伪需求/无需工作单元）不渲染联动区、不调 resolve。

## 3. 否决方案

- **事件驱动全自动**（监听 RequirementTerminalEvent 无弹窗直写 Jira）：剥夺人的一键
  否决口，误验收或工时口径不合时污染 Jira；且非人工验收路径（如后续自动 DONE）会被
  无差别波及。否。
- **实例级「解决转换 id」配置**：转换清单本就动态可得，配置会随工作流调整腐化；词表
  匹配覆盖主流实例（含中文工作流）。否。
- **前端串联既有 transit + logWork 两端点**：部分失败语义散在前端、转换匹配逻辑无法
  单测、force 二次确认路径要重复编排。否，收一个编排端点。

## 4. 架构与模块归属

- `devmind-integration`：`JiraIssueActionService.resolveOnAcceptance()` 编排 +
  `ProjectJiraIssueController` 新增 `POST /resolve` + DTO
  （`JiraResolveRequest` / `JiraResolveResultView`）。复用本类既有 resolve/recordCall/
  audit/refreshAfterTransit 设施，不新增模块依赖。
- `frontend/src/features/requirements`：`api.ts` 加 `resolveJiraOnAcceptance`，
  `RequirementDetailPage.confirmMarkDone` 验收弹窗加联动区。

## 5. 数据模型

无新表新列。复用 `external_links`（需求 ↔ issue 关联）与 `integrations` 凭证。

## 6. API 概要

- 新增：`POST /api/projects/{pid}/requirements/{rid}/jira/resolve`
  - body：`{ "seconds": 3600, "comment": "验收通过" }`（两者皆可空）
  - 返回：`{ transitioned, transitionName?, remoteStatus, loggedSeconds?, worklogError? }`
- 复用：`PUT /api/projects/{pid}/requirements/{rid}/status`（DONE 翻转，不变）；
  `GET .../jira/transitions`（「Jira 操作」下拉，不变）。

## 7. 安全

- 写身份沿用 CAP-35 FR-03：个人平台账号优先，缺省回落实例凭证；token 不出服务端。
- 端点走既有 JWT 鉴权 + 项目作用域路径；resolve() 已校验需求归属项目。
- 工时上限沿用 CAP-27（≤360000 秒 ≈ 100h），防弹窗误输天文数字。

## 8. 里程碑

- **M1**：FR-01~05（后端 + 单测）
- **M2**：FR-06~08（前端 + E2E）

## 9. 验收标准

- M1：`JiraIssueActionServiceTest` 新增用例——词表命中（toStatus 优先于 name）、
  无匹配跳过不报错、仅登记工时（seconds 无转换也登记）、转换成功工时失败返回
  worklogError、转换失败抛错不登记工时；`mvn -q test` 通过；
  `tests/cap101_verify.py`（mock Jira 服务 E2E）通过。
- M2：`npx tsc -b` 通过；验收弹窗在 Jira 需求下渲染联动区且默认勾选，取消勾选则
  不调 resolve；force 路径沿用勾选态；Jira 步失败有 warning 且需求已 DONE。

## 10. 风险与开放问题

| 编号 | 内容 | 口径 |
|---|---|---|
| R1 | 词表匹配误伤（某工作流里「解决」语义不同或命中多个转换取错） | 接受：取 Jira 返回顺序首个；匹配错可经「Jira 操作」手动纠正，不阻断验收 |
| R2 | 解决转换要求过屏填 resolution 字段的工作流会 400 | 缓解：Jira 错误原文透出（extractMessage），提示转手动；不自动补 resolution |
| R3 | 远程状态刷新失败（refreshAfterTransit 静默降级） | 接受：远端已生效，下轮 JQL 同步补齐（与 CAP-19 FR-08 同口径） |
| O1 | 工时是否同步写本地 worklog 台账 | 否：本能力只回写 Jira worklog（与 CAP-27 同口径），本地工时模块不动 |
| O2 | CAP-100 自动链/rollup 触发的 DONE 是否也联动 | 否：仅人工验收弹窗路径；自动路径无工时口径决策人，保持不动 |
