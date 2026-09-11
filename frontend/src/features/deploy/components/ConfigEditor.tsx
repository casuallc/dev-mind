// 部署计划配置编辑器：部署步骤 + 回滚步骤两组有序步骤列表。
import {
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined } from '@ant-design/icons'
import type { DeployConfig, DeployStepInput } from '../types'
import type { TemplateView } from '../../execution/types'
import { listTemplates } from '../../execution/api'
import { paramsToText, textToParams } from '../../../shared/utils/format'

const STEP_TYPES = ['artifact', 'backup', 'deploy', 'start', 'health']

export default function ConfigEditor({ cfg, onChanged }: { cfg: DeployConfig; onChanged: (c: DeployConfig) => void }) {
  // CAP-36 命令模板白名单：步骤的 templateCode 只能从这里选，不允许手填
  const [templates, setTemplates] = useState<TemplateView[]>([])
  useEffect(() => {
    listTemplates(cfg.projectId).then(setTemplates).catch(() => setTemplates([]))
  }, [cfg.projectId])

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      <StepListEditor
        title="部署步骤（拉取产物 → 备份 → 部署 → 启动 → 健康检查）"
        steps={cfg.steps}
        templates={templates}
        onChange={(steps) => onChanged({ ...cfg, steps })}
      />
      <StepListEditor
        title="回滚步骤（失败后按此恢复，${backup} 为备份引用）"
        steps={cfg.rollbackSteps}
        templates={templates}
        onChange={(rollbackSteps) => onChanged({ ...cfg, rollbackSteps })}
      />
    </Space>
  )
}

function StepListEditor({ title, steps, templates, onChange }: {
  title: string
  steps: DeployStepInput[]
  templates: TemplateView[]
  onChange: (s: DeployStepInput[]) => void
}) {
  const [editing, setEditing] = useState<DeployStepInput | null>(null)
  // 新增时 editing 为 null，Modal 开关不能绑在 editing 上，需要独立的 open 状态
  const [modalOpen, setModalOpen] = useState(false)
  const [isNew, setIsNew] = useState(false)
  const [form] = Form.useForm()

  const openEdit = (s: DeployStepInput | null) => {
    setIsNew(!s)
    setEditing(s)
    setModalOpen(true)
    form.setFieldsValue(
      s
        ? { ...s, paramsText: paramsToText(s.params) }
        : { name: '', type: 'deploy', templateCode: '', paramsText: '' },
    )
  }

  const closeModal = () => {
    setModalOpen(false)
    setEditing(null)
  }

  // 选中模板后顺带带出名称（未填时）与参数骨架（默认值/必填项），减少手抄
  const onTemplateSelect = (code: string) => {
    const tpl = templates.find((t) => t.code === code)
    if (!tpl) return
    const patch: Record<string, string> = {}
    if (!form.getFieldValue('name')) patch.name = tpl.name
    patch.paramsText = tpl.params.map((p) => `${p.name}=${p.defaultValue ?? ''}`).join('\n')
    form.setFieldsValue(patch)
  }

  const save = async (v: { name: string; type: string; templateCode: string; paramsText: string }) => {
    const step: DeployStepInput = { name: v.name, type: v.type, templateCode: v.templateCode, params: textToParams(v.paramsText) }
    if (editing) {
      onChange(steps.map((s) => (s === editing ? step : s)))
    } else {
      onChange([...steps, step])
    }
    closeModal()
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
          <Popconfirm
            title={`删除步骤「${r.name || r.templateCode}」？`}
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => onChange(steps.filter((s) => s !== r))}
          >
            <Button size="small" danger>删除</Button>
          </Popconfirm>
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
      <Modal title={isNew ? '添加步骤' : '编辑步骤'} open={modalOpen} onCancel={closeModal}
        onOk={() => form.submit()} okText="保存" width={520} destroyOnHidden>
        <Form form={form} layout="vertical" onFinish={save}>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入步骤名' }]}>
            <Input placeholder="如 启动服务" />
          </Form.Item>
          <Form.Item label="类型" name="type" rules={[{ required: true }]}>
            <Select options={STEP_TYPES.map((t) => ({ value: t, label: t }))} />
          </Form.Item>
          <Form.Item label="模板（CAP-07 白名单）" name="templateCode" rules={[{ required: true, message: '请选择命令模板' }]}
            extra={templates.length === 0 ? '当前项目暂无命令模板，请到「系统管理 → 模板与审计」先创建' : undefined}>
            <Select
              showSearch
              placeholder="选择命令模板"
              optionFilterProp="label"
              onChange={onTemplateSelect}
              options={templates.map((t) => ({ value: t.code, label: `${t.code}（${t.name}）` }))}
            />
          </Form.Item>
          <Form.Item label="参数（每行 key=value，可引用 ${artifact} ${backup} ${env}）" name="paramsText">
            <Input.TextArea rows={3} placeholder="port=8080" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
