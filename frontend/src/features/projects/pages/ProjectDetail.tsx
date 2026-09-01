// CAP-02 项目详情（业务视图，全角色）：头部信息 + 需求研发主线（CAP-13）+ 执行记录 Tabs。
// 配置类功能（仓库/服务器/环境/构建/发版/锁）在后台 /admin/projects/:id（仅 ADMIN）。
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Descriptions, Empty, Space, Spin, Tabs, Tag, Typography } from 'antd'
import { ReloadOutlined, SettingOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import BuildCenterTab from '../../build/components/BuildTab'
import DeployTab from '../../deploy/components/DeployTab'
import TestTab from '../../test/components/TestTab'
import RequirementCockpit from '../components/RequirementCockpit'
import WorktreesTab from '../components/detail/WorktreesTab'
import { useProject } from '../hooks/useProject'
import { listWorktrees } from '../api'
import { isAdmin } from '../../auth/authStore'
import type { WorktreeInfo } from '../types'

export default function ProjectDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { project, loading, reload } = useProject(id)
  const [worktrees, setWorktrees] = useState<WorktreeInfo[]>([])

  const loadWorktrees = useCallback(() => {
    if (!id) return
    listWorktrees(id)
      .then(setWorktrees)
      .catch(() => setWorktrees([]))
  }, [id])

  useEffect(loadWorktrees, [loadWorktrees])

  if (loading) {
    return <Card><Spin /></Card>
  }
  if (!project) {
    return <Card><Empty description="项目不存在" /></Card>
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
            {isAdmin() && (
              <Button
                size="small"
                icon={<SettingOutlined />}
                onClick={() => navigate(`/admin/projects/${project.id}`)}
              >
                项目设置
              </Button>
            )}
            <Button size="small" icon={<ReloadOutlined />} onClick={reload}>
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

      <Card size="small" title="研发主线（需求）" extra={
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          需求 → 分析/方案 → 工作单元 → 会话 → 构建 → 测试 → 部署 → 发版 → 时间线
        </Typography.Text>
      }>
        <RequirementCockpit projectId={project.id} />
      </Card>

      <Card size="small" title="执行记录">
        <Tabs
          items={[
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
              key: 'worktrees',
              label: 'Worktree',
              children: <WorktreesTab worktrees={worktrees} onRefresh={loadWorktrees} />,
            },
          ]}
        />
      </Card>
    </Space>
  )
}
