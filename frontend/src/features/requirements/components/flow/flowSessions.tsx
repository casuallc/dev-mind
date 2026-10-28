// 流程会话共享小件（CAP-38）：阶段 Tab/工作单元 Tab 的会话状态标签与查找。
// overview.sessions 的 taskSpec 是 preview 截断版，但 [flow:*] 标记在首行，startsWith 判断可靠。
import { Tag } from 'antd'
import type { RequirementOverview } from '../../types'

export type OverviewSession = RequirementOverview['sessions'][number]

/** 会话视为「进行中」的状态（其余为终态） */
export const ACTIVE_SESSION_STATES = ['RUNNING', 'WAITING_INPUT', 'WAITING_AUTH', 'QUEUED']

/** 按标记取最近一次流程会话（createdAt 最新在前） */
export function latestFlowSession(sessions: OverviewSession[], marker: string): OverviewSession | undefined {
  return sessions
    .filter((s) => s.taskSpec && s.taskSpec.startsWith(marker))
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))[0]
}

/** 取工作单元最近一次会话（createdAt 最新在前）。
 *  CAP-100 FR-02：优先按服务端回填的归属会话 boundSessionId 反查（需求级 [flow:dev] 会话
 *  不挂 workItemId，只有这条通道能在 WI 行可见）；未命中回落 workItemId 匹配。 */
export function latestSessionOfWorkItem(
  sessions: OverviewSession[],
  workItemId: string,
  boundSessionId?: string,
): OverviewSession | undefined {
  if (boundSessionId) {
    const bound = sessions.find((s) => s.id === boundSessionId)
    if (bound) return bound
  }
  return sessions
    .filter((s) => s.workItemId === workItemId)
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))[0]
}

/** 会话状态标签（点击新窗口打开会话详情） */
export function SessionTag({ s }: { s?: OverviewSession }) {
  if (!s) return null
  const active = ACTIVE_SESSION_STATES.includes(s.status)
  return (
    <a href={`/sessions?sid=${s.id}`} target="_blank" rel="noreferrer">
      <Tag color={active ? 'processing' : s.status === 'DONE' ? 'success' : 'default'}>
        会话 {active ? '进行中' : s.status}
      </Tag>
    </a>
  )
}
