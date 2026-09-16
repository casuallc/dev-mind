// 项目二级页签条：项目上下文页面（概览/会话/需求/知识/构建/部署/测试/发版）的顶部导航，
// 由 AppLayout 在命中项目页路由时渲染于内容区上方。
// 右端承载项目切换器（从顶部导航移入——切换器显隐不再挤压一级导航位置）。
import { Menu } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import { useSyncExternalStore } from 'react'
import { menuSelectedKey } from './menuSelectedKey'
import { getCurrentProjectId, subscribeCurrentProject } from './currentProjectStore'
import ProjectSwitcher from './ProjectSwitcher'
import type { Project } from '../features/projects/types'

/** 项目上下文页面前缀（命中这些路径时 AppLayout 渲染本页签条） */
const PROJECT_PAGE_PREFIXES = [
  '/overview',
  '/sessions',
  '/requirements',
  '/context',
  '/builds',
  '/deployments',
  '/tests',
  '/releases',
]

/** 需求详情页（/projects/:id/requirements/:rid）也属项目上下文 */
const REQUIREMENT_DETAIL_RE = /^\/projects\/[^/]+\/requirements\//

export function isProjectPage(pathname: string): boolean {
  return (
    PROJECT_PAGE_PREFIXES.some((p) => pathname.startsWith(p)) ||
    REQUIREMENT_DETAIL_RE.test(pathname)
  )
}

interface Props {
  /** 引导数据（AppLayout 常驻 useProjectBootstrap 提供），透传给右端的项目切换器 */
  projects: Project[]
  loadError: boolean
  onRetry: () => void
}

export default function ProjectSubNav({ projects, loadError, onRetry }: Props) {
  const navigate = useNavigate()
  const location = useLocation()
  const currentProjectId = useSyncExternalStore(subscribeCurrentProject, getCurrentProjectId)

  if (!currentProjectId) return null

  const items = [
    { key: '/overview', label: '概览' },
    { key: '/sessions', label: '会话' },
    { key: '/requirements', label: '需求' },
    { key: '/context', label: '知识' },
    { key: '/builds', label: '构建' },
    { key: '/deployments', label: '部署' },
    { key: '/tests', label: '测试' },
    { key: '/releases', label: '发版' },
  ]

  return (
    <div
      style={{
        background: '#fff',
        borderRadius: 8,
        marginBottom: 16,
        padding: '0 12px 0 8px',
        flexShrink: 0,
        display: 'flex',
        alignItems: 'center',
        gap: 12,
      }}
    >
      <Menu
        mode="horizontal"
        selectedKeys={[menuSelectedKey(location.pathname)]}
        onClick={({ key }) => navigate(key)}
        items={items}
        style={{ background: 'transparent', borderBottom: 'none', flex: 1, minWidth: 0 }}
      />
      <ProjectSwitcher projects={projects} loadError={loadError} onRetry={onRetry} />
    </div>
  )
}
