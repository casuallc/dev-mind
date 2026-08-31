// CAP-02 项目详情：Tabs（基本信息/服务器/构建配置/发版配置/上下文摘要/Worktree/锁定）。
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  ArrowDownOutlined,
  ArrowUpOutlined,
  DiffOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import BuildCenterTab from '../../build/BuildTab'
import DeployTab from '../../deploy/DeployTab'
import {
  addBuildStep,
  addRepo,
  addServer,
  claimWrite,
  createRequirement,
  deleteBuildStep,
  deleteRepo,
  deleteRequirement,
  deleteServer,
  getLock,
  getProject,
  getReleaseConfig,
  getSummary,
  listBuildSteps,
  listRepos,
  listRequirements,
  listServers,
  listWorktrees,
  reorderBuildSteps,
  refreshSummary,
  releaseWrite,
  saveReleaseConfig,
  saveSummary,
  setPrimaryRepo,
  updateBuildStep,
  updateLock,
  updateProject,
  updateRepo,
  updateRequirement,
  updateRequirementStatus,
  updateServer,
} from '../api'
import type {
  BuildStep,
  BuildStepInput,
  ContextSummary,
  Project,
  ProjectInput,
  ProjectLock,
  ProjectRepo,
  ProjectRepoInput,
  ProjectServer,
  ReleaseConfig,
  Requirement,
  RequirementInput,
  RequirementStatus,
  ServerInput,
  WorktreeInfo,
} from '../types'

const ENV_OPTIONS = ['test', 'staging', 'prod']
const ACCESS_OPTIONS = ['ssh', 'http']
const LOCATION_OPTIONS = ['LOCAL', 'REMOTE']
const ROLE_OPTIONS = ['CODE', 'DOCS', 'CONFIG']
const REQ_STATUS_OPTIONS: RequirementStatus[] = [
  'DRAFT', 'DESIGNING', 'DEVELOPING', 'TESTING', 'ACCEPTANCE', 'DONE', 'CANCELLED',
]

export default function ProjectDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [project, setProject] = useState<Project | null>(null)
  const [loading, setLoading] = useState(true)
  const [servers, setServers] = useState<ProjectServer[]>([])
  const [repos, setRepos] = useState<ProjectRepo[]>([])
  const [requirements, setRequirements] = useState<Requirement[]>([])
  const [steps, setSteps] = useState<BuildStep[]>([])
  const [release, setRelease] = useState<ReleaseConfig | null>(null)
  const [summary, setSummary] = useState<ContextSummary>({ projectId: id ?? '', summary: '' })
  const [worktrees, setWorktrees] = useState<WorktreeInfo[]>([])
  const [lock, setLock] = useState<ProjectLock | null>(null)
  const [editOpen, setEditOpen] = useState(false)
  const [form] = Form.useForm()

  const loadAll = useCallback(async () => {
    if (!id) return
    try {
      const [p, rp, rq, s, b, r, sm, wt, lk] = await Promise.all([
        getProject(id),
        listRepos(id).catch(() => []),
        listRequirements(id).catch(() => []),
        listServers(id),
        listBuildSteps(id),
        getReleaseConfig(id).catch(() => null),
        getSummary(id).catch(() => ({ projectId: id, summary: '' })),
        listWorktrees(id).catch(() => []),
        getLock(id).catch(() => null),
      ])
      setProject(p)
      setRepos(rp)
      setRequirements(rq)
      setServers(s)
      setSteps(b)
      setRelease(r)
      setSummary(sm)
      setWorktrees(wt)
      setLock(lk)
    } catch (e) {
      message.error(`加载项目失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    setLoading(true)
    loadAll()
  }, [loadAll])

  if (loading) {
    return <Card><Spin /></Card>
  }
  if (!project) {
    return <Card><Empty description="项目不存在" /></Card>
  }

  const openEdit = () => {
    form.setFieldsValue({
      name: project.name,
      path: project.path,
      defaultBranch: project.defaultBranch,
      tags: project.tags,
      description: project.description,
      status: project.status,
      apiDocSource: project.apiDocSource,
    })
    setEditOpen(true)
  }

  const onSaveEdit = async (values: ProjectInput) => {
    try {
      setProject(await updateProject(project.id, values))
      setEditOpen(false)
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card
        size="small"
        title={
          <Space>
            <Typography.Text strong>{project.name}</Typography.Text>
            <Typography.Text code>{project.id}</Typography.Text>
            <Tag color={project.status === 'ACTIVE' ? 'green' : 'default'}>{project.status}</Tag>
          </Space>
        }
        extra={
          <Space>
            <Button size="small" icon={<EditOutlined />} onClick={openEdit}>
              编辑
            </Button>
            <Button size="small" icon={<ReloadOutlined />} onClick={loadAll}>
              刷新
            </Button>
            <Button size="small" onClick={() => navigate('/projects')}>
              返回列表
            </Button>
          </Space>
        }
      >
        <Descriptions size="small" column={{ xs: 1, sm: 2 }}>
          <Descriptions.Item label="仓库路径">
            <Typography.Text code copyable style={{ fontSize: 12 }}>
              {project.path}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="默认分支">{project.defaultBranch || '-'}</Descriptions.Item>
          <Descriptions.Item label="标签">
            {project.tags?.length ? project.tags.map((t) => <Tag key={t}>{t}</Tag>) : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="API 文档源">{project.apiDocSource || '-'}</Descriptions.Item>
          <Descriptions.Item label="创建">{new Date(project.createdAt).toLocaleString()}</Descriptions.Item>
          <Descriptions.Item label="更新">{new Date(project.updatedAt).toLocaleString()}</Descriptions.Item>
          {project.description && (
            <Descriptions.Item label="描述" span={2}>
              {project.description}
            </Descriptions.Item>
          )}
        </Descriptions>
      </Card>

      <Card size="small">
        <Tabs
          items={[
            {
              key: 'requirements',
              label: '需求',
              children: (
                <RequirementsTab id={project.id} requirements={requirements} onChanged={setRequirements} />
              ),
            },
            {
              key: 'repos',
              label: '仓库',
              children: (
                <ReposTab
                  id={project.id}
                  repos={repos}
                  onChanged={(rs) => {
                    setRepos(rs)
                    // 主库切换会同步 projects.path 镜像，刷新头部展示
                    getProject(project.id).then(setProject).catch(() => undefined)
                  }}
                />
              ),
            },
            {
              key: 'summary',
              label: '上下文摘要',
              children: <SummaryTab id={project.id} summary={summary} onChanged={setSummary} />,
            },
            {
              key: 'servers',
              label: '服务器',
              children: <ServersTab id={project.id} servers={servers} onChanged={setServers} />,
            },
            {
              key: 'build',
              label: '构建配置',
              children: <BuildTab id={project.id} steps={steps} onChanged={setSteps} />,
            },
            {
              key: 'builds',
              label: '构建',
              children: <BuildCenterTab id={project.id} />,
            },
            {
              key: 'deploy',
              label: '部署',
              children: <DeployTab id={project.id} />,
            },
            {
              key: 'release',
              label: '发版配置',
              children: <ReleaseTab id={project.id} release={release} onChanged={setRelease} />,
            },
            {
              key: 'worktrees',
              label: 'Worktree',
              children: <WorktreesTab worktrees={worktrees} onRefresh={() => listWorktrees(project.id).then(setWorktrees)} />,
            },
            {
              key: 'lock',
              label: '锁定',
              children: <LockTab id={project.id} lock={lock} onChanged={setLock} />,
            },
          ]}
        />
      </Card>

      {/* 编辑项目 */}
      <Modal
        title="编辑项目"
        open={editOpen}
        onCancel={() => setEditOpen(false)}
        onOk={() => form.submit()}
        okText="保存"
        width={560}
      >
        <Form form={form} layout="vertical" onFinish={onSaveEdit}>
          <Form.Item label="名称" name="name" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item label="本地仓库路径" name="path" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item label="默认分支" name="defaultBranch">
            <Input />
          </Form.Item>
          <Form.Item label="标签" name="tags">
            <Select mode="tags" open={false} suffixIcon={null} />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item label="状态" name="status">
            <Select options={[{ value: 'ACTIVE', label: 'ACTIVE' }, { value: 'ARCHIVED', label: 'ARCHIVED' }]} />
          </Form.Item>
          <Form.Item label="API 文档源" name="apiDocSource">
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}

// ---------------- 需求（P0-5 项目内主线） ----------------

function RequirementsTab({ id, requirements, onChanged }: {
  id: string
  requirements: Requirement[]
  onChanged: (r: Requirement[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Requirement | null>(null)
  const [statusFilter, setStatusFilter] = useState<string>('ALL')
  const [form] = Form.useForm()

  const openEdit = (r: Requirement | null) => {
    setEditing(r)
    form.setFieldsValue(r ?? { title: '', description: '', ownerId: '', branchSlug: '' })
    setOpen(true)
  }

  const reload = async () => onChanged(await listRequirements(id))

  const onSave = async (v: RequirementInput) => {
    try {
      if (editing) {
        await updateRequirement(id, editing.id, v)
      } else {
        await createRequirement(id, v)
      }
      setOpen(false)
      await reload()
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const advance = async (r: Requirement, status: RequirementStatus) => {
    try {
      await updateRequirementStatus(id, r.id, status)
      await reload()
      message.success(`${r.code} → ${status}`)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const confirmDelete = (r: Requirement) => {
    Modal.confirm({
      centered: true,
      title: '删除需求？',
      content: `将删除需求「${r.code} ${r.title}」（关联的文档/构建/部署记录保留，仅解除主线）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteRequirement(id, r.id)
        await reload()
        message.success('已删除')
      },
    })
  }

  const filtered = statusFilter === 'ALL' ? requirements : requirements.filter((r) => r.status === statusFilter)

  const columns: ColumnsType<Requirement> = [
    { title: '编号', dataIndex: 'code', width: 90, render: (c: string) => <Typography.Text code>{c}</Typography.Text> },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 130,
      render: (s: RequirementStatus) => <Tag color={reqStatusColor(s)}>{s}</Tag>,
    },
    { title: '负责人', dataIndex: 'ownerId', width: 100, render: (v?: string) => v || '-' },
    {
      title: '需求分支',
      dataIndex: 'branchSlug',
      width: 180,
      ellipsis: true,
      render: (slug: string, r) => <Typography.Text code style={{ fontSize: 12 }}>req/{r.seq}{slug ? `-${slug}` : ''}</Typography.Text>,
    },
    {
      title: '更新',
      dataIndex: 'updatedAt',
      width: 150,
      render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span>,
    },
    {
      title: '操作',
      key: 'action',
      width: 240,
      render: (_, r) => (
        <Space size={4}>
          <Select
            size="small"
            value={r.status}
            style={{ width: 118 }}
            options={REQ_STATUS_OPTIONS.map((s) => ({ value: s, label: s }))}
            onChange={(s) => advance(r, s)}
          />
          <Button size="small" onClick={() => openEdit(r)}>编辑</Button>
          <Button size="small" danger onClick={() => confirmDelete(r)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space>
        <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
          新建需求
        </Button>
        <Select
          size="small"
          value={statusFilter}
          style={{ width: 130 }}
          options={[{ value: 'ALL', label: '全部状态' }, ...REQ_STATUS_OPTIONS.map((s) => ({ value: s, label: s }))]}
          onChange={setStatusFilter}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          需求为项目内主线：文档/会话/构建/测试/部署按需求关联，状态人工或 API 推进。
        </Typography.Text>
      </Space>
      <Table rowKey="id" size="small" columns={columns} dataSource={filtered} pagination={false}
        locale={{ emptyText: '暂无需求，点击「新建需求」开始一条主线' }} />
      <Modal title={editing ? `编辑需求 ${editing.code}` : '新建需求'} open={open} onCancel={() => setOpen(false)}
        onOk={() => form.submit()} okText="保存" width={560}>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="标题" name="title" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="如 用户登录支持扫码" />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item label="负责人" name="ownerId">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item label="分支 slug" name="branchSlug" extra="需求分支 req/<seq>-<slug>，缺省由标题生成">
            <Input placeholder="如 login-qrcode" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}

function reqStatusColor(s: RequirementStatus): string {
  switch (s) {
    case 'DRAFT': return 'default'
    case 'DESIGNING': return 'cyan'
    case 'DEVELOPING': return 'blue'
    case 'TESTING': return 'orange'
    case 'ACCEPTANCE': return 'purple'
    case 'DONE': return 'green'
    case 'CANCELLED': return 'red'
  }
}

// ---------------- 项目仓库（P0-4 多库模型） ----------------

function ReposTab({ id, repos, onChanged }: {
  id: string
  repos: ProjectRepo[]
  onChanged: (r: ProjectRepo[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ProjectRepo | null>(null)
  const [form] = Form.useForm()

  const openEdit = (r: ProjectRepo | null) => {
    setEditing(r)
    form.setFieldsValue(
      r ?? { name: '', path: '', remoteUrl: '', defaultBranch: '', role: 'CODE', primary: repos.length === 0, sortOrder: repos.length },
    )
    setOpen(true)
  }

  const reload = async () => onChanged(await listRepos(id))

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
      render: (_, r) => (
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
      <Table rowKey="id" size="small" columns={columns} dataSource={repos} pagination={false} />
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

// ---------------- 上下文摘要 ----------------

function SummaryTab({ id, summary, onChanged }: {
  id: string
  summary: ContextSummary
  onChanged: (s: ContextSummary) => void
}) {
  const [text, setText] = useState(summary.summary)
  const [busy, setBusy] = useState(false)

  useEffect(() => setText(summary.summary), [summary.summary])

  const doRefresh = async () => {
    setBusy(true)
    try {
      const s = await refreshSummary(id)
      onChanged(s)
      message.success('已重新扫描生成摘要')
    } catch (e) {
      message.error(`生成失败：${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  const doSave = async () => {
    setBusy(true)
    try {
      const s = await saveSummary(id, text)
      onChanged(s)
      message.success('已保存（人工修正）')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space>
        <Button size="small" type="primary" icon={<ReloadOutlined />} loading={busy} onClick={doRefresh}>
          重新扫描生成
        </Button>
        <Button size="small" type="primary" ghost icon={<DiffOutlined />} loading={busy} onClick={doSave}>
          保存修改
        </Button>
        {summary.generatedAt && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            生成于 {new Date(summary.generatedAt).toLocaleString()}
          </Typography.Text>
        )}
      </Space>
      <Input.TextArea
        rows={18}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="点击「重新扫描生成」自动扫描仓库结构；也可直接编辑此摘要作为项目上下文（供需求对话/方案/会话注入）。"
        style={{ fontFamily: 'monospace', fontSize: 12 }}
      />
    </Space>
  )
}

// ---------------- 服务器 ----------------

function ServersTab({ id, servers, onChanged }: {
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

function envColor(env: string): string {
  return env === 'prod' ? 'red' : env === 'staging' ? 'orange' : 'blue'
}

// ---------------- 构建配置 ----------------

function BuildTab({ id, steps, onChanged }: {
  id: string
  steps: BuildStep[]
  onChanged: (s: BuildStep[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<BuildStep | null>(null)
  const [form] = Form.useForm()

  const openEdit = (s: BuildStep | null) => {
    setEditing(s)
    form.setFieldsValue(
      s ?? { name: '', command: '', workingDir: '', location: 'LOCAL', sortOrder: steps.length },
    )
    setOpen(true)
  }

  const onSave = async (v: BuildStepInput) => {
    try {
      if (editing) {
        await updateBuildStep(id, editing.id, v)
      } else {
        await addBuildStep(id, v)
      }
      setOpen(false)
      onChanged(await listBuildSteps(id))
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const move = async (index: number, dir: -1 | 1) => {
    const next = [...steps]
    const target = index + dir
    if (target < 0 || target >= next.length) return
    const [it] = next.splice(index, 1)
    next.splice(target, 0, it)
    const ordered = next.map((s, i) => ({ ...s, sortOrder: i }))
    onChanged(await reorderBuildSteps(id, ordered))
  }

  const confirmDelete = (s: BuildStep) => {
    Modal.confirm({
      centered: true,
      title: '删除构建步骤？',
      content: `「${s.command}」`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteBuildStep(id, s.id)
        onChanged(await listBuildSteps(id))
        message.success('已删除')
      },
    })
  }

  const columns: ColumnsType<BuildStep> = [
    { title: '顺序', dataIndex: 'sortOrder', width: 70 },
    { title: '名称', dataIndex: 'name', width: 140, render: (n?: string) => n || '-' },
    { title: '命令', dataIndex: 'command', render: (c: string) => <code style={{ fontSize: 12 }}>{c}</code> },
    { title: '目录', dataIndex: 'workingDir', width: 120, render: (d?: string) => d || '-' },
    { title: '位置', dataIndex: 'location', width: 90, render: (l: string) => <Tag color={l === 'REMOTE' ? 'purple' : 'default'}>{l}</Tag> },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_, r, idx) => (
        <Space size={4}>
          <Button size="small" icon={<ArrowUpOutlined />} disabled={idx === 0} onClick={() => move(idx, -1)} />
          <Button size="small" icon={<ArrowDownOutlined />} disabled={idx === steps.length - 1} onClick={() => move(idx, 1)} />
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
          添加步骤
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          有序构建步骤，按顺序执行；位置可选本机（LOCAL）或远程服务器（REMOTE，委托 CAP-08）。
        </Typography.Text>
      </Space>
      <Table rowKey="id" size="small" columns={columns} dataSource={steps} pagination={false} />
      <Modal title={editing ? '编辑构建步骤' : '添加构建步骤'} open={open} onCancel={() => setOpen(false)}
        onOk={() => form.submit()} okText="保存" width={560}>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="名称" name="name">
            <Input placeholder="如 编译打包" />
          </Form.Item>
          <Form.Item label="命令" name="command" rules={[{ required: true, message: '请输入命令' }]}>
            <Input.TextArea rows={2} placeholder="如 mvn -q package -DskipTests" />
          </Form.Item>
          <Form.Item label="执行目录（相对仓库根，留空=根）" name="workingDir">
            <Input placeholder="如 app/" />
          </Form.Item>
          <Form.Item label="执行位置" name="location">
            <Select options={LOCATION_OPTIONS.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item label="排序" name="sortOrder">
            <InputNumber min={0} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}

// ---------------- 发版配置 ----------------

function ReleaseTab({ id, release, onChanged }: {
  id: string
  release: ReleaseConfig | null
  onChanged: (r: ReleaseConfig) => void
}) {
  const [form] = Form.useForm()
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    form.setFieldsValue({
      nexusRepo: release?.nexusRepo ?? '',
      scriptTemplateRef: release?.scriptTemplateRef ?? '',
      versionRule: release?.versionRule ?? '',
    })
  }, [release, form])

  const onSave = async (v: { nexusRepo?: string; scriptTemplateRef?: string; versionRule?: string }) => {
    setBusy(true)
    try {
      const r = await saveReleaseConfig(id, v)
      onChanged(r)
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%', maxWidth: 560 }}>
      <Form form={form} layout="vertical" onFinish={onSave}>
        <Form.Item label="Nexus 目标仓库" name="nexusRepo" extra="如 snapshots / releases">
          <Input placeholder="snapshots" />
        </Form.Item>
        <Form.Item label="推送脚本模板引用" name="scriptTemplateRef" extra="docs-repo 中模板 id 或路径（委托 CAP-11）">
          <Input placeholder="如 nexus-push-template" />
        </Form.Item>
        <Form.Item label="版本规则" name="versionRule" extra="如 semver 递增策略描述">
          <Input placeholder="如 patch 版本 +1，仅 master 发版" />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={busy}>
          保存发版配置
        </Button>
      </Form>
    </Space>
  )
}

// ---------------- Worktree ----------------

function WorktreesTab({ worktrees, onRefresh }: {
  worktrees: WorktreeInfo[]
  onRefresh: () => void
}) {
  const columns: ColumnsType<WorktreeInfo> = [
    { title: 'Session', dataIndex: 'sessionId', width: 120, render: (s: string) => <Typography.Text code>{s}</Typography.Text> },
    { title: '分支', dataIndex: 'branch', width: 160, render: (b: string) => <Tag>{b}</Tag> },
    { title: '路径', dataIndex: 'path', render: (p: string) => <span style={{ fontSize: 12 }}>{p}</span> },
  ]
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space>
        <Button size="small" icon={<ReloadOutlined />} onClick={onRefresh}>刷新</Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          该项目的活跃 worktree（CAP-05 会话隔离工作区）。
        </Typography.Text>
      </Space>
      <Table rowKey="path" size="small" columns={columns} dataSource={worktrees} pagination={false}
        locale={{ emptyText: '暂无活跃 worktree' }} />
    </Space>
  )
}

// ---------------- 锁定 ----------------

function LockTab({ id, lock, onChanged }: {
  id: string
  lock: ProjectLock | null
  onChanged: (l: ProjectLock) => void
}) {
  const [max, setMax] = useState(lock?.maxConcurrent ?? 1)

  useEffect(() => setMax(lock?.maxConcurrent ?? 1), [lock?.maxConcurrent])

  const act = async (fn: () => Promise<ProjectLock>, ok: string) => {
    try {
      const l = await fn()
      onChanged(l)
      message.success(ok)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const saveMax = async () => {
    try {
      const l = await updateLock(id, Math.max(1, max))
      onChanged(l)
      message.success('已保存并发上限')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%', maxWidth: 480 }}>
      <Descriptions size="small" column={2}>
        <Descriptions.Item label="当前写任务">
          <Typography.Text strong>{lock?.activeWrites ?? 0}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="最大并发写">
          <Typography.Text strong>{lock?.maxConcurrent ?? '-'}</Typography.Text>
        </Descriptions.Item>
      </Descriptions>
      <Space wrap>
        <InputNumber min={1} value={max} onChange={(v) => setMax(v ?? 1)} addonBefore="并发上限" />
        <Button size="small" type="primary" onClick={saveMax}>保存上限</Button>
        <Button size="small" icon={<DiffOutlined />} onClick={() => act(() => claimWrite(id), '已占用一个写配额')}>
          占用写配额
        </Button>
        <Button size="small" icon={<StopOutlined />} onClick={() => act(() => releaseWrite(id), '已释放一个写配额')}>
          释放写配额
        </Button>
      </Space>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        供任务编排 Orchestrator 做项目级并发控制；达上限时 claim 返回冲突。
      </Typography.Text>
    </Space>
  )
}
