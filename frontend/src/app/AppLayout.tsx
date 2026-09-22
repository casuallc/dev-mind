// 个人工作台外壳：顶部导航（品牌 + 一级导航 + 通知/用户，布局恒定）+ 全宽内容区。
// 项目切换器放在 ProjectSubNav 二级页签条右端（仅项目上下文页），不进顶部导航——
// 否则切 tab 时一级导航位置随切换器显隐左右移动。
import { Button, Layout, Menu, Tooltip } from 'antd'
import {
  CommentOutlined,
  FieldTimeOutlined,
  FolderOutlined,
  HomeOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect, useSyncExternalStore } from 'react'
import ProjectSubNav, { isProjectPage } from './ProjectSubNav'
import { useProjectBootstrap } from './useProjectBootstrap'
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
  // 项目列表常驻引导（加载 + currentId 兜底），与切换器 UI 解耦
  const projectBootstrap = useProjectBootstrap()
  const projectPage = isProjectPage(location.pathname)

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
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            fontWeight: 700,
            fontSize: 16,
            cursor: 'pointer',
            whiteSpace: 'nowrap',
          }}
        >
          <img src="/logo.svg" alt="Dev-Mind" width={24} height={24} />
          Dev-Mind
        </div>
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
            // 个人设置已从用户下拉上提为一级导航（页内视图切换在页面 Card 头部）
            { key: '/settings', icon: <SettingOutlined />, label: '设置' },
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
            width: '100%',
            flex: 1,
            minHeight: 0,
            display: 'flex',
            flexDirection: 'column',
          }}
        >
          {projectPage && (
            <ProjectSubNav
              projects={projectBootstrap.projects}
              loadError={projectBootstrap.loadError}
              onRetry={projectBootstrap.reload}
            />
          )}
          <Outlet />
        </div>
      </Content>
    </Layout>
  )
}
