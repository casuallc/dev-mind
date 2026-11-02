// 需求新建/编辑抽屉：项目列表卡（新建）与需求详情页（编辑）复用。
// Jira 来源（source=JIRA）：托管字段（标题/类型/描述/优先级/标签/经办人/报告人/截止日期/修复版本）
// 由同步维护、本地禁用，仅本地负责人可改（服务端同样强制忽略）。
// 描述为 Markdown 富文本（CAP-44 MarkdownEditor）：粘贴/拖拽/工具栏上传图片附件内联进描述
// （scope=SHARED 项目成员可见），与 CAP-40 需求附件装配同一口径——描述里的引用会被投送到 AI 会话。
import { useEffect, useState } from 'react'
import { Button, DatePicker, Drawer, Form, Input, Modal, Select, Space, Typography, message } from 'antd'
import dayjs from 'dayjs'
import { aiExpandRequirement, createRequirement, updateRequirement } from '../api'
import type { Requirement, RequirementInput } from '../types'
import { ALL_PRIORITIES, ALL_TYPES, TYPE_LABEL } from './requirementMeta'
import { listEnvironments } from '../../projects/api'
import type { ProjectEnvironment } from '../../projects/types'
import MarkdownEditor from '../../../shared/components/MarkdownEditor'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

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
  // CAP-105 AI 扩充：要点弹层 + 生成态 + 覆盖前描述备份（可恢复）
  const [aiOpen, setAiOpen] = useState(false)
  const [aiNotes, setAiNotes] = useState('')
  const [aiGenerating, setAiGenerating] = useState(false)
  const [descBackup, setDescBackup] = useState<string | null>(null)

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
      // CAP-105：每次打开抽屉重置 AI 扩充弹层与描述备份
      setAiOpen(false)
      setAiNotes('')
      setDescBackup(null)
    }
  }, [open, editing, form])

  /** CAP-105：AI 扩充——成功回填（描述非空先确认覆盖，覆盖前备份可恢复）；失败不动表单、不关弹层 */
  const onAiGenerate = async () => {
    const notes = aiNotes.trim()
    if (!notes) {
      message.warning('请先输入关键信息/要点')
      return
    }
    setAiGenerating(true)
    try {
      const title = form.getFieldValue('title') as string | undefined
      const type = form.getFieldValue('type') as FormValues['type']
      const priority = form.getFieldValue('priority') as string | undefined
      const result = await aiExpandRequirement(projectId, {
        notes,
        title: title?.trim() || undefined,
        type,
        priority: priority || undefined,
      })
      const apply = () => {
        const current = (form.getFieldValue('description') as string | undefined) ?? ''
        if (current.trim()) setDescBackup(current) // 重新生成时刷新备份为本次生成前内容
        form.setFieldsValue({ description: result.description })
        if (result.truncated) {
          message.warning('生成内容超出长度上限，已截断至 20000 字，请人工补全')
        }
        setAiOpen(false)
        setAiNotes('')
      }
      const current = (form.getFieldValue('description') as string | undefined) ?? ''
      if (current.trim()) {
        Modal.confirm({
          centered: true,
          title: '覆盖当前描述？',
          content: '生成内容将覆盖当前描述；覆盖前的原文会保留，可通过描述下方的「恢复原文」还原。',
          okText: '覆盖',
          cancelText: '取消',
          onOk: apply,
        })
      } else {
        apply()
      }
    } catch (e) {
      showError(e, 'AI 生成失败')
    } finally {
      setAiGenerating(false)
    }
  }

  /** CAP-105：恢复覆盖前的手填描述并清空备份 */
  const onRestoreBackup = () => {
    if (descBackup == null) return
    form.setFieldsValue({ description: descBackup })
    setDescBackup(null)
  }

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
      width={DRAWER_DEFAULT}
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
        <Form.Item
          label={
            <Space size={8}>
              <span>描述（业务目标）</span>
              <Button size="small" disabled={jiraManaged} onClick={() => setAiOpen(true)}>
                AI 扩充
              </Button>
            </Space>
          }
          name="description"
          tooltip={jiraManaged ? JIRA_MANAGED_TIP : undefined}
          extra={
            descBackup != null && (
              <Typography.Link style={{ fontSize: 12 }} onClick={onRestoreBackup}>
                恢复原文（AI 扩充前的手填描述）
              </Typography.Link>
            )
          }
        >
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
      {/* CAP-105 AI 扩充要点弹层：失败不关弹层、不动表单；成功后关闭并清空 notes */}
      <Modal
        title="AI 扩充描述"
        open={aiOpen}
        okText="生成"
        cancelText="取消"
        confirmLoading={aiGenerating}
        okButtonProps={{ disabled: !aiNotes.trim() }}
        onOk={onAiGenerate}
        onCancel={() => setAiOpen(false)}
        destroyOnHidden
      >
        <Input.TextArea
          rows={6}
          maxLength={4000}
          showCount
          value={aiNotes}
          onChange={(e) => setAiNotes(e.target.value)}
          placeholder="输入关键信息/零散要点，如：用户反馈导出报表慢，希望支持异步导出并通知"
        />
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, margin: '8px 0 0' }}>
          {aiGenerating ? '生成中…' : '将携带当前已填的标题/类型/优先级作为生成上下文；生成结果只回填到描述，保存时才提交。'}
        </Typography.Paragraph>
      </Modal>
    </Drawer>
  )
}
