import { Button, Drawer, Form, Input, InputNumber, Select, Space, DatePicker } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect } from 'react'
import type { EntryPayload, WorklogEntry } from '../types'
import { ENTRY_TYPES } from '../types'

interface Props {
  open: boolean
  /** null = 新建 */
  target: WorklogEntry | null
  /** 新建时默认日期 */
  defaultDate: string
  saving: boolean
  onCancel: () => void
  onSave: (payload: EntryPayload) => void
}

/** CAP-28 工作条目编辑抽屉：新建/编辑共用（按布局约定成套表单收进 Drawer）。 */
export default function EntryFormDrawer({ open, target, defaultDate, saving, onCancel, onSave }: Props) {
  const [form] = Form.useForm()

  useEffect(() => {
    if (!open) return
    if (target) {
      form.setFieldsValue({
        ...target,
        workDate: dayjs(target.workDate),
      })
    } else {
      form.setFieldsValue({
        workDate: dayjs(defaultDate),
        entryType: 'DEV',
        hours: 1,
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
      workDate: (v.workDate as Dayjs).format('YYYY-MM-DD'),
      title: v.title,
      content: v.content || undefined,
      entryType: v.entryType,
      hours: v.hours,
      requirementId: v.requirementId || undefined,
      jiraIssueKey: v.jiraIssueKey || undefined,
    })
  }

  return (
    <Drawer
      title={target ? '编辑工作条目' : '新建工作条目'}
      open={open}
      onClose={onCancel}
      width={480}
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
        <Form.Item name="workDate" label="日期" rules={[{ required: true, message: '请选择日期' }]}>
          <DatePicker style={{ width: '100%' }} allowClear={false} />
        </Form.Item>
        <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
          <Input placeholder="做了什么，一句话" maxLength={256} />
        </Form.Item>
        <Form.Item name="entryType" label="类型" rules={[{ required: true }]}>
          <Select options={Object.entries(ENTRY_TYPES).map(([value, label]) => ({ value, label }))} />
        </Form.Item>
        <Form.Item
          name="hours"
          label="工时（小时）"
          rules={[{ required: true, message: '请填写工时' }]}
          extra="0.25 小时（15 分钟）为最小粒度，后端按此取整"
        >
          <InputNumber min={0.25} max={24} step={0.25} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item name="content" label="详情">
          <Input.TextArea rows={4} placeholder="可选：背景、结论、链接等" />
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
