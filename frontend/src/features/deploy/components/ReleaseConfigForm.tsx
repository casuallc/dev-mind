// 发版配置表单（CAP-11 FR-01，每项目一份）：Nexus 仓库/推送模板/版本规则/执行方式。
// 供项目设置「发版配置」Tab 使用；发版的创建与历史在工作台 /releases。
import { Button, Card, Form, Input, InputNumber, Select, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import { SaveOutlined } from '@ant-design/icons'
import { getReleaseConfig, saveReleaseConfig } from '../api'
import type { ReleaseConfigInput } from '../types'

export default function ReleaseConfigForm({ id }: { id: string }) {
  const [form] = Form.useForm<ReleaseConfigInput>()
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    getReleaseConfig(id)
      .then((c) =>
        form.setFieldsValue({
          nexusRepo: c?.nexusRepo ?? '',
          scriptTemplateRef: c?.scriptTemplateRef ?? '',
          versionRule: c?.versionRule ?? '',
          executor: c?.executor ?? 'LOCAL',
          remoteServerId: c?.remoteServerId,
        }),
      )
      .catch(() => {})
  }, [id, form])

  const onSave = async (v: ReleaseConfigInput) => {
    setBusy(true)
    try {
      await saveReleaseConfig(id, v)
      message.success('发版配置已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
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
          extra="server-adapter 白名单模板 code，渲染后执行推送"
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
              { value: 'REMOTE', label: 'REMOTE（远程服务器）' },
            ]}
          />
        </Form.Item>
        <Form.Item noStyle shouldUpdate={(a, b) => a.executor !== b.executor}>
          {({ getFieldValue }) =>
            getFieldValue('executor') === 'REMOTE' ? (
              <Form.Item label="远程服务器 id" name="remoteServerId">
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            ) : null
          }
        </Form.Item>
      </Form>
    </Card>
  )
}
