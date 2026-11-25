# CAP-116 规划阶段可选（开启 AI 规划时选择是否走需求分析 / 方案设计）

> 能力 ID：CAP-116 ｜ 分类：需求流程 ｜ 状态：**已完成** ｜ 日期：2026-11-25
> 决策记录：跳过范围**不落需求实体**（不写 analysisSkipped/designSkipped 存量标记，
> 避免一次试跑污染需求属性、也避免与 CAP-38 存量语义混淆），而是随当次规划会话的
> taskSpec 编码（`[plan:skip ...]` 行），产出分流时从 taskSpec 解析豁免缺失判定——
> taskSpec 本就是 FlowOutputContract 声明的 agent ↔ 流程层唯一耦合点，会话行即事实源。

## 1. 目的

「开启 AI 规划」（CAP-52）固定产出 分析 + 方案 + 工作单元 三件套。但不少小需求
（改文案、加配置、小缺陷修复）不需要正式的分析与方案文档，直接拆工作单元进开发
即可。本能力在「开启 AI 规划 / 重新规划」提交对话框提供两个开关（**默认都开**），
让用户按次选择本次规划是否走需求分析、方案设计环节。

## 2. 功能需求

- **FR-01 提交对话框开关**：需求详情页「开启 AI 规划 / 重新规划」对话框新增
  「需求分析」「方案设计」两个开关，默认开启；关闭后本次规划会话不再要求产出
  对应文件（analysis.md / design.md），只做剩余环节并照常自动接开发会话。
- **FR-02 请求体透传**：`POST /api/projects/{pid}/requirements/{rid}/flow/plan`
  请求体新增可选 `withAnalysis` / `withDesign`（Boolean 包装，缺省/null = 开）。
  与存量实体标记取「或」：实体已标记跳过 或 本次关开关，都收窄当次产出范围。
- **FR-03 产出范围随会话编码**：planSpec 在收窄时于 taskSpec 第二行写
  `[plan:skip analysis design]`（仅列跳过的环节）；产出分流（handlePlanOutput）
  判定「缺失文件」时同时豁免实体标记与 taskSpec 声明的跳过环节，不误报
  `flow.plan.partial`。
- **FR-04 兼容**：不传新字段（飞书 /plan、FlowTools、旧前端）行为与现状完全一致；
  存量实体的 analysisSkipped/designSkipped 语义不变。

## 3. 否决方案

- **把开关直接写进需求实体跳过标记**：一次试跑就改写需求属性，重新规划想恢复还得
  再改回去；且分流豁免读的是实体，并发/重跑语义混乱。否。
- **新增独立「直接开发」入口**：CAP-52 刚收敛为单入口，再开一个按钮违背入口收敛
  原则；开关即足够。否。

## 4. 架构与模块归属

- 后端：`devmind-flow`——`FlowPlanRequest`（新字段）、`RequirementFlowService.startPlan`
  （四参重载）、`FlowOutputContract.planSpec`（skip 行编码 + `planSkips` 解析）。
- 前端：`features/requirements`——规划对话框加两个 Switch，`flowPlan` api 改 options 传参。

## 5. 数据模型

无表结构变更。跳过范围只存在于会话行 taskSpec 文本内。

## 6. API 概要

`POST /api/projects/{pid}/requirements/{rid}/flow/plan`
body：`{ "userSuggestion": "...", "withAnalysis": false, "withDesign": false }`（全字段可选）。

## 7. 安全

无新权限面；Boolean 用包装类型（Jackson 3 红线）。

## 8. 里程碑

- M1：后端（请求体 + startPlan + spec 编码/解析 + 分流豁免）与单测。
- M2：前端对话框开关与 api 透传，`npx tsc -b` 通过。

## 9. 验收标准

1. 默认打开对话框直接提交：taskSpec 与现状一致（含三份产出要求）。
2. 关掉「方案设计」提交：taskSpec 含 `[plan:skip design]`、不要求 design.md；
   会话完成只登记分析 + 工作单元，不发 partial 通知，自动起开发会话。
3. 两个都关：只拆工作单元，编号从 1 起，产出后直接进开发。
4. 不传新字段的旧调用方（两参 startPlan、无 body POST）行为字节级不变。

## 10. 风险与开放问题

- 跳过方案后工作单元 spec 的质量完全依赖模型在单会话内的推理（无方案文档可挂
  WI.designId 追溯边）——属用户明示选择的取舍；FlowTools / 飞书入口后续如需同能力，
  在各自确认单上加参数即可（未做）。
