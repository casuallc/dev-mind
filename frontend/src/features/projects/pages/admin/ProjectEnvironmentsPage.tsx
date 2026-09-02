// 项目环境页（/admin/projects/:id/environments，P1-1）：环境聚合服务器 + 变量 + 密钥引用。
import { useCallback, useEffect, useState } from 'react'
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
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined } from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { addEnvironment, deleteEnvironment, listEnvironments, listServers, updateEnvironment } from '../../api'
import type { EnvironmentInput, ProjectEnvironment, ProjectServer } from '../../types'
import { envColor } from '../../components/utils'

const ENV_NAME_OPTIONS = ['DEV', 'TEST', 'STAGING', 'PROD']

/** 变量按 KEY=VALUE 逐行编辑，与 Record<string,string> 互转 */
function varsToText(vars: Record<string, string>): string {
  return Object.entries(vars ?? {}).map(([k, v]) => `${k}=${v}`).join('\n')
}

function textToVars(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of (text ?? '').split('\n')) {
    const t = line.trim()
    if (!t || t.startsWith('#')) continue
    const eq = t.indexOf('=')
    if (eq <= 0) continue
    out[t.slice(0, eq).trim()] = t.slice(eq + 1).trim()
  }
  return out
}

export default function ProjectEnvironmentsPage() {
  const { id = '' } = useParams<{ id: string }>()
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])
  const [servers, setServers] = useState<ProjectServer[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ProjectEnvironment | null>(null)
  const [form] = Form.useForm()

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const [envs, srvs] = await Promise.all([
        listEnvironments(id).catch(() => []),
        listServers(id).catch(() => []),
      ])
      setEnvironments(envs)
      setServers(srvs)
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    reload()
  }, [reload])

  const serverOptions = servers.map((sv) => ({ value: sv.id, label: `${sv.name}（#${sv.id}）` }))

  const openEdit = (e: ProjectEnvironment | null) => {
    setEditing(e)
    form.setFieldsValue(
      e
        ? { name: e.name, description: e.description, serverIds: e.serverIds, varsText: varsToText(e.variables), secrets: e.secrets }
        : { name: 'DEV', description: '', serverIds: [], varsText: '', secrets: [] },
    )
    setOpen(true)
  }

  const onSave = async (v: { name: string; description?: string; serverIds: number[]; varsText: string; secrets: string[] }) => {
    const input: EnvironmentInput = {
      name: v.name,
      description: v.description,
      serverIds: v.serverIds ?? [],
      variables: textToVars(v.varsText),
      secrets: v.secrets ?? [],
    }
    try {
      if (editing) {
        await updateEnvironment(id, editing.id, input)
      } else {
        await addEnvironment(id, input)
      }
      setOpen(false)
      await reload()
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const confirmDelete = (e: ProjectEnvironment) => {
    Modal.confirm({
      centered: true,
      title: '删除环境？',
      content: `将删除环境「${e.name}」（不影响其引用的服务器）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteEnvironment(id, e.id)
        await reload()
        message.success('已删除')
      },
    })
  }

  const serverName = (sid: number) => servers.find((sv) => sv.id === sid)?.name ?? `#${sid}`

  const columns: ColumnsType<ProjectEnvironment> = [
    { title: '环境', dataIndex: 'name', width: 110, render: (v: string) => <Tag color={envColor(v.toLowerCase() === 'dev' ? 'test' : v.toLowerCase())}>{v}</Tag> },
    {
      title: '服务器',
      dataIndex: 'serverIds',
      render: (ids: number[]) => ids?.length ? ids.map((sid) => <Tag key={sid}>{serverName(sid)}</Tag>) : '-',
    },
    {
      title: '变量',
      dataIndex: 'variables',
      width: 220,
      ellipsis: true,
      render: (vars: Record<string, string>) => {
        const n = Object.keys(vars ?? {}).length
        return n > 0 ? <span style={{ fontSize: 12 }}>{n} 个变量</span> : '-'
      },
    },
    {
      title: '密钥引用',
      dataIndex: 'secrets',
      width: 160,
      render: (sec: string[]) => sec?.length ? sec.map((x) => <Tag key={x} color="purple">{x}</Tag>) : '-',
    },
    { title: '描述', dataIndex: 'description', ellipsis: true, render: (d?: string) => d || '-' },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: unknown, r: ProjectEnvironment) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEdit(r)}>编辑</Button>
          <Button size="small" danger onClick={() => confirmDelete(r)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space>
        <Button size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
          添加环境
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          部署/测试目标从服务器升级为环境（DEV/TEST/STAGING/PROD），环境聚合服务器 + 变量 + 密钥引用
        </Typography.Text>
      </Space>
      <Table rowKey="id" size="small" columns={columns} dataSource={environments} loading={loading} pagination={false} />
      <Modal title={editing ? `编辑环境 ${editing.name}` : '添加环境'} open={open} onCancel={() => setOpen(false)}
        onOk={() => form.submit()} okText="保存" width={560}>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="名称" name="name" rules={[{ required: true }]}>
            <Select options={ENV_NAME_OPTIONS.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item label="服务器" name="serverIds" extra="从「服务器」页已登记的服务器中选择">
            <Select mode="multiple" options={serverOptions} placeholder="选择服务器" />
          </Form.Item>
          <Form.Item label="环境变量" name="varsText" extra="每行一条 KEY=VALUE，# 开头为注释">
            <Input.TextArea rows={4} placeholder="APP_PROFILE=dev（每行一条 KEY=VALUE）" />
          </Form.Item>
          <Form.Item label="密钥引用" name="secrets" extra="只存名称引用，密钥值由服务器凭证体系保管，永不落库">
            <Select mode="tags" placeholder="如 NEXUS_PASSWORD" open={false} suffixIcon={null} />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
