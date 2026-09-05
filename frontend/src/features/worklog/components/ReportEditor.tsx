import { Button, Empty, Input, Space, Tag, Typography, message } from 'antd'
import { RobotOutlined, CheckOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { fmtTime } from '../../../shared/utils/format'

interface Props {
  /** 报告主键（无报告时 undefined） */
  id?: number
  status?: string
  updatedAt?: string
  /** 编辑字段：日报单段 contentMd；周报两段 summaryMd/nextPlanMd */
  fields: { key: string; label: string; value: string }[]
  generating: boolean
  /** 触发 AI 生成（已存在且已确认由后端 409 拦截） */
  onGenerate: (force: boolean) => void
  onSave: (values: Record<string, string>) => Promise<void>
  onConfirm: () => Promise<void>
}

/**
 * CAP-28 报告编辑卡：AI 草稿 → 人工修订 → 确认。
 * CONFIRMED 后只读（后端也拒绝 force 覆盖）。
 */
export default function ReportEditor({
  id,
  status,
  updatedAt,
  fields,
  generating,
  onGenerate,
  onSave,
  onConfirm,
}: Props) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
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
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  if (!id) {
    return (
      <Empty
        description="还没有报告：点击「AI 生成」根据当天/当周工作条目与 git 提交生成草稿"
        style={{ padding: '48px 0' }}
      >
        <Button type="primary" icon={<RobotOutlined />} loading={generating} onClick={() => onGenerate(false)}>
          AI 生成
        </Button>
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
      {fields.map((f) => (
        <div key={f.key} style={{ marginBottom: 16 }}>
          <Typography.Text strong>{f.label}</Typography.Text>
          <Input.TextArea
            style={{ marginTop: 8, fontFamily: 'monospace' }}
            rows={Math.max(6, (values[f.key] || '').split('\n').length + 1)}
            value={values[f.key] || ''}
            disabled={confirmed}
            onChange={(e) => setValues({ ...values, [f.key]: e.target.value })}
          />
        </div>
      ))}
    </div>
  )
}
