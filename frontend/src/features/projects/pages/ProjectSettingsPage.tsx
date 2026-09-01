// 工作台项目设置页（/settings）：当前项目的配置类 Tabs，全角色可见。
// readOnly = !canWrite()：ADMIN/DEVELOPER 可编辑（与后端 SecurityConfig 写权限一致），VIEWER 只读。
// Jira 同步属平台集成配置，只留在后台 /admin/projects/:id，不在此挂载。
import { useState } from 'react'
import { Button, Card, Empty, Space, Spin, Tabs, Tag, Typography } from 'antd'
import { EditOutlined, ReloadOutlined } from '@ant-design/icons'
import ReleaseTab from '../../release/components/ReleaseTab'
import ReposTab from '../components/detail/ReposTab'
import SummaryTab from '../components/detail/SummaryTab'
import ServersTab from '../components/detail/ServersTab'
import EnvironmentsTab from '../components/detail/EnvironmentsTab'
import BuildTab from '../components/detail/BuildTab'
import LockTab from '../components/detail/LockTab'
import ProjectFormModal from '../components/ProjectFormModal'
import { useCurrentProject } from '../hooks/useCurrentProject'
import { useProjectConfig } from '../hooks/useProjectConfig'
import { canWrite } from '../../auth/authStore'
import { getProject } from '../api'

export default function ProjectSettingsPage() {
  const { projectId, project, setProject, loading, reload } = useCurrentProject()
  const {
    servers, setServers,
    environments, setEnvironments,
    repos, setRepos,
    steps, setSteps,
    summary, setSummary,
    lock, setLock,
    loadConfig,
  } = useProjectConfig(projectId ?? undefined)
  const [editOpen, setEditOpen] = useState(false)
  const readOnly = !canWrite()

  if (loading) {
    return <Card><Spin /></Card>
  }
  if (!project) {
    return <Card><Empty description="项目不存在或已删除" /></Card>
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
            {readOnly && <Tag>只读</Tag>}
          </Space>
        }
        extra={
          <Space>
            {!readOnly && (
              <Button size="small" icon={<EditOutlined />} onClick={() => setEditOpen(true)}>
                编辑基本信息
              </Button>
            )}
            <Button size="small" icon={<ReloadOutlined />} onClick={reloadAll}>
              刷新
            </Button>
          </Space>
        }
      >
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          项目配置（仓库/摘要/服务器/环境/构建/发版/锁定）{readOnly ? '，当前角色为只读视图' : ''}
        </Typography.Text>
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
                  readOnly={readOnly}
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
              children: <SummaryTab id={project.id} summary={summary} onChanged={setSummary} readOnly={readOnly} />,
            },
            {
              key: 'servers',
              label: '服务器',
              children: <ServersTab id={project.id} servers={servers} onChanged={setServers} readOnly={readOnly} />,
            },
            {
              key: 'environments',
              label: '环境',
              children: (
                <EnvironmentsTab id={project.id} environments={environments} servers={servers}
                  onChanged={setEnvironments} readOnly={readOnly} />
              ),
            },
            {
              key: 'build',
              label: '构建配置',
              children: <BuildTab id={project.id} steps={steps} onChanged={setSteps} readOnly={readOnly} />,
            },
            {
              key: 'release',
              label: '发版配置',
              children: <ReleaseTab id={project.id} readOnly={readOnly} />,
            },
            {
              key: 'lock',
              label: '锁定',
              children: <LockTab id={project.id} lock={lock} onChanged={setLock} readOnly={readOnly} />,
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
