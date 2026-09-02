// 项目仓库页（/admin/projects/:id/repos，P0-4 多库模型）：列表 + 添加/编辑弹窗 + 设主库/移除。
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Form,
  Input,
  InputNumber,
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
import { PlusOutlined } from '@ant-design/icons'
import { useOutletContext, useParams } from 'react-router-dom'
import { addRepo, deleteRepo, listRepos, setPrimaryRepo, updateRepo } from '../../api'
import type { ProjectRepo, ProjectRepoInput } from '../../types'

const ROLE_OPTIONS = ['CODE', 'DOCS', 'CONFIG']

export default function ReposPage() {
  const { id = '' } = useParams<{ id: string }>()
  // 主库切换会同步 projects.path 镜像，通知布局刷新头部展示
  const { reloadProject } = useOutletContext<{ reloadProject: () => void }>()
  const [repos, setRepos] = useState<ProjectRepo[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ProjectRepo | null>(null)
  const [form] = Form.useForm()

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      setRepos(await listRepos(id))
    } catch (e) {
      message.error(`加载仓库失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    reload()
  }, [reload])

  const openEdit = (r: ProjectRepo | null) => {
    setEditing(r)
    form.setFieldsValue(
      r ?? { name: '', path: '', remoteUrl: '', defaultBranch: '', role: 'CODE', primary: repos.length === 0, sortOrder: repos.length },
    )
    setOpen(true)
  }

  const onSave = async (v: ProjectRepoInput) => {
    try {
      if (editing) {
        await updateRepo(id, editing.id, v)
      } else {
        await addRepo(id, v)
      }
      setOpen(false)
      await reload()
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const makePrimary = (r: ProjectRepo) => {
    Modal.confirm({
      centered: true,
      title: '设为主库？',
      content: `「${r.name}」将成为项目主库（会话/构建/摘要默认使用的仓库）。`,
      okText: '设为主库',
      cancelText: '取消',
      onOk: async () => {
        try {
          await setPrimaryRepo(id, r.id)
          await reload()
          reloadProject()
          message.success('已切换主库')
        } catch (e) {
          message.error((e as Error).message)
        }
      },
    })
  }

  const confirmDelete = (r: ProjectRepo) => {
    Modal.confirm({
      centered: true,
      title: '移除仓库？',
      content: `将从项目移除仓库「${r.name}」（不删除磁盘上的仓库）。`,
      okText: '移除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteRepo(id, r.id)
          await reload()
          message.success('已移除')
        } catch (e) {
          message.error((e as Error).message)
        }
      },
    })
  }

  const columns: ColumnsType<ProjectRepo> = [
    {
      title: '名称',
      dataIndex: 'name',
      width: 160,
      render: (n: string, r) => (
        <Space size={4}>
          {n}
          {r.primary && <Tag color="gold">主库</Tag>}
        </Space>
      ),
    },
    { title: '角色', dataIndex: 'role', width: 90, render: (v: string) => <Tag color={v === 'CODE' ? 'blue' : v === 'DOCS' ? 'green' : 'purple'}>{v}</Tag> },
    { title: '路径', dataIndex: 'path', ellipsis: true, render: (p: string) => <Typography.Text code style={{ fontSize: 12 }}>{p}</Typography.Text> },
    { title: '默认分支', dataIndex: 'defaultBranch', width: 110, render: (v?: string) => v || '-' },
    { title: '远端', dataIndex: 'remoteUrl', width: 160, ellipsis: true, render: (v?: string) => v || '-' },
    { title: '排序', dataIndex: 'sortOrder', width: 60 },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_: unknown, r: ProjectRepo) => (
        <Space size={4}>
          {!r.primary && <Button size="small" onClick={() => makePrimary(r)}>设为主库</Button>}
          <Button size="small" onClick={() => openEdit(r)}>编辑</Button>
          {!r.primary && <Button size="small" danger onClick={() => confirmDelete(r)}>移除</Button>}
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space>
        <Button size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
          添加仓库
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          项目为多 git 库组合：代码/文档/配置各有角色；恰好一个主库（projects.path 为主库镜像）。
        </Typography.Text>
      </Space>
      <Table rowKey="id" size="small" columns={columns} dataSource={repos} loading={loading} pagination={false} />
      <Modal title={editing ? '编辑仓库' : '添加仓库'} open={open} onCancel={() => setOpen(false)}
        onOk={() => form.submit()} okText="保存" width={560}>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 后端服务 / 前端 / 文档库" />
          </Form.Item>
          <Form.Item label="本地仓库路径" name="path" rules={[{ required: true, message: '请输入 git 仓库路径' }]}>
            <Input placeholder="本地 git 仓库绝对路径" />
          </Form.Item>
          <Form.Item label="远端地址" name="remoteUrl">
            <Input placeholder="可选，仅记录" />
          </Form.Item>
          <Form.Item label="默认分支" name="defaultBranch">
            <Input placeholder="如 master / main" />
          </Form.Item>
          <Form.Item label="角色" name="role">
            <Select options={ROLE_OPTIONS.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item label="设为主库" name="primary" valuePropName="checked" extra="项目内唯一；会话/构建/摘要默认使用主库">
            <Switch disabled={editing?.primary} />
          </Form.Item>
          <Form.Item label="排序" name="sortOrder">
            <InputNumber min={0} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
