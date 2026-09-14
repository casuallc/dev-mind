// 项目二级页签条：项目上下文页面（概览/会话/需求/知识/构建/部署/测试/发版）的顶部导航，
// 由 AppLayout 在命中项目页路由时渲染于内容区上方。WORKLOG 项目裁剪代码类页签。
import { Menu } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useState, useSyncExternalStore } from 'react'
import { menuSelectedKey } from './menuSelectedKey'
import { getCurrentProjectId, subscribeCurrentProject } from './currentProjectStore'
import { getProject } from '../features/projects/api'

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

export default function ProjectSubNav() {
  const navigate = useNavigate()
  const location = useLocation()
  // CAP-41：当前项目为 WORKLOG（工作日志空间）时裁剪代码类页签（需求/构建/部署/测试/发版）
  const currentProjectId = useSyncExternalStore(subscribeCurrentProject, getCurrentProjectId)
  const [currentKind, setCurrentKind] = useState<string | null>(null)
  useEffect(() => {
    setCurrentKind(null)
    if (!currentProjectId) return
    let alive = true
    getProject(currentProjectId)
      .then((p) => {
        if (alive) setCurrentKind(p.kind ?? 'NORMAL')
      })
      .catch(() => {
        if (alive) setCurrentKind(null)
      })
    return () => {
      alive = false
    }
  }, [currentProjectId])
  const worklogProject = currentKind === 'WORKLOG'

  if (!currentProjectId) return null

  const items = [
    { key: '/overview', label: '概览' },
    { key: '/sessions', label: '会话' },
    ...(!worklogProject ? [{ key: '/requirements', label: '需求' }] : []),
    { key: '/context', label: '知识' },
    ...(!worklogProject
      ? [
          { key: '/builds', label: '构建' },
          { key: '/deployments', label: '部署' },
          { key: '/tests', label: '测试' },
          { key: '/releases', label: '发版' },
        ]
      : []),
  ]

  return (
    <div
      style={{
        background: '#fff',
        borderRadius: 8,
        marginBottom: 16,
        padding: '0 8px',
        flexShrink: 0,
      }}
    >
      <Menu
        mode="horizontal"
        selectedKeys={[menuSelectedKey(location.pathname)]}
        onClick={({ key }) => navigate(key)}
        items={items}
        style={{ background: 'transparent', borderBottom: 'none' }}
      />
    </div>
  )
}
