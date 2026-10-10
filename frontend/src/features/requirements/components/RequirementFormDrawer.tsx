// 需求新建/编辑抽屉：项目列表卡（新建）与需求详情页（编辑）复用。
// Jira 来源（source=JIRA）：托管字段（标题/类型/描述/优先级/标签/经办人/报告人/截止日期/修复版本）
// 由同步维护、本地禁用，仅本地负责人可改（服务端同样强制忽略）。
import { useEffect, useRef } from 'react'
import { Button, DatePicker, Drawer, Form, Input, Select, Space, Typography, message } from 'antd'
import dayjs from 'dayjs'
import { createRequirement, updateRequirement } from '../api'
import type { Requirement, RequirementInput } from '../types'
import { ALL_PRIORITIES, ALL_TYPES, TYPE_LABEL } from './requirementMeta'
import RequirementAttachmentPanel, { type RequirementAttachmentPanelHandle } from './RequirementAttachmentPanel'
import { showError } from '../../../shared/utils/showError'

/** 表单值形态：dueDate 在表单里是 dayjs，提交时转 'YYYY-MM-DD' */
type FormValues = Omit<RequirementInput, 'dueDate'> & { dueDate?: dayjs.Dayjs | null }

const JIRA_MANAGED_TIP = 'Jira 来源字段由同步维护，本地只读'

export default function RequirementFormDrawer({ projectId, editing, open, onClose, onSaved }: {
  projectId: string
  editing: Requirement | null
  open: boolean
  onClose: () => void
  onSaved: (saved: Requirement) => void
}) {
  const [form] = Form.useForm<FormValues>()
  const jiraManaged = editing?.source === 'JIRA'
  const attachPanelRef = useRef<RequirementAttachmentPanelHandle>(null)

  useEffect(() => {
    if (open) {
      if (editing) {
        form.setFieldsValue({
          ...editing,
          dueDate: editing.dueDate ? dayjs(editing.dueDate) : null,
        })
      } else {
        form.setFieldsValue({
          title: '', description: '', ownerId: '', type: 'FEATURE',
          priority: undefined, assignee: '', reporter: '', labels: [], fixVersions: [], dueDate: null,
        })
      }
    }
  }, [open, editing, form])

  const onSave = async (v: FormValues) => {
    const input: RequirementInput = {
      ...v,
      dueDate: v.dueDate ? v.dueDate.format('YYYY-MM-DD') : undefined,
    }
    try {
      const saved = editing
        ? await updateRequirement(projectId, editing.id, input)
        : await createRequirement(projectId, input)
      // 新建模式的暂存附件：需求建成后再上传+绑定；失败不阻塞主保存，仅提示（可编辑时再补传）
      if (!editing) {
        const failed = await attachPanelRef.current?.flush(saved.id)
        if (failed) message.warning(`${failed} 个附件上传失败，可在编辑需求时补传`)
      }
      message.success('已保存')
      onClose()
      onSaved(saved)
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  return (
    <Drawer
      title={editing ? `编辑需求 ${editing.code}` : '新建需求'}
      open={open}
      onClose={onClose}
      width={600}
      destroyOnHidden
      footer={
        <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" onClick={() => form.submit()}>
            保存
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" onFinish={onSave} preserve={false}>
        <Form.Item label="标题" name="title" rules={[{ required: true, message: '请输入标题' }]}
          tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Input placeholder="如 用户登录支持扫码" disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="类型" name="type" rules={[{ required: true, message: '请选择类型' }]}
          tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Select options={ALL_TYPES.map((t) => ({ value: t, label: TYPE_LABEL[t] }))} disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="描述（业务目标）" name="description"
          tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Input.TextArea rows={3} disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="优先级" name="priority" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Select allowClear options={ALL_PRIORITIES.map((p) => ({ value: p, label: p }))} disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="标签" name="labels" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          {/* 不设 tokenSeparators：标签按整串存储（后端逗号拼接），防止逗号被拆 */}
          <Select mode="tags" open={false} placeholder="回车添加" disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="经办人" name="assignee" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Input placeholder="可选" disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="报告人" name="reporter" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Input placeholder="可选" disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="截止日期" name="dueDate" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <DatePicker style={{ width: '100%' }} disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="修复版本" name="fixVersions" tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}>
          <Select mode="tags" open={false} placeholder="回车添加，如 1.0" disabled={jiraManaged} />
        </Form.Item>
        <Form.Item label="本地负责人" name="ownerId"
          tooltip={jiraManaged ? '平台侧流程负责人，与 Jira 经办人相互独立，本地可改' : undefined}>
          <Input placeholder="可选" />
        </Form.Item>
        {/* 附件：非表单字段（React state 管理）。新建=暂存，保存后随需求一并上传；编辑=即传即绑 */}
        <div style={{ marginBottom: 24 }}>
          <div style={{ marginBottom: 8 }}>
            附件
            <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
              {editing ? '增删即时生效' : '图片/文本等均可，保存需求后上传'}
            </Typography.Text>
          </div>
          <RequirementAttachmentPanel
            ref={attachPanelRef}
            projectId={projectId}
            requirementId={editing?.id ?? null}
          />
        </div>
      </Form>
    </Drawer>
  )
}
