import { Button, Empty, Modal, Space, Typography, message } from 'antd'
import { RobotOutlined, EditOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { fmtTime } from '../../../shared/utils/format'
import SimpleMarkdownEditor, { type SimpleMarkdownEditorMode } from '../../../shared/components/SimpleMarkdownEditor'
import { showError } from '../../../shared/utils/showError'

interface Props {
  /** 报告是否已存在（CAP-74：无 DB id，exists=false 渲染空白引导） */
  exists?: boolean
  updatedAt?: string
  /** 编辑字段：日报单段 contentMd；周报两段 summaryMd/nextPlanMd */
  fields: { key: string; label: string; value: string }[]
  generating: boolean
  /** 手动创建空白稿（不经 AI），创建后进入编辑态 */
  onCreateManual: () => Promise<void>
  /** 触发 AI 生成（非 force 已存在由后端跳过；force=显式覆盖旧稿，git log 可找回） */
  onGenerate: (force: boolean) => void
  /** 保存即生效写仓（CAP-74：无确认态，保存后仍可随时再改） */
  onSave: (values: Record<string, string>) => Promise<void>
  /** 撑满父容器剩余高度（日报页要求编辑器底部与页面底部对齐） */
  fill?: boolean
}

/**
 * CAP-28 报告编辑卡：空白手填 / AI 草稿 → 人工修订 → 保存。
 * CAP-74：去 DRAFT/CONFIRMED 状态机——事实源是服务端仓，保存即 commit，历史走 git log，
 * 不再有「确认定稿/只读」概念。编辑态复用极简 Markdown 编辑器（Qoder 文档式：只有编辑/预览切换，
 * 默认预览；日报以读为主，不摆格式工具栏）。
 * 全部字段处于预览时不显示「保存修改」；保存需二次确认。
 */
export default function ReportEditor({
  exists,
  updatedAt,
  fields,
  generating,
  onCreateManual,
  onGenerate,
  onSave,
  fill,
}: Props) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [modes, setModes] = useState<Record<string, SimpleMarkdownEditorMode>>({})
  const [saving, setSaving] = useState(false)
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    const next: Record<string, string> = {}
    fields.forEach((f) => {
      next[f.key] = f.value
    })
    setValues(next)
  }, [fields])

  const modeOf = (key: string) => modes[key] ?? 'preview'
  // 任一字段在编辑/分栏态才显示保存按钮（全预览=纯阅读，隐藏保存防误触）
  const anyEditing = fields.some((f) => modeOf(f.key) !== 'preview')

  const doSave = async () => {
    setSaving(true)
    try {
      await onSave(values)
      message.success('已保存')
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const save = () => {
    Modal.confirm({
      title: '保存修改',
      content: '保存即写入仓库并提交（历史可走 git log 找回），确定保存吗？',
      okText: '保存',
      cancelText: '取消',
      onOk: doSave,
    })
  }

  if (!exists) {
    return (
      <Empty
        description="还没有报告：可「手动填写」直接写，或点「AI 生成」根据当天/当周工作条目与 git 提交生成草稿"
        style={{ padding: '48px 0' }}
      >
        <Space>
          <Button
            type="primary"
            icon={<EditOutlined />}
            loading={creating}
            onClick={async () => {
              setCreating(true)
              try {
                await onCreateManual()
              } catch (e) {
                showError(e, '创建失败')
              } finally {
                setCreating(false)
              }
            }}
          >
            手动填写
          </Button>
          <Button icon={<RobotOutlined />} loading={generating} onClick={() => onGenerate(false)}>
            AI 生成
          </Button>
        </Space>
      </Empty>
    )
  }

  return (
    <div
      style={fill ? { height: '100%', display: 'flex', flexDirection: 'column', minHeight: 0 } : undefined}
    >
      <Space style={{ marginBottom: 12, flex: 'none' }} wrap>
        <Typography.Text type="secondary">更新于 {fmtTime(updatedAt)}</Typography.Text>
        <Button size="small" icon={<RobotOutlined />} loading={generating} onClick={() => onGenerate(true)}>
          重新生成
        </Button>
        {anyEditing && (
          <Button size="small" type="primary" loading={saving} onClick={save}>
            保存修改
          </Button>
        )}
      </Space>
      <div
        style={fill
          ? { flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', gap: 16 }
          : undefined}
      >
        {fields.map((f) => {
          const content = values[f.key] || ''
          const rows = Math.max(6, content.split('\n').length + 1)
          // 普通模式高度随内容行数伸缩（下限 220）；fill 模式由 flex 撑满页面剩余高度
          const boxHeight = Math.max(220, rows * 22 + 12)
          return (
            <div
              key={f.key}
              style={fill
                ? { flex: 1, minHeight: 220, display: 'flex', flexDirection: 'column' }
                : { marginBottom: 16 }}
            >
              <Typography.Text strong style={{ flex: 'none' }}>{f.label}</Typography.Text>
              <div
                style={fill
                  ? { marginTop: 8, flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }
                  : { marginTop: 8 }}
              >
                <SimpleMarkdownEditor
                  fill={fill}
                  height={boxHeight}
                  value={content}
                  onChange={(v) => setValues({ ...values, [f.key]: v })}
                  mode={modeOf(f.key)}
                  onModeChange={(m) => setModes({ ...modes, [f.key]: m })}
                />
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
