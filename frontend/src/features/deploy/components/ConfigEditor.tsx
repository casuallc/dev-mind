// 部署计划配置编辑器：部署步骤 + 回滚步骤两组有序步骤列表。
import {
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import { useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined } from '@ant-design/icons'
import type { DeployConfig, DeployStepInput } from '../types'
import { paramsToText, textToParams } from '../../../shared/utils/format'

const STEP_TYPES = ['artifact', 'backup', 'deploy', 'start', 'health']

export default function ConfigEditor({ cfg, onChanged }: { cfg: DeployConfig; onChanged: (c: DeployConfig) => void }) {
  return (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      <StepListEditor
        title="部署步骤（拉取产物 → 备份 → 部署 → 启动 → 健康检查）"
        steps={cfg.steps}
        onChange={(steps) => onChanged({ ...cfg, steps })}
      />
      <StepListEditor
        title="回滚步骤（失败后按此恢复，${backup} 为备份引用）"
        steps={cfg.rollbackSteps}
        onChange={(rollbackSteps) => onChanged({ ...cfg, rollbackSteps })}
      />
    </Space>
  )
}

function StepListEditor({ title, steps, onChange }: {
  title: string
  steps: DeployStepInput[]
  onChange: (s: DeployStepInput[]) => void
}) {
  const [editing, setEditing] = useState<DeployStepInput | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [form] = Form.useForm()

  const openEdit = (s: DeployStepInput | null) => {
    setIsNew(!s)
    setEditing(s)
    form.setFieldsValue(
      s
        ? { ...s, paramsText: paramsToText(s.params) }
        : { name: '', type: 'deploy', templateCode: '', paramsText: '' },
    )
  }

  const save = async (v: { name: string; type: string; templateCode: string; paramsText: string }) => {
    const step: DeployStepInput = { name: v.name, type: v.type, templateCode: v.templateCode, params: textToParams(v.paramsText) }
    if (editing) {
      onChange(steps.map((s) => (s === editing ? step : s)))
    } else {
      onChange([...steps, step])
    }
    setEditing(null)
  }

  const columns: ColumnsType<DeployStepInput> = [
    { title: '名称', dataIndex: 'name', width: 140, render: (n: string) => n || '-' },
    { title: '类型', dataIndex: 'type', width: 100, render: (t: string) => <Tag color="geekblue">{t}</Tag> },
    { title: '模板 code', dataIndex: 'templateCode', width: 160, render: (c: string) => <Typography.Text code>{c}</Typography.Text> },
    {
      title: '参数',
      dataIndex: 'params',
      ellipsis: true,
      render: (p: Record<string, string>) => paramsToText(p) || '-',
    },
    {
      title: '',
      key: 'act',
      width: 130,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEdit(r)}>编辑</Button>
          <Button size="small" danger onClick={() => onChange(steps.filter((s) => s !== r))}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }}>
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>{title}</Typography.Text>
        <Button size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>添加步骤</Button>
      </Space>
      <Table<DeployStepInput> rowKey={(r) => r.name + r.type + r.templateCode} size="small" columns={columns} dataSource={steps} pagination={false} />
      <Modal title={isNew ? '添加步骤' : '编辑步骤'} open={!!editing} onCancel={() => setEditing(null)}
        onOk={() => form.submit()} okText="保存" width={520} destroyOnClose>
        <Form form={form} layout="vertical" onFinish={save}>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入步骤名' }]}>
            <Input placeholder="如 启动服务" />
          </Form.Item>
          <Form.Item label="类型" name="type" rules={[{ required: true }]}>
            <Select options={STEP_TYPES.map((t) => ({ value: t, label: t }))} />
          </Form.Item>
          <Form.Item label="模板 code（CAP-07 白名单）" name="templateCode" rules={[{ required: true, message: '请输入模板 code' }]}>
            <Input placeholder="如 dep_start" />
          </Form.Item>
          <Form.Item label="参数（每行 key=value，可引用 ${artifact} ${backup} ${env}）" name="paramsText">
            <Input.TextArea rows={3} placeholder="port=8080" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
