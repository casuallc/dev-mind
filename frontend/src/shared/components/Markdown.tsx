// 只读 Markdown 渲染：react-markdown + remark-gfm（表格/删除线等 GFM 语法）+ 轻量样式（.doc-md 见 index.css）。
// 附件链接（/api/attachments/...）渲染期统一刷 token：剥掉文本里烤入的陈旧 access_token
// （早期 DocEditorPage 写入姿势，会过期失效）再拼当前登录 token，裸路径/存量烤 token 文本均可正常出图。
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { withAccessToken } from '../attachments/url'

/** 附件库同源路径：剥陈旧 access_token 后重拼当前 token；非附件路径原样返回 */
function refreshAttachmentToken(src: string | undefined): string | undefined {
  if (!src || !src.startsWith('/api/attachments/')) {
    return src
  }
  const bare = src.replace(/([?&])access_token=[^&]*&?/, '$1').replace(/[?&]$/, '')
  return withAccessToken(bare)
}

export default function Markdown({ content }: { content: string }) {
  return (
    <div className="doc-md" style={{ lineHeight: 1.7 }}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          img: ({ src, alt, ...rest }) => <img src={refreshAttachmentToken(src)} alt={alt} {...rest} />,
          a: ({ href, children, ...rest }) => (
            <a href={refreshAttachmentToken(href)} target="_blank" rel="noreferrer" {...rest}>
              {children}
            </a>
          ),
        }}
      >
        {content || ''}
      </ReactMarkdown>
    </div>
  )
}
