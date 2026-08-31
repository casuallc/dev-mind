// CAP-02 项目详情：头部信息 + 任务主线 + 项目级资产 Tabs（各 Tab 拆至 components/detail/）。
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Switch,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import { EditOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import BuildCenterTab from '../../build/BuildTab'
import DeployTab from '../../deploy/DeployTab'
import TestTab from '../../test/TestTab'
import ReleaseTab from '../../release/ReleaseTab'
import TaskCockpit from '../components/TaskCockpit'
import ReposTab from '../components/detail/ReposTab'
import SummaryTab from '../components/detail/SummaryTab'
import ServersTab from '../components/detail/ServersTab'
import EnvironmentsTab from '../components/detail/EnvironmentsTab'
import BuildTab from '../components/detail/BuildTab'
import WorktreesTab from '../components/detail/WorktreesTab'
import LockTab from '../components/detail/LockTab'
import {
  getLock,
  getProject,
  getSummary,
  listBuildSteps,
  listEnvironments,
  listRepos,
  listServers,
  listWorktrees,
  updateProject,
} from '../api'
import type {
  BuildStep,
  ContextSummary,
  ProjectEnvironment,
  Project,
  ProjectInput,
  ProjectLock,
  ProjectRepo,
  ProjectServer,
  WorktreeInfo,
} from '../types'

export default function ProjectDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [project, setProject] = useState<Project | null>(null)
  const [loading, setLoading] = useState(true)
  const [servers, setServers] = useState<ProjectServer[]>([])
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])
  const [repos, setRepos] = useState<ProjectRepo[]>([])
  const [steps, setSteps] = useState<BuildStep[]>([])
  const [summary, setSummary] = useState<ContextSummary>({ projectId: id ?? '', summary: '' })
  const [worktrees, setWorktrees] = useState<WorktreeInfo[]>([])
  const [lock, setLock] = useState<ProjectLock | null>(null)
  const [editOpen, setEditOpen] = useState(false)
  const [form] = Form.useForm()

  const loadAll = useCallback(async () => {
    if (!id) return
    try {
      const [p, rp, s, env, b, sm, wt, lk] = await Promise.all([
        getProject(id),
        listRepos(id).catch(() => []),
        listServers(id),
        listEnvironments(id).catch(() => []),
        listBuildSteps(id),
        getSummary(id).catch(() => ({ projectId: id, summary: '' })),
        listWorktrees(id).catch(() => []),
        getLock(id).catch(() => null),
      ])
      setProject(p)
      setRepos(rp)
      setServers(s)
      setEnvironments(env)
      setSteps(b)
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
      autoRegressionOnDeploy: project.autoRegressionOnDeploy,
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

      <Card size="small" title="任务主线" extra={
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          项目内每个任务一条独立流程：文档 → 会话 → 构建 → 测试 → 部署 → 发版 → 时间线
        </Typography.Text>
      }>
        <TaskCockpit projectId={project.id} />
      </Card>

      <Card size="small" title="项目级资产">
        <Tabs
          items={[
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
              key: 'environments',
              label: '环境',
              children: (
                <EnvironmentsTab id={project.id} environments={environments} servers={servers}
                  onChanged={setEnvironments} />
              ),
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
              key: 'test',
              label: '测试',
              children: <TestTab id={project.id} />,
            },
            {
              key: 'release',
              label: '发版配置',
              children: <ReleaseTab id={project.id} />,
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
          <Form.Item label="部署成功后自动回归" name="autoRegressionOnDeploy" valuePropName="checked" extra="CAP-10 FR-05：部署单成功后自动对该项目全部套件跑一次回归">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
