// 统一行内运行弹窗（取代 ScriptRunModal）：按套件类型渲染字段——
// script = 执行节点/env 覆盖/命令覆盖（仅本次生效，走 runScriptSuite；路由链 显式节点 → 套件默认 → 项目默认 → 平台默认）；
// api/smoke = 目标环境/执行节点/baseUrl（走 createRun 单套件；环境与节点互斥，环境自带节点）。
// 顶部批量「新建运行」（多套件）入口保留在 TestsPage，不走这里。
import { Form, Input, Modal, Select, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { createRun, runScriptSuite } from '../api'
import type { ScriptSuite, ScriptSuiteRunInput, TestRun, TestSuite } from '../types'
import type { AgentNode } from '../../agent/types'
import type { ProjectEnvironment } from '../../projects/types'
import EnvEditor from './EnvEditor'
import { showError } from '../../../shared/utils/showError'

interface Props {
  /** null = 关闭 */
  suite: TestSuite | null
  /** kind=script 时由父级传入（拿 command 做覆盖占位符） */
  scriptSuite?: ScriptSuite
  projectId: string
  nodes: AgentNode[]
  environments: ProjectEnvironment[]
  onClose: () => void
  onRan: (run: TestRun) => void
}

export default function RunSuiteModal({ suite, scriptSuite, projectId, nodes, environments, onClose, onRan }: Props) {
  const [form] = Form.useForm()
  const [running, setRunning] = useState(false)
  const environmentId = Form.useWatch('environmentId', form)
  const isScript = suite?.kind === 'script'

  useEffect(() => {
    if (suite) form.setFieldsValue({ agentNodeId: undefined, environmentId: undefined, baseUrl: '', env: [], command: '' })
  }, [suite, form])

  const nodeOptions = nodes.map((n) => ({
    value: String(n.id),
    label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
  }))

  const onRun = async () => {
    if (!suite) return
    const v = await form.validateFields()
    setRunning(true)
    try {
      if (isScript) {
        const envRows = (v.env ?? []).filter((e: { key?: string }) => e.key?.trim())
        const input: ScriptSuiteRunInput = {
          agentNodeId: v.agentNodeId || undefined,
          env: envRows.length ? Object.fromEntries(envRows.map((e: { key: string; value?: string }) => [e.key, e.value ?? ''])) : undefined,
          command: v.command?.trim() || undefined,
        }
        onRan(await runScriptSuite(suite.id, input))
      } else {
        onRan(await createRun({
          projectId,
          suiteIds: [suite.id],
          agentNodeId: v.agentNodeId || undefined,
          environmentId: v.environmentId || undefined,
          baseUrl: v.baseUrl?.trim() || undefined,
        }))
      }
    } catch (e) {
      showError(e)
    } finally {
      setRunning(false)
    }
  }

  return (
    <Modal
      title={suite ? `运行套件「${suite.name}」` : '运行套件'}
      open={!!suite}
      onCancel={onClose}
      onOk={onRun}
      okText="执行测试"
      confirmLoading={running}
      width={560}
    >
      <Form form={form} layout="vertical">
        {isScript ? (
          <>
            <Form.Item label="执行节点（可选）" name="agentNodeId"
              extra="留空 = 套件默认节点 → 项目默认 → 平台默认">
              <Select allowClear placeholder="套件默认 → 项目默认 → 平台默认" options={nodeOptions} />
            </Form.Item>
            <Form.Item label="env 覆盖（可选，仅本次生效）" name="env">
              <EnvEditor withSecret={false} />
            </Form.Item>
            <Form.Item label="命令覆盖（可选，仅本次生效）" name="command">
              <Input.TextArea rows={3} placeholder={scriptSuite?.command || '留空 = 套件命令'} style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          </>
        ) : (
          <>
            <Form.Item label="目标环境（可选）" name="environmentId"
              extra="选环境后由其自带节点执行，下方执行节点不可选">
              <Select
                allowClear
                placeholder="目标环境（可选）"
                options={environments.map((e) => ({ value: e.id, label: e.name }))}
                onChange={(v) => { if (v != null) form.setFieldsValue({ agentNodeId: undefined }) }}
              />
            </Form.Item>
            <Form.Item label="执行节点（可选，command 型健康检查用）" name="agentNodeId">
              <Select
                allowClear
                disabled={environmentId != null}
                placeholder={nodes.length ? '执行节点（可选）' : '无可用节点（先到后台「Agent 节点」登记）'}
                options={nodeOptions}
              />
            </Form.Item>
            <Form.Item label="baseUrl（可选，http 用例目标）" name="baseUrl"
              extra="目标优先级：baseUrl 显式 > 环境变量（baseUrl/BASE_URL）> 关联部署的环境。http 用例由服务端直接探测；command 型健康检查经 exec 帧下发执行节点。">
              <Input placeholder="http://host:port" />
            </Form.Item>
          </>
        )}
      </Form>
      {!isScript && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0, marginTop: -8 }}>
          多套件批量运行请用运行历史视图右上角「新建运行」。
        </Typography.Paragraph>
      )}
    </Modal>
  )
}
