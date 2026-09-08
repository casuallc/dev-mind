import { Typography } from 'antd'
import { LeftOutlined, RightOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { useRef, useState } from 'react'

interface Props {
  /** 周一 */
  weekStart: Dayjs
  /** 渲染某天第二行状态小字（dateKey=YYYY-MM-DD，future=未来日期）；不传则不渲染状态行 */
  renderStatus?: (dateKey: string, future: boolean) => React.ReactNode
  /** 选中日期 YYYY-MM-DD */
  selected: string
  onSelect: (date: Dayjs) => void
  /** 左右滑动 / 点两侧箭头切周：-1 上一周，+1 下一周 */
  onShiftWeek: (n: number) => void
}

/** 滑动超过该距离（px）才触发切周 */
const SWIPE_THRESHOLD = 60
/** 区分点击与拖拽的最小位移（px） */
const DRAG_MIN = 8

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

/**
 * 通用的周日选择条（日报/工作条目共用）：一周 7 格（周一至周日），点击选中某天，
 * 第二行状态小字由 renderStatus 自定义（日报=报告状态，条目=当日条数）。
 * 支持在整条上水平滑动（触摸/鼠标拖拽）切换上一周/下一周，两侧箭头为可点击的回退方式；
 * 未来周不可切（当前周隐藏右箭头、左滑不生效），未来日期的格子不可选。
 * 滑动手势用 Pointer Events 统一触摸与鼠标；touchAction: pan-y 保留纵向滚动。
 */
export default function WeekDayStrip({ weekStart, renderStatus, selected, onSelect, onShiftWeek }: Props) {
  const today = dayjs().format('YYYY-MM-DD')
  /** 当前周周一；已处于当前周时禁止再向后切（未来周无意义） */
  const currentMonday = dayjs().startOf('week').add(1, 'day')
  const canShiftNext = weekStart.isBefore(currentMonday, 'day')
  const [dragX, setDragX] = useState(0)
  const [dragging, setDragging] = useState(false)
  const gesture = useRef<{ startX: number; startY: number; pointerId: number; axis: 'h' | 'v' | null } | null>(null)
  /** 拖拽结束后抑制紧随其后的 click，避免误选某天 */
  const suppressClick = useRef(false)

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    gesture.current = { startX: e.clientX, startY: e.clientY, pointerId: e.pointerId, axis: null }
    // 注意：不能在此 setPointerCapture——捕获会把后续 click 重定向到本条容器，
    // 子格子的 onClick 永不触发（表现为日期点不动）。拖到横向阈值再捕获。
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
      if (Math.abs(dragX) > SWIPE_THRESHOLD && (dragX > 0 || canShiftNext)) {
        onShiftWeek(dragX < 0 ? 1 : -1)
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
        aria-label="上一周"
        style={{ ...arrowStyle, left: 4 }}
        onClick={() => onShiftWeek(-1)}
      >
        <LeftOutlined />
      </div>
      {canShiftNext && (
        <div
          role="button"
          aria-label="下一周"
          style={{ ...arrowStyle, right: 4 }}
          onClick={() => onShiftWeek(1)}
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
          const d = weekStart.add(i, 'day')
          const key = d.format('YYYY-MM-DD')
          const future = key > today
          const active = key === selected
          return (
            <div
              key={key}
              onClick={() => {
                if (!future) onSelect(d)
              }}
              style={{
                flex: 1,
                minWidth: 0,
                padding: '6px 0 4px',
                textAlign: 'center',
                cursor: future ? 'default' : 'pointer',
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
              <div style={{ fontSize: 12, marginTop: 2 }}>{renderStatus?.(key, future)}</div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
