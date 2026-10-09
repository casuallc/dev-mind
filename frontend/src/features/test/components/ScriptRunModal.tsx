// CAP-69 脚本套件运行弹窗：节点/env/命令三样覆盖全可选（仅本次生效，不落库）。
// 路由链：显式节点 → 套件默认 → 项目默认 → 平台默认 → 409。
import { Form, Input, Modal, Select } from 'antd'
import { useEffect, useState } from 'react'
import { runScriptSuite } from '../api'
import type { ScriptSuite, ScriptSuiteRunInput, TestRun } from '../types'
import type { AgentNode } from '../../agent/types'
import EnvEditor from './EnvEditor'
import { showError } from '../../../shared/utils/showError'

interface Props {
  /** null = 关闭 */
  suite: ScriptSuite | null
  nodes: AgentNode[]
  onClose: () => void
  onRan: (run: TestRun) => void
}

export default function ScriptRunModal({ suite, nodes, onClose, onRan }: Props) {
  const [form] = Form.useForm()
  const [running, setRunning] = useState(false)

  useEffect(() => {
    if (suite) form.setFieldsValue({ agentNodeId: undefined, env: [], command: '' })
  }, [suite, form])

  const nodeOptions = nodes.map((n) => ({
    value: String(n.id),
    label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
  }))

  const onRun = async () => {
    if (!suite) return
    const v = await form.validateFields()
    const envRows = (v.env ?? []).filter((e: { key?: string }) => e.key?.trim())
    const input: ScriptSuiteRunInput = {
      agentNodeId: v.agentNodeId || undefined,
      env: envRows.length ? Object.fromEntries(envRows.map((e: { key: string; value?: string }) => [e.key, e.value ?? ''])) : undefined,
      command: v.command?.trim() || undefined,
    }
    setRunning(true)
    try {
      onRan(await runScriptSuite(suite.id, input))
    } catch (e) {
      showError(e)
    } finally {
      setRunning(false)
    }
  }

  return (
    <Modal
      title={suite ? `运行脚本套件「${suite.name}」` : '运行脚本套件'}
      open={!!suite}
      onCancel={onClose}
      onOk={onRun}
      okText="执行测试"
      confirmLoading={running}
      width={560}
    >
      <Form form={form} layout="vertical">
        <Form.Item label="执行节点（可选）" name="agentNodeId"
          extra="留空 = 套件默认节点 → 项目默认 → 平台默认">
          <Select allowClear placeholder="套件默认 → 项目默认 → 平台默认" options={nodeOptions} />
        </Form.Item>
        <Form.Item label="env 覆盖（可选，仅本次生效）" name="env">
          <EnvEditor withSecret={false} />
        </Form.Item>
        <Form.Item label="命令覆盖（可选，仅本次生效）" name="command">
          <Input.TextArea rows={3} placeholder={suite?.command || '留空 = 套件命令'} style={{ fontFamily: 'monospace' }} />
        </Form.Item>
      </Form>
    </Modal>
  )
}
