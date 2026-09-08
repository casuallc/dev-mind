import { Typography } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import type { DailyReport } from '../types'

interface Props {
  /** 周一 */
  weekStart: Dayjs
  /** workDate(YYYY-MM-DD) → 日报；无报告的天不在 map 里 */
  reports: Record<string, DailyReport>
  /** 选中日期 YYYY-MM-DD */
  selected: string
  onSelect: (date: Dayjs) => void
}

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

/**
 * 日报周视图的周日选择条：一周 7 格（周一至周日），每格显示当日报告状态
 * （● 已确认 / ◐ 草稿 / ○ 无；未来日期显 —），点击选中某天。
 */
export default function WeekDayStrip({ weekStart, reports, selected, onSelect }: Props) {
  const today = dayjs().format('YYYY-MM-DD')
  return (
    <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
      {Array.from({ length: 7 }, (_, i) => {
        const d = weekStart.add(i, 'day')
        const key = d.format('YYYY-MM-DD')
        const report = reports[key]
        const future = key > today
        const active = key === selected
        return (
          <div
            key={key}
            onClick={() => onSelect(d)}
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
              周{WEEKDAYS[i]} {d.format('MM-DD')}
              {key === today && (
                <Typography.Text type="warning" style={{ fontSize: 12 }}>
                  {' '}· 今天
                </Typography.Text>
              )}
            </div>
            <div style={{ fontSize: 12, marginTop: 2 }}>
              {future ? (
                <Typography.Text type="secondary">—</Typography.Text>
              ) : !report ? (
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
