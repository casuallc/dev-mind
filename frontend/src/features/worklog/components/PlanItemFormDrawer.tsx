import { Button, Drawer, Form, Input, InputNumber, Select, Space, DatePicker } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useState } from 'react'
import { listLinkableKrs } from '../api'
import type { LinkableKr, PlanItem, PlanItemPayload } from '../types'
import { ENTRY_TYPES } from '../types'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

interface Props {
  open: boolean
  /** null = 新建 */
  target: PlanItem | null
  /** 新建时默认周（周内任意日，后端归一到周一） */
  defaultDate: string
  /** CAP-91 FR-05：计划列表响应带的 krId→标题映射（编辑时选中 KR 已失效的下拉兜底标签用） */
  krTitles?: Record<number, string>
  saving: boolean
  onCancel: () => void
  onSave: (payload: PlanItemPayload) => void
}

/**
 * CAP-88 FR-06 计划项编辑抽屉：新建/编辑共用。
 * CAP-90 FR-06：收窄为 WEEK 唯一粒度（日计划已并入条目——建计划态条目代替）。
 * CAP-91 FR-05/06：可选关联「目标」页签当季的 KR（周计划挂 KR 对齐，季度复盘按此聚合）。
 */
export default function PlanItemFormDrawer({ open, target, defaultDate, krTitles, saving, onCancel, onSave }: Props) {
  const [form] = Form.useForm()
  const [krs, setKrs] = useState<LinkableKr[]>([])
  const dateVal: Dayjs | undefined = Form.useWatch('date', form)

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
        krId: undefined,
      })
    }
  }, [open, target, defaultDate, form])

  // 可关联 KR 跟随所选周（取该周周一所在季的 ACTIVE KR）；抽屉开着改周会重取
  useEffect(() => {
    if (!open || !dateVal) return
    listLinkableKrs(dateVal.format('YYYY-MM-DD'))
      .then(setKrs)
      .catch(() => setKrs([]))
  }, [open, dateVal])

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
      krId: v.krId ?? undefined,
    })
  }

  // 编辑时若已关联的 KR 不在当季可关联清单（已盖棺/跨季），补一个兜底选项防显示裸 id
  const krOptions = krs.map((k) => ({
    value: k.id,
    label: `KR#${k.id} ${k.title}${k.objectiveTitle ? `（${k.objectiveTitle}）` : ''}`,
  }))
  if (target?.krId != null && !krOptions.some((o) => o.value === target.krId)) {
    const knownTitle = krTitles?.[target.krId]
    krOptions.push({
      value: target.krId,
      label: `KR#${target.krId} ${knownTitle ? `${knownTitle} ` : ''}（当前不可关联）`,
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
        <Form.Item
          name="krId"
          label="对齐 KR（可选）"
          extra="关联「目标」页签当季进行中的关键结果；AI 建议计划会按 KR 标注自动带出"
        >
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="不对齐 = 计划外工作"
            options={krOptions}
          />
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
