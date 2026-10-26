import { Button, Drawer, Form, Input, InputNumber, Select, Space, DatePicker } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect } from 'react'
import type { PlanItem, PlanItemPayload } from '../types'
import { ENTRY_TYPES } from '../types'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

interface Props {
  open: boolean
  /** null = 新建 */
  target: PlanItem | null
  /** 新建时默认周（周内任意日，后端归一到周一） */
  defaultDate: string
  saving: boolean
  onCancel: () => void
  onSave: (payload: PlanItemPayload) => void
}

/**
 * CAP-88 FR-06 计划项编辑抽屉：新建/编辑共用。
 * CAP-90 FR-06：收窄为 WEEK 唯一粒度（日计划已并入条目——建计划态条目代替）。
 */
export default function PlanItemFormDrawer({ open, target, defaultDate, saving, onCancel, onSave }: Props) {
  const [form] = Form.useForm()

  useEffect(() => {
    if (!open) return
    if (target) {
      form.setFieldsValue({
        ...target,
        date: dayjs(target.planDate),
        hours: target.estimateHours,
      })
    } else {
      form.setFieldsValue({
        date: dayjs(defaultDate),
        planType: 'DEV',
        hours: undefined,
        title: '',
        content: '',
        requirementId: '',
        jiraIssueKey: '',
      })
    }
  }, [open, target, defaultDate, form])

  const submit = async () => {
    const v = await form.validateFields()
    onSave({
      scope: 'WEEK',
      date: (v.date as Dayjs).format('YYYY-MM-DD'),
      title: v.title,
      content: v.content || undefined,
      planType: v.planType,
      hours: v.hours ?? undefined,
      requirementId: v.requirementId || undefined,
      jiraIssueKey: v.jiraIssueKey || undefined,
    })
  }

  return (
    <Drawer
      title={target ? '编辑计划项' : '新建周计划项'}
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
      <Form form={form} layout="vertical">
        <Form.Item name="date" label="周（任选周内一天）" rules={[{ required: true, message: '请选择周' }]}>
          <DatePicker style={{ width: '100%' }} allowClear={false} picker="week" />
        </Form.Item>
        <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
          <Input placeholder="计划做什么，一句话" maxLength={256} />
        </Form.Item>
        <Form.Item name="planType" label="类型" rules={[{ required: true }]}>
          <Select options={Object.entries(ENTRY_TYPES).map(([value, label]) => ({ value, label }))} />
        </Form.Item>
        <Form.Item name="hours" label="预估工时（小时，可空）" extra="0.25 小时（15 分钟）为最小粒度">
          <InputNumber min={0.25} max={24} step={0.25} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="content" label="详情">
          <Input.TextArea rows={4} placeholder="可选：目标、口径、链接等" />
        </Form.Item>
        <Form.Item name="requirementId" label="关联需求 ID">
          <Input placeholder="可选" />
        </Form.Item>
        <Form.Item name="jiraIssueKey" label="关联 Jira 单号">
          <Input placeholder="可选，如 PROJ-123" />
        </Form.Item>
      </Form>
    </Drawer>
  )
}
