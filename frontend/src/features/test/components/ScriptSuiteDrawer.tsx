// CAP-69 脚本套件新建/编辑抽屉（强制绑定项目，projectId 由父级传入）：自带 git 源 + 命令模板
// + env（脱敏，掩码原样回传 = 该条值不变）+ 超时 + 工作区 key。触发运行见 ScriptRunModal。
import { Button, Drawer, Form, Input, InputNumber, Select, Space, message } from 'antd'
import { useEffect, useState } from 'react'
import { createScriptSuite, updateScriptSuite } from '../api'
import type { ScriptSuite, ScriptSuiteInput } from '../types'
import type { AgentNode } from '../../agent/types'
import EnvEditor from './EnvEditor'
import { showError } from '../../../shared/utils/showError'

interface Props {
  open: boolean
  projectId: string
  /** null = 新建；prefillName 用于「新建套件」弹窗选了 script 类型后带名跳入 */
  editing: ScriptSuite | null
  prefillName?: string
  nodes: AgentNode[]
  onClose: () => void
  onSaved: () => void
}

export default function ScriptSuiteDrawer({ open, projectId, editing, prefillName, nodes, onClose, onSaved }: Props) {
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!open) return
    if (editing) {
      form.setFieldsValue({
        name: editing.name,
        repoUrl: editing.repoUrl,
        branch: editing.branch,
        workSubdir: editing.workSubdir ?? '',
        command: editing.command,
        junitPath: editing.junitPath,
        timeoutSec: editing.timeoutSec ?? 7200,
        workspaceKey: editing.workspaceKey ?? '',
        agentNodeId: editing.agentNodeId ?? undefined,
        env: editing.env,
      })
    } else {
      form.setFieldsValue({
        name: prefillName ?? '', repoUrl: '', branch: 'master', workSubdir: '', command: '',
        junitPath: 'target/junit.xml', timeoutSec: 7200, workspaceKey: '', agentNodeId: undefined, env: [],
      })
    }
  }, [open, editing, prefillName, form])

  const nodeOptions = nodes.map((n) => ({
    value: String(n.id),
    label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
  }))

  const onSave = async () => {
    const v = await form.validateFields()
    const input: ScriptSuiteInput = {
      projectId,
      name: v.name,
      repoUrl: v.repoUrl,
      branch: v.branch,
      workSubdir: v.workSubdir?.trim() || undefined,
      command: v.command,
      junitPath: v.junitPath,
      env: (v.env ?? []).filter((e: { key?: string }) => e.key?.trim()),
      agentNodeId: v.agentNodeId || undefined,
      timeoutSec: v.timeoutSec ?? undefined,
      workspaceKey: v.workspaceKey?.trim() || undefined,
    }
    setSaving(true)
    try {
      if (editing) {
        await updateScriptSuite(editing.id, input)
        message.success('套件已保存')
      } else {
        await createScriptSuite(input)
        message.success('套件已创建')
      }
      onSaved()
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Drawer
      title={editing ? `编辑脚本套件「${editing.name}」` : '新建脚本套件'}
      width={640}
      open={open}
      onClose={onClose}
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={onSave}>{editing ? '保存' : '创建'}</Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical">
        <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入套件名' }]}>
          <Input placeholder="如 ADMQ Manager UI E2E" />
        </Form.Item>
        <Form.Item label="git 仓库地址" name="repoUrl" rules={[{ required: true, message: '请输入仓库地址' }]}
          extra="节点凭自身 git 凭据 clone/pull（内网匿名读或节点 credential helper）">
          <Input placeholder="http://git.local/group/repo.git" />
        </Form.Item>
        <Space size={12} style={{ display: 'flex' }}>
          <Form.Item label="分支" name="branch" rules={[{ required: true, message: '请输入分支' }]} style={{ flex: 1 }}>
            <Input placeholder="master" />
          </Form.Item>
          <Form.Item label="工作子目录（可选）" name="workSubdir" style={{ flex: 1 }}
            extra="命令在仓库内该子目录执行，如 e2e">
            <Input placeholder="e2e" />
          </Form.Item>
        </Space>
        <Form.Item label="执行命令" name="command" rules={[{ required: true, message: '请输入执行命令' }]}
          extra="runner execAllowlist 逐行校验首词，npm/npx/git 等需先放行">
          <Input.TextArea rows={4} placeholder={'npm ci\nnpx playwright test'} style={{ fontFamily: 'monospace' }} />
        </Form.Item>
        <Form.Item label="JUnit 产出路径" name="junitPath" rules={[{ required: true, message: '请输入 JUnit 产出路径' }]}
          extra="相对工作子目录；缺失时不判失败，仅在备注中注记">
          <Input placeholder="test-results/junit.xml" />
        </Form.Item>
        <Space size={12} style={{ display: 'flex' }}>
          <Form.Item label="超时（秒）" name="timeoutSec" style={{ flex: 1 }}>
            <InputNumber min={60} max={86400} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="默认节点" name="agentNodeId" style={{ flex: 1 }}
            extra="留空 = 项目默认节点 → 平台默认">
            <Select allowClear placeholder="项目默认 → 平台默认" options={nodeOptions} />
          </Form.Item>
        </Space>
        <Form.Item label="工作区 key（可选）" name="workspaceKey"
          extra="默认 = 套件 id；多套件共享同一 key 即共享节点工作区目录（套件间状态文件跨运行保留）">
          <Input placeholder="如 admq-e2e" />
        </Form.Item>
        <Form.Item label="环境变量" name="env"
          extra="注入执行进程；脱敏项保存后恒显示掩码，掩码原样回传表示该条不变">
          <EnvEditor />
        </Form.Item>
      </Form>
    </Drawer>
  )
}
