// 只读 Markdown 渲染（FR-03）：react-markdown + 轻量样式。
import ReactMarkdown from 'react-markdown'

export default function Markdown({ content }: { content: string }) {
  return (
    <div className="doc-md" style={{ lineHeight: 1.7 }}>
      <ReactMarkdown>{content || ''}</ReactMarkdown>
    </div>
  )
}
