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
