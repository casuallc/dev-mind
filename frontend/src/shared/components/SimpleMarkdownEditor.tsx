// 极简 Markdown 编辑器：Qoder 文档式顶栏，只保留「源码/分栏/预览」三个图标，不摆格式工具栏。
// 源码态基于 CodeMirror：行号、Markdown 语法高亮、当前行高亮；分栏左侧源码、右侧实时预览，
// 两边滚动条按比例双向联动；预览态复用共享 Markdown。
// 阅读优先的报告类场景用这个；需要插表格/代码块/附件的富编辑用 MarkdownEditor。
// 受控组件：value/onChange；mode/onModeChange 可受控，也可内部自管；fill 时撑满父容器剩余高度。
import { useCallback, useDeferredValue, useEffect, useRef, useState } from 'react'
import { Button, Tooltip } from 'antd'
import { CodeOutlined, EyeOutlined, FileMarkdownOutlined, SplitCellsOutlined } from '@ant-design/icons'
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

export type SimpleMarkdownEditorMode = 'edit' | 'split' | 'preview'

export interface SimpleMarkdownEditorProps {
  value?: string
  onChange?: (v: string) => void
  height?: number
  /** 撑满父容器剩余高度（父级需为确定高度的 flex 列），忽略 height */
  fill?: boolean
  placeholder?: string
  disabled?: boolean
  /** 初始模式，默认 preview（阅读优先） */
  defaultMode?: SimpleMarkdownEditorMode
  mode?: SimpleMarkdownEditorMode
  onModeChange?: (m: SimpleMarkdownEditorMode) => void
}

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

export default function SimpleMarkdownEditor({
  value = '',
  onChange,
  height = 320,
  fill = false,
  placeholder,
  disabled,
  defaultMode = 'preview',
  mode: controlledMode,
  onModeChange,
}: SimpleMarkdownEditorProps) {
  const [innerMode, setInnerMode] = useState<SimpleMarkdownEditorMode>(defaultMode)
  const mode = controlledMode ?? innerMode
  const setMode = (m: SimpleMarkdownEditorMode) => {
    setInnerMode(m)
    onModeChange?.(m)
  }
  const previewValue = useDeferredValue(value)
  const hostRef = useRef<HTMLDivElement>(null)
  const previewRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  // 异步/延迟回调拿旧闭包时也能把最新文本交回父级
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  const valueRef = useRef(value)
  valueRef.current = value
  const modeRef = useRef(mode)
  modeRef.current = mode
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
          keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
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
    view.focus()
    return () => {
      view.scrollDOM.removeEventListener('scroll', onSourceScroll)
      view.destroy()
      viewRef.current = null
    }
  }, [showSource, disabled, placeholder, syncScroll])

  // 切到分栏时对齐一次：源码可能已在某处滚动，右侧预览刚从 0 挂载
  useEffect(() => {
    if (mode !== 'split') return
    const id = window.setTimeout(() => syncScroll('source'), 50)
    return () => window.clearTimeout(id)
  }, [mode, syncScroll])

  // 外部改值（如 AI 重写）同步进编辑器；编辑器自己触发的 onChange 不回灌，避免打断输入
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const current = view.state.doc.toString()
    if (value !== current) {
      view.dispatch({ changes: { from: 0, to: current.length, insert: value } })
    }
  }, [value, showSource])

  return (
    <div className={fill ? 'md-simple md-simple-fill' : 'md-simple'} style={fill ? { height: '100%' } : undefined}>
      <div className="md-simple-head">
        <span className="md-simple-format">
          <FileMarkdownOutlined />
          Markdown
        </span>
        <span className="md-simple-modes">
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
      <div
        className={mode === 'split' ? 'md-simple-body md-simple-split' : 'md-simple-body'}
        style={fill ? undefined : { height }}
      >
        {showSource && <div key="source" ref={hostRef} className="md-simple-source" />}
        {mode !== 'edit' && (
          <div
            key="preview"
            ref={previewRef}
            className="md-simple-preview"
            onScroll={() => syncScroll('preview')}
          >
            <Markdown content={previewValue} />
          </div>
        )}
      </div>
    </div>
  )
}
