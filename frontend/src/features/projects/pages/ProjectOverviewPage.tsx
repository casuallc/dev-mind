// 项目概览（/overview）：当前项目的信息卡 + 活跃 Worktree，全角色可见。原 ProjectDetail 头部平移。
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Descriptions, Empty, Space, Spin, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import WorktreesTab from '../components/detail/WorktreesTab'
import { listWorktrees } from '../api'
import { useCurrentProject } from '../../../app/useCurrentProject'
import type { WorktreeInfo } from '../types'

export default function ProjectOverviewPage() {
  const { projectId, project, loading, reload } = useCurrentProject()
  const [worktrees, setWorktrees] = useState<WorktreeInfo[]>([])

  const loadWorktrees = useCallback(() => {
    if (!projectId) return
    listWorktrees(projectId)
      .then(setWorktrees)
      .catch(() => setWorktrees([]))
  }, [projectId])

  useEffect(loadWorktrees, [loadWorktrees])

  if (loading) {
    return <Card><Spin /></Card>
  }
  if (!project) {
    return <Card><Empty description="项目不存在或已删除" /></Card>
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
          <Button size="small" icon={<ReloadOutlined />} onClick={reload}>
            刷新
          </Button>
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

      <Card size="small" title={`活跃 Worktree（${worktrees.length}）`}>
        <WorktreesTab worktrees={worktrees} onRefresh={loadWorktrees} />
      </Card>
    </Space>
  )
}
