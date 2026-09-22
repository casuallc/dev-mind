// CAP-55 决策引擎（laya 边车）消费侧视图：决策记录（模型建议 vs 人工裁决）+ 训练集导出。
// 与后端 devmind-decision/record/dto 一一对应；answers 是 laya 应答原文，前端只做展示不解析协议。

/** laya 答案：三原语 choice/score/noul，只有对应的那个字段有值（其余 null） */
export interface DecisionAnswer {
  type: 'choice' | 'score' | 'noul' | string | null
  choice: string | null
  score: number | null
  noul: number | null
  confidence: number | null
  /** 各选项概率（choice 的 key 是机器值、score 的 key 是等级下标字符串；noul 通常为空） */
  probabilities: Record<string, number> | null
}

/** 一条决策记录：一次「模型建议 + 人工裁决」配对（未裁决时人工那半边为空） */
export interface DecisionRecord {
  id: number
  /** 能力标识（如 kb-proposal-triage），一行数据集按它切分 */
  capability: string
  /** 能力自己的实体 id（如提案 id） */
  refId: string
  degraded: boolean
  /** 降级原因（未降级时空串），可直接展示 */
  degradedReason: string
  latencyMs: number
  /** 边车实际选中的 checkpoint */
  model: string
  /** 边车选它的理由（可能为空） */
  routingReason: string
  answers: Record<string, DecisionAnswer>
  /** 人工裁决转成题 id→答案（空 = 这次裁决不构成任何题的 gold） */
  gold: Record<string, unknown>
  /** 人工动作机器值：adopt:global / adopt:project / reject（未裁决为 null） */
  humanAction: string | null
  /** 逐题一致性：只含两边都答的题（模型没答或人没裁这一题都不算） */
  agreement: Record<string, boolean>
  /** 三份快照齐全 + gold 至少落上一题（与导出侧同一口径） */
  trainable: boolean
  decidedBy: string | null
  decidedAt: string | null
  /** 模型建议产生时间（= 分诊那一刻） */
  suggestedAt: string | null
  createdAt: string
}

/** 详情：多出当初发给模型的输入与题面（抽屉里逐字回放） */
export interface DecisionRecordDetail {
  record: DecisionRecord
  state: Record<string, unknown>
  questions: Record<string, DecisionQuestion> | null
}

/** laya 题面：choice 的 criteria 是 {机器值:说明}，score 的是等级标签数组，noul 无 criteria */
export interface DecisionQuestion {
  type: string
  instructions?: string
  criteria?: Record<string, string> | string[]
}

// ---------------- CAP-56 决策实验室：评测集 / 评测运行 / 微调任务 / 产物登记 ----------------
// 与后端 devmind-decision-lab 的 dto 一一对应。report 是脚本回传的报告原文（键名即契约）：
// 缺失的键 = 没测出来 → 界面显示「—」，绝不用 0 顶替（准确率 0 与"压根没测"必须两回事）。

/** 服务端分页响应（PageView 的前端投影，与决策记录同口径：page 从 0 起、size ≤ 200） */
export interface LabPage<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** 运行状态（评测与微调共用同一词表） */
export type LabStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | string

/** 评测集一条：冻结后不可改，要改用「修订为新版本」 */
export interface DatasetView {
  id: number
  name: string
  /** BENCHMARK 基准集·人工标注 ｜ REPLAY 回流集·决策记录 */
  kind: string
  kindLabel: string
  version: number
  frozen: boolean
  /** 冻结时记下的题面版本（冻结前为 null） */
  questionSetVersion: string | null
  itemCount: number
  note: string | null
  createdBy: string | null
  createdAt: string | null
  frozenBy: string | null
  frozenAt: string | null
}

/** 题面覆盖：某一题有多少样本标了 gold（冻结前提醒用） */
export interface QuestionCoverage {
  questionId: string
  type: string
  annotated: number
}

/** 评测集详情：分组统计四类全在（缺的记 0）、冻结前提醒、冻结快照 */
export interface DatasetDetail {
  dataset: DatasetView
  /** caseGroup → 条数，四个组键恒在 */
  caseGroupCounts: Record<string, number>
  coverage: QuestionCoverage[]
  /** 冻结前提醒（样本量不足等）；冻结后恒为空数组 */
  warnings: string[]
  /** 冻结快照（草稿期为 {}） */
  manifest: Record<string, unknown>
}

/** 一条样本（列表行；state/questions/gold 正文在详情里） */
export interface DatasetItemView {
  id: number
  caseGroup: string
  caseGroupLabel: string
  /** MANUAL 页面人工标注 ｜ RECORD 从决策记录收编 */
  source: string
  originRecordId: number | null
  note: string | null
  title: string
  annotatedQuestions: string[]
  /** 非空 = 标签与 state 内容不符（冻结会被拒），直接显示 */
  caseGroupIssue: string | null
  createdAt: string | null
}

/** 样本详情：state/questions/gold 三件套逐字回放 */
export interface DatasetItemDetail {
  item: DatasetItemView
  state: Record<string, unknown>
  questions: Record<string, DecisionQuestion>
  gold: Record<string, unknown>
  distributions: Record<string, unknown>
  /** gold 里落不上题面的题 id（这些题等于没标） */
  goldNotLanded: string[]
}

/** 手工标注/替换一条样本的请求体 */
export interface DatasetItemRequest {
  state: Record<string, unknown>
  /** 空 = 用标准题面 */
  questions?: Record<string, unknown>
  gold: Record<string, unknown>
  /** 空 = NORMAL */
  caseGroup?: string
  note?: string
}

/** 对照组模板（后端固定 3 条：空召回 / 逐字重复 / 不相关），用于新建样本时预填 */
export interface CaseGroupTemplate {
  caseGroup: string
  label: string
  hint: string
  state: Record<string, unknown>
  questions: Record<string, DecisionQuestion>
  gold: Record<string, unknown>
  annotateQuestions: string[]
}

/** 收编预览的候选项 */
export interface IntakeCandidate {
  recordId: number
  capability: string | null
  subjectId: string | null
  title: string
  humanAction: string | null
  caseGroup: string
  caseGroupLabel: string
  /** COLLECT 可收编 ｜ SKIP 跳过 */
  outcome: string
  reasonCode: string | null
  reasonLabel: string | null
  detail: string | null
}

/** 从决策记录收编的预览（不改数据） */
export interface RecordsPreview {
  capability: string | null
  since: string | null
  total: number
  scanned: number
  /** true = 扫到上限就停了，须按 capability/since 收窄（不静默截断） */
  truncated: boolean
  collectable: number
  skipReasons: Record<string, number>
  caseGroups: Record<string, number>
  samples: IntakeCandidate[]
}

/** 收编结果 */
export interface RecordsIntakeResult {
  added: number
  skipped: number
  skipReasons: Record<string, number>
  dataset: DatasetDetail
}

/** 一次评测运行（列表行） */
export interface EvalView {
  id: number
  datasetId: number
  datasetLabel: string | null
  questionSetVersion: string | null
  checkpointId: number
  checkpointLabel: string | null
  serveSlot: string | null
  baseCheckpointId: number | null
  baseCheckpointLabel: string | null
  nodeId: string | null
  status: LabStatus
  itemCount: number | null
  reportStatus: string | null
  /** 服务端拼好的人读报告状态，直接显示 */
  reportLabel: string
  /** 列表头条指标（accuracy/softAccuracy/brier/mae/random/majority/win/lose/tie/eceBefore/eceAfter），缺的键为 null */
  headline: Record<string, number | null>
  exitCode: number | null
  errorSummary: string | null
  timeoutSeconds: number | null
  createdBy: string | null
  createdAt: string | null
  startedAt: string | null
  finishedAt: string | null
}

/** 发起评测的请求体 */
export interface EvalTriggerRequest {
  checkpointId: number
  datasetId: number
  baseCheckpointId?: number | null
  nodeId?: string | null
  /** 逗号分隔的节点标签要求（如 gpu,T4） */
  requiredLabels?: string | null
  pythonPath?: string | null
  outputPath?: string | null
  timeoutSec?: number | null
  /** 不传 = 不做温度校准 */
  fitTemperature?: boolean | null
}

/** 逐题明细（评测与微调回评同一形状） */
export interface PerItem {
  id: number
  caseGroup: string
  question: string
  qtype: string
  gold: unknown
  pred: unknown
  correct: boolean | null
  confidence: number | null
  latencyMs: number | null
  /** gold 落不上题面的题 */
  skipped?: boolean
}

/** 某一题型的指标块（该题型没有样本时整块不出现，不是 0） */
export interface MetricBlock {
  questions?: number
  accuracy?: number
  avgConfidence?: number
  ece?: number
  softAccuracy?: number
  brier?: number
  /** score 题才有 */
  mae?: number
  within1Level?: number
  /** noul 题才有 */
  noulRate?: number
  /** 按预测标签计数（choice/noul） */
  answered?: Record<string, number>
}

export interface ReportMetrics {
  items?: number
  questions?: number
  latencyMs?: { p50?: number; p95?: number }
  choice?: MetricBlock
  score?: MetricBlock
  noul?: MetricBlock
}

/** 与基线 checkpoint 的逐题胜负 */
export interface LabCompare {
  win?: number
  lose?: number
  tie?: number
  baselineCheckpoint?: number
  /** 评测的 compare 有 baselineSlot，微调没有 */
  baselineSlot?: string
  baselineMetrics?: ReportMetrics
}

/** 温度校准段（仅 fitTemperature=true 时出现） */
export interface LabCalibration {
  mode?: string
  source?: string
  fitItems?: number
  evalItems?: number
  /** "<题型>:<选项数桶>" → 温度 */
  temperature?: Record<string, number>
  note?: string
  before?: { ece?: number }
  after?: { ece?: number }
  allItems?: { before?: { ece?: number }; after?: { ece?: number } }
}

/** 按对照组分组的指标 */
export interface CaseGroupMetric {
  caseGroup: string
  items?: number
  questions?: number
  accuracy?: number
}

/** 脚本回传的报告原文（评测 kind=evaluation、微调 kind=finetune 共用同一骨架） */
export interface LabReport {
  schemaVersion?: number
  generatedAt?: string
  kind?: string
  layaVersion?: string
  checkpoint?: { id?: number; name?: string; slot?: string; serveSlot?: string; path?: string }
  baseCheckpoint?: { id?: number; name?: string; serveSlot?: string; path?: string }
  dataset?: { id?: number; name?: string; version?: number; questionSetVersion?: string; itemCount?: number }
  metrics?: ReportMetrics
  /** 两条基线（FR-03 硬要求，必须始终有） */
  baselines?: { random?: number; majority?: number }
  byCaseGroup?: CaseGroupMetric[]
  perItem?: PerItem[]
  compare?: LabCompare
  calibration?: LabCalibration
  warnings?: string[]
  /** 微调独有：切分与训练过程 */
  split?: { train?: number; val?: number; splitSeed?: number; trainRatio?: number }
  train?: Record<string, number | string | boolean | null>
}

/** 评测详情 */
export interface EvalDetail {
  view: EvalView
  /** 报告原文，永不 null（读不出来时是 {}） */
  report: LabReport
  byCaseGroup: CaseGroupMetric[]
  perItem: PerItem[]
  calibration: LabCalibration | null
  compare: LabCompare | null
  commandText: string | null
}

/** 一次微调任务（列表行） */
export interface FinetuneView {
  id: number
  datasetId: number
  datasetLabel: string | null
  questionSetVersion: string | null
  itemCount: number | null
  trainCount: number | null
  valCount: number | null
  splitSeed: number | null
  trainRatio: number | null
  /** 回评用的评测集 */
  evalDatasetId: number | null
  evalDatasetLabel: string | null
  baseCheckpointId: number | null
  baseCheckpointLabel: string | null
  serveSlot: string | null
  outputPath: string | null
  epochs: number | null
  learningRate: number | null
  batchSize: number | null
  trainSeed: number | null
  launcher: string | null
  nodeId: string | null
  status: LabStatus
  reportStatus: string | null
  reportLabel: string
  headline: Record<string, number | null>
  /** 自动登记的产物 */
  checkpointId: number | null
  checkpointName: string | null
  /** 结束自动回评的评测 id */
  evalId: number | null
  /** 收尾结果人读文案（成功才有），直接显示 */
  postLabel: string | null
  /** 收尾失败原因（如产物登记失败） */
  postError: string | null
  exitCode: number | null
  errorSummary: string | null
  timeoutSeconds: number | null
  createdBy: string | null
  createdAt: string | null
  startedAt: string | null
  finishedAt: string | null
}

/** 发起微调的请求体 */
export interface FinetuneTriggerRequest {
  datasetId: number
  evalDatasetId: number
  baseCheckpointId: number
  nodeId?: string | null
  requiredLabels?: string | null
  pythonPath?: string | null
  outputPath: string
  epochs?: number | null
  learningRate?: number | null
  batchSize?: number | null
  trainSeed?: number | null
  splitSeed?: number | null
  trainRatio?: number | null
  launcher?: string | null
  timeoutSec?: number | null
}

/** 微调详情（没有独立的 byCaseGroup/compare 字段：它们都在 report 里） */
export interface FinetuneDetail {
  view: FinetuneView
  report: LabReport
  perItem: PerItem[]
  calibration: LabCalibration | null
  train: Record<string, unknown> | null
  /** 验证集条目 id（= report.perItem[].id 的集合） */
  valItemIds: number[]
  commandText: string | null
}

/** 一份模型产物登记 */
export interface CheckpointView {
  id: number
  name: string
  serveSlot: string | null
  /** BASE 官方基础 ｜ FINETUNED 微调产物 */
  kind: string
  kindLabel: string
  sourcePath: string | null
  nodeId: string | null
  fingerprintPath: string | null
  fingerprintBytes: number | null
  fingerprintSha256: string | null
  /** 人工放行（准入闸门看的就是它） */
  verified: boolean
  verifiedBy: string | null
  verifiedAt: string | null
  verifiedNote: string | null
  hasMetrics: boolean
  hasCalibration: boolean
  serveCheckedAt: string | null
  serveCheckStatus: string | null
  createdBy: string | null
  createdAt: string | null
  note: string | null
}

/** 登记产物的请求体 */
export interface CheckpointRequest {
  name: string
  serveSlot: string
  kind: string
  sourcePath: string
  nodeId?: string | null
  fingerprintPath?: string | null
  fingerprintBytes?: number | null
  fingerprintSha256?: string | null
  metricsJson?: string | null
  note?: string | null
}

/** serve 自检的一条结论 */
export interface ServeCheck {
  item: string
  /** OK ｜ WARN ｜ FAIL */
  status: string
  detail: string
}

/** serve 自检报告（打一次边车 /healthz，核对登记的那份是不是在跑的那份） */
export interface ServeCheckResult {
  status: string
  summary: string
  checks: ServeCheck[]
  report: Record<string, unknown>
  checkedAt: string | null
}

export interface CheckpointDetail {
  checkpoint: CheckpointView
  /** 最近一次产出报告的完整指标（无报告时为 {}） */
  metrics: LabReport
  calibration: LabCalibration | null
  serveCheck: ServeCheckResult | null
}

/** 准入闸门状态（FR-07：未放行时消费方（分诊）整体不可用） */
export interface CheckpointGateView {
  open: boolean
  reason: string | null
  serving: CheckpointView[]
  checkedAt: string | null
}
