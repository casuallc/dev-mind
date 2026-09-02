import { Layout, Menu } from 'antd'
import {
  ApiOutlined,
  ArrowLeftOutlined,
  CloudServerOutlined,
  DashboardOutlined,
  DeploymentUnitOutlined,
  FileTextOutlined,
  FolderOutlined,
  KeyOutlined,
  ReadOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  ToolOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect } from 'react'
import AppHeader from './AppHeader'
import { menuSelectedKey } from './menuSelectedKey'
import { startNotificationStream, stopNotificationStream } from '../features/notifications/store'

const { Sider, Content } = Layout

/** 管理后台布局（仅 ADMIN，由 RequireAdmin 守卫）：指挥中心 / 项目与资源 / 内容 / 系统。 */
export default function AdminLayout() {
  const navigate = useNavigate()
  const location = useLocation()

  // 管理员同样接收全局通知实时流
  useEffect(() => {
    startNotificationStream()
    return () => stopNotificationStream()
  }, [])

  const selectedKey = menuSelectedKey(location.pathname)

  return (
    <Layout style={{ height: '100vh' }}>
      <Sider theme="light" width={200} style={{ borderRight: '1px solid #f0f0f0' }}>
        <div style={{ padding: '16px', fontWeight: 600 }}>
          Dev-Mind 后台
        </div>
        <Menu
          theme="light"
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
          items={[
            { key: '/admin/dashboard', icon: <DashboardOutlined />, label: '指挥中心' },
            {
              type: 'group' as const,
              label: '项目与资源',
              children: [
                { key: '/admin/projects', icon: <FolderOutlined />, label: '项目管理' },
                { key: '/admin/servers', icon: <CloudServerOutlined />, label: '服务器运维' },
                { key: '/admin/agent-nodes', icon: <RobotOutlined />, label: 'Agent 节点' },
                { key: '/admin/integrations', icon: <ApiOutlined />, label: '平台集成' },
                { key: '/admin/keys', icon: <KeyOutlined />, label: 'API 密钥' },
                { key: '/admin/templates', icon: <DeploymentUnitOutlined />, label: '会话模板' },
              ],
            },
            {
              type: 'group' as const,
              label: '内容',
              children: [
                { key: '/admin/knowledge', icon: <ReadOutlined />, label: '知识库' },
                { key: '/admin/skills', icon: <ToolOutlined />, label: 'Skill 管理' },
                { key: '/admin/docs', icon: <FileTextOutlined />, label: '文档管理' },
              ],
            },
            {
              type: 'group' as const,
              label: '系统',
              children: [
                { key: '/admin/users', icon: <SafetyCertificateOutlined />, label: '用户管理' },
              ],
            },
            { type: 'divider' as const },
            { key: '/', icon: <ArrowLeftOutlined />, label: '返回工作台' },
          ]}
        />
      </Sider>
      <Layout>
        <AppHeader />
        <Content style={{ padding: 24, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
