# CAP-55 决策引擎接入与知识库提案分诊（Decision Engine + Proposal Triage）

> 新增能力。缘起：平台里大量「高频低风险的类型化判断」（提案采纳去哪层、是否重复、质量几分、通知紧急度、
> 失败类别）目前要么靠人工、要么烧 claude token。Laya（Apache 2.0，非自回归 System 1 决策模型，
> choice/score/noul 三原语，单次前向 33ms 级，多语言 checkpoint 支持中文）以 Python 边车服务接入，
> 首场景落地知识库提案分诊，并同步沉淀人工决策数据为后续微调的 gold 数据集（数据飞轮）。

## 1. 目的

- 建立**决策引擎**能力：Java 侧统一 SPI，屏蔽「HTTP 调边车服务」细节；消费方像用 embedding 一样用决策模型。
- 边车服务注册进 CAP-48 模型端点体系（`kind=DECISION`），连接测试/平台默认端点/密文凭据全部复用，
  不新造配置体系。
- 首场景：知识库 inbox 提案自动分诊（采纳层级 choice + 重复判定 noul + 质量 score），
  **MVP 只出建议不自动执行**，人工仍一键确认；每次人工裁决落库为训练样本。
- 边车不可用/未配置时全链路降级为现状（纯人工 inbox），绝不让知识库功能 5xx。

**关键约束**：laya base checkpoint 零样本接近随机（官方自测 0.362 vs 0.318 随机线），首发用官方微调产物
`laya-typed-decisions` + Router 自动选 checkpoint；中文走 multilingual（state 预算 ~768 token，须截断）。
置信度门槛在温度校准前只作展示参考，不作自动执行依据。

## 2. 功能需求

- **FR-01 边车服务（Python，独立部署，不进 Maven）**：FastAPI 包 laya `Router(preload=True)`，
  暴露 `GET /healthz`（含已加载 checkpoint 列表）与 `POST /v1/predict {state, questions, model?}`，
  透传 laya 的 answers/routing 结构；CPU 可跑（~200-460ms/题），有 GPU 更佳。仓库内放
  `tools/laya-sidecar/`（Dockerfile + app.py + requirements.txt），部署机与端口由运维定。
- **FR-02 端点接入（复用 CAP-48）**：`model_endpoints.kind` 放开 `DECISION`（登记 + 连接测试；
  连接测试 = `GET /healthz` + 一条固定样例 `POST /v1/predict` 实测往返）。无 apiKey 概念，凭据字段可空。
  解析链与 CAP-48 一致：平台默认 DECISION 端点，未配置 → degraded。
- **FR-03 DecisionEngine SPI（devmind-common）**：`decide(state: Map, questions: Map) → DecisionResult`
  （answers + routing + degraded 标记）；`devmind-decision` 新模块实现 HTTP 客户端
  （超时 3s、失败一次重试、异常吞掉转 degraded=true），消费方 ObjectProvider 探测注入。
  questions 用 laya 原生 schema（type/criteria/instructions），state 值截断 1500 字符防超 context。
- **FR-04 提案分诊（首场景，devmind-knowledge 消费）**：inbox 提案列表/详情提供「AI 分诊」按钮 +
  新提案入库后自动异步分诊（@Async 禁 @Transactional）：
  - `choice` 采纳层级：global / project / discard（附各选项描述）；
  - `noul` 重复风险：先经 KnowledgeRetriever 检索 top3 相似条目拼进 state，判「是否与现有条目实质重复」；
  - `score` 质量分：三级（含糊不可用 / 可用需润色 / 直接可用）。
  结果落 `knowledge_proposals` 新增列（triage JSON + triage_at + triage_degraded），
  前端 inbox 显示建议徽标（采纳层级 + 置信度 + 重复预警 + 质量分），人工采纳/丢弃动作不变。
- **FR-05 决策反馈落库（数据飞轮）**：新表 `decision_records`（capability=kb-proposal-triage、
  state 快照、questions、模型答案+置信度、routing、人工最终裁决、是否采纳模型建议、created_at）。
  人工每次裁决 upsert 一条；提供 `GET /api/decision/records/export?capability=&since=`
  导出 laya 训练格式 JSONL（state/questions/gold 概率分布，人工裁决转 one-hot 分布）。
- **FR-06 降级链（已定）**：未配置 DECISION 端点 / 边车超时或 5xx / 返回体解析失败 →
  triage_degraded=true、UI 不显示建议徽标、分诊按钮置灰带原因；知识库其余功能零影响。
- **FR-07 前端**：`features/decision` 自包含（决策记录列表页：能力筛选/模型建议 vs 人工裁决对比/
  导出按钮）；知识库 inbox 加分诊徽标与「查看依据」抽屉（answers + routing.reason 原文展示）；
  模型端点管理页 kind 选项加 DECISION。

## 3. 关键设计

- **建议先行，自动后置（已定）**：MVP 阶段模型只出建议徽标，人工确认动作不变。
  自动采纳（高置信度直接落层）待温度校准与线上准确率验证后另立 CAP 放开，届时按库开关 + 阈值可配。
- **边车独立部署（已定）**：Python/torch 依赖不进 Maven 与 dist 包；边车可跑在任何可达机器
  （含 agent 节点机），服务端只认 HTTP 端点。与 CAP-34「服务端零执行」不冲突——决策调用是
  同步查询不是执行，同 CAP-49 的模型执行体先例。
- **复用模型端点体系（已定）**：不新造 `decision.*` 配置命名空间；DECISION 端点享受 CAP-48 的
  密文凭据/连接测试/默认端点解析，embedding/CHAT/DECISION 三类端点统一管理。
- **重复判定两段式（已定）**：embedding 检索召回（便宜）→ laya noul 精判（懂语义），
  不与 CAP-44 检索重复造轮子，直接消费 KnowledgeRetriever SPI。
- **反馈即训练集（已定）**：decision_records 的导出格式对齐 laya 微调数据格式，
  攒量后离线微调（训练执行器与发布闭环另立 CAP，本平台不扛 torch）。

## 4. 插件化接口

- `DecisionEngine`（devmind-common SPI）：决策契约，decision 模块实现，knowledge（本 CAP）与
  notification/session 等（后续 CAP）经 ObjectProvider 消费。
- `KnowledgeRetriever`（既有 SPI）：重复判定召回段直接复用，零改动。

## 5. 数据模型

```
model_endpoints：kind 枚举放开 DECISION（既有表，无结构变更）
knowledge_proposals + triage_json CLOB?（模型 answers+routing 原文）
                  + triage_at TIMESTAMP?  + triage_degraded BOOLEAN 默认 false（初始值，禁 @ColumnDefault）
decision_records(id, capability VARCHAR(64), subject_id VARCHAR(64)（如 proposalId）,
                 state_json CLOB, questions_json CLOB, model_answer CLOB?,
                 routing_json CLOB?, degraded BOOLEAN,
                 human_decision VARCHAR(64)?, adopted_model_suggestion BOOLEAN?,
                 decided_by VARCHAR(64)?, decided_at TIMESTAMP?, created_at)
```

红线：@ColumnDefault 字符串带引号；@Lob 必带 @JdbcTypeCode(SqlTypes.LONGVARCHAR)；
Boolean 列禁 @ColumnDefault（实体初始值 + getter 兜底）；异步分诊方法禁 @Transactional。

## 6. API 概要

```
POST   /api/knowledge/proposals/{id}/triage      手动触发分诊（异步，202）
GET    /api/knowledge/proposals                  列表返回附带 triage 摘要（徽标数据）
GET    /api/decision/records                     决策记录列表（capability/时间筛选，分页）
GET    /api/decision/records/export              导出训练 JSONL（capability/since 过滤）
既有   /api/model-endpoints（kind=DECISION 放开）  端点登记 + 连接测试
```

## 7. 验收标准

- 登记 DECISION 端点并连接测试通过；inbox 新提案自动分诊出徽标（层级/置信度/质量分/重复预警），
  抽屉可见 routing.reason；
- 人工裁决后 decision_records 落行且 export 产出合法 laya JSONL（state/questions/gold 三字段齐全）；
- 端点删除或边车关停后：inbox 无徽标不报错、分诊按钮置灰、知识库全流程回归绿；
- E2E：mock 边车（固定 answers）跑通「提案入库→自动分诊→徽标→人工裁决→记录导出」全链。

## 8. 非目标

训练执行器（GPU 节点 + torchrun 编排）、checkpoint 版本化与发布回滚、温度校准自动化
（以上三者另立 CAP，依赖本 CAP 的数据积累）；检索重排、自动打标、通知分流、prompt 护栏
（DecisionEngine 的后续消费场景，逐个另立 CAP）；模型建议的自动执行。
