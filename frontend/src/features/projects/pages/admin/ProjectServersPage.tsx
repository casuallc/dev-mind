// 项目服务器页（/admin/projects/:id/servers）：列表 + 添加/编辑抽屉 + 删除。
// 连接配置为结构化表单（随接入类型切换），支持粘贴一段话智能识别填入；提交时序列化为 accessConfig JSON。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { addServer, deleteServer, listServers, updateServer } from '../../api'
import type { ProjectServer, ServerInput } from '../../types'
import { envColor } from '../../components/utils'
import {
  buildAccessConfig,
  parseAccessConfig,
  ServerAccessFormItems,
  smartParseAccess,
  summarizeAccessConfig,
} from '../../components/ServerAccessFields'

const ENV_OPTIONS = ['test', 'staging', 'prod']
const ACCESS_OPTIONS = ['ssh', 'http']

export default function ProjectServersPage() {
  const { id = '' } = useParams<{ id: string }>()
  const [servers, setServers] = useState<ProjectServer[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ProjectServer | null>(null)
  const [smartText, setSmartText] = useState('')
  const [form] = Form.useForm()
  const accessType: string = Form.useWatch('accessType', form) ?? 'ssh'
  const authType: string = Form.useWatch('authType', form) ?? 'password'
  /** 编辑时原 JSON 里未识别的扩展键，保存时原样带回 */
  const extrasRef = useRef<Record<string, unknown>>({})

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      setServers(await listServers(id))
    } catch (e) {
      message.error(`加载服务器失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    reload()
  }, [reload])

  const openEdit = (s: ProjectServer | null) => {
    setEditing(s)
    setSmartText('')
    if (s) {
      const { values, extras } = parseAccessConfig(s.accessConfig)
      extrasRef.current = extras
      form.setFieldsValue({ ...s, authType: 'password', port: 22, ...values })
    } else {
      extrasRef.current = {}
      form.setFieldsValue({
        name: '', env: 'test', accessType: 'ssh', capabilities: [], enabled: true,
        host: '', port: 22, username: '', authType: 'password',
        password: '', privateKey: '', passphrase: '', baseUrl: '', token: '', timeoutMs: undefined,
      })
    }
    setOpen(true)
  }

  /** 智能识别：解析一段话填入表单（不覆盖名称/环境等业务字段） */
  const applySmartParse = () => {
    if (!smartText.trim()) {
      message.warning('请先粘贴一段连接信息')
      return
    }
    const r = smartParseAccess(smartText)
    if (!r.hits.length) {
      message.warning('未识别到有效连接信息，试试「ssh 用户@主机:端口 密码 xxx」或「https://… token: xxx」')
      return
    }
    if (r.accessType) form.setFieldValue('accessType', r.accessType)
    form.setFieldsValue(r.values)
    message.success(`已识别填入：${r.hits.join('，')}`)
  }

  const onSave = async (v: Record<string, unknown>) => {
    const input: ServerInput = {
      name: v.name as string,
      env: v.env as string | undefined,
      accessType: v.accessType as string,
      accessConfig: buildAccessConfig(v.accessType as string, v, extrasRef.current),
      capabilities: (v.capabilities as string[]) ?? [],
      enabled: v.enabled !== false,
    }
    try {
      if (editing) {
        await updateServer(id, editing.id, input)
      } else {
        await addServer(id, input)
      }
      setOpen(false)
      await reload()
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
        await reload()
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
      render: (c: string | undefined, r: ProjectServer) => (
        <span style={{ fontSize: 12 }}>{c ? summarizeAccessConfig(r.accessType, c) : '-'}</span>
      ),
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
      render: (_: unknown, r: ProjectServer) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEdit(r)}>编辑</Button>
          <Button size="small" danger onClick={() => confirmDelete(r)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Card
      title="服务器"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
            添加服务器
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        登记项目可用的目标服务器（SSH/HTTP 接入），按环境/能力标注，供构建、部署、测试等执行器挑选。
      </Typography.Paragraph>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={servers}
        loading={loading}
        pagination={false}
        locale={{ emptyText: '暂无服务器。点击「添加服务器」登记第一台。' }}
      />
      <Drawer title={editing ? '编辑服务器' : '添加服务器'} open={open} onClose={() => setOpen(false)}
        width={600}
        footer={
          <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button onClick={() => setOpen(false)}>取消</Button>
            <Button type="primary" onClick={() => form.submit()}>保存</Button>
          </Space>
        }>
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
          <Card
            size="small"
            title="连接配置"
            style={{ marginBottom: 16, background: '#fafafa' }}
            styles={{ header: { minHeight: 36, padding: '0 12px' }, body: { padding: 12 } }}
          >
            <Input.TextArea
              rows={2}
              value={smartText}
              onChange={(e) => setSmartText(e.target.value)}
              placeholder={'粘贴一段话自动识别填入，如：\nssh root@172.20.140.156:22 密码 xxx，或 https://ci.example.com token: xxx'}
              style={{ resize: 'none', marginBottom: 8 }}
            />
            <Button size="small" onClick={applySmartParse} style={{ marginBottom: 16 }}>
              识别填入
            </Button>
            <ServerAccessFormItems accessType={accessType} authType={authType} />
          </Card>
          <Form.Item label="能力" name="capabilities">
            <Select mode="tags" placeholder="build / deploy / test / release" open={false} suffixIcon={null} />
          </Form.Item>
          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Drawer>
    </Card>
  )
}
