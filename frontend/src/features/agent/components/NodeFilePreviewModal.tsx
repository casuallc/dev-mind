// CAP-65 节点文件预览/编辑弹窗：按文件类型渲染（markdown→渲染视图 / 图片→blob 直出 / shell→语法高亮 / txt→纯文本），
// 支持放大还原、内容查找（预览模式 mark 高亮定位、编辑模式选区定位；md 渲染下查找自动跳源码）、自动换行。
// 文本类可编辑（预览/编辑 Segmented 切换，dirty 才放行保存，关闭有未保存确认）；图片只读。
import { useEffect, useMemo, useRef, useState } from 'react'
import { Button, Input, Modal, Segmented, Space, Switch, Tag, Tooltip, Typography, message } from 'antd'
import {
  DownOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  UpOutlined,
} from '@ant-design/icons'
import type { TextAreaRef } from 'antd/es/input/TextArea'
import type { AgentNode, NodeFileEntry } from '../types'
import { fetchNodeFileBlob, writeNodeFile } from '../api'
import Markdown from '../../../shared/components/Markdown'
import { showError } from '../../../shared/utils/showError'

/** 预览/编辑等宽字体栈（CAP-60 终端同款） */
const MONO_FONT = "'JetBrains Mono', 'Cascadia Code', 'Cascadia Mono', Consolas, Menlo, 'Courier New', monospace"

export type NodeFileKind = 'image' | 'markdown' | 'shell' | 'text'

const KIND_LABEL: Record<NodeFileKind, string> = {
  image: '图片',
  markdown: 'Markdown',
  shell: 'Shell',
  text: '文本',
}

/** 按扩展名分渲染类型（未识别一律当纯文本） */
export function fileKindOf(name: string): NodeFileKind {
  const ext = name.includes('.') ? name.split('.').pop()!.toLowerCase() : ''
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico'].includes(ext)) return 'image'
  if (['md', 'markdown'].includes(ext)) return 'markdown'
  if (['sh', 'bash', 'zsh'].includes(ext)) return 'shell'
  return 'text'
}

// ---------------- shell 轻量语法高亮 + 查找命中叠加 ----------------

type Seg = { text: string; cls?: 'cm' | 'str' | 'kw'; mark?: boolean; cur?: boolean }

const SH_RE =
  /(#.*$)|("(?:[^"\\]|\\.)*")|('[^']*')|\b(if|then|else|elif|fi|for|while|until|do|done|case|esac|in|function|return|exit|echo|export|local|readonly|shift|source|set|unset|trap|eval|exec|sudo)\b/gm

/** 基础 token 流（shell 高亮；纯文本整段一个 token），覆盖全文且有序 */
function baseTokens(text: string, shell: boolean): { start: number; end: number; cls?: Seg['cls'] }[] {
  if (!shell) return [{ start: 0, end: text.length }]
  const toks: { start: number; end: number; cls?: Seg['cls'] }[] = []
  let last = 0
  SH_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = SH_RE.exec(text))) {
    if (m.index > last) toks.push({ start: last, end: m.index })
    const cls: Seg['cls'] = m[1] ? 'cm' : m[2] || m[3] ? 'str' : 'kw'
    toks.push({ start: m.index, end: m.index + m[0].length, cls })
    last = m.index + m[0].length
    if (m[0].length === 0) SH_RE.lastIndex++
  }
  if (last < text.length) toks.push({ start: last, end: text.length })
  return toks
}

/** token 流与查找命中按全局偏移切齐，产出可直渲染的段序列（双指针，避免 O(n·m)） */
function buildSegs(text: string, shell: boolean, matches: number[], qlen: number, curIdx: number): Seg[] {
  const toks = baseTokens(text, shell)
  const cuts = new Set<number>([0, text.length])
  toks.forEach((t) => {
    cuts.add(t.start)
    cuts.add(t.end)
  })
  matches.forEach((s) => {
    cuts.add(s)
    cuts.add(s + qlen)
  })
  const pts = [...cuts].sort((a, b) => a - b)
  const segs: Seg[] = []
  let ti = 0
  let mi = 0
  for (let i = 0; i < pts.length - 1; i++) {
    const a = pts[i]
    const b = pts[i + 1]
    if (a === b) continue
    while (ti < toks.length - 1 && toks[ti].end <= a) ti++
    while (mi < matches.length && matches[mi] + qlen <= a) mi++
    const cls = toks[ti] && toks[ti].start <= a ? toks[ti].cls : undefined
    const marked = mi < matches.length && matches[mi] <= a
    segs.push({ text: text.slice(a, b), cls, mark: marked, cur: marked && mi === curIdx })
  }
  return segs
}

const CLS_STYLE: Record<NonNullable<Seg['cls']>, React.CSSProperties> = {
  cm: { color: '#6a9955' },
  str: { color: '#ce9178' },
  kw: { color: '#1890ff', fontWeight: 600 },
}

export default function NodeFilePreviewModal({
  node,
  root,
  dir,
  entry,
  kind,
  initialContent,
  onClose,
}: {
  node: AgentNode
  root: string
  dir: string
  entry: NodeFileEntry
  kind: NodeFileKind
  /** 文本类已读出的内容；图片传 null（弹窗自行拉 blob） */
  initialContent: string | null
  onClose: (saved: boolean) => void
}) {
  const rel = dir ? `${dir}/${entry.name}` : entry.name
  const [orig] = useState(initialContent ?? '')
  const [draft, setDraft] = useState(initialContent ?? '')
  const [mode, setMode] = useState<'preview' | 'edit'>('preview')
  const [full, setFull] = useState(false)
  const [wrap, setWrap] = useState(true)
  const [saving, setSaving] = useState(false)

  const [query, setQuery] = useState('')
  const [curIdx, setCurIdx] = useState(0)

  const [imgUrl, setImgUrl] = useState<string | null>(null)
  const [imgErr, setImgErr] = useState<string | null>(null)

  const taRef = useRef<TextAreaRef>(null)
  const preRef = useRef<HTMLPreElement>(null)
  const pendingJump = useRef<number | null>(null)

  // 图片：走下载端点拉 blob 直出（卸载回收 objectURL）
  useEffect(() => {
    if (kind !== 'image') return
    let url: string | null = null
    fetchNodeFileBlob(node.id, root, rel)
      .then((b) => {
        url = URL.createObjectURL(b)
        setImgUrl(url)
      })
      .catch((e) => setImgErr((e as Error).message))
    return () => {
      if (url) URL.revokeObjectURL(url)
    }
  }, [kind, node.id, root, rel])

  // 命中偏移列表（大小写不敏感，封顶 5000 防大卡）
  const matches = useMemo(() => {
    if (!query || kind === 'image') return []
    const q = query.toLowerCase()
    const lower = draft.toLowerCase()
    const out: number[] = []
    let i = lower.indexOf(q)
    while (i !== -1 && out.length < 5000) {
      out.push(i)
      i = lower.indexOf(q, i + q.length)
    }
    return out
  }, [draft, query, kind])

  const cur = matches.length ? Math.min(curIdx, matches.length - 1) : 0

  // txt/shell 预览段序列（高亮 + 命中 mark 一次算好）
  const segs = useMemo(
    () =>
      (kind === 'shell' || kind === 'text') && mode === 'preview'
        ? buildSegs(draft, kind === 'shell', matches, query.length, cur)
        : null,
    [draft, kind, mode, matches, query, cur],
  )

  // 预览模式：当前命中滚动进可视区
  useEffect(() => {
    if (mode !== 'preview' || !query) return
    preRef.current?.querySelector('[data-cur="1"]')?.scrollIntoView({ block: 'center' })
  }, [cur, query, mode, segs])

  // 编辑模式：选区定位 + 按行号估算滚动（等宽 12px ≈ 18px 行高）
  useEffect(() => {
    if (pendingJump.current == null || mode !== 'edit') return
    const idx = pendingJump.current
    pendingJump.current = null
    const ta = taRef.current?.resizableTextArea?.textArea
    const start = matches[idx]
    if (!ta || start == null) return
    ta.focus()
    ta.setSelectionRange(start, start + query.length)
    const line = draft.slice(0, start).split('\n').length
    ta.scrollTop = Math.max(0, (line - 5) * 18)
  }, [mode, matches, draft, query, curIdx])

  /** 上/下一个：md 渲染态下查找自动切源码定位 */
  const step = (d: 1 | -1) => {
    if (!matches.length) return
    const next = (cur + d + matches.length) % matches.length
    setCurIdx(next)
    if (mode === 'edit' || kind === 'markdown') {
      if (mode !== 'edit') setMode('edit')
      pendingJump.current = next
    }
  }

  const save = async () => {
    setSaving(true)
    try {
      await writeNodeFile(node.id, root, rel, draft)
      message.success(`已保存 ${entry.name}`)
      onClose(true)
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const close = () => {
    if (kind !== 'image' && draft !== orig) {
      Modal.confirm({
        centered: true,
        title: '放弃未保存的修改？',
        okText: '放弃',
        okButtonProps: { danger: true },
        cancelText: '继续编辑',
        onOk: () => onClose(false),
      })
      return
    }
    onClose(false)
  }

  const contentH = full ? 'calc(100vh - 220px)' : 420
  const dirty = draft !== orig

  return (
    <Modal
      centered
      open
      width={full ? 'calc(100vw - 32px)' : 960}
      onCancel={close}
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingRight: 24 }}>
          <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {entry.name}
          </span>
          <Tag style={{ marginRight: 0 }}>{KIND_LABEL[kind]}</Tag>
          <Tooltip title={full ? '还原' : '放大'}>
            <Button
              type="text"
              size="small"
              icon={full ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
              onClick={() => setFull((f) => !f)}
            />
          </Tooltip>
        </div>
      }
      footer={
        kind === 'image' ? (
          <Button onClick={close}>关闭</Button>
        ) : (
          <Space>
            <Button onClick={close}>关闭</Button>
            <Button type="primary" loading={saving} disabled={!dirty} onClick={() => void save()}>
              保存
            </Button>
          </Space>
        )
      }
    >
      {kind !== 'image' && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', marginBottom: 8 }}>
          <Segmented
            value={mode}
            options={[
              { label: '预览', value: 'preview' },
              { label: '编辑', value: 'edit' },
            ]}
            onChange={(v) => setMode(v as 'preview' | 'edit')}
          />
          <span style={{ flex: 1 }} />
          <Space size={4}>
            <Switch size="small" checked={wrap} onChange={setWrap} />
            <Typography.Text type="secondary">自动换行</Typography.Text>
          </Space>
          <Space size={4}>
            <Input
              allowClear
              style={{ width: 200 }}
              placeholder="查找（Enter 下一个）"
              value={query}
              onChange={(e) => {
                setQuery(e.target.value)
                setCurIdx(0)
              }}
              onPressEnter={() => step(1)}
            />
            <Typography.Text type="secondary" style={{ minWidth: 48, textAlign: 'center' }}>
              {query ? `${matches.length ? cur + 1 : 0}/${matches.length}` : ''}
            </Typography.Text>
            <Button icon={<UpOutlined />} disabled={!matches.length} onClick={() => step(-1)} />
            <Button icon={<DownOutlined />} disabled={!matches.length} onClick={() => step(1)} />
          </Space>
        </div>
      )}

      {kind === 'image' ? (
        <div style={{ textAlign: 'center' }}>
          {imgErr ? (
            <Typography.Text type="danger">图片加载失败：{imgErr}</Typography.Text>
          ) : imgUrl ? (
            <img
              src={imgUrl}
              alt={entry.name}
              style={{ maxWidth: '100%', maxHeight: full ? 'calc(100vh - 200px)' : 480, objectFit: 'contain' }}
            />
          ) : (
            <Typography.Text type="secondary">加载中…</Typography.Text>
          )}
        </div>
      ) : mode === 'edit' ? (
        <Input.TextArea
          ref={taRef}
          wrap={wrap ? 'soft' : 'off'}
          style={{ fontFamily: MONO_FONT, fontSize: 12, height: contentH, resize: 'none' }}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
      ) : kind === 'markdown' ? (
        <div style={{ height: contentH, overflow: 'auto', padding: '0 4px' }}>
          <Markdown content={draft} />
        </div>
      ) : (
        <pre
          ref={preRef}
          style={{
            margin: 0,
            padding: 12,
            background: '#fafafa',
            border: '1px solid #f0f0f0',
            borderRadius: 8,
            overflow: 'auto',
            height: contentH,
            fontFamily: MONO_FONT,
            fontSize: 12,
            lineHeight: 1.6,
            whiteSpace: wrap ? 'pre-wrap' : 'pre',
            wordBreak: wrap ? 'break-all' : 'normal',
          }}
        >
          {(segs ?? []).map((s, i) => (
            <span
              key={i}
              data-cur={s.cur ? '1' : undefined}
              style={{
                ...(s.cls ? CLS_STYLE[s.cls] : undefined),
                ...(s.mark ? { background: s.cur ? '#ffa940' : '#ffe58f' } : undefined),
              }}
            >
              {s.text}
            </span>
          ))}
        </pre>
      )}
    </Modal>
  )
}
