// CAP-02 项目列表：表格 + 新建/编辑弹窗 + 居中确认删除。
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
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
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { createProject, deleteProject, listProjects, updateProject } from '../api'
import type { Project, ProjectInput } from '../types'

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: 'ACTIVE', color: 'green' },
  { value: 'ARCHIVED', label: 'ARCHIVED', color: 'default' },
]

export default function ProjectsPage() {
  const navigate = useNavigate()
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState('ALL')
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<Project | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  const load = useCallback(async (st?: string) => {
    setLoading(true)
    try {
      setProjects(await listProjects(st ?? status))
    } catch (e) {
      message.error(`加载项目失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [status])

  useEffect(() => {
    load()
  }, [load])

  const openEdit = (p: Project | null) => {
    setEditing(p)
    form.setFieldsValue(
      p ?? {
        name: '',
        path: '',
        defaultBranch: 'master',
        tags: [],
        description: '',
        status: 'ACTIVE',
        apiDocSource: '',
      },
    )
    setEditOpen(true)
  }

  const onSave = async (values: ProjectInput) => {
    setSaving(true)
    try {
      if (editing) {
        await updateProject(editing.id, values)
        message.success('已更新')
      } else {
        await createProject(values)
        message.success('已创建')
      }
      setEditOpen(false)
      load()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  const confirmDelete = (p: Project) => {
    Modal.confirm({
      centered: true,
      title: '删除项目？',
      content: `将删除项目「${p.name}」及其服务器/构建/发版/锁配置（不影响仓库本身）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteProject(p.id)
          message.success('已删除')
          load()
        } catch (e) {
          message.error(`删除失败：${(e as Error).message}`)
        }
      },
    })
  }

  const columns: ColumnsType<Project> = [
    {
      title: '名称',
      dataIndex: 'name',
      width: 180,
      render: (n: string, r) => (
        <Button type="link" style={{ padding: 0 }} onClick={() => navigate(`/projects/${r.id}`)}>
          {n}
        </Button>
      ),
    },
    { title: 'ID', dataIndex: 'id', width: 110, render: (id: string) => <Typography.Text code>{id}</Typography.Text> },
    {
      title: '仓库路径',
      dataIndex: 'path',
      ellipsis: true,
      render: (p: string) => <Typography.Text style={{ fontSize: 12 }}>{p}</Typography.Text>,
    },
    { title: '分支', dataIndex: 'defaultBranch', width: 110, render: (b?: string) => b || '-' },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 220,
      render: (tags: string[]) =>
        tags?.length ? tags.map((t) => <Tag key={t} style={{ marginBottom: 2 }}>{t}</Tag>) : '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: string) => {
        const o = STATUS_OPTIONS.find((x) => x.value === s)
        return <Tag color={o?.color ?? 'default'}>{s}</Tag>
      },
    },
    {
      title: '摘要',
      dataIndex: 'summaryGeneratedAt',
      width: 140,
      render: (t?: string) => (t ? new Date(t).toLocaleString() : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 170,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => navigate(`/projects/${r.id}`)}>
            管理
          </Button>
          <Button size="small" onClick={() => openEdit(r)}>
            编辑
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
      title="项目管理"
      extra={
        <Space>
          <Select
            value={status}
            onChange={(v) => {
              setStatus(v)
              load(v)
            }}
            options={[{ value: 'ALL', label: '全部' }, ...STATUS_OPTIONS.map((o) => ({ value: o.value, label: o.label }))]}
            style={{ width: 130 }}
          />
          <Button icon={<ReloadOutlined />} onClick={() => load()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
            新建项目
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
        注册本地 git 仓库作为项目，作为会话/构建/发版/测试能力的挂载点。worktree 约定在
        <Typography.Text code>path/.devmind/worktrees/&lt;sessionId&gt;</Typography.Text>。
      </Typography.Paragraph>
      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={projects}
        pagination={false}
        locale={{ emptyText: '暂无项目。点击「新建项目」注册一个本地 git 仓库。' }}
      />

      <Modal
        title={editing ? '编辑项目' : '新建项目'}
        open={editOpen}
        onCancel={() => setEditOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        okText="保存"
        width={560}
      >
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 在线商城后端" />
          </Form.Item>
          <Form.Item
            label="本地仓库路径"
            name="path"
            rules={[{ required: true, message: '请输入本地 git 仓库绝对路径' }]}
            extra="必须是本地 git 仓库（含 .git），如 D:/code/my-repo"
          >
            <Input placeholder="D:/code/my-repo" />
          </Form.Item>
          <Form.Item label="默认分支" name="defaultBranch">
            <Input placeholder="master / main" />
          </Form.Item>
          <Form.Item label="标签" name="tags" extra="如 java/spring/frontend，供知识库注入筛选">
            <Select mode="tags" placeholder="输入后回车添加" open={false} suffixIcon={null} />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={2} placeholder="项目职责、目标（可选）" />
          </Form.Item>
          <Form.Item label="状态" name="status">
            <Select
              options={STATUS_OPTIONS.map((o) => ({ value: o.value, label: o.label }))}
            />
          </Form.Item>
          <Form.Item label="API 文档源" name="apiDocSource" extra="OpenAPI 文件路径，供测试套件生成（可选）">
            <Input placeholder="如 docs/openapi.yaml" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
