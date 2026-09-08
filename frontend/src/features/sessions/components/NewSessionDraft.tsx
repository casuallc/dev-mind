// 新会话草稿态：输入框直接开聊——首条消息即 taskSpec，发送即创建会话。
// CAP-31：会话固定归属「当前项目」（侧边栏顶部切换，不再可选）；
// 高级选项（仓库多选/节点/需求/工作单元/模板/模型/权限模式）收进 Popover，有非默认值时 Badge 点缀。
import { useEffect, useMemo, useState } from 'react'
import { Badge, Button, Empty, Form, Input, Popover, Select, Space, message } from 'antd'
import { SendOutlined, SettingOutlined } from '@ant-design/icons'
import { createSession } from '../api'
import type { SessionSummary } from '../types'
import { useSessionOptionData } from '../hooks/useSessionOptionData'
import { useCurrentProject } from '../../../app/useCurrentProject'

const DEFAULTS = { permissionMode: 'acceptEdits' }

export default function NewSessionDraft({
  onCreated,
  onCancel,
}: {
  onCreated: (s: SessionSummary) => void
  /** 有可选会话时提供「取消」返回选中态 */
  onCancel?: () => void
}) {
  const [form] = Form.useForm()
  // ProjectContextGate 保证进入本页必有当前项目
  const { projectId, project } = useCurrentProject()
  const { templates, agentNodes, repos, requirements, workItems } = useSessionOptionData(form, projectId)
  const [text, setText] = useState('')
  const [creating, setCreating] = useState(false)
  const [optionsOpen, setOptionsOpen] = useState(false)
  const values = Form.useWatch([], form) ?? {}

  // 仓库默认勾主库；执行节点预填项目默认节点（可改/清除）
  const defaultRepoIds = useMemo(() => repos.filter((r) => r.primary).map((r) => r.id), [repos])
  useEffect(() => {
    if (!form.isFieldTouched('repoIds') && defaultRepoIds.length > 0) {
      form.setFieldsValue({ repoIds: defaultRepoIds })
    }
  }, [defaultRepoIds, form])
  useEffect(() => {
    if (project?.agentNodeId) {
      form.setFieldsValue({ agentNodeId: project.agentNodeId })
    }
  }, [project, form])

  // 有任意非默认选项时给入口加徽标点
  const hasCustomOptions = useMemo(
    () =>
      Boolean(
        values.model ||
          values.templateCode ||
          (values.agentNodeId && values.agentNodeId !== project?.agentNodeId) ||
          values.requirementId ||
          values.workItemId ||
          (values.permissionMode && values.permissionMode !== DEFAULTS.permissionMode) ||
          (values.repoIds &&
            JSON.stringify([...values.repoIds].sort()) !== JSON.stringify([...defaultRepoIds].sort())),
      ),
    [values, defaultRepoIds, project],
  )

  const onSend = async () => {
    const t = text.trim()
    if (!t || creating || !projectId) return
    setCreating(true)
    try {
      const v = form.getFieldsValue()
      const s = await createSession({
        taskSpec: t,
        templateCode: v.templateCode || undefined,
        model: v.model || undefined,
        permissionMode: v.permissionMode || undefined,
        projectId,
        requirementId: v.requirementId || undefined,
        workItemId: v.workItemId || undefined,
        agentNodeId: v.agentNodeId || undefined,
        repoIds: v.repoIds?.length ? v.repoIds : undefined,
      })
      message.success(`会话已创建：${s.id}`)
      setText('')
      form.resetFields()
      onCreated(s)
    } catch (e) {
      message.error(`创建失败：${(e as Error).message}`)
    } finally {
      setCreating(false)
    }
  }

  const optionsForm = (
    <div style={{ width: 380 }}>
      <Form form={form} layout="vertical" size="small" initialValues={DEFAULTS}>
        <Form.Item
          label="关联仓库"
          name="repoIds"
          extra="会话在这些仓库的 worktree 中工作；多库时聚合到一个目录（claude 在聚合根运行，各库为子目录）"
          style={{ marginBottom: 12 }}
        >
          <Select
            mode="multiple"
            options={repos.map((r) => ({
              value: r.id,
              label: `${r.name}${r.primary ? '（主库）' : ''}`,
            }))}
            placeholder="默认主库"
            notFoundContent="当前项目未关联仓库（后台 → 项目 → 仓库）"
          />
        </Form.Item>
        <Form.Item label="执行节点" name="agentNodeId" extra="留空 = 项目默认 → 平台默认；皆无则创建失败" style={{ marginBottom: 12 }}>
          <Select
            placeholder="跟随默认（项目 → 平台）"
            allowClear
            options={agentNodes
              .filter((n) => n.status === 'ONLINE')
              .map((n) => ({
                value: String(n.id),
                label: `${n.name} (${n.os ?? '远程节点'})${n.isDefault ? ' · 平台默认' : ''}`,
              }))}
            notFoundContent="暂无在线节点（后台 → Agent 节点 注册）"
          />
        </Form.Item>
        <Form.Item label="关联需求" name="requirementId" style={{ marginBottom: 12 }}>
          <Select
            options={requirements.map((r) => ({ value: r.id, label: `${r.code} ${r.title}` }))}
            placeholder="（可选）选择需求"
            allowClear
            showSearch
            optionFilterProp="label"
          />
        </Form.Item>
        <Form.Item label="关联工作单元" name="workItemId" style={{ marginBottom: 12 }}>
          <Select
            options={workItems.map((w) => ({ value: w.id, label: `${w.code} ${w.title}` }))}
            placeholder="（可选）选择工作单元"
            allowClear
            disabled={!values.requirementId}
            showSearch
            optionFilterProp="label"
          />
        </Form.Item>
        <Form.Item label="会话模板" name="templateCode" extra="模板作为 prompt 骨架包裹首条消息" style={{ marginBottom: 12 }}>
          <Select
            allowClear
            placeholder="（可选）选择模板"
            options={templates.filter((t) => t.enabled).map((t) => ({ value: t.code, label: t.name }))}
          />
        </Form.Item>
        <Form.Item label="模型" name="model" style={{ marginBottom: 12 }}>
          <Input placeholder="留空使用全局默认模型" />
        </Form.Item>
        <Form.Item label="权限模式" name="permissionMode" style={{ marginBottom: 0 }}>
          <Select
            options={[
              { value: 'acceptEdits', label: 'acceptEdits（默认）' },
              { value: 'default', label: 'default（需要授权）' },
              { value: 'bypassPermissions', label: 'bypassPermissions（全放）' },
              { value: 'plan', label: 'plan（只读规划）' },
            ]}
          />
        </Form.Item>
      </Form>
    </div>
  )

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 420 }}>
      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Empty
          description={
            <>
              新对话——在下方输入任务说明，发送即基于当前项目（{project?.name ?? projectId}）创建会话。
              <br />
              需要多仓库 / 模板 / 执行节点等时，点输入框左下「高级选项」。
            </>
          }
        />
      </div>
      <div style={{ border: '1px solid #d9d9d9', borderRadius: 8, padding: 12 }}>
        <Input.TextArea
          autoSize={{ minRows: 3, maxRows: 8 }}
          variant="borderless"
          value={text}
          placeholder="例如：为主库添加用户登录功能，编写测试并通过。Enter 发送 / Shift+Enter 换行…"
          onChange={(e) => setText(e.target.value)}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault()
              onSend()
            }
          }}
        />
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 4 }}>
          <Space>
            <Popover
              content={optionsForm}
              trigger="click"
              open={optionsOpen}
              onOpenChange={setOptionsOpen}
              placement="topLeft"
              destroyOnHidden
            >
              <Badge dot={hasCustomOptions}>
                <Button size="small" icon={<SettingOutlined />}>
                  高级选项
                </Button>
              </Badge>
            </Popover>
            {onCancel && (
              <Button size="small" type="text" onClick={onCancel}>
                取消
              </Button>
            )}
          </Space>
          <Button
            type="primary"
            icon={<SendOutlined />}
            loading={creating}
            disabled={!text.trim()}
            onClick={onSend}
          >
            发送
          </Button>
        </div>
      </div>
    </div>
  )
}
