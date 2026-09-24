import { Tooltip } from 'antd'
import { fmtTime } from '../../../shared/utils/format'
import type { Bookmark } from '../types'

/** FR-04 状态点：绿=OK / 红=FAIL / 灰=未探测；悬停看状态码、耗时与最近探测时间 */
const COLOR: Record<string, string> = { OK: '#52c41a', FAIL: '#ff4d4f', UNKNOWN: '#bfbfbf' }

export default function StatusDot({ bookmark }: { bookmark: Bookmark }) {
  const { lastStatus, lastStatusCode, lastLatencyMs, lastCheckedAt } = bookmark
  const title = lastCheckedAt
    ? `${lastStatus}${lastStatusCode ? ` · ${lastStatusCode}` : ''}` +
      `${lastLatencyMs != null ? ` · ${lastLatencyMs}ms` : ''} · ${fmtTime(lastCheckedAt)}`
    : '尚未探测'
  return (
    <Tooltip title={title}>
      <span
        style={{
          display: 'inline-block',
          width: 8,
          height: 8,
          borderRadius: '50%',
          background: COLOR[lastStatus] ?? COLOR.UNKNOWN,
        }}
      />
    </Tooltip>
  )
}
