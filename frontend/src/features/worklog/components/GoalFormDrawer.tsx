import { Button, Drawer, Form, Input, InputNumber, Select, Space, Typography } from 'antd'
import { useEffect } from 'react'
import type { GoalPayload, WorklogGoal } from '../types'
import { GOAL_STATUSES } from '../types'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

/** 新建入口预设：scope/period/kind/parent 由触发按钮决定，抽屉内只读展示 */
export interface GoalPreset {
  scope: 'YEAR' | 'QUARTER'
  period: string
  kind: 'OBJECTIVE' | 'KEY_RESULT'
  parentId?: number
  parentTitle?: string
}

interface Props {
  open: boolean
  /** null = 新建（按 preset） */
  target: WorklogGoal | null
  preset: GoalPreset | null
  /** 新建季度 O 时可选挂靠的年度目标（同年 YEAR O） */
  yearObjectives?: WorklogGoal[]
  saving: boolean
  onCancel: () => void
  onSave: (payload: GoalPayload) => void
}

/** 人工可设状态（CARRIED 由滚转写入，不在下拉里） */
const EDITABLE_STATUSES = ['ACTIVE', 'DONE', 'DROPPED']

/**
 * CAP-91 FR-02 目标编辑抽屉：新建/编辑共用。年度/季度、O/KR、父 O 由入口预设（不可在抽屉里改）；
 * 权重/人工进度可选填；状态仅编辑时可改（创建恒 ACTIVE）。
 */
export default function GoalFormDrawer({ open, target, preset, yearObjectives, saving, onCancel, onSave }: Props) {
  const [form] = Form.useForm()
  const editing = target != null

  useEffect(() => {
    if (!open) return
    if (target) {
      form.setFieldsValue({
        title: target.title,
        content: target.content ?? '',
        weight: target.weight,
        progress: target.progress ?? 0,
        status: target.status,
      })
    } else {
      form.setFieldsValue({ title: '', content: '', weight: undefined, parentId: undefined })
    }
  }, [open, target, form])

  const ctx = editing
    ? {
        scope: target.scope,
        period: target.period,
        kind: target.kind,
        parentId: target.parentId,
        parentTitle: target.parentTitle,
      }
    : preset

  const submit = async () => {
    const v = await form.validateFields()
    if (!ctx) return
    onSave({
      scope: ctx.scope,
      period: ctx.period,
      kind: ctx.kind,
      parentId: v.parentId ?? ctx.parentId,
      title: v.title,
      content: v.content || undefined,
      weight: v.weight ?? undefined,
      // status/progress 仅更新时有效（创建后端恒 ACTIVE/0）
      progress: editing ? (v.progress ?? 0) : undefined,
      status: editing ? v.status : undefined,
    })
  }

  if (!ctx) return null
  // 新建季度 O 可挂靠同年年度 O；KR 的父 O 由入口预设，不在抽屉改
  const showYearParentPick = !editing && ctx.scope === 'QUARTER' && ctx.kind === 'OBJECTIVE'

  return (
    <Drawer
      title={editing ? '编辑目标' : preset?.kind === 'KEY_RESULT' ? '新建关键结果' : '新建目标'}
      open={open}
      onClose={onCancel}
      width={DRAWER_DEFAULT}
      destroyOnHidden
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onCancel}>取消</Button>
          <Button type="primary" loading={saving} onClick={submit}>
            保存
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
        {ctx.period} · {ctx.kind === 'OBJECTIVE' ? '目标（O）' : '关键结果（KR）'}
        {ctx.parentTitle ? ` · 父 O：${ctx.parentTitle}` : ''}
      </Typography.Paragraph>
      <Form form={form} layout="vertical">
        {showYearParentPick && (
          <Form.Item name="parentId" label="挂靠年度目标（可选）">
            <Select
              allowClear
              placeholder="不挂靠 = 季度独立目标"
              options={(yearObjectives ?? []).map((o) => ({ value: o.id, label: o.title }))}
            />
          </Form.Item>
        )}
        <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
          <Input
            placeholder={ctx.kind === 'OBJECTIVE' ? '方向性目标，一句话' : '可衡量的关键结果，一句话'}
            maxLength={256}
          />
        </Form.Item>
        <Form.Item name="content" label="描述">
          <Input.TextArea rows={4} placeholder="可选：背景、衡量口径、验收标准等" />
        </Form.Item>
        <Form.Item name="weight" label="权重（%，可空）">
          <InputNumber min={0} max={100} style={{ width: '100%' }} placeholder="可选" />
        </Form.Item>
        {editing && (
          <>
            <Form.Item name="progress" label="人工进度（%）" extra="由本人手动维护；KR 行的「建议进度」由计划项派生、只展示不回写">
              <InputNumber min={0} max={100} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="status" label="状态" rules={[{ required: true }]}>
              <Select
                options={EDITABLE_STATUSES.map((v) => ({
                  value: v,
                  label: GOAL_STATUSES[v]?.label ?? v,
                }))}
              />
            </Form.Item>
          </>
        )}
      </Form>
    </Drawer>
  )
}
