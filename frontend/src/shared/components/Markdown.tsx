// 只读 Markdown 渲染（全站阅读口径）：react-markdown + remark-gfm + rehype-highlight。
// - 代码块：语言标签 + 一键复制 + highlight.js 轻量高亮（常见语言；只认显式围栏语言，
//   不做自动识别——误判出来的语言标签比不高亮更误导）；
// - 表格：横向滚动容器，窄屏不撑破内容区；
// - 图片：antd Image 预览（多图同组左右切换）；
// - 附件链接（/api/attachments/...）渲染期统一刷 token：剥掉文本里烤入的陈旧 access_token
//   （早期 DocEditorPage 写入姿势，会过期失效）再拼当前登录 token，裸路径/存量烤 token 文本均可正常出图。
import { memo, useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import type { Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import type { PluggableList } from 'unified'
import { Button, Image, Tooltip, message } from 'antd'
import { CheckOutlined, CopyOutlined } from '@ant-design/icons'
import { withAccessToken } from '../attachments/url'

/** 附件库同源路径：剥陈旧 access_token 后重拼当前 token；非附件路径原样返回 */
function refreshAttachmentToken(src: string | undefined): string | undefined {
  if (!src || !src.startsWith('/api/attachments/')) {
    return src
  }
  const bare = src.replace(/([?&])access_token=[^&]*&?/, '$1').replace(/[?&]$/, '')
  return withAccessToken(bare)
}

const REHYPE_PLUGINS = [[rehypeHighlight, { detect: false, ignoreMissing: true }]] as PluggableList

const LANG_LABEL: Record<string, string> = {
  js: 'JavaScript', javascript: 'JavaScript', mjs: 'JavaScript', cjs: 'JavaScript',
  ts: 'TypeScript', typescript: 'TypeScript', tsx: 'TSX', jsx: 'JSX',
  py: 'Python', python: 'Python', java: 'Java', kotlin: 'Kotlin', go: 'Go', rust: 'Rust',
  c: 'C', cpp: 'C++', cs: 'C#', csharp: 'C#', php: 'PHP', ruby: 'Ruby', swift: 'Swift',
  sh: 'Shell', bash: 'Bash', shell: 'Shell', zsh: 'Shell', ps1: 'PowerShell', powershell: 'PowerShell',
  json: 'JSON', jsonc: 'JSON', yaml: 'YAML', yml: 'YAML', toml: 'TOML', ini: 'INI', properties: 'Properties',
  html: 'HTML', xml: 'XML', css: 'CSS', scss: 'SCSS', less: 'Less', sql: 'SQL',
  md: 'Markdown', markdown: 'Markdown', diff: 'Diff', patch: 'Diff', dockerfile: 'Dockerfile',
}

/** hast 节点的最小结构（react-markdown 传入的 node 是完整 Element，结构兼容） */
interface HastNode {
  type?: string
  tagName?: string
  value?: string
  properties?: Record<string, unknown>
  children?: HastNode[]
}

function hastText(node?: HastNode): string {
  if (!node) return ''
  if (node.type === 'text') return node.value ?? ''
  return (node.children ?? []).map(hastText).join('')
}

function codeInfo(node?: HastNode): { lang?: string; code: string } {
  const codeEl = node?.children?.find((c) => c.tagName === 'code')
  const cls = codeEl?.properties?.className
  const classes = Array.isArray(cls) ? cls : typeof cls === 'string' ? cls.split(/\s+/) : []
  const lang = classes
    .find((c): c is string => typeof c === 'string' && c.startsWith('language-'))
    ?.slice('language-'.length)
  return { lang, code: hastText(codeEl ?? node).replace(/\n$/, '') }
}

async function copyToClipboard(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    // 非安全上下文（http 局域网）clipboard API 不可用，退化为 execCommand
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
}

function CodeBlock({ node, children }: { node?: HastNode; children?: React.ReactNode }) {
  const { lang, code } = codeInfo(node)
  const [copied, setCopied] = useState(false)
  const timerRef = useRef<number | undefined>(undefined)

  useEffect(() => () => window.clearTimeout(timerRef.current), [])

  const onCopy = async () => {
    try {
      await copyToClipboard(code)
      message.success('代码已复制')
      setCopied(true)
      window.clearTimeout(timerRef.current)
      timerRef.current = window.setTimeout(() => setCopied(false), 1500)
    } catch {
      message.error('复制失败')
    }
  }

  return (
    <div className="md-code">
      <div className="md-code-head">
        <span className="md-code-lang">{lang ? (LANG_LABEL[lang.toLowerCase()] ?? lang) : '代码'}</span>
        <Tooltip title={copied ? '已复制' : '复制代码'}>
          <Button
            className="md-code-copy"
            size="small"
            type="text"
            aria-label="复制代码"
            icon={copied ? <CheckOutlined /> : <CopyOutlined />}
            onClick={onCopy}
          />
        </Tooltip>
      </div>
      <pre>{children}</pre>
    </div>
  )
}

const COMPONENTS: Components = {
  pre: ({ node, children }) => <CodeBlock node={node}>{children}</CodeBlock>,
  // 图片由 antd Image 包裹（根节点是 div），含图段落改用 div，避免 div 嵌在 p 里的非法嵌套告警
  p: ({ node, children }) =>
    node?.children?.some((c) => c.type === 'element' && c.tagName === 'img')
      ? <div className="md-img-block">{children}</div>
      : <p>{children}</p>,
  table: ({ node, ...props }) => {
    void node
    return (
      <div className="md-table-wrap">
        <table {...props} />
      </div>
    )
  },
  img: ({ node, src, alt, title, width, height }) => {
    void node
    return (
      <Image
        src={refreshAttachmentToken(src)}
        alt={alt ?? ''}
        title={title}
        width={width}
        height={height}
        preview={{ mask: '预览' }}
      />
    )
  },
  a: ({ node, href, children, ...rest }) => {
    void node
    return (
      <a href={refreshAttachmentToken(href)} target="_blank" rel="noreferrer" {...rest}>
        {children}
      </a>
    )
  },
}

export interface MarkdownProps {
  content?: string
  /** 空内容占位；传 false 不渲染占位（默认「暂无内容」） */
  emptyText?: React.ReactNode | false
}

function Markdown({ content, emptyText }: MarkdownProps) {
  if (!content?.trim()) {
    if (emptyText === false) return null
    return <div className="doc-md md-empty">{emptyText ?? '暂无内容'}</div>
  }
  return (
    <div className="doc-md" style={{ lineHeight: 1.7 }}>
      <Image.PreviewGroup>
        <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={REHYPE_PLUGINS} components={COMPONENTS}>
          {content}
        </ReactMarkdown>
      </Image.PreviewGroup>
    </div>
  )
}

export default memo(Markdown)
