import { useEffect, useState } from 'react'
import {
  Button,
  Card,
  Form,
  Input,
  Modal,
  Space,
  Switch,
  Table,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  createTemplate,
  deleteTemplate,
  listTemplates,
  previewTemplate,
  updateTemplate,
} from '../api'
import type { SessionTemplate } from '../types'

const PREVIEW_VARS: Record<string, string> = {
  task: '新增登录页',
  project: 'playground',
  branch: 'feature/demo',
}

export default function SessionTemplates() {
  const [templates, setTemplates] = useState<SessionTemplate[]>([])
  const [loading, setLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<SessionTemplate | null>(null)
  const [preview, setPreview] = useState<{ open: boolean; text: string }>({ open: false, text: '' })
  const [form] = Form.useForm()

  const load = async () => {
    setLoading(true)
    try {
      setTemplates(await listTemplates())
    } catch (e) {
      message.error(`加载模板失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  const openEdit = (t: SessionTemplate | null) => {
    setEditing(t)
    form.setFieldsValue(
      t ?? { code: '', name: '', prompt: '任务：{{task}}\n项目：{{project}}', sortOrder: templates.length + 1, enabled: true },
    )
    setEditOpen(true)
  }

  const onSave = async (values: SessionTemplate) => {
    try {
      if (editing?.id) {
        await updateTemplate({ ...values, id: editing.id })
        message.success('已更新')
      } else {
        await createTemplate(values)
        message.success('已创建')
      }
      setEditOpen(false)
      load()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const confirmDelete = (t: SessionTemplate) => {
    Modal.confirm({
      centered: true,
      title: '删除该模板？',
      content: `Code: ${t.code}（${t.name}）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteTemplate(t.id!)
          message.success('已删除')
          load()
        } catch (e) {
          message.error(`删除失败：${(e as Error).message}`)
        }
      },
    })
  }

  const onPreview = async (code: string) => {
    try {
      const text = await previewTemplate(code, PREVIEW_VARS)
      setPreview({ open: true, text })
    } catch (e) {
      message.error(`预览失败：${(e as Error).message}`)
    }
  }

  const columns: ColumnsType<SessionTemplate> = [
    { title: 'Code', dataIndex: 'code', width: 160, render: (c: string) => <Typography.Text code>{c}</Typography.Text> },
    { title: '名称', dataIndex: 'name', width: 180 },
    {
      title: 'Prompt',
      dataIndex: 'prompt',
      ellipsis: true,
      render: (p: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{p}</span>,
    },
    {
      title: '排序',
      dataIndex: 'sortOrder',
      width: 70,
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 80,
      render: (v: boolean) => <Switch size="small" checked={v} disabled />,
    },
    {
      title: '操作',
      key: 'action',
      width: 180,
      render: (_, r) => (
        <Space>
          <Button size="small" onClick={() => openEdit(r)}>
            编辑
          </Button>
          <Button size="small" onClick={() => onPreview(r.code)}>
            预览
          </Button>
          <Button size="small" danger onClick={() => confirmDelete(r)}>
            删除
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <Card
      title="会话模板"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
            新建模板
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        模板以代码形式为会话提供任务骨架，支持 <Typography.Text code>{'{{task}}'}</Typography.Text>、{' '}
        <Typography.Text code>{'{{project}}'}</Typography.Text>、{' '}
        <Typography.Text code>{'{{branch}}'}</Typography.Text> 变量占位，新建会话时选择模板自动渲染。
      </Typography.Paragraph>
      <Table rowKey="id" loading={loading} columns={columns} dataSource={templates} pagination={false} />

      <Modal
        title={editing ? '编辑模板' : '新建模板'}
        open={editOpen}
        onCancel={() => setEditOpen(false)}
        onOk={() => form.submit()}
        okText="保存"
        width={640}
      >
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="Code" name="code" rules={[{ required: true, message: '请输入唯一 code' }]}>
            <Input placeholder="如 bugfix, feature, review" />
          </Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 缺陷修复" />
          </Form.Item>
          <Form.Item label="Prompt（{{变量}} 占位）" name="prompt" rules={[{ required: true, message: '请输入 prompt' }]}>
            <Input.TextArea rows={6} />
          </Form.Item>
          <Space size="large">
            <Form.Item label="排序" name="sortOrder">
              <Input type="number" style={{ width: 100 }} />
            </Form.Item>
            <Form.Item label="启用" name="enabled" valuePropName="checked">
              <Switch />
            </Form.Item>
          </Space>
        </Form>
      </Modal>

      <Modal
        title="渲染预览"
        open={preview.open}
        onCancel={() => setPreview({ open: false, text: '' })}
        footer={null}
        width={640}
      >
        <pre style={{ whiteSpace: 'pre-wrap', background: '#f6f6f6', padding: 12, borderRadius: 4 }}>{preview.text}</pre>
      </Modal>
    </Card>
  )
}
