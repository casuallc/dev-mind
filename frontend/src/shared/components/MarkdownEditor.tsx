// 共享 Markdown 编辑器（CAP-44）：工具栏按选区包裹/行前缀 + 编辑/分栏/预览三态 + 全屏 + 快捷键。
// 源码统一用 CodeMirror：行号、Markdown 语法高亮、当前行高亮；分栏左源码右实时预览，两栏滚动条
// 按比例双向联动。showToolbar=false 切 Qoder 文档式极简顶栏（只有源码/分栏/预览三个图标、默认预览，
// 不摆格式工具栏/字符统计），阅读优先的报告场景用；fill 撑满父容器剩余高度。
// 受控组件：value/onChange 与 Input.TextArea 同型，可直接被 Form.Item 托管。
// 快捷键：Ctrl/⌘+B 加粗、Ctrl/⌘+I 斜体、Ctrl/⌘+K 链接、Tab/Shift+Tab 缩进/反缩进、Esc 退出全屏。
// attachmentUpload 开启图片/附件内联：工具栏按钮 + 粘贴/拖拽 → 上传附件库（占位符防并发错位，
// 同 DocEditorPage 姿势），文中写裸路径 /api/attachments/{id}/raw（token 渲染期由 Markdown 组件补，
// 烤进文本会过期失效）；与 CAP-40 需求附件装配同一引用口径，需求描述里的引用会被投送到会话。
import { useCallback, useDeferredValue, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Button, Segmented, Space, Tooltip, Typography, message } from 'antd'
import {
  BoldOutlined,
  CodeOutlined,
  EyeOutlined,
  FileMarkdownOutlined,
  FullscreenExitOutlined,
  FullscreenOutlined,
  ItalicOutlined,
  LinkOutlined,
  MinusOutlined,
  OrderedListOutlined,
  PaperClipOutlined,
  PictureOutlined,
  SplitCellsOutlined,
  StrikethroughOutlined,
  TableOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons'
import { EditorState } from '@codemirror/state'
import {
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers,
  placeholder as cmPlaceholder,
} from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { markdown } from '@codemirror/lang-markdown'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags } from '@lezer/highlight'
import Markdown from './Markdown'
import { uploadAttachment } from '../attachments/api'
import { showError } from '../utils/showError'

export type MarkdownEditorMode = 'edit' | 'split' | 'preview'

export interface MarkdownEditorProps {
  value?: string
  onChange?: (v: string) => void
  height?: number
  /** 撑满父容器剩余高度（父级需为确定高度的 flex 列），忽略 height */
  fill?: boolean
  /** 显示格式/附件快捷按钮、字符统计与全屏（默认 true）；false 为极简顶栏，只留源码/分栏/预览切换 */
  showToolbar?: boolean
  placeholder?: string
  disabled?: boolean
  /** 开启图片/附件上传内联；scope 默认 PRIVATE（需求描述等成员共享场景传 SHARED） */
  attachmentUpload?: { scope?: 'PRIVATE' | 'SHARED' }
  /** 初始模式：默认 edit；showToolbar=false 时默认 preview（阅读优先） */
  defaultMode?: MarkdownEditorMode
  mode?: MarkdownEditorMode
  onModeChange?: (m: MarkdownEditorMode) => void
}

interface ToolbarAction {
  key: string
  title: string
  label: React.ReactNode
  run: () => void
}

const MODE_OPTIONS = [
  { value: 'edit', label: '编辑' },
  { value: 'split', label: '分栏' },
  { value: 'preview', label: '预览' },
]

const sourceTheme = EditorView.theme({
  '&': { height: '100%', fontSize: '13px', color: 'rgba(0, 0, 0, 0.88)', backgroundColor: '#fff' },
  '&.cm-focused': { outline: 'none' },
  '.cm-scroller': {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace',
    lineHeight: '1.7',
    overflow: 'auto',
  },
  '.cm-content': { padding: '12px 0', caretColor: '#1677ff' },
  '.cm-line': { padding: '0 14px 0 8px' },
  '.cm-gutters': {
    backgroundColor: '#fafafa',
    color: '#bfbfbf',
    border: 'none',
    borderRight: '1px solid #f0f0f0',
    userSelect: 'none',
  },
  '.cm-gutterElement': { padding: '0 8px 0 12px' },
  '.cm-activeLine': { backgroundColor: 'rgba(0, 0, 0, 0.035)' },
  '.cm-activeLineGutter': { backgroundColor: 'rgba(0, 0, 0, 0.05)', color: '#595959' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: '#1677ff' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
    backgroundColor: '#d6e4ff',
  },
})

const sourceHighlight = HighlightStyle.define([
  { tag: tags.heading1, color: '#0958d9', fontWeight: '600' },
  { tag: tags.heading2, color: '#0958d9', fontWeight: '600' },
  { tag: [tags.heading3, tags.heading4, tags.heading5, tags.heading6], color: '#0958d9', fontWeight: '600' },
  { tag: tags.strong, fontWeight: '600' },
  { tag: tags.emphasis, fontStyle: 'italic' },
  { tag: [tags.link, tags.url], color: '#1677ff' },
  { tag: tags.quote, color: '#8c8c8c' },
  { tag: tags.list, color: '#d46b08' },
  { tag: [tags.meta, tags.processingInstruction], color: '#8c8c8c' },
  { tag: tags.contentSeparator, color: '#bfbfbf' },
])

export default function MarkdownEditor({
  value = '',
  onChange,
  height = 360,
  fill = false,
  showToolbar = true,
  placeholder,
  disabled,
  attachmentUpload,
  defaultMode,
  mode: controlledMode,
  onModeChange,
}: MarkdownEditorProps) {
  const [innerMode, setInnerMode] = useState<MarkdownEditorMode>(defaultMode ?? (showToolbar ? 'edit' : 'preview'))
  const mode = controlledMode ?? innerMode
  const setMode = (m: MarkdownEditorMode) => {
    setInnerMode(m)
    onModeChange?.(m)
  }
  const [fullscreen, setFullscreen] = useState(false)
  // 输入频繁时预览稍后跟进，避免每次按键都重解析整篇 Markdown（尤其是分栏态）
  const previewValue = useDeferredValue(value)
  const hostRef = useRef<HTMLDivElement>(null)
  const previewRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  const imageInputRef = useRef<HTMLInputElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const uploadSeqRef = useRef(0)
  // 异步/延迟回调拿旧闭包时也能把最新文本交回父级
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  const valueRef = useRef(value)
  valueRef.current = value
  const modeRef = useRef(mode)
  modeRef.current = mode
  const attachmentUploadRef = useRef(attachmentUpload)
  attachmentUploadRef.current = attachmentUpload
  // 程序化滚动会再触发对侧 scroll 事件，短时间内忽略对侧，避免两栏互相拉扯
  const scrollLockRef = useRef<{ ignore: 'source' | 'preview'; until: number } | null>(null)

  const syncScroll = useCallback((from: 'source' | 'preview') => {
    if (modeRef.current !== 'split') return
    const source = viewRef.current?.scrollDOM
    const preview = previewRef.current
    if (!source || !preview) return
    const lock = scrollLockRef.current
    const now = Date.now()
    if (lock && lock.ignore === from && now < lock.until) return
    const [fromEl, toEl] = from === 'source' ? [source, preview] : [preview, source]
    const fromRange = fromEl.scrollHeight - fromEl.clientHeight
    const toRange = toEl.scrollHeight - toEl.clientHeight
    if (fromRange <= 0 || toRange <= 0) return
    scrollLockRef.current = { ignore: from === 'source' ? 'preview' : 'source', until: now + 100 }
    toEl.scrollTop = (fromEl.scrollTop / fromRange) * toRange
  }, [])

  // 全屏时锁页面滚动，Esc 退出；capture 阶段拦下事件，避免连带关闭外层 Drawer
  useEffect(() => {
    if (!fullscreen) return
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopPropagation()
      setFullscreen(false)
    }
    window.addEventListener('keydown', onKey, true)
    return () => {
      document.body.style.overflow = prevOverflow
      window.removeEventListener('keydown', onKey, true)
    }
  }, [fullscreen])

  /** 源码编辑区当前选区；编辑器未挂载（预览态）返回 null */
  const selection = () => {
    const view = viewRef.current
    if (!view) return null
    const { from, to } = view.state.selection.main
    return { view, text: view.state.doc.toString(), from, to }
  }

  /** 包裹当前选区（加粗/斜体/行内码/链接等）；无选区时插入占位并选中 */
  const wrapSelection = (before: string, after: string, placeholderText = '') => {
    const sel = selection()
    if (!sel) return
    const chosen = sel.text.slice(sel.from, sel.to) || placeholderText
    sel.view.dispatch({
      changes: { from: sel.from, to: sel.to, insert: before + chosen + after },
      selection: { anchor: sel.from + before.length, head: sel.from + before.length + chosen.length },
      scrollIntoView: true,
    })
    sel.view.focus()
  }

  /** 给选区覆盖到的所有非空行加前缀（标题/列表/引用） */
  const prefixLines = (mk: (line: string, i: number) => string) => {
    const sel = selection()
    if (!sel) return
    const lineStart = sel.text.lastIndexOf('\n', Math.max(0, sel.from - 1)) + 1
    const lineBreak = sel.text.indexOf('\n', sel.to)
    const end = lineBreak === -1 ? sel.text.length : lineBreak
    const block = sel.text.slice(lineStart, end)
    const next = block.split('\n').map((l, i) => (l.trim() ? mk(l, i) : l)).join('\n')
    sel.view.dispatch({
      changes: { from: lineStart, to: end, insert: next },
      selection: { anchor: lineStart, head: lineStart + next.length },
      scrollIntoView: true,
    })
    sel.view.focus()
  }

  /** 在光标处插入块级内容（代码块/表格/分割线），自动补空行 */
  const insertBlock = (block: string, cursorOffset = 0) => {
    const sel = selection()
    if (!sel) return
    const s = sel.from
    const needLeading = s > 0 && !sel.text.slice(0, s).endsWith('\n\n')
    const text = (needLeading ? (sel.text[s - 1] === '\n' ? '\n' : '\n\n') : '') + block
    const at = s + text.length + cursorOffset
    sel.view.dispatch({
      changes: { from: s, insert: text },
      selection: { anchor: at },
      scrollIntoView: true,
    })
    sel.view.focus()
  }

  /** 在光标处插入代码片段（图片/附件引用），无焦点时追加到文末 */
  const insertSnippet = (snippet: string) => {
    const view = viewRef.current
    if (!view) {
      onChangeRef.current?.(valueRef.current + snippet)
      return
    }
    const at = view.state.selection.main.head
    view.dispatch({
      changes: { from: at, insert: snippet },
      selection: { anchor: at + snippet.length },
      scrollIntoView: true,
    })
  }

  /** 用最新文档替换占位符（上传完成时源码可能已切走，退回受控值） */
  const replaceInDoc = (placeholderText: string, replacement: string) => {
    const view = viewRef.current
    const text = view ? view.state.doc.toString() : valueRef.current
    const at = text.indexOf(placeholderText)
    if (at < 0) return
    if (view) {
      view.dispatch({
        changes: { from: at, to: at + placeholderText.length, insert: replacement },
      })
    } else {
      onChangeRef.current?.(text.slice(0, at) + replacement + text.slice(at + placeholderText.length))
    }
  }

  /** 上传附件库后光标处插入引用：图片 `![名](裸路径)`、其他 `[名](裸路径)`；占位符上传中占位，失败剔除 */
  const uploadFiles = (files: File[], imagesOnly: boolean) => {
    for (const f of files) {
      if (imagesOnly && !f.type.startsWith('image/')) {
        message.warning(`仅支持插入图片，已忽略：${f.name}`)
        continue
      }
      const image = f.type.startsWith('image/')
      const placeholder = `${image ? '!' : ''}[${f.name}](uploading://${++uploadSeqRef.current})`
      insertSnippet(placeholder)
      uploadAttachment(f, f.name, attachmentUpload?.scope ?? 'PRIVATE')
        .then((v) => {
          replaceInDoc(placeholder,
            `${image ? '!' : ''}[${v.originalName}](/api/attachments/${v.attachmentId}/raw)`)
        })
        .catch((e) => {
          showError(e, '附件上传失败')
          replaceInDoc(placeholder, '')
        })
    }
  }

  const uploadFilesRef = useRef(uploadFiles)
  uploadFilesRef.current = uploadFiles
  const actionsRef = useRef({ wrapSelection, insertBlock })
  actionsRef.current = { wrapSelection, insertBlock }

  const showSource = mode !== 'preview'

  useEffect(() => {
    if (!showSource || !hostRef.current) return
    const view = new EditorView({
      parent: hostRef.current,
      state: EditorState.create({
        doc: valueRef.current,
        extensions: [
          lineNumbers(),
          highlightActiveLineGutter(),
          highlightActiveLine(),
          history(),
          markdown(),
          syntaxHighlighting(sourceHighlight),
          sourceTheme,
          EditorView.lineWrapping,
          keymap.of([
            {
              key: 'Mod-b',
              run: () => {
                actionsRef.current.wrapSelection('**', '**', '加粗文本')
                return true
              },
            },
            {
              key: 'Mod-i',
              run: () => {
                actionsRef.current.wrapSelection('*', '*', '斜体文本')
                return true
              },
            },
            {
              key: 'Mod-k',
              run: () => {
                actionsRef.current.wrapSelection('[', '](https://)', '链接文字')
                return true
              },
            },
            ...defaultKeymap,
            ...historyKeymap,
            indentWithTab,
          ]),
          EditorView.contentAttributes.of({ 'aria-label': 'Markdown 源码' }),
          cmPlaceholder(placeholder ?? ''),
          EditorView.updateListener.of((u) => {
            if (u.docChanged) onChangeRef.current?.(u.state.doc.toString())
          }),
          EditorState.readOnly.of(!!disabled),
          EditorView.editable.of(!disabled),
        ],
      }),
    })
    viewRef.current = view
    const onSourceScroll = () => syncScroll('source')
    view.scrollDOM.addEventListener('scroll', onSourceScroll, { passive: true })
    const onPaste = (e: ClipboardEvent) => {
      if (!attachmentUploadRef.current) return
      const files = Array.from(e.clipboardData?.files ?? []).filter((f) => f.type.startsWith('image/'))
      if (files.length === 0) return
      e.preventDefault()
      uploadFilesRef.current(files, true)
    }
    const onDrop = (e: DragEvent) => {
      if (!attachmentUploadRef.current) return
      const files = Array.from(e.dataTransfer?.files ?? [])
      if (files.length === 0) return
      e.preventDefault()
      uploadFilesRef.current(files, false)
    }
    view.dom.addEventListener('paste', onPaste)
    view.dom.addEventListener('drop', onDrop)
    view.focus()
    return () => {
      view.scrollDOM.removeEventListener('scroll', onSourceScroll)
      view.dom.removeEventListener('paste', onPaste)
      view.dom.removeEventListener('drop', onDrop)
      view.destroy()
      viewRef.current = null
    }
  }, [showSource, disabled, placeholder, fullscreen, syncScroll])

  // 切到分栏时对齐一次：源码可能已在某处滚动，右侧预览刚从 0 挂载
  useEffect(() => {
    if (mode !== 'split') return
    const id = window.setTimeout(() => syncScroll('source'), 50)
    return () => window.clearTimeout(id)
  }, [mode, fullscreen, syncScroll])

  // 外部改值（如 AI 重写、表单回填）同步进编辑器；编辑器自己触发的 onChange 不回灌，避免打断输入
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const current = view.state.doc.toString()
    if (value !== current) {
      view.dispatch({ changes: { from: 0, to: current.length, insert: value } })
    }
  }, [value, showSource, fullscreen])

  const heading = (level: number) => () => {
    const prefix = '#'.repeat(level) + ' '
    prefixLines((l) => prefix + l.replace(/^#{1,6}\s+/, ''))
  }

  const actions: ToolbarAction[] = [
    { key: 'h1', title: '一级标题', label: 'H1', run: heading(1) },
    { key: 'h2', title: '二级标题', label: 'H2', run: heading(2) },
    { key: 'h3', title: '三级标题', label: 'H3', run: heading(3) },
    { key: 'bold', title: '加粗（Ctrl/⌘+B）', label: <BoldOutlined />, run: () => wrapSelection('**', '**', '加粗文本') },
    { key: 'italic', title: '斜体（Ctrl/⌘+I）', label: <ItalicOutlined />, run: () => wrapSelection('*', '*', '斜体文本') },
    { key: 'strike', title: '删除线', label: <StrikethroughOutlined />, run: () => wrapSelection('~~', '~~', '删除文本') },
    { key: 'code', title: '行内代码', label: <CodeOutlined />, run: () => wrapSelection('`', '`', 'code') },
    {
      key: 'codeblock', title: '代码块', label: '{ }',
      run: () => insertBlock('```\n代码\n```\n', -5),
    },
    { key: 'ul', title: '无序列表', label: <UnorderedListOutlined />, run: () => prefixLines((l) => `- ${l.replace(/^[-*]\s+/, '')}`) },
    { key: 'ol', title: '有序列表', label: <OrderedListOutlined />, run: () => prefixLines((l, i) => `${i + 1}. ${l.replace(/^\d+\.\s+/, '')}`) },
    { key: 'quote', title: '引用', label: '引用', run: () => prefixLines((l) => `> ${l.replace(/^>\s+/, '')}`) },
    { key: 'link', title: '链接（Ctrl/⌘+K）', label: <LinkOutlined />, run: () => wrapSelection('[', '](https://)', '链接文字') },
    {
      key: 'table', title: '表格', label: <TableOutlined />,
      run: () => insertBlock('| 列1 | 列2 | 列3 |\n| --- | --- | --- |\n|  |  |  |\n'),
    },
    { key: 'hr', title: '分割线', label: <MinusOutlined />, run: () => insertBlock('---\n') },
  ]

  const shellClass = [
    'md-editor',
    showToolbar ? '' : 'md-editor-minimal',
    fill ? 'md-editor-fill' : '',
    fullscreen ? 'md-editor-fullscreen' : '',
  ].filter(Boolean).join(' ')

  const shell = (
    <div className={shellClass}>
      {showToolbar ? (
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: '4px 12px',
            padding: '4px 8px',
            borderBottom: '1px solid #f0f0f0',
            background: '#fafafa',
          }}
        >
          <Space size={2} wrap>
            {actions.map((a) => (
              <Tooltip key={a.key} title={a.title}>
                <Button
                  size="small"
                  type="text"
                  disabled={disabled || mode === 'preview'}
                  // mousedown 抢焦点会让选区丢失，拦掉（click 仍会触发）
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={a.run}
                >
                  {a.label}
                </Button>
              </Tooltip>
            ))}
            {attachmentUpload && (
              <>
                <Tooltip title="插入图片（也可直接粘贴/拖拽）">
                  <Button
                    size="small"
                    type="text"
                    disabled={disabled || mode === 'preview'}
                    icon={<PictureOutlined />}
                    onClick={() => imageInputRef.current?.click()}
                  />
                </Tooltip>
                <Tooltip title="插入附件">
                  <Button
                    size="small"
                    type="text"
                    disabled={disabled || mode === 'preview'}
                    icon={<PaperClipOutlined />}
                    onClick={() => fileInputRef.current?.click()}
                  />
                </Tooltip>
              </>
            )}
          </Space>
          <Space size={8} align="center" wrap>
            <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>
              {value.length.toLocaleString()} 字符 · {value ? value.split('\n').length : 0} 行
            </Typography.Text>
            <Segmented
              size="small"
              value={mode}
              onChange={(v) => setMode(v as MarkdownEditorMode)}
              options={MODE_OPTIONS}
            />
            <Tooltip title={fullscreen ? '退出全屏（Esc）' : '全屏编辑'}>
              <Button
                size="small"
                type="text"
                aria-label={fullscreen ? '退出全屏' : '全屏编辑'}
                icon={fullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}
                onClick={() => setFullscreen((v) => !v)}
              />
            </Tooltip>
          </Space>
        </div>
      ) : (
        <div className="md-editor-head-minimal">
          <span className="md-editor-brand">
            <FileMarkdownOutlined />
            Markdown
          </span>
          <span className="md-editor-modes">
            <Tooltip title="编辑源码（Markdown）">
              <Button
                size="small"
                type="text"
                aria-label="编辑源码"
                aria-pressed={mode === 'edit'}
                className={mode === 'edit' ? 'is-active' : undefined}
                icon={<CodeOutlined />}
                disabled={disabled}
                onClick={() => setMode('edit')}
              />
            </Tooltip>
            <Tooltip title="分栏（源码 + 实时预览）">
              <Button
                size="small"
                type="text"
                aria-label="分栏"
                aria-pressed={mode === 'split'}
                className={mode === 'split' ? 'is-active' : undefined}
                icon={<SplitCellsOutlined />}
                onClick={() => setMode('split')}
              />
            </Tooltip>
            <Tooltip title="预览">
              <Button
                size="small"
                type="text"
                aria-label="预览"
                aria-pressed={mode === 'preview'}
                className={mode === 'preview' ? 'is-active' : undefined}
                icon={<EyeOutlined />}
                onClick={() => setMode('preview')}
              />
            </Tooltip>
          </span>
        </div>
      )}
      <div
        className={mode === 'split' ? 'md-editor-body md-editor-split' : 'md-editor-body'}
        style={fullscreen || fill ? undefined : { height }}
      >
        {showSource && <div ref={hostRef} className="md-editor-source" />}
        {mode !== 'edit' && (
          <div
            ref={previewRef}
            className="md-editor-preview"
            style={{ padding: fullscreen ? '16px 24px' : showToolbar ? '10px 16px' : '12px 16px' }}
            onScroll={() => syncScroll('preview')}
          >
            <Markdown content={previewValue} />
          </div>
        )}
      </div>
      {/* 隐藏 input 即选即传；value 清空保证同名文件可重复选 */}
      {attachmentUpload && (
        <>
          <input
            ref={imageInputRef}
            type="file"
            accept="image/*"
            multiple
            style={{ display: 'none' }}
            onChange={(e) => {
              const files = Array.from(e.target.files ?? [])
              e.target.value = ''
              if (files.length) uploadFiles(files, true)
            }}
          />
          <input
            ref={fileInputRef}
            type="file"
            multiple
            style={{ display: 'none' }}
            onChange={(e) => {
              const files = Array.from(e.target.files ?? [])
              e.target.value = ''
              if (files.length) uploadFiles(files, false)
            }}
          />
        </>
      )}
    </div>
  )

  return fullscreen ? createPortal(shell, document.body) : shell
}
