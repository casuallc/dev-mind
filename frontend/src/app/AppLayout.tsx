import { Layout, Menu } from 'antd'
import {
  RobotOutlined,
  SafetyCertificateOutlined,
  BellOutlined,
  HomeOutlined,
  BulbOutlined,
  DatabaseOutlined,
  FolderOutlined,
  ToolOutlined,
  DeploymentUnitOutlined,
  ExperimentOutlined,
  RocketOutlined,
  FieldTimeOutlined,
  CommentOutlined,
  ApiOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useState, useSyncExternalStore } from 'react'
import AppHeader from './AppHeader'
import ProjectSwitcher from './ProjectSwitcher'
import { menuSelectedKey } from './menuSelectedKey'
import { getCurrentProjectId, subscribeCurrentProject } from './currentProjectStore'
import { getProject } from '../features/projects/api'
import { startNotificationStream, stopNotificationStream } from '../features/notifications/store'
import { getUserSnapshot, isAdmin, subscribeAuth } from '../features/auth/authStore'

const { Sider, Content } = Layout

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  // 认证态变化（登录/退出/刷新轮换）时重渲染菜单与用户区
  useSyncExternalStore(subscribeAuth, getUserSnapshot)
  // CAP-41：当前项目为 WORKLOG（工作日志空间）时裁剪代码类菜单（需求/构建/部署/测试/发版）
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

  // 启动全局通知实时流（铃铛角标/浏览器通知依赖它）
  useEffect(() => {
    startNotificationStream()
    return () => stopNotificationStream()
  }, [])

  const selectedKey = menuSelectedKey(location.pathname)

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider theme="light" width={200} style={{ borderRight: '1px solid #f0f0f0' }}>
        <div style={{ padding: '16px 16px 8px', fontWeight: 600 }}>
          Dev-Mind
        </div>
        <ProjectSwitcher />
        <Menu
          theme="light"
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
          items={[
            // 当前项目区：以某个具体项目为主线，切换项目在侧边栏顶部
            {
              type: 'group' as const,
              label: '当前项目',
              children: [
                { key: '/overview', icon: <HomeOutlined />, label: '概览' },
                { key: '/sessions', icon: <RobotOutlined />, label: '会话' },
                ...(!worklogProject
                  ? [
                      { key: '/requirements', icon: <BulbOutlined />, label: '需求' },
                      { key: '/context', icon: <DatabaseOutlined />, label: '知识' },
                      { key: '/builds', icon: <ToolOutlined />, label: '构建' },
                      { key: '/deployments', icon: <DeploymentUnitOutlined />, label: '部署' },
                      { key: '/tests', icon: <ExperimentOutlined />, label: '测试' },
                      { key: '/releases', icon: <RocketOutlined />, label: '发版' },
                    ]
                  : [{ key: '/context', icon: <DatabaseOutlined />, label: '知识' }]),
              ],
            },
            {
              type: 'group' as const,
              label: '协作',
              children: [{ key: '/notifications', icon: <BellOutlined />, label: '通知中心' }],
            },
            {
              type: 'group' as const,
              label: '个人',
              children: [
                { key: '/chats', icon: <CommentOutlined />, label: 'AI 问答' },
                { key: '/worklog', icon: <FieldTimeOutlined />, label: '工作日志' },
                // CAP-35 第三方平台账号绑定（原头像下拉「Git 凭证」入口）
                { key: '/me/accounts', icon: <ApiOutlined />, label: '第三方账号' },
              ],
            },
            {
              type: 'group' as const,
              label: '平台',
              children: [
                { key: '/projects', icon: <FolderOutlined />, label: '全部项目' },
                // 管理功能集中在 /admin 后台，仅 ADMIN 可见入口
                ...(isAdmin()
                  ? [{ key: '/admin', icon: <SafetyCertificateOutlined />, label: '后台管理' }]
                  : []),
              ],
            },
          ]}
        />
      </Sider>
      <Layout>
        <AppHeader />
        {/* flex 列布局：默认子页仍按内容自适应；需撑满屏的页面（如 AI 问答）根节点 flex:1 minHeight:0 即可 */}
        <Content style={{ padding: '16px 24px 24px', overflow: 'auto', display: 'flex', flexDirection: 'column' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
