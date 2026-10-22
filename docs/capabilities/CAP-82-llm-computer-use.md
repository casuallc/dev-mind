# CAP-82 LLM Computer-Use 循环（模型驱动的 GUI 自动化）

> 能力 ID：CAP-82 ｜ 分类：Agent 域 ｜ 状态：设计定稿 ｜ 日期：2026-10-22
> 依赖 [CAP-81](CAP-81-computer-use.md)（computer 原语：截屏/键鼠/剪贴板 + 观看会话 +
> 动作审计）、CAP-48（模型端点）、CAP-71（agent_audit_logs）。
> 决策记录：
> 1. **LLM computer-use 循环在服务端编排**，不进 runner、也不复用 CAP-80 types
>    executor——循环状态（步序列/模型上下文/人工接管）是平台数据，runner 保持瘦执行体；
>    每步就是一次普通 computer_req 下发（复用 CAP-81 原语）。
> 2. **视觉模型接入是前置工作量**：平台模型调用层（`OpenAiCompatChat`）目前只发纯文本，
>    模型端点 kind 只有 EMBEDDING/CHAT/DECISION 且无「视觉能力」标记。本能力的「截屏 →
>    视觉模型」链路需先扩展 common 多模态入参（OpenAI 兼容 image_url/base64 content
>    block）并为 CHAT 端点加视觉能力位，否则不可落地。
> 3. **dryRun 试运行档 + 人工接管**：模型动作可先只记录不下发用于评估；任务运行中
>    随时可停止/接管，控制权与过程全程可视、可审计。

## 1. 目的

CAP-81 交付了「看屏幕 + 投键鼠」的人控远程桌面原语。本能力在此之上叠加视觉模型的
决策循环，把「控制多台电脑」从人工远程桌面升级为可被模型驱动的自动化：给一个节点 +
一段任务描述，模型循环操作 GUI 直至完成/失败/步数耗尽，全程可视化、可人工接管。

**门控前提**：①平台模型接入需先支持多模态（视觉）——当前 common 模型层只发纯文本；
②需出现明确的 GUI 自动化驱动场景（ERP 客户端/浏览器内部系统等真实目标）。两者任一
未满足，本能力只保留设计不落地。

## 2. 功能需求

### M1 任务实体与编排

- **FR-01 任务实体与编排**：`computer_use_tasks`（id/nodeId/prompt/status=
  RUNNING|DONE|FAILED|STOPPED|TAKEN_OVER/maxSteps/maxMinutes/stepCount/模型配置引用/
  创建人/起止时间）。`ComputerUseTaskService` 服务端编排循环：截屏（scale 0.5，
  复用 CAP-81 computer 原语）→ 送「模型接入」配置的视觉模型（历史 N 步摘要 + 当前
  截图）→ 解析动作 JSON → 下发 computer_req → 等 ack → 下一步；命中 maxSteps/
  maxMinutes 任一即 FAILED（原因可读）。
- **FR-02 动作协议**：模型输出严格 JSON：`{"action":"left_click|type|key|scroll|
  wait|done|fail","coordinate":[x,y],"text"?,"key"?,"reason":"…"}`，坐标系 = **缩放后
  截图像素**（与模型所见一致），服务端按 scale 换算回原始分辨率再下发（复用 CAP-81
  动作原语）；解析失败/越界坐标计一次可重试错误（连续 3 次 FAILED）。`reason` 必填
  落库，过程可审计。
- **FR-03 过程可视与接管**：任务详情页步序列（缩略图 + 动作 + reason + 耗时），
  WS 推送追加；「人工接管」按钮把任务置 TAKEN_OVER 并跳转该节点远程桌面交互态
  （循环停、画面不断，复用 CAP-81 FR-07）；「停止」随时可按。
- **FR-04 试运行档**：任务可标 `dryRun=true`——循环照跑、模型动作只记录不下发，
  用于评估提示词与模型可靠性（接 CAP-55/56 决策记录导出口径预留）。

### M2 审计与安全闸门

- **FR-05 单节点独占与限频**：LLM 循环每节点同时只许 1 个 RUNNING 任务
  （唯一约束 + 友好 409）；动作限频复用 CAP-81 FR-14（每节点 ≤20/s）。
- **FR-06 误操作缓解**：dryRun 档（FR-04）、只读任务白名单（待定：是否加「禁键名单/
  禁点区域」）、动作审计可追责（复用 CAP-81 FR-13）、人工接管（FR-03）。上线初期
  建议仅对非生产办公机开放 CONTROL 档。

## 3. 否决方案

- **LLM 循环放 runner 侧**：runner 瘦 jar 无模型接入、无任务状态；编排是平台职责，
  循环状态是平台数据（可接管/可审计/重启可续）。
- **复用 CAP-80 types executor 跑 computer-use**：types 任务是「runner 上跑一段命令」，
  computer-use 是「服务端编排的模型循环」，语义与状态归属都不同。
- **新造视觉模型协议**：复用 CAP-48 模型端点 + OpenAI 兼容多模态入参，不为 GUI 自动化
  单开一套模型接入（只需给 CHAT 端点补视觉能力标记）。

## 4. 架构与模块归属

| 层 | 归属 | 内容 |
|---|---|---|
| 服务端 | devmind-agent | `ComputerUseTaskService`（编排循环）、`ComputerUseTaskController`（REST）、任务/步实体 |
| 模型 | devmind-common（扩展） | OpenAI 兼容多模态 chat 入参（image_url/base64）+ `ModelEndpointView` 视觉能力标记 |
| 前端 | features/agent（任务页） | 任务列表/详情步序列/接管与停止 |
| 复用 | devmind-agent（CAP-81） | computer 原语下发/截图拉取/观看会话/动作审计 |

依赖方向：agent →（WS）→ runner（CAP-81 computer_req）；任务编排在 agent 模块，复用
CAP-81 registry.computer() 与 FileTransferStore 截图通道。common 扩展多模态 chat，
不反向依赖。

## 5. 数据模型

- 新表 `computer_use_tasks`：id CHAR(26) PK / node_id BIGINT / prompt
  LONGVARCHAR / status / dry_run BIT / max_steps INT / max_minutes INT /
  step_count INT / model_config_id（指向视觉能力开启的 CHAT 端点）/ taken_over_by /
  created_by / created_at / finished_at / fail_reason VARCHAR(500)。
- 新表 `computer_use_steps`：id PK / task_id FK / step_no INT / action /
  coordinate / reason VARCHAR(1000) / ack_error / duration_ms / screenshot_token
  （TTL 存储引用，过期即空）。**禁 H2 保留字列名**；文本列带 `@JdbcTypeCode`。
- 模型端点：`model_endpoints` 增「视觉能力」标记位（或新 kind），供任务选择可出图的
  CHAT 端点；无视觉能力端点时建任务 409。

## 6. API 概要

REST（均挂 `/api/agent`，全端点 ADMIN，同 CAP-81 口径）：

- `POST /computer-tasks`、`GET /computer-tasks`、`GET /computer-tasks/{id}`（含 steps）
- `POST /computer-tasks/{id}/stop`、`POST /computer-tasks/{id}/takeover`

WS 复用 CAP-81：编排循环每步经 `computer_req`/`computer_ack` 下发动作、拉截图。

## 7. 安全

- 视觉模型端点须为视觉能力开启的 CHAT 端点（决策 2），模型凭据走 CAP-48 SecretCipher，
  不落任务表。
- 单节点独占（FR-05）+ dryRun 档（FR-04）+ 随时停止/接管（FR-03）。
- 动作审计复用 CAP-81 FR-13（含 type_text/clipboard 只记长度）；`reason` 必填落库
  过程可追溯。
- 明确不覆盖：UAC 安全桌面、锁屏界面（截到即模型所见图，不绕过，CAP-81 同口径）。

## 8. 里程碑

| 里程碑 | 内容 | 对应 FR |
|---|---|---|
| M1 | 任务实体与编排循环 | FR-01~04 |
| M2 | 审计与安全闸门 | FR-05~06 |

## 9. 验收标准

- M1：给「打开记事本输入指定文本」任务，循环 ≤10 步完成 DONE，步序列含截图与
  reason；maxSteps 耗尽 FAILED 原因可读；接管后循环停、画面可操作（复用 CAP-81）。
- M2：dryRun 下模型动作只记录不下发；单节点并发建第二个 RUNNING 任务 409；
  动作审计逐条可查且不含明文键入内容。
- 通用：`mvn -q test` 绿、`npx tsc -b` 绿；E2E（tests/ 沉淀）与 CAP-81 隔离同跑，
  视觉模型用 mock/桩端点；E2E 后清理自起进程。

## 10. 风险与开放问题

- **视觉模型可用性（前置）**：依赖视觉能力开启的 CHAT 端点，平台当前模型调用层只发
  纯文本、模型端点无视觉标记——须先扩 common 多模态 + 端点视觉位（决策 2），且需具备
  可用的多模态模型；无则本能力不落地。
- **LLM 误操作**：模型点错/删东西——缓解靠 dryRun、只读任务白名单（待定：是否加
  「禁键名单/禁点区域」）、审计可追责、人工接管。上线初期建议仅对非生产办公机开放
  CONTROL 档。
- **驱动场景不明确**：本能力目前无明确的 GUI 自动化目标，属「机制就绪、场景待定」；
  保留设计，等真实驱动场景出现再排期落地。
