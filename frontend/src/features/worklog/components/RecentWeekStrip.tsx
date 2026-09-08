import { Typography } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import type { WeeklyReport } from '../types'

interface Props {
  /** weekStart(YYYY-MM-DD 周一) → 周报；无报告的周不在 map 里 */
  reports: Record<string, WeeklyReport>
  /** 选中周周一 YYYY-MM-DD */
  selected: string
  onSelect: (monday: Dayjs) => void
}

/**
 * 周报视图的最近 7 周选择条：左旧右新（最右=本周），每格显当周报告状态
 * （● 已确认 / ◐ 草稿 / ○ 无），点击切换到该周。超出 7 周的历史周走顶部周选择器。
 */
export default function RecentWeekStrip({ reports, selected, onSelect }: Props) {
  const thisMonday = dayjs().startOf('week').add(1, 'day') // dayjs 周日开头，+1 = 周一
  return (
    <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
      {Array.from({ length: 7 }, (_, i) => {
        const monday = thisMonday.subtract(6 - i, 'week')
        const key = monday.format('YYYY-MM-DD')
        const report = reports[key]
        const active = key === selected
        return (
          <div
            key={key}
            onClick={() => onSelect(monday)}
            style={{
              flex: 1,
              minWidth: 0,
              padding: '6px 0 4px',
              textAlign: 'center',
              cursor: 'pointer',
              border: `1px solid ${active ? '#1677ff' : '#f0f0f0'}`,
              borderRadius: 8,
              background: active ? '#e6f4ff' : undefined,
            }}
          >
            <div>
              {monday.format('MM-DD')} ~ {monday.add(6, 'day').format('MM-DD')}
              {i === 6 && (
                <Typography.Text type="warning" style={{ fontSize: 12 }}>
                  {' '}· 本周
                </Typography.Text>
              )}
            </div>
            <div style={{ fontSize: 12, marginTop: 2 }}>
              {!report ? (
                <Typography.Text type="secondary">○ 无</Typography.Text>
              ) : report.status === 'CONFIRMED' ? (
                <Typography.Text style={{ color: '#52c41a' }}>● 已确认</Typography.Text>
              ) : (
                <Typography.Text style={{ color: '#faad14' }}>◐ 草稿</Typography.Text>
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}
