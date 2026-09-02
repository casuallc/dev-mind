// 后台项目设置页（仅 ADMIN）：基本信息编辑 + 配置类 Tabs（仓库/摘要/服务器/环境/构建配置/发版配置/Jira 同步/锁定）。
// 业务视图在工作台（/overview 等项目上下文页面）；工作台的 /settings 复用同一批 Tab 组件（readOnly 受角色控制）。
import { useState } from 'react'
import { Button, Card, Descriptions, Empty, Space, Spin, Tabs, Tag, Typography } from 'antd'
import { EditOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import ReleaseTab from '../../release/components/ReleaseTab'
import JiraSyncTab from '../../integrations/components/JiraSyncTab'
import ReposTab from '../components/detail/ReposTab'
import SummaryTab from '../components/detail/SummaryTab'
import ServersTab from '../components/detail/ServersTab'
import EnvironmentsTab from '../components/detail/EnvironmentsTab'
import BuildConfigTab from '../components/detail/BuildConfigTab'
import LockTab from '../components/detail/LockTab'
import ProjectFormModal from '../components/ProjectFormModal'
import { useProject } from '../hooks/useProject'
import { useProjectConfig } from '../hooks/useProjectConfig'
import { getProject } from '../api'

export default function AdminProjectDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { project, setProject, loading, reload } = useProject(id)
  const {
    servers, setServers,
    environments, setEnvironments,
    repos, setRepos,
    steps, setSteps,
    summary, setSummary,
    lock, setLock,
    loadConfig,
  } = useProjectConfig(id)
  const [editOpen, setEditOpen] = useState(false)

  if (loading) {
    return <Card><Spin /></Card>
  }
  if (!project) {
    return <Card><Empty description="项目不存在" /></Card>
  }

  const reloadAll = () => {
    reload()
    loadConfig().catch(() => undefined)
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
            <Button size="small" icon={<EditOutlined />} onClick={() => setEditOpen(true)}>
              编辑基本信息
            </Button>
            <Button size="small" icon={<ReloadOutlined />} onClick={reloadAll}>
              刷新
            </Button>
            <Button size="small" onClick={() => navigate('/admin/projects')}>
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

      <Card size="small" title="项目配置">
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
              children: <BuildConfigTab id={project.id} steps={steps} onChanged={setSteps} />,
            },
            {
              key: 'release',
              label: '发版配置',
              children: <ReleaseTab id={project.id} />,
            },
            {
              key: 'jira-sync',
              label: 'Jira 同步',
              children: <JiraSyncTab projectId={project.id} />,
            },
            {
              key: 'lock',
              label: '锁定',
              children: <LockTab id={project.id} lock={lock} onChanged={setLock} />,
            },
          ]}
        />
      </Card>

      <ProjectFormModal
        open={editOpen}
        project={project}
        onCancel={() => setEditOpen(false)}
        onSaved={() => {
          setEditOpen(false)
          reload()
        }}
      />
    </Space>
  )
}
