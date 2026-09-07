// Jira 来源需求描述渲染（CAP-19 FR-09）：description 为 Jira wiki 原文，其中图片标记
// !name.png|attrs! 解析为 antd Image——附件名走项目作用域代理端点（/jira/attachments?name=），
// <img> 无法带 Authorization header，统一 withAccessToken 拼 ?access_token=（CAP-32 同款机制）；
// http(s) 外链图直渲。文本段保持 pre-wrap 纯文本原样（wiki 其他语法不转换）。
import { useState } from 'react'
import { Image, Typography } from 'antd'
import { withAccessToken } from '../../../shared/attachments/url'

/** wiki 图片标记：!文件名! 或 !文件名|width=300,thumbnail!（属性段剥掉只留文件名） */
const WIKI_IMAGE = /!([^!\n|]+?)(\|[^!\n]*)?!/g

type Segment = { kind: 'text'; text: string } | { kind: 'image'; target: string }

function parseSegments(description: string): Segment[] {
  const segments: Segment[] = []
  let last = 0
  for (const m of description.matchAll(WIKI_IMAGE)) {
    const idx = m.index ?? 0
    if (idx > last) {
      segments.push({ kind: 'text', text: description.slice(last, idx) })
    }
    segments.push({ kind: 'image', target: m[1].trim() })
    last = idx + m[0].length
  }
  if (last < description.length) {
    segments.push({ kind: 'text', text: description.slice(last) })
  }
  return segments
}

function JiraImage({ src, name }: { src: string; name: string }) {
  const [failed, setFailed] = useState(false)
  if (failed) {
    return <Typography.Text type="secondary" style={{ fontSize: 12 }}>[图片不可用: {name}]</Typography.Text>
  }
  return (
    <Image
      src={src}
      alt={name}
      style={{ maxWidth: 360, maxHeight: 240, objectFit: 'contain', verticalAlign: 'top' }}
      onError={() => setFailed(true)}
    />
  )
}

export default function JiraDescription({ description, pid, rid }: {
  description: string
  pid: string
  rid: string
}) {
  const segments = parseSegments(description)
  return (
    <div style={{ fontSize: 13, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
      {segments.map((s, i) => {
        if (s.kind === 'text') {
          return <span key={i}>{s.text}</span>
        }
        const external = /^https?:\/\//.test(s.target)
        const src = external
          ? s.target
          : withAccessToken(
              `/api/projects/${pid}/requirements/${rid}/jira/attachments?name=${encodeURIComponent(s.target)}`)
        return (
          <span key={i} style={{ display: 'inline-block', margin: '4px 8px 4px 0' }}>
            <JiraImage src={src} name={s.target} />
          </span>
        )
      })}
    </div>
  )
}
