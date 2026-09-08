// 新问答草稿态：输入框直接开问——首条消息即开场提问，发送即创建问答。
// 高级选项只留 执行节点/模型/权限模式（无项目/仓库/模板，与项目会话完全分开），收进 Popover。
import { useEffect, useMemo, useState } from 'react'
import { Badge, Button, Empty, Form, Input, Popover, Select, Space, message } from 'antd'
import { SendOutlined, SettingOutlined } from '@ant-design/icons'
import { createChat } from '../api'
import type { ChatSummary } from '../types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'

const DEFAULTS = { permissionMode: 'acceptEdits' }

export default function NewChatDraft({
  onCreated,
  onCancel,
}: {
  onCreated: (c: ChatSummary) => void
  /** 有可选问答时提供「取消」返回选中态 */
  onCancel?: () => void
}) {
  const [form] = Form.useForm()
  const [agentNodes, setAgentNodes] = useState<AgentNode[]>([])
  const [text, setText] = useState('')
  const [creating, setCreating] = useState(false)
  const [optionsOpen, setOptionsOpen] = useState(false)
  const values = Form.useWatch([], form) ?? {}

  useEffect(() => {
    listAgentNodes()
      .then(setAgentNodes)
      .catch(() => undefined)
  }, [])

  // 有任意非默认选项时给入口加徽标点
  const hasCustomOptions = useMemo(
    () =>
      Boolean(
        values.model ||
          values.agentNodeId ||
          (values.permissionMode && values.permissionMode !== DEFAULTS.permissionMode),
      ),
    [values],
  )

  const onSend = async () => {
    const t = text.trim()
    if (!t || creating) return
    setCreating(true)
    try {
      const v = form.getFieldsValue()
      const c = await createChat({
        message: t,
        model: v.model || undefined,
        permissionMode: v.permissionMode || undefined,
        agentNodeId: v.agentNodeId || undefined,
      })
      message.success(`问答已创建：${c.id}`)
      setText('')
      form.resetFields()
      onCreated(c)
    } catch (e) {
      message.error(`创建失败：${(e as Error).message}`)
    } finally {
      setCreating(false)
    }
  }

  const optionsForm = (
    <div style={{ width: 340 }}>
      <Form form={form} layout="vertical" size="small" initialValues={DEFAULTS}>
        <Form.Item
          label="执行节点"
          name="agentNodeId"
          extra="留空 = 平台默认节点；无默认则创建失败"
          style={{ marginBottom: 12 }}
        >
          <Select
            placeholder="跟随默认（平台默认节点）"
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
              新问答——在下方输入问题，发送即开始对话。
              <br />
              纯问答不关联项目/仓库，在干净沙箱中运行；需要指定执行节点 / 模型时，点左下「高级选项」。
            </>
          }
        />
      </div>
      <div style={{ border: '1px solid #d9d9d9', borderRadius: 8, padding: 12 }}>
        <Input.TextArea
          autoSize={{ minRows: 3, maxRows: 8 }}
          variant="borderless"
          value={text}
          placeholder="例如：解释一下 Spring 虚拟线程的调度模型。Enter 发送 / Shift+Enter 换行…"
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
