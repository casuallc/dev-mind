// 后台项目设置布局（仅 ADMIN）：头部信息卡 + 子路由 Tabs + Outlet。
// 各配置子页面（仓库/摘要/服务器/环境/构建步骤/发版/Jira/锁定）独立成路由、各自加载数据，
// 取代原 AdminProjectDetail 一页 8 Tab + useProjectConfig 全量预加载的模式。
import { useState } from 'react'
import { Button, Card, Descriptions, Empty, Space, Spin, Tabs, Tag, Typography } from 'antd'
import { EditOutlined, ReloadOutlined } from '@ant-design/icons'
import { Outlet, useLocation, useNavigate, useParams } from 'react-router-dom'
import ProjectFormModal from '../../components/ProjectFormModal'
import { useProject } from '../../hooks/useProject'
import { fmtTime } from '../../../../shared/utils/format'

const SUB_TABS = [
  { key: 'repos', label: '仓库' },
  { key: 'summary', label: '上下文摘要' },
  { key: 'servers', label: '服务器' },
  { key: 'environments', label: '环境' },
  { key: 'build', label: '构建配置' },
  { key: 'release', label: '发版配置' },
  { key: 'jira', label: 'Jira 同步' },
  { key: 'lock', label: '锁定' },
]

export default function ProjectSettingsLayout() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const location = useLocation()
  const { project, loading, reload } = useProject(id)
  const [editOpen, setEditOpen] = useState(false)

  if (loading) {
    return <Card><Spin /></Card>
  }
  if (!project) {
    return <Card><Empty description="项目不存在" /></Card>
  }

  // /admin/projects/:id 会被 index 路由重定向到 repos，这里兜底保证高亮合法
  const seg = location.pathname.split('/').pop() ?? ''
  const activeKey = SUB_TABS.some((t) => t.key === seg) ? seg : 'repos'

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
            <Button size="small" icon={<ReloadOutlined />} onClick={reload}>
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
          <Descriptions.Item label="创建">{fmtTime(project.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="更新">{fmtTime(project.updatedAt)}</Descriptions.Item>
          {project.description && (
            <Descriptions.Item label="描述" span={2}>
              {project.description}
            </Descriptions.Item>
          )}
        </Descriptions>
      </Card>

      <Card size="small" title="项目配置">
        <Tabs
          activeKey={activeKey}
          onChange={(k) => navigate(`/admin/projects/${project.id}/${k}`)}
          items={SUB_TABS}
        />
        <Outlet context={{ reloadProject: reload }} />
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
