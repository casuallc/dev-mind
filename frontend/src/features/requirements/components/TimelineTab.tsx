// 时间线 Tab（CAP-13）：需求主线的稀疏事件流，时间倒序由后端给出，这里只渲染前 50 条。
import { Space, Tag, Timeline, Typography } from 'antd'
import { fmtTime } from '../../../shared/utils/format'
import type { RequirementOverview } from '../types'

export default function TimelineTab({ items }: { items: RequirementOverview['timeline'] }) {
  return (
    <Timeline
      style={{ marginTop: 8 }}
      items={items.slice(0, 50).map((t) => ({
        key: `${t.type}-${t.refId}-${t.time}`,
        color: timelineColor(t.type),
        children: (
          <Space size={8}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {fmtTime(t.time)}
            </Typography.Text>
            <Tag style={{ fontSize: 11 }}>{t.type}</Tag>
            <span style={{ fontSize: 13 }}>{t.label}</span>
          </Space>
        ),
      }))}
    />
  )
}

function timelineColor(type: string): string {
  switch (type) {
    case 'REQUIREMENT': return 'purple'
    case 'WORK_ITEM': return 'geekblue'
    case 'DOC': return 'green'
    case 'SESSION': return 'blue'
    case 'BUILD': return 'orange'
    case 'TEST_RUN': return 'cyan'
    case 'DEPLOYMENT': return 'red'
    case 'RELEASE': return 'magenta'
    case 'ARTIFACT': return 'gold'
    default: return 'gray'
  }
}
