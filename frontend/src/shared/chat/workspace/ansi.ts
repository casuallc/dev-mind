// CAP-60 ANSI 转义序列解析器（纯函数，无 React 依赖，node 可直跑冒烟）。
// 追加式终端面板只取 SGR 样式子集：16 色/256 色/truecolor 前景背景、bold/dim/italic/underline；
// 其余序列（光标移动/清屏 CSI、OSC 标题/超链接、字符集切换、\r）一律吞掉不显示——
// 面板是追加日志模型，不支持原地重绘（进度条类只保留文字，方向 C PTY 才管交互重绘）。

export interface AnsiStyle {
  color?: string
  background?: string
  bold?: boolean
  dim?: boolean
  italic?: boolean
  underline?: boolean
}

export interface AnsiSegment {
  text: string
  style: AnsiStyle
}

/** 16 色调色板（暗色终端底配色，亮色系同时服务 bold 增亮语义） */
const BASE16: string[] = [
  '#555753', '#ef2929', '#8ae234', '#fce94f', '#729fcf', '#ad7fa8', '#34e2e2', '#d3d7cf',
  '#888a85', '#ff7875', '#95de64', '#ffe58f', '#85a5ff', '#ff85c0', '#5cdbd3', '#ffffff',
]

const CUBE_LEVELS = [0, 95, 135, 175, 215, 255]

/** xterm 256 色编号 → hex */
export function color256(n: number): string {
  if (n < 16) return BASE16[Math.max(0, n)]
  if (n < 232) {
    const i = n - 16
    const r = CUBE_LEVELS[Math.floor(i / 36) % 6]
    const g = CUBE_LEVELS[Math.floor(i / 6) % 6]
    const b = CUBE_LEVELS[i % 6]
    return `#${hex2(r)}${hex2(g)}${hex2(b)}`
  }
  const v = 8 + Math.min(23, n - 232) * 10
  return `#${hex2(v)}${hex2(v)}${hex2(v)}`
}

function hex2(v: number): string {
  return v.toString(16).padStart(2, '0')
}

// OSC（]...BEL / ]...ESC\）| SGR（[...m，捕获参数）| 其余 CSI | 字符集/单行 ESC | 回车/响铃
const ESC_RE =
  // eslint-disable-next-line no-control-regex
  /\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b\[([0-9;]*)m|\x1b\[[0-9;?]*[A-Za-z]|\x1b[()#][0-9A-B]|\x1b.|[\r\x07]/g

/** 应用一条 SGR 参数序列到 style（原地改） */
function applySgr(style: AnsiStyle, params: string): void {
  const codes = params === '' ? [0] : params.split(';').map((p) => (p === '' ? 0 : parseInt(p, 10)))
  for (let i = 0; i < codes.length; i++) {
    const c = codes[i]
    if (c === 0) {
      for (const k of Object.keys(style) as (keyof AnsiStyle)[]) delete style[k]
    } else if (c === 1) style.bold = true
    else if (c === 2) style.dim = true
    else if (c === 3) style.italic = true
    else if (c === 4) style.underline = true
    else if (c === 22) {
      delete style.bold
      delete style.dim
    } else if (c === 23) delete style.italic
    else if (c === 24) delete style.underline
    else if (c >= 30 && c <= 37) style.color = BASE16[c - 30]
    else if (c >= 90 && c <= 97) style.color = BASE16[c - 90 + 8]
    else if (c === 39) delete style.color
    else if (c >= 40 && c <= 47) style.background = BASE16[c - 40]
    else if (c >= 100 && c <= 107) style.background = BASE16[c - 100 + 8]
    else if (c === 49) delete style.background
    else if ((c === 38 || c === 48) && i + 1 < codes.length) {
      const isFg = c === 38
      if (codes[i + 1] === 5 && i + 2 < codes.length) {
        if (isFg) style.color = color256(codes[i + 2])
        else style.background = color256(codes[i + 2])
        i += 2
      } else if (codes[i + 1] === 2 && i + 4 < codes.length) {
        const hex = `#${hex2(codes[i + 2])}${hex2(codes[i + 3])}${hex2(codes[i + 4])}`
        if (isFg) style.color = hex
        else style.background = hex
        i += 4
      }
    }
  }
}

/** 裸文本 → 分段序列（无样式段 style 为空对象，渲染层继承父色） */
export function parseAnsi(text: string): AnsiSegment[] {
  const segs: AnsiSegment[] = []
  const style: AnsiStyle = {}
  let last = 0
  ESC_RE.lastIndex = 0
  for (let m = ESC_RE.exec(text); m !== null; m = ESC_RE.exec(text)) {
    if (m.index > last) segs.push({ text: text.slice(last, m.index), style: { ...style } })
    if (m[1] !== undefined) applySgr(style, m[1])
    last = m.index + m[0].length
  }
  if (last < text.length) segs.push({ text: text.slice(last), style: { ...style } })
  return segs
}
