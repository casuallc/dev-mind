import { Typography } from 'antd'
import { LeftOutlined, RightOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { useRef, useState } from 'react'
import type { WeeklyReport } from '../types'

interface Props {
  /** weekStart(YYYY-MM-DD 周一) → 周报；无报告的周不在 map 里 */
  reports: Record<string, WeeklyReport>
  /** 选中周周一 YYYY-MM-DD */
  selected: string
  onSelect: (monday: Dayjs) => void
  /** 窗口整体回退的周数：0 = 最右一格为本周 */
  weeksBack: number
  /** 滑动 / 点两侧箭头整页翻窗：+1 更早 7 周，-1 更近 7 周 */
  onShiftWindow: (pages: number) => void
}

/** 滑动超过该距离（px）才触发翻页 */
const SWIPE_THRESHOLD = 60
/** 区分点击与拖拽的最小位移（px） */
const DRAG_MIN = 8

/**
 * 周报视图的 7 周选择条：左旧右新，每格显当周报告状态（● 已确认 / ◐ 草稿 / ○ 无），
 * 点击切换到该周。支持在整条上水平滑动（触摸/鼠标拖拽）或点两侧箭头整页翻窗看更早/更近的 7 周；
 * 窗口最右不越过本周（weeksBack=0 时隐藏右箭头、左滑不生效），即不可查看未来周。
 * 滑动手势用 Pointer Events 统一触摸与鼠标；touchAction: pan-y 保留纵向滚动。
 */
export default function RecentWeekStrip({ reports, selected, onSelect, weeksBack, onShiftWindow }: Props) {
  const thisMonday = dayjs().startOf('week').add(1, 'day') // dayjs 周日开头，+1 = 周一
  const thisMondayKey = thisMonday.format('YYYY-MM-DD')
  /** 窗口最新（最右）一周的周一 */
  const anchor = thisMonday.subtract(weeksBack, 'week')
  const canShiftNewer = weeksBack > 0

  const [dragX, setDragX] = useState(0)
  const [dragging, setDragging] = useState(false)
  const gesture = useRef<{ startX: number; startY: number; pointerId: number; axis: 'h' | 'v' | null } | null>(null)
  /** 拖拽结束后抑制紧随其后的 click，避免误选某周 */
  const suppressClick = useRef(false)

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    gesture.current = { startX: e.clientX, startY: e.clientY, pointerId: e.pointerId, axis: null }
    // 注意：不能在此 setPointerCapture——捕获会把后续 click 重定向到本条容器，
    // 子格子的 onClick 永不触发（表现为周点不动）。拖到横向阈值再捕获。
    setDragging(true)
  }

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const g = gesture.current
    if (!g) return
    const dx = e.clientX - g.startX
    const dy = e.clientY - g.startY
    if (!g.axis) {
      if (Math.abs(dx) < DRAG_MIN && Math.abs(dy) < DRAG_MIN) return
      g.axis = Math.abs(dx) > Math.abs(dy) ? 'h' : 'v'
      if (g.axis === 'h') e.currentTarget.setPointerCapture(g.pointerId)
    }
    if (g.axis === 'h') setDragX(dx)
  }

  const endGesture = () => {
    const g = gesture.current
    if (!g) return
    gesture.current = null
    setDragging(false)
    if (g.axis === 'h') {
      suppressClick.current = true
      if (Math.abs(dragX) > SWIPE_THRESHOLD && (dragX > 0 || canShiftNewer)) {
        onShiftWindow(dragX < 0 ? -1 : 1)
      }
    }
    setDragX(0)
  }

  const arrowStyle: React.CSSProperties = {
    position: 'absolute',
    top: '50%',
    transform: 'translateY(-50%)',
    zIndex: 1,
    width: 24,
    height: 24,
    borderRadius: '50%',
    background: '#fff',
    boxShadow: '0 1px 4px rgba(0,0,0,0.15)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    cursor: 'pointer',
    color: '#999',
    fontSize: 12,
  }

  return (
    <div style={{ position: 'relative', marginBottom: 16 }}>
      <div
        role="button"
        aria-label="更早 7 周"
        style={{ ...arrowStyle, left: 4 }}
        onClick={() => onShiftWindow(1)}
      >
        <LeftOutlined />
      </div>
      {canShiftNewer && (
        <div
          role="button"
          aria-label="更近 7 周"
          style={{ ...arrowStyle, right: 4 }}
          onClick={() => onShiftWindow(-1)}
        >
          <RightOutlined />
        </div>
      )}
      <div
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endGesture}
        onPointerCancel={endGesture}
        onClickCapture={(e) => {
          if (suppressClick.current) {
            suppressClick.current = false
            e.stopPropagation()
          }
        }}
        style={{
          display: 'flex',
          gap: 8,
          touchAction: 'pan-y',
          userSelect: 'none',
          transform: `translateX(${dragX}px)`,
          transition: dragging ? 'none' : 'transform 0.15s ease-out',
        }}
      >
        {Array.from({ length: 7 }, (_, i) => {
          const monday = anchor.subtract(6 - i, 'week')
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
                {key === thisMondayKey && (
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
    </div>
  )
}
