// 需求新建/编辑抽屉：项目列表卡（新建）与需求详情页（编辑）复用。
// Jira 来源（source=JIRA）：托管字段（标题/类型/描述/优先级/标签/经办人/报告人/截止日期/修复版本）
// 由同步维护、本地禁用，仅本地负责人可改（服务端同样强制忽略）。
// 描述为 Markdown 富文本（CAP-44 MarkdownEditor）：粘贴/拖拽/工具栏上传图片附件内联进描述
// （scope=SHARED 项目成员可见），与 CAP-40 需求附件装配同一口径——描述里的引用会被投送到 AI 会话。
import { useEffect, useState } from 'react'
import { Button, DatePicker, Drawer, Form, Input, Select, Space, message } from 'antd'
import dayjs from 'dayjs'
import { createRequirement, updateRequirement } from '../api'
import type { Requirement, RequirementInput } from '../types'
import { ALL_PRIORITIES, ALL_TYPES, TYPE_LABEL } from './requirementMeta'
import { listEnvironments } from '../../projects/api'
import type { ProjectEnvironment } from '../../projects/types'
import MarkdownEditor from '../../../shared/components/MarkdownEditor'
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
  // CAP-73：验证环境选项（PROD 永远不可绑，服务端同样校验同项目归属）
  const [envOptions, setEnvOptions] = useState<{ value: number; label: string }[]>([])

  useEffect(() => {
    if (!open) return
    listEnvironments(projectId)
      .then((envs: ProjectEnvironment[]) =>
        setEnvOptions(envs
          .filter((e) => e.name.toUpperCase() !== 'PROD')
          .map((e) => ({
            value: e.id,
            label: `${e.name}${e.patchDir ? '' : '（未配补丁目录，不可用于验证）'}`,
          }))))
      .catch(() => setEnvOptions([]))
  }, [open, projectId])

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
          verifyEnvironmentId: undefined,
        })
      }
    }
  }, [open, editing, form])

  const onSave = async (v: FormValues) => {
    const input: RequirementInput = {
      ...v,
      dueDate: v.dueDate ? v.dueDate.format('YYYY-MM-DD') : undefined,
      // 后端语义：不传(null)=保持不变，0=解绑，>0=绑定；allowClear 清除后是 undefined，
      // 编辑时原本有绑定而表单被清空 = 用户主动解绑
      verifyEnvironmentId: v.verifyEnvironmentId
        ?? (editing?.verifyEnvironmentId != null ? 0 : undefined),
    }
    try {
      const saved = editing
        ? await updateRequirement(projectId, editing.id, input)
        : await createRequirement(projectId, input)
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
      width={960}
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
          <MarkdownEditor
            height={220}
            placeholder="支持 Markdown；粘贴/拖拽图片或点工具栏按钮上传附件，内联进描述（AI 会话可读）"
            disabled={jiraManaged}
            attachmentUpload={{ scope: 'SHARED' }}
          />
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
        <Form.Item label="验证环境" name="verifyEnvironmentId"
          extra="CAP-73：绑定后 AI 在开发会话里 commit 即可自主触发「推送→增量构建→补丁更新→冒烟」，结果回灌会话；bug 类需求绑复现所在环境，PROD 不可绑">
          <Select allowClear placeholder="不绑定 = 开发中验证不可用" options={envOptions} />
        </Form.Item>
      </Form>
    </Drawer>
  )
}
