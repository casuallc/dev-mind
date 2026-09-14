// 个人工作台外壳：顶部导航（品牌 + 项目切换 + 一级导航 + 通知/用户）+ 居中内容区。
// 项目上下文页面由 ProjectSubNav 在内容区顶部提供二级页签；后台管理走 AdminLayout（/admin）。
import { Button, Layout, Menu, Tooltip } from 'antd'
import {
  CommentOutlined,
  FieldTimeOutlined,
  FolderOutlined,
  HomeOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useSyncExternalStore } from 'react'
import ProjectSwitcher from './ProjectSwitcher'
import ProjectSubNav, { isProjectPage } from './ProjectSubNav'
import { topNavKey } from './menuSelectedKey'
import NotificationBell from '../features/notifications/components/NotificationBell'
import UserMenu from '../features/auth/components/UserMenu'
import { startNotificationStream, stopNotificationStream } from '../features/notifications/store'
import { getUserSnapshot, isAdmin, subscribeAuth } from '../features/auth/authStore'

const { Header, Content } = Layout

export default function AppLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  // 认证态变化（登录/退出/刷新轮换）时重渲染导航与用户区
  useSyncExternalStore(subscribeAuth, getUserSnapshot)

  // 启动全局通知实时流（铃铛角标/浏览器通知依赖它）
  useEffect(() => {
    startNotificationStream()
    return () => stopNotificationStream()
  }, [])

  return (
    <Layout style={{ height: '100vh' }}>
      <Header
        style={{
          background: '#fff',
          padding: '0 24px',
          display: 'flex',
          alignItems: 'center',
          gap: 16,
          borderBottom: '1px solid #f0f0f0',
          flexShrink: 0,
        }}
      >
        <div
          onClick={() => navigate('/home')}
          style={{ fontWeight: 700, fontSize: 16, cursor: 'pointer', whiteSpace: 'nowrap' }}
        >
          ◆ Dev-Mind
        </div>
        <ProjectSwitcher />
        <Menu
          mode="horizontal"
          selectedKeys={[topNavKey(location.pathname)]}
          onClick={({ key }) => navigate(key)}
          style={{ flex: 1, minWidth: 0, borderBottom: 'none' }}
          items={[
            { key: '/home', icon: <HomeOutlined />, label: '工作台' },
            { key: '/overview', icon: <FolderOutlined />, label: '项目' },
            { key: '/chats', icon: <CommentOutlined />, label: 'AI 问答' },
            { key: '/worklog', icon: <FieldTimeOutlined />, label: '工作日志' },
          ]}
        />
        {/* 管理功能集中在 /admin 后台，仅 ADMIN 可见入口 */}
        {isAdmin() && (
          <Tooltip title="后台管理">
            <Button
              type="text"
              icon={<SafetyCertificateOutlined />}
              onClick={() => navigate('/admin')}
            />
          </Tooltip>
        )}
        <NotificationBell />
        <UserMenu />
      </Header>
      {/* flex 列布局：默认子页仍按内容自适应；需撑满屏的页面（如 AI 问答）根节点 flex:1 minHeight:0 即可 */}
      <Content
        style={{
          padding: '16px 24px 24px',
          overflow: 'auto',
          display: 'flex',
          flexDirection: 'column',
          background: '#f5f7fa',
        }}
      >
        <div
          style={{
            maxWidth: 1400,
            margin: '0 auto',
            width: '100%',
            flex: 1,
            minHeight: 0,
            display: 'flex',
            flexDirection: 'column',
          }}
        >
          {isProjectPage(location.pathname) && <ProjectSubNav />}
          <Outlet />
        </div>
      </Content>
    </Layout>
  )
}
