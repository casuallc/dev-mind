// 紧凑流程进度（CAP-13）：替代 Steps 六态步骤条，一行分段进度条 + 当前阶段文案。
// Hover 展示完整链路；CANCELLED 由调用方决定不渲染。
import { Progress, Space, Tooltip, Typography } from 'antd'
import { STATUS_FLOW, requirementStatusColor } from '../requirementMeta'
import type { RequirementStatus } from '../../types'

export default function FlowProgress({ status }: { status: RequirementStatus }) {
  const idx = STATUS_FLOW.indexOf(status)
  const percent = status === 'DONE' ? 100 : ((idx + 1) / STATUS_FLOW.length) * 100
  return (
    <Tooltip title={STATUS_FLOW.join(' → ')}>
      <Space size={8} style={{ width: '100%', marginBottom: 4 }}>
        <Progress
          type="line"
          steps={STATUS_FLOW.length}
          percent={percent}
          size="small"
          showInfo={false}
          strokeColor={requirementStatusColor(status)}
          style={{ flex: 1, marginBottom: 0 }}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
          {status} · {idx + 1}/{STATUS_FLOW.length}
        </Typography.Text>
      </Space>
    </Tooltip>
  )
}
