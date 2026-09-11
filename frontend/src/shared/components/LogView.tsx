// 通用日志查看器：左侧行号 + 关键词高亮搜索（计数/上下定位/回车跳下一个）
// + 跟随最新自动滚动（上翻暂停，浮出「回到底部」恢复）+ 下载为 .log。
// 构建/部署等执行日志共用；text 全量受控传入，实时流由父组件拼接。
import { Button, Input, Space, Typography } from 'antd'
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DownloadOutlined,
  VerticalAlignBottomOutlined,
} from '@ant-design/icons'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'

export default function LogView({ text, downloadName = 'log', maxHeight = 'calc(100vh - 200px)' }: {
  text: string
  downloadName?: string
  maxHeight?: string
}) {
  const [kw, setKw] = useState('')
  const [current, setCurrent] = useState(0)
  const [follow, setFollow] = useState(true) // 跟随最新日志：用户上翻自动暂停，回到底部恢复
  const bodyRef = useRef<HTMLDivElement | null>(null)

  // 按行渲染（左侧行号）；有关键词时行内切片高亮，<mark data-mi=序号> 序号全局连续（大小写不敏感）
  const rendered = useMemo(() => {
    if (!text) return null
    const k = kw.toLowerCase()
    let ordinal = 0
    const rows = text.split('\n').map((line, li) => {
      let content: ReactNode = line
      if (k) {
        const lower = line.toLowerCase()
        const parts: ReactNode[] = []
        let i = 0
        for (;;) {
          const idx = lower.indexOf(k, i)
          if (idx === -1) {
            parts.push(line.slice(i))
            break
          }
          if (idx > i) parts.push(line.slice(i, idx))
          const o = ordinal++
          parts.push(
            <mark
              key={o}
              data-mi={o}
              style={{
                padding: 0,
                color: '#0f1115',
                background: o === current ? '#fa8c16' : '#d4b106',
              }}
            >
              {line.slice(idx, idx + kw.length)}
            </mark>,
          )
          i = idx + kw.length
        }
        content = parts
      }
      return { li, content }
    })
    return { rows, total: ordinal }
  }, [text, kw, current])
  const total = rendered?.total ?? 0

  // 跟随模式：新日志到达自动滚到底部
  useEffect(() => {
    if (follow && bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight
  }, [text, follow])

  // 输入关键词后跳到第一处匹配
  useEffect(() => {
    if (!kw || !total) return
    setCurrent(0)
    requestAnimationFrame(() => {
      bodyRef.current?.querySelector('[data-mi="0"]')?.scrollIntoView({ block: 'center' })
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kw])

  const onScroll = () => {
    const el = bodyRef.current
    if (!el) return
    setFollow(el.scrollHeight - el.scrollTop - el.clientHeight < 40)
  }

  const jumpTo = (idx: number) => {
    if (!total) return
    const next = ((idx % total) + total) % total
    setCurrent(next)
    requestAnimationFrame(() => {
      bodyRef.current?.querySelector(`[data-mi="${next}"]`)?.scrollIntoView({ block: 'center' })
    })
  }

  const scrollToBottom = () => {
    if (bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight
  }

  const download = () => {
    if (!text) return
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${downloadName}.log`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div>
      <Space size={8} wrap style={{ marginBottom: 8 }}>
        <Input
          allowClear
          placeholder="搜索日志"
          style={{ width: 220 }}
          value={kw}
          onChange={(e) => setKw(e.target.value)}
          onPressEnter={() => jumpTo(current + 1)}
        />
        {kw && (
          <Typography.Text type={total ? undefined : 'danger'} style={{ minWidth: 48 }}>
            {total ? `${current + 1}/${total}` : '0/0'}
          </Typography.Text>
        )}
        <Button size="small" icon={<ArrowUpOutlined />} disabled={!total} onClick={() => jumpTo(current - 1)} />
        <Button size="small" icon={<ArrowDownOutlined />} disabled={!total} onClick={() => jumpTo(current + 1)} />
        <Button size="small" icon={<DownloadOutlined />} disabled={!text} onClick={download}>
          下载
        </Button>
      </Space>
      <div style={{ position: 'relative' }}>
        <div
          ref={bodyRef}
          onScroll={onScroll}
          style={{
            background: '#0f1115',
            color: '#d0d7de',
            padding: '12px 12px 12px 0',
            borderRadius: 6,
            fontSize: 12,
            lineHeight: 1.6,
            fontFamily: 'Consolas, Menlo, monospace',
            maxHeight,
            overflow: 'auto',
          }}
        >
          {rendered
            ? rendered.rows.map((r) => (
                <div key={r.li} style={{ display: 'flex', minHeight: '1.6em' }}>
                  <span
                    style={{
                      flex: '0 0 48px',
                      paddingRight: 12,
                      textAlign: 'right',
                      color: '#6e7681',
                      userSelect: 'none',
                    }}
                  >
                    {r.li + 1}
                  </span>
                  <span style={{ flex: 1, minWidth: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                    {r.content}
                  </span>
                </div>
              ))
            : '（等待日志…）'}
        </div>
        {!follow && (
          <Button
            size="small"
            icon={<VerticalAlignBottomOutlined />}
            style={{ position: 'absolute', right: 24, bottom: 16, opacity: 0.9 }}
            onClick={scrollToBottom}
          >
            回到底部
          </Button>
        )}
      </div>
    </div>
  )
}
