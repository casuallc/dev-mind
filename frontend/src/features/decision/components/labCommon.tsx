// CAP-56 决策实验室的公共展示件：状态/分组的标签与配色、数值格式化。
// 分四视图（评测集 / 评测运行 / 微调任务 / 产物登记）共用；状态词表评测与微调是同一套。
import { Tag } from 'antd'

/** 运行状态（评测与微调共用词表） */
export const RUN_STATUS_LABEL: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCESS: '成功',
  FAILED: '失败',
}

export const RUN_STATUS_COLOR: Record<string, string> = {
  QUEUED: 'default',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
}

/** 未终态 = 可以接 WS 实时日志、需要轮询刷新 */
export const runActive = (status: string | null | undefined) =>
  status === 'QUEUED' || status === 'RUNNING'

export function RunStatusTag({ status }: { status: string }) {
  return <Tag color={RUN_STATUS_COLOR[status] ?? 'default'}>{RUN_STATUS_LABEL[status] ?? status}</Tag>
}

/** 报告状态上色：文案用服务端拼好的 reportLabel（口径只有一处） */
export const REPORT_STATUS_COLOR: Record<string, string> = {
  OK: 'green',
  MISSING: 'orange',
  MALFORMED: 'red',
  INCOMPLETE: 'gold',
}

export function ReportStatusTag({ status, label }: { status: string | null; label: string }) {
  return <Tag color={status ? REPORT_STATUS_COLOR[status] ?? 'default' : 'default'}>{label}</Tag>
}

/** 样本分组：三个对照组是冻结红线（缺一不可），与 NORMAL 用不同色系区分 */
export const CASE_GROUP_LABEL: Record<string, string> = {
  NORMAL: '普通',
  EMPTY_RECALL: '对照·空召回',
  VERBATIM_DUP: '对照·逐字重复',
  IRRELEVANT: '对照·不相关',
}

export const CASE_GROUP_COLOR: Record<string, string> = {
  NORMAL: 'default',
  EMPTY_RECALL: 'geekblue',
  VERBATIM_DUP: 'purple',
  IRRELEVANT: 'cyan',
}

/** 冻结红线要求的三个对照组（顺序即后端 CaseGroups.CONTROL） */
export const CONTROL_GROUPS = ['EMPTY_RECALL', 'VERBATIM_DUP', 'IRRELEVANT']

export function CaseGroupTag({ value }: { value: string }) {
  return <Tag color={CASE_GROUP_COLOR[value] ?? 'default'}>{CASE_GROUP_LABEL[value] ?? value}</Tag>
}

/** 样本来源 */
export const SOURCE_LABEL: Record<string, string> = {
  MANUAL: '页面标注',
  RECORD: '记录回流',
}

/** 收编跳过原因的人话（后端汇总表只给码，逐条样例才带 label） */
export const SKIP_REASON_LABEL: Record<string, string> = {
  SNAPSHOT_INCOMPLETE: '缺快照或没有人工裁决（只有模型建议的记录收不了）',
  QUESTION_SET_MISMATCH: '题面与当前标准题面不同（旧题面测出来的记录不混进来）',
  GOLD_NOT_ON_QUESTION_SET: '人工动作落不上题面（不构成任何一道题的答案）',
  ALREADY_COLLECTED: '本集已收编过',
}

/** serve 自检 / 边车探针结论 */
export const CHECK_STATUS_COLOR: Record<string, string> = {
  OK: 'success',
  WARN: 'warning',
  FAIL: 'error',
}

/** 产物类型 */
export const CP_KIND_LABEL: Record<string, string> = {
  BASE: '官方基础',
  FINETUNED: '微调产物',
}

/** 题面里三题的人读名（题 id 是模型契约，界面加一层中文更好认） */
export const QUESTION_LABEL: Record<string, string> = {
  adopt_layer: '采纳层级',
  duplicate: '是否重复',
  quality: '可用水准',
}

export const questionLabel = (id: string) => QUESTION_LABEL[id] ?? id
