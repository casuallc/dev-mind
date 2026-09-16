// 通用横向滚动行：children 超宽时不换行不截断，容器内横向滑动（箭头点击 / 纵向滚轮转横向 / 触屏原生滑动），
// 两端渐变遮罩 + 圆形箭头提示可滚方向。用于 Card 页头等拥挤区域（参考用法 = /worklog 页签）。
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react'
import { LeftOutlined, RightOutlined } from '@ant-design/icons'

interface Props {
  children: ReactNode
  /** 子项间隙（px），默认 12 */
  gap?: number
  /** 选中项 CSS 选择器（如 '.ant-segmented-item-selected'）：children 变化后自动将其滚入可见区 */
  activeSelector?: string
  className?: string
  style?: CSSProperties
}

export default function ScrollRow({ children, gap = 12, activeSelector, className, style }: Props) {
  const viewportRef = useRef<HTMLDivElement>(null)
  const [canLeft, setCanLeft] = useState(false)
  const [canRight, setCanRight] = useState(false)

  const update = useCallback(() => {
    const el = viewportRef.current
    if (!el) return
    setCanLeft(el.scrollLeft > 1)
    setCanRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 1)
  }, [])

  // 容器与内容尺寸变化都重估可滚状态（窗口缩放、extra 增减、页签增删/文案变长）
  useEffect(() => {
    const el = viewportRef.current
    if (!el) return
    update()
    const ro = new ResizeObserver(update)
    ro.observe(el)
    if (el.firstElementChild) ro.observe(el.firstElementChild)
    return () => ro.disconnect()
  }, [update])

  // 悬停时纵向滚轮转横向。React 的 onWheel 是 passive 监听（preventDefault 无效），须原生注册
  useEffect(() => {
    const el = viewportRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      if (el.scrollWidth <= el.clientWidth) return
      if (Math.abs(e.deltaY) <= Math.abs(e.deltaX)) return
      el.scrollLeft += e.deltaY
      e.preventDefault()
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [])

  // 选中项入镜（外层受控切换页签时，保证目标页签可见）
  useEffect(() => {
    if (!activeSelector) return
    viewportRef.current
      ?.querySelector(activeSelector)
      ?.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' })
  }, [activeSelector, children])

  const scrollBy = (dir: 1 | -1) => {
    const el = viewportRef.current
    el?.scrollBy({ left: dir * el.clientWidth * 0.8, behavior: 'smooth' })
  }

  const cls = ['scroll-row', canLeft && 'has-left', canRight && 'has-right', className]
    .filter(Boolean)
    .join(' ')
  return (
    <div className={cls} style={style}>
      {canLeft && (
        <button type="button" className="scroll-row-arrow left" aria-label="向左滚动" onClick={() => scrollBy(-1)}>
          <LeftOutlined />
        </button>
      )}
      <div className="scroll-row-viewport" ref={viewportRef} onScroll={update}>
        <div className="scroll-row-inner" style={{ gap }}>
          {children}
        </div>
      </div>
      {canRight && (
        <button type="button" className="scroll-row-arrow right" aria-label="向右滚动" onClick={() => scrollBy(1)}>
          <RightOutlined />
        </button>
      )}
    </div>
  )
}
