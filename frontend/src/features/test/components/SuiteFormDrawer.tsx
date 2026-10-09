// 统一新建套件抽屉：类型选择在表单内——smoke/api 只填名称直建（createSuite），
// script 展开 git 源/命令/env 等字段（createScriptSuite，字段与内层编辑页共用 ScriptSuiteFields）。
// 编辑不走这里：所有类型统一跳内层页 /tests/suites/:id。
import { Button, Drawer, Form, Input, Select, Space, message } from 'antd'
import { useEffect, useState } from 'react'
import { createScriptSuite, createSuite } from '../api'
import type { AgentNode } from '../../agent/types'
import ScriptSuiteFields, { toScriptSuiteInput } from './ScriptSuiteFields'
import { showError } from '../../../shared/utils/showError'

interface Props {
  open: boolean
  projectId: string
  nodes: AgentNode[]
  onClose: () => void
  onSaved: () => void
}

export default function SuiteFormDrawer({ open, projectId, nodes, onClose, onSaved }: Props) {
  const [form] = Form.useForm()
  const [saving, setSaving] = useState(false)
  const kind = Form.useWatch('kind', form) as string | undefined

  useEffect(() => {
    if (!open) return
    form.setFieldsValue({
      kind: 'smoke', name: '',
      // script 分支默认值（切到 script 时即带默认；smoke/api 不校验这些未挂载字段）
      repoUrl: '', branch: 'master', workSubdir: '', command: '',
      junitPath: 'target/junit.xml', timeoutSec: 7200, workspaceKey: '', agentNodeId: undefined, env: [],
    })
  }, [open, form])

  const onSave = async () => {
    const v = await form.validateFields()
    setSaving(true)
    try {
      if (v.kind === 'script') {
        await createScriptSuite(toScriptSuiteInput(projectId, v))
        message.success('脚本套件已创建')
      } else {
        await createSuite(projectId, { name: v.name, kind: v.kind })
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
      title="新建套件"
      width={640}
      open={open}
      onClose={onClose}
      destroyOnHidden
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={onSave}>创建</Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical">
        <Space size={12} style={{ display: 'flex' }} align="start">
          <Form.Item label="类型" name="kind" rules={[{ required: true }]} style={{ width: 220 }}>
            <Select options={[
              { value: 'smoke', label: 'smoke（冒烟：health 用例）' },
              { value: 'api', label: 'api（手工编排 http 用例）' },
              { value: 'script', label: 'script（脚本：git 源 + 命令）' },
            ]} />
          </Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入套件名' }]} style={{ flex: 1 }}>
            <Input placeholder={kind === 'script' ? '如 ADMQ Manager UI E2E' : '如 冒烟套件 / 支付回归'} />
          </Form.Item>
        </Space>
        {kind === 'script' && <ScriptSuiteFields nodes={nodes} />}
        {kind !== 'script' && (
          <div style={{ color: '#888', fontSize: 12 }}>
            创建后跳内层页编排用例：api 套件手工维护 http 用例；smoke 套件自带 health 存活检查用例。
          </div>
        )}
      </Form>
    </Drawer>
  )
}
