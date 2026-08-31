// 服务器 Tab：列表 + 添加/编辑弹窗 + 删除。
import { useState } from 'react'
import {
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined } from '@ant-design/icons'
import { addServer, deleteServer, listServers, updateServer } from '../../api'
import type { ProjectServer, ServerInput } from '../../types'
import { envColor } from './utils'

const ENV_OPTIONS = ['test', 'staging', 'prod']
const ACCESS_OPTIONS = ['ssh', 'http']

export default function ServersTab({ id, servers, onChanged }: {
  id: string
  servers: ProjectServer[]
  onChanged: (s: ProjectServer[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ProjectServer | null>(null)
  const [form] = Form.useForm()

  const openEdit = (s: ProjectServer | null) => {
    setEditing(s)
    form.setFieldsValue(
      s ?? { name: '', env: 'test', accessType: 'ssh', accessConfig: '', capabilities: [], enabled: true },
    )
    setOpen(true)
  }

  const onSave = async (v: ServerInput) => {
    try {
      if (editing) {
        await updateServer(id, editing.id, v)
      } else {
        await addServer(id, v)
      }
      setOpen(false)
      onChanged(await listServers(id))
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const confirmDelete = (s: ProjectServer) => {
    Modal.confirm({
      centered: true,
      title: '删除服务器？',
      content: `将从项目移除服务器「${s.name}」（不影响服务器本身）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteServer(id, s.id)
        onChanged(await listServers(id))
        message.success('已删除')
      },
    })
  }

  const columns: ColumnsType<ProjectServer> = [
    { title: '名称', dataIndex: 'name', width: 160 },
    { title: '环境', dataIndex: 'env', width: 100, render: (v?: string) => v ? <Tag color={envColor(v)}>{v}</Tag> : '-' },
    { title: '接入', dataIndex: 'accessType', width: 90 },
    {
      title: '配置',
      dataIndex: 'accessConfig',
      ellipsis: true,
      render: (c?: string) => <span style={{ fontSize: 12 }}>{c || '-'}</span>,
    },
    {
      title: '能力',
      dataIndex: 'capabilities',
      width: 180,
      render: (c: string[]) => c?.length ? c.map((x) => <Tag key={x} color="blue">{x}</Tag>) : '-',
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 80,
      render: (v: boolean) => (v ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>),
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEdit(r)}>编辑</Button>
          <Button size="small" danger onClick={() => confirmDelete(r)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Button size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
        添加服务器
      </Button>
      <Table rowKey="id" size="small" columns={columns} dataSource={servers} pagination={false} />
      <Modal title={editing ? '编辑服务器' : '添加服务器'} open={open} onCancel={() => setOpen(false)}
        onOk={() => form.submit()} okText="保存" width={560}>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="名称" name="name" rules={[{ required: true }]}>
            <Input placeholder="如 生产环境网关" />
          </Form.Item>
          <Form.Item label="环境" name="env">
            <Select options={ENV_OPTIONS.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item label="接入类型" name="accessType" rules={[{ required: true }]}>
            <Select options={ACCESS_OPTIONS.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item label="连接配置" name="accessConfig" extra="JSON：主机/用户/端口/密钥路径，或 base-url/token（接入 CAP-07 前存明文）">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item label="能力" name="capabilities">
            <Select mode="tags" placeholder="build / deploy / test / release" open={false} suffixIcon={null} />
          </Form.Item>
          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
