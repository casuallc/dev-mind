import { Typography } from 'antd'
import { LeftOutlined, RightOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useRef, useState } from 'react'

interface Props {
  /** 选中年份 */
  selected: number
  onSelect: (year: number) => void
  /** 窗口中央相对今年的偏移年数：0 = 今年居中 */
  yearsOffset: number
  /** 滑动 / 点两侧箭头翻窗：+1 窗口向更早平移 1 年，-1 向更晚平移 1 年（前后不限——允许规划未来年） */
  onShift: (years: number) => void
}

/** 窗口格数：以锚点年居中，前后各 2 年 */
const WINDOW = 5
/** 滑动超过该距离（px）才触发翻页 */
const SWIPE_THRESHOLD = 60
/** 区分点击与拖拽的最小位移（px） */
const DRAG_MIN = 8

/**
 * 目标视图的 5 年选择条（交互对齐周报的 7 周条 RecentWeekStrip）：窗口以「今年 + yearsOffset」居中，
 * 点击切换年份，今年格带「今年」徽标；支持在整条上水平滑动（触摸/鼠标拖拽）或点两侧箭头平移窗口。
 * 与周条不同：前后方向都不设限（Q4 规划来年目标属正常操作）。
 * 滑动手势用 Pointer Events 统一触摸与鼠标；touchAction: pan-y 保留纵向滚动。
 */
export default function YearStrip({ selected, onSelect, yearsOffset, onShift }: Props) {
  const thisYear = dayjs().year()
  const anchor = thisYear + yearsOffset

  const [dragX, setDragX] = useState(0)
  const [dragging, setDragging] = useState(false)
  const gesture = useRef<{ startX: number; startY: number; pointerId: number; axis: 'h' | 'v' | null } | null>(null)
  /** 拖拽结束后抑制紧随其后的 click，避免误选某年 */
  const suppressClick = useRef(false)

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    gesture.current = { startX: e.clientX, startY: e.clientY, pointerId: e.pointerId, axis: null }
    // 注意：不能在此 setPointerCapture——捕获会把后续 click 重定向到本条容器，
    // 子格子的 onClick 永不触发（表现为年点不动）。拖到横向阈值再捕获。
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
      if (Math.abs(dragX) > SWIPE_THRESHOLD) onShift(dragX < 0 ? 1 : -1)
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
        aria-label="更早的年份"
        style={{ ...arrowStyle, left: 4 }}
        onClick={() => onShift(-1)}
      >
        <LeftOutlined />
      </div>
      <div
        role="button"
        aria-label="更晚的年份"
        style={{ ...arrowStyle, right: 4 }}
        onClick={() => onShift(1)}
      >
        <RightOutlined />
      </div>
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
        {Array.from({ length: WINDOW }, (_, i) => {
          const year = anchor - Math.floor(WINDOW / 2) + i
          const active = year === selected
          return (
            <div
              key={year}
              onClick={() => onSelect(year)}
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
              {year}
              {year === thisYear && (
                <Typography.Text type="warning" style={{ fontSize: 12 }}>
                  {' '}· 今年
                </Typography.Text>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
