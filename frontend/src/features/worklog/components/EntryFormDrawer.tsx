import { Button, Drawer, Form, Input, InputNumber, Select, Space, DatePicker, Tag } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useState } from 'react'
import type { EntryPayload, PlanItem, WorklogEntry } from '../types'
import { ENTRY_TYPES, PLAN_STATUSES } from '../types'
import { listLinkablePlans } from '../api'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

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

/** 人工可设条目状态（CARRIED 由滚转写入，不在下拉里；GIT/AGENT 来源条目恒 DONE 不出现该字段） */
const ENTRY_EDITABLE_STATUSES = ['DONE', 'TODO', 'DOING', 'DROPPED']

/**
 * CAP-28 工作条目编辑抽屉：新建/编辑共用（按布局约定成套表单收进 Drawer）。
 * CAP-88 FR-04/06：增「关联计划项」下拉（当周周计划；DOING/DONE 也可选——补录忘挂的执行），
 * 关联后服务端自动把 TODO 项置「进行中」。
 * CAP-90 FR-01/02/07：日计划并入条目——状态字段（DONE=事实默认 / TODO·DOING=计划态）+ 计划态预估工时；
 * GIT/AGENT 来源是已发生事实，状态恒 DONE 不提供修改。
 */
export default function EntryFormDrawer({ open, target, defaultDate, saving, onCancel, onSave }: Props) {
  const [form] = Form.useForm()
  const [linkable, setLinkable] = useState<PlanItem[]>([])
  // 日期变化 → 可关联计划项跟着变（当周周计划）
  const workDate: Dayjs | undefined = Form.useWatch('workDate', { form, preserve: true })
  const status: string | undefined = Form.useWatch('status', { form, preserve: true })
  // GIT/AGENT 来源条目是已发生事实（后端拒转非 DONE），状态字段不渲染
  const factSource = target != null && (target.source === 'GIT' || target.source === 'AGENT')
  const planState = !factSource && (status === 'TODO' || status === 'DOING')

  useEffect(() => {
    if (!open) return
    if (target) {
      form.setFieldsValue({
        ...target,
        workDate: dayjs(target.workDate),
        planId: target.planItemId,
        estimateHours: target.estimateHours,
      })
    } else {
      form.setFieldsValue({
        workDate: dayjs(defaultDate),
        entryType: 'DEV',
        status: 'DONE',
        hours: 1,
        estimateHours: undefined,
        title: '',
        content: '',
        requirementId: '',
        jiraIssueKey: '',
        planId: undefined,
      })
    }
  }, [open, target, defaultDate, form])

  useEffect(() => {
    if (!open || !workDate) return
    listLinkablePlans(workDate.format('YYYY-MM-DD'))
      .then(setLinkable)
      .catch(() => setLinkable([]))
  }, [open, workDate])

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
      planId: v.planId ?? undefined,
      status: factSource ? undefined : v.status,
      estimateHours: v.estimateHours ?? undefined,
    })
  }

  // 已关联项不在可关联范围（如关联后又被放弃/滚转）时兜底显示，避免下拉只露裸 id
  const linkedMissing =
    target?.planItemId != null && !linkable.some((p) => p.id === target.planItemId)
      ? target.planItemId
      : null

  return (
    <Drawer
      title={target ? '编辑工作条目' : '新建工作条目'}
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
        <Form.Item name="workDate" label="日期" rules={[{ required: true, message: '请选择日期' }]}>
          <DatePicker style={{ width: '100%' }} allowClear={false} />
        </Form.Item>
        <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
          <Input placeholder="做了什么 / 要做什么，一句话" maxLength={256} />
        </Form.Item>
        <Form.Item name="entryType" label="类型" rules={[{ required: true }]}>
          <Select options={Object.entries(ENTRY_TYPES).map(([value, label]) => ({ value, label }))} />
        </Form.Item>
        {!factSource && (
          <Form.Item
            name="status"
            label="状态"
            rules={[{ required: true }]}
            extra="已完成=补录已发生的事实；待办/进行中=计划态（即今日计划，日计划已并入条目）"
          >
            <Select
              options={ENTRY_EDITABLE_STATUSES.map((v) => ({
                value: v,
                label: PLAN_STATUSES[v]?.label ?? v,
              }))}
            />
          </Form.Item>
        )}
        {planState && (
          <Form.Item
            name="estimateHours"
            label="预估工时（小时，可空）"
            extra="计划态预估；点「完成」时预填确认实际工时"
          >
            <InputNumber min={0.25} max={24} step={0.25} style={{ width: '100%' }} />
          </Form.Item>
        )}
        <Form.Item
          name="hours"
          label={planState ? '已用工时（小时）' : '工时（小时）'}
          rules={[{ required: true, message: '请填写工时' }]}
          extra={
            planState
              ? '计划态一般填 0；0.25 小时（15 分钟）为最小粒度'
              : '0.25 小时（15 分钟）为最小粒度，后端按此取整'
          }
        >
          <InputNumber min={planState ? 0 : 0.25} max={24} step={0.25} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          name="planId"
          label="关联周计划项"
          extra="当周周计划（日计划已并入条目，日执行直接对齐周方向）；关联后计划项自动置「进行中」（完成仍需人工）"
        >
          <Select
            allowClear
            placeholder="可选"
            options={[
              ...linkable.map((p) => ({
                value: p.id,
                label: p.title,
                status: p.status,
              })),
              ...(linkedMissing != null
                ? [{ value: linkedMissing, label: `关联计划 #${linkedMissing}（已放弃/已滚转）`, status: '' }]
                : []),
            ]}
            optionRender={(opt) => (
              <Space size={4}>
                <span>{opt.label}</span>
                {(opt.data as { status?: string }).status && (
                  <TypographyTextStatus status={(opt.data as { status?: string }).status!} />
                )}
              </Space>
            )}
          />
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

/** 计划状态下拉选项里的小徽标（避免选项里塞整包 Tag 样式过重） */
function TypographyTextStatus({ status }: { status: string }) {
  const s = PLAN_STATUSES[status]
  if (!s) return null
  return (
    <Tag color={s.color} style={{ marginInlineEnd: 0 }}>
      {s.label}
    </Tag>
  )
}
