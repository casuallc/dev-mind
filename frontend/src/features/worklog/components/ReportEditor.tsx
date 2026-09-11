import { Button, Empty, Input, Space, Tag, Typography, message } from 'antd'
import { RobotOutlined, CheckOutlined, EditOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { fmtTime } from '../../../shared/utils/format'
import Markdown from '../../../shared/components/Markdown'
import { showError } from '../../../shared/utils/showError'

interface Props {
  /** 报告主键（无报告时 undefined） */
  id?: number
  status?: string
  updatedAt?: string
  /** 编辑字段：日报单段 contentMd；周报两段 summaryMd/nextPlanMd */
  fields: { key: string; label: string; value: string }[]
  generating: boolean
  /** 手动创建空白草稿（不经 AI），创建后进入编辑态 */
  onCreateManual: () => Promise<void>
  /** 触发 AI 生成（已存在且已确认由后端 409 拦截） */
  onGenerate: (force: boolean) => void
  onSave: (values: Record<string, string>) => Promise<void>
  onConfirm: () => Promise<void>
}

/**
 * CAP-28 报告编辑卡：空白手填 / AI 草稿 → 人工修订 → 确认。
 * 编辑态：左编辑右预览，Markdown 实时渲染；CONFIRMED 后只读 Markdown 渲染（白底，非禁用灰框）。
 */
export default function ReportEditor({
  id,
  status,
  updatedAt,
  fields,
  generating,
  onCreateManual,
  onGenerate,
  onSave,
  onConfirm,
}: Props) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [creating, setCreating] = useState(false)
  const confirmed = status === 'CONFIRMED'

  useEffect(() => {
    const next: Record<string, string> = {}
    fields.forEach((f) => {
      next[f.key] = f.value
    })
    setValues(next)
  }, [fields])

  const save = async () => {
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

  if (!id) {
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
    <div>
      <Space style={{ marginBottom: 12 }} wrap>
        {confirmed ? <Tag color="green">已确认</Tag> : <Tag color="gold">草稿</Tag>}
        <Typography.Text type="secondary">更新于 {fmtTime(updatedAt)}</Typography.Text>
        <Button size="small" icon={<RobotOutlined />} loading={generating} disabled={confirmed} onClick={() => onGenerate(true)}>
          重新生成
        </Button>
        {!confirmed && (
          <>
            <Button size="small" loading={saving} onClick={save}>
              保存修改
            </Button>
            <Button
              size="small"
              type="primary"
              icon={<CheckOutlined />}
              onClick={async () => {
                await save()
                await onConfirm()
              }}
            >
              确认定稿
            </Button>
          </>
        )}
      </Space>
      {fields.map((f) => {
        const content = values[f.key] || ''
        if (confirmed) {
          return (
            <div key={f.key} style={{ marginBottom: 16 }}>
              <Typography.Text strong>{f.label}</Typography.Text>
              <div style={{ marginTop: 8 }}>
                <Markdown content={content} />
              </div>
            </div>
          )
        }
        const rows = Math.max(6, content.split('\n').length + 1)
        // 预览框与输入框同高：TextArea 行高约 22px + 上下 padding
        const boxHeight = rows * 22 + 12
        return (
          <div key={f.key} style={{ marginBottom: 16 }}>
            <Typography.Text strong>{f.label}</Typography.Text>
            <div style={{ display: 'flex', gap: 12, marginTop: 8 }}>
              <Input.TextArea
                style={{ flex: 1, minWidth: 0, fontFamily: 'monospace' }}
                rows={rows}
                value={content}
                onChange={(e) => setValues({ ...values, [f.key]: e.target.value })}
              />
              <div
                style={{
                  flex: 1,
                  minWidth: 0,
                  height: boxHeight,
                  overflow: 'auto',
                  border: '1px solid #d9d9d9',
                  borderRadius: 6,
                  padding: '4px 11px',
                }}
              >
                <Markdown content={content} />
              </div>
            </div>
          </div>
        )
      })}
    </div>
  )
}
