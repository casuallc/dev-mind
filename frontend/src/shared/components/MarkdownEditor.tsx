// 共享 Markdown 编辑器（CAP-44）：工具栏按选区包裹/行前缀（借鉴 WeKnora manual-knowledge-editor
// 的 selectionStart/End + setSelectionRange 手法）+ 编辑/预览切换（预览复用 shared Markdown）。
// 受控组件：props 与 Input.TextArea 同型（value/onChange），可直接被 Form.Item 托管。
// attachmentUpload 开启图片/附件内联：工具栏按钮 + 粘贴/拖拽 → 上传附件库（占位符防并发错位，
// 同 DocEditorPage 姿势），文中写裸路径 /api/attachments/{id}/raw（token 渲染期由 Markdown 组件补，
// 烤进文本会过期失效）；与 CAP-40 需求附件装配同一引用口径，需求描述里的引用会被投送到会话。
import { useRef, useState } from 'react'
import { Button, Input, Segmented, Space, Tooltip, message } from 'antd'
import {
  BoldOutlined,
  CodeOutlined,
  ItalicOutlined,
  LinkOutlined,
  MinusOutlined,
  OrderedListOutlined,
  PaperClipOutlined,
  PictureOutlined,
  StrikethroughOutlined,
  TableOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons'
import type { TextAreaRef } from 'antd/es/input/TextArea'
import Markdown from './Markdown'
import { uploadAttachment } from '../attachments/api'
import { showError } from '../utils/showError'

export interface MarkdownEditorProps {
  value?: string
  onChange?: (v: string) => void
  height?: number
  placeholder?: string
  disabled?: boolean
  /** 开启图片/附件上传内联；scope 默认 PRIVATE（需求描述等成员共享场景传 SHARED） */
  attachmentUpload?: { scope?: 'PRIVATE' | 'SHARED' }
  /** 初始模式，默认 edit；受控用 mode + onModeChange */
  defaultMode?: 'edit' | 'preview'
  mode?: 'edit' | 'preview'
  onModeChange?: (m: 'edit' | 'preview') => void
}

interface ToolbarAction {
  key: string
  title: string
  label: React.ReactNode
  run: () => void
}

export default function MarkdownEditor({
  value = '',
  onChange,
  height = 360,
  placeholder,
  disabled,
  attachmentUpload,
  defaultMode,
  mode: controlledMode,
  onModeChange,
}: MarkdownEditorProps) {
  const [innerMode, setInnerMode] = useState<'edit' | 'preview'>(defaultMode ?? 'edit')
  const mode = controlledMode ?? innerMode
  const setMode = (m: 'edit' | 'preview') => {
    setInnerMode(m)
    onModeChange?.(m)
  }
  const areaRef = useRef<TextAreaRef>(null)
  const imageInputRef = useRef<HTMLInputElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const uploadSeqRef = useRef(0)
  // 异步上传回调拿到的是闭包旧值，用 ref 镜最新文本做占位符替换
  const valueRef = useRef(value)
  valueRef.current = value

  const textarea = (): HTMLTextAreaElement | null => {
    const r = areaRef.current as unknown as { resizableTextArea?: { textArea?: HTMLTextAreaElement } } | null
    return r?.resizableTextArea?.textArea ?? null
  }

  const emit = (next: string, selStart: number, selEnd: number) => {
    onChange?.(next)
    // 受控更新落在下一次渲染，rAF 后再恢复焦点与选区
    requestAnimationFrame(() => {
      const ta = textarea()
      if (!ta) return
      ta.focus()
      ta.setSelectionRange(selStart, selEnd)
    })
  }

  /** 包裹当前选区（加粗/斜体/行内码/链接等）；无选区时插入占位并选中 */
  const wrapSelection = (before: string, after: string, placeholderText = '') => {
    const ta = textarea()
    if (!ta) return
    const { selectionStart: s, selectionEnd: e } = ta
    const sel = value.slice(s, e) || placeholderText
    emit(value.slice(0, s) + before + sel + after + value.slice(e),
      s + before.length, s + before.length + sel.length)
  }

  /** 给选区覆盖到的所有非空行加前缀（标题/列表/引用） */
  const prefixLines = (mk: (line: string, i: number) => string) => {
    const ta = textarea()
    if (!ta) return
    const { selectionStart: s, selectionEnd: e } = ta
    const lineStart = value.lastIndexOf('\n', Math.max(0, s - 1)) + 1
    const block = value.slice(lineStart, e)
    const next = block.split('\n').map((l, i) => (l.trim() ? mk(l, i) : l)).join('\n')
    emit(value.slice(0, lineStart) + next + value.slice(e), lineStart, lineStart + next.length)
  }

  /** 在光标处插入块级内容（代码块/表格/分割线），自动补空行 */
  const insertBlock = (block: string, cursorOffset = 0) => {
    const ta = textarea()
    if (!ta) return
    const { selectionStart: s } = ta
    const needLeading = s > 0 && !value.slice(0, s).endsWith('\n\n')
    const text = (needLeading ? (value[s - 1] === '\n' ? '\n' : '\n\n') : '') + block
    emit(value.slice(0, s) + text + value.slice(s), s + text.length + cursorOffset, s + text.length + cursorOffset)
  }

  /** 在光标处插入代码片段（图片/附件引用），无焦点时追加到文末 */
  const insertSnippet = (snippet: string) => {
    const ta = textarea()
    const s = ta?.selectionStart ?? value.length
    emit(value.slice(0, s) + snippet + value.slice(s), s + snippet.length, s + snippet.length)
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
          onChange?.(valueRef.current.replace(placeholder,
            `${image ? '!' : ''}[${v.originalName}](/api/attachments/${v.attachmentId}/raw)`))
        })
        .catch((e) => {
          showError(e, '附件上传失败')
          onChange?.(valueRef.current.replace(placeholder, ''))
        })
    }
  }

  const onAreaPaste = (e: React.ClipboardEvent) => {
    if (!attachmentUpload) return
    const files = Array.from(e.clipboardData?.files ?? []).filter((f) => f.type.startsWith('image/'))
    if (files.length > 0) {
      e.preventDefault()
      uploadFiles(files, true)
    }
  }

  const onAreaDrop = (e: React.DragEvent) => {
    if (!attachmentUpload) return
    const files = Array.from(e.dataTransfer?.files ?? [])
    if (files.length > 0) {
      e.preventDefault()
      uploadFiles(files, false)
    }
  }

  const heading = (level: number) => () => {
    const prefix = '#'.repeat(level) + ' '
    prefixLines((l) => prefix + l.replace(/^#{1,6}\s+/, ''))
  }

  const actions: ToolbarAction[] = [
    { key: 'h1', title: '一级标题', label: 'H1', run: heading(1) },
    { key: 'h2', title: '二级标题', label: 'H2', run: heading(2) },
    { key: 'h3', title: '三级标题', label: 'H3', run: heading(3) },
    { key: 'bold', title: '加粗', label: <BoldOutlined />, run: () => wrapSelection('**', '**', '加粗文本') },
    { key: 'italic', title: '斜体', label: <ItalicOutlined />, run: () => wrapSelection('*', '*', '斜体文本') },
    { key: 'strike', title: '删除线', label: <StrikethroughOutlined />, run: () => wrapSelection('~~', '~~', '删除文本') },
    { key: 'code', title: '行内代码', label: <CodeOutlined />, run: () => wrapSelection('`', '`', 'code') },
    {
      key: 'codeblock', title: '代码块', label: '{ }',
      run: () => insertBlock('```\n代码\n```\n', -5),
    },
    { key: 'ul', title: '无序列表', label: <UnorderedListOutlined />, run: () => prefixLines((l) => `- ${l.replace(/^[-*]\s+/, '')}`) },
    { key: 'ol', title: '有序列表', label: <OrderedListOutlined />, run: () => prefixLines((l, i) => `${i + 1}. ${l.replace(/^\d+\.\s+/, '')}`) },
    { key: 'quote', title: '引用', label: '引用', run: () => prefixLines((l) => `> ${l.replace(/^>\s+/, '')}`) },
    { key: 'link', title: '链接', label: <LinkOutlined />, run: () => wrapSelection('[', '](https://)', '链接文字') },
    {
      key: 'table', title: '表格', label: <TableOutlined />,
      run: () => insertBlock('| 列1 | 列2 | 列3 |\n| --- | --- | --- |\n|  |  |  |\n'),
    },
    { key: 'hr', title: '分割线', label: <MinusOutlined />, run: () => insertBlock('---\n') },
  ]

  return (
    <div style={{ border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden' }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
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
        <Segmented
          size="small"
          value={mode}
          onChange={(v) => setMode(v as 'edit' | 'preview')}
          options={[
            { value: 'edit', label: '编辑' },
            { value: 'preview', label: '预览' },
          ]}
        />
      </div>
      {mode === 'edit' ? (
        <Input.TextArea
          ref={areaRef}
          value={value}
          onChange={(e) => onChange?.(e.target.value)}
          onPaste={onAreaPaste}
          onDrop={onAreaDrop}
          placeholder={placeholder}
          disabled={disabled}
          style={{ height, resize: 'vertical', border: 'none', borderRadius: 0, boxShadow: 'none' }}
        />
      ) : (
        <div style={{ height, overflow: 'auto', padding: '8px 12px' }}>
          <Markdown content={value} />
        </div>
      )}
    </div>
  )
}
