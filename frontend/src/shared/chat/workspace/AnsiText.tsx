// CAP-60 ANSI 渲染层：parseAnsi 分段 → <span>，无样式段原样返回（继承父级颜色）。
import type { CSSProperties, ReactNode } from 'react'
import { parseAnsi } from './ansi'

export function renderAnsi(text: string): ReactNode[] {
  return parseAnsi(text).map((seg, i) => {
    const s = seg.style
    const css: CSSProperties = {}
    if (s.color) css.color = s.color
    if (s.background) css.background = s.background
    if (s.bold) css.fontWeight = 600
    if (s.dim) css.opacity = 0.65
    if (s.italic) css.fontStyle = 'italic'
    if (s.underline) css.textDecoration = 'underline'
    return Object.keys(css).length > 0 ? (
      <span key={i} style={css}>
        {seg.text}
      </span>
    ) : (
      seg.text
    )
  })
}
