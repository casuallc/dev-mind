import { Badge, Tag } from 'antd'

const RISK_LABEL: Record<string, { color: string; label: string }> = {
  READ: { color: 'blue', label: '只读' },
  WRITE: { color: 'orange', label: '写入' },
  DANGEROUS: { color: 'red', label: '危险' },
}

export function RiskTag({ risk }: { risk: string }) {
  const v = RISK_LABEL[risk] ?? { color: 'default', label: risk }
  return <Tag color={v.color}>{v.label}</Tag>
}

const TURN_STATUS: Record<string, { status: 'success' | 'error' | 'warning' | 'processing' | 'default'; label: string }> = {
  OK: { status: 'success', label: '成功' },
  FAILED: { status: 'error', label: '失败' },
  REJECTED: { status: 'warning', label: '拒绝' },
  PENDING_CONFIRM: { status: 'processing', label: '待确认' },
}

export function TurnStatusBadge({ status }: { status: string }) {
  const v = TURN_STATUS[status] ?? { status: 'default' as const, label: status }
  return <Badge status={v.status} text={v.label} />
}

const ACTION_STATUS: Record<string, { status: 'success' | 'error' | 'warning' | 'processing' | 'default'; label: string }> = {
  PENDING: { status: 'processing', label: '待确认' },
  EXECUTING: { status: 'processing', label: '执行中' },
  SUCCEEDED: { status: 'success', label: '成功' },
  FAILED: { status: 'error', label: '失败' },
  CANCELLED: { status: 'default', label: '已取消' },
  EXPIRED: { status: 'warning', label: '已过期' },
}

export function ActionStatusBadge({ status }: { status: string }) {
  const v = ACTION_STATUS[status] ?? { status: 'default' as const, label: status }
  return <Badge status={v.status} text={v.label} />
}

export const TURN_STATUS_OPTIONS = Object.entries(TURN_STATUS).map(([value, v]) => ({
  value,
  label: v.label,
}))

export const ACTION_STATUS_OPTIONS = Object.entries(ACTION_STATUS).map(([value, v]) => ({
  value,
  label: v.label,
}))

export const RISK_OPTIONS = Object.entries(RISK_LABEL).map(([value, v]) => ({
  value,
  label: v.label,
}))

/** FR-36 计划步骤状态（copilot_tool_calls.step_seq 行；PENDING = 尚无审计行） */
const STEP_STATUS: Record<string, { status: 'success' | 'error' | 'warning' | 'processing' | 'default'; label: string }> = {
  PENDING: { status: 'default', label: '未开始' },
  RUNNING: { status: 'processing', label: '执行中' },
  WAITING: { status: 'processing', label: '等待作业' },
  OK: { status: 'success', label: '成功' },
  FAILED: { status: 'error', label: '失败' },
  REJECTED: { status: 'warning', label: '未执行' },
}

export function StepStatusBadge({ status }: { status: string }) {
  const v = STEP_STATUS[status] ?? { status: 'default' as const, label: status }
  return <Badge status={v.status} text={v.label} />
}
