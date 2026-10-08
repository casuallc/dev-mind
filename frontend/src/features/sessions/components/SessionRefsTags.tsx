// 会话关联研发主线展示：需求渲染为可跳详情页的链接（stopPropagation 供可点击行内嵌套），
// 工作单元为纯文本标签（其详情即需求页）。id 未解析到（超出需求映射页大小）时回退原文。
import { Link } from 'react-router-dom'
import { Tag, Tooltip, Typography } from 'antd'
import type { SessionSummary } from '../types'
import type { SessionRefs } from '../hooks/useSessionRefs'

export default function SessionRefsTags({
  session,
  refs,
  maxWidth = 240,
}: {
  session: SessionSummary
  refs: SessionRefs
  /** 需求链接文本最大宽度（超出省略，Tooltip 见全文） */
  maxWidth?: number
}) {
  const req = refs.requirementOf(session.requirementId)
  const wi = refs.workItemOf(session.workItemId)
  if (!session.requirementId && !session.workItemId) return null
  const reqText = req ? `${req.code} ${req.title}` : session.requirementId!
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, minWidth: 0 }}>
      {session.requirementId && (
        <Tooltip title={`需求：${reqText}`}>
          <Typography.Text style={{ maxWidth, fontSize: 12 }} ellipsis>
            <Link
              to={`/projects/${session.projectId}/requirements/${session.requirementId}`}
              onClick={(e) => e.stopPropagation()}
            >
              {reqText}
            </Link>
          </Typography.Text>
        </Tooltip>
      )}
      {session.workItemId && (
        <Tooltip title={`工作单元：${wi ? `${wi.code} ${wi.title}` : session.workItemId}`}>
          <Tag style={{ marginInlineEnd: 0, fontSize: 11, lineHeight: '16px', padding: '0 4px' }}>
            {wi ? wi.code : session.workItemId}
          </Tag>
        </Tooltip>
      )}
    </span>
  )
}
