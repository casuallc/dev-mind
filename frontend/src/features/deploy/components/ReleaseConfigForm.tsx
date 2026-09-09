// 发版配置表单（CAP-11 FR-01，每项目一份）：Nexus 仓库/推送模板/版本规则/执行方式。
// 供项目设置「发版配置」Tab 使用；发版的创建与历史在工作台 /releases。
// CAP-36：AGENT 执行由 runner 节点承接（exec 帧下发渲染后的脚本串）。
import { Button, Card, Form, Input, Select, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import { SaveOutlined } from '@ant-design/icons'
import { getReleaseConfig, saveReleaseConfig } from '../api'
import type { ReleaseConfigInput } from '../types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { showError } from '../../../shared/utils/showError'

export default function ReleaseConfigForm({ id }: { id: string }) {
  const [form] = Form.useForm<ReleaseConfigInput>()
  const [busy, setBusy] = useState(false)
  const [nodes, setNodes] = useState<AgentNode[]>([])

  useEffect(() => {
    getReleaseConfig(id)
      .then((c) =>
        form.setFieldsValue({
          nexusRepo: c?.nexusRepo ?? '',
          scriptTemplateRef: c?.scriptTemplateRef ?? '',
          versionRule: c?.versionRule ?? '',
          executor: c?.executor ?? 'LOCAL',
          agentNodeId: c?.agentNodeId,
        }),
      )
      .catch(() => {})
    listAgentNodes().then(setNodes).catch(() => {})
  }, [id, form])

  const onSave = async (v: ReleaseConfigInput) => {
    setBusy(true)
    try {
      await saveReleaseConfig(id, v)
      message.success('发版配置已保存')
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card
      title="发版配置"
      extra={
        <Button type="primary" icon={<SaveOutlined />} loading={busy} onClick={() => form.submit()}>
          保存配置
        </Button>
      }
    >
      <Typography.Paragraph type="secondary">
        发版执行器（CAP-11）：配置制品推送 Nexus 的模板与版本规则；配置好后到工作台「发版」创建并跟踪发版。
      </Typography.Paragraph>
      <Form form={form} layout="vertical" onFinish={onSave} style={{ maxWidth: 480 }}>
        <Form.Item label="Nexus 仓库" name="nexusRepo" extra="目标仓库，如 snapshots / releases">
          <Input placeholder="snapshots / releases" />
        </Form.Item>
        <Form.Item
          label="推送模板 code"
          name="scriptTemplateRef"
          extra="执行底座白名单模板 code（后台「模板与审计」登记），服务端渲染后执行推送"
        >
          <Input placeholder="如 nexus_push" />
        </Form.Item>
        <Form.Item
          label="版本规则"
          name="versionRule"
          extra="可递增 semver 基准；新建发版版本留空时按此自动 +1"
        >
          <Input placeholder="1.0.0" />
        </Form.Item>
        <Form.Item label="执行方式" name="executor">
          <Select
            options={[
              { value: 'LOCAL', label: 'LOCAL（本机）' },
              { value: 'AGENT', label: 'AGENT（Agent 节点）' },
            ]}
          />
        </Form.Item>
        <Form.Item noStyle shouldUpdate={(a, b) => a.executor !== b.executor}>
          {({ getFieldValue }) =>
            getFieldValue('executor') === 'AGENT' ? (
              <Form.Item label="执行节点" name="agentNodeId" extra="留空 = 路由链（项目默认 → 平台默认）">
                <Select
                  allowClear
                  placeholder="选择 runner 节点"
                  options={nodes.map((n) => ({
                    value: String(n.id),
                    label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
                  }))}
                />
              </Form.Item>
            ) : null
          }
        </Form.Item>
      </Form>
    </Card>
  )
}
