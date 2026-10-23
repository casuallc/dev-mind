// 后端高亮片段渲染：只认 <mark>/<em> 标记，其余按文本节点输出（转义实体还原但不注入 HTML）。
// 后端已对正文做 HTML 转义，这里若用 dangerouslySetInnerHTML 等于把文档内容当代码执行——绝不能这么干。
import { Fragment } from 'react'

function decodeEntities(text: string): string {
  return text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
}

export default function HighlightedSnippet({ text }: { text: string }) {
  const parts = text.split(/(<\/?(?:mark|em)>)/gi)
  let marked = false
  return (
    <>
      {parts.map((part, i) => {
        if (/^<mark>$/i.test(part) || /^<em>$/i.test(part)) {
          marked = true
          return null
        }
        if (/^<\/mark>$/i.test(part) || /^<\/em>$/i.test(part)) {
          marked = false
          return null
        }
        if (!part) return null
        const plain = decodeEntities(part)
        return marked ? (
          <mark key={i} style={{ background: '#ffe58f', padding: '0 1px' }}>
            {plain}
          </mark>
        ) : (
          <Fragment key={i}>{plain}</Fragment>
        )
      })}
    </>
  )
}
