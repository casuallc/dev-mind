// 极简 Markdown 编辑器：Qoder 文档式顶栏，只保留「编辑（源码）/预览」两个图标，
// 不摆格式工具栏。阅读优先的报告类场景用这个；需要插表格/代码块/附件的富编辑用 MarkdownEditor。
// 受控组件：value/onChange 与 Input.TextArea 同型；mode/onModeChange 可受控，也可内部自管。
import { useDeferredValue, useState } from 'react'
import { Button, Input, Tooltip } from 'antd'
import { EditOutlined, EyeOutlined, FileMarkdownOutlined } from '@ant-design/icons'
import Markdown from './Markdown'

export type SimpleMarkdownEditorMode = 'edit' | 'preview'

export interface SimpleMarkdownEditorProps {
  value?: string
  onChange?: (v: string) => void
  height?: number
  placeholder?: string
  disabled?: boolean
  /** 初始模式，默认 preview（阅读优先） */
  defaultMode?: SimpleMarkdownEditorMode
  mode?: SimpleMarkdownEditorMode
  onModeChange?: (m: SimpleMarkdownEditorMode) => void
}

export default function SimpleMarkdownEditor({
  value = '',
  onChange,
  height = 320,
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

  return (
    <div className="md-simple">
      <div className="md-simple-head">
        <span className="md-simple-format">
          <FileMarkdownOutlined />
          Markdown
        </span>
        <span className="md-simple-modes">
          <Tooltip title="编辑（Markdown 源码）">
            <Button
              size="small"
              type="text"
              aria-label="编辑源码"
              aria-pressed={mode === 'edit'}
              className={mode === 'edit' ? 'is-active' : undefined}
              icon={<EditOutlined />}
              disabled={disabled}
              onClick={() => setMode('edit')}
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
      {mode === 'edit' ? (
        <Input.TextArea
          value={value}
          onChange={(e) => onChange?.(e.target.value)}
          placeholder={placeholder}
          disabled={disabled}
          variant="borderless"
          className="md-simple-area"
          style={{ height, resize: 'vertical' }}
        />
      ) : (
        <div className="md-simple-preview" style={{ height }}>
          <Markdown content={previewValue} />
        </div>
      )}
    </div>
  )
}
