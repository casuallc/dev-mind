import { Layout, Menu } from 'antd'
import {
  ApiOutlined,
  ArrowLeftOutlined,
  AuditOutlined,
  CodeOutlined,
  DashboardOutlined,
  DeploymentUnitOutlined,
  FileTextOutlined,
  FolderOutlined,
  KeyOutlined,
  PaperClipOutlined,
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
                { key: '/admin/repos', icon: <CodeOutlined />, label: '代码仓库' },
                { key: '/admin/agent-nodes', icon: <RobotOutlined />, label: 'Agent 节点' },
                { key: '/admin/execution', icon: <AuditOutlined />, label: '模板与审计' },
                { key: '/admin/integrations', icon: <ApiOutlined />, label: '平台集成' },
                { key: '/admin/keys', icon: <KeyOutlined />, label: 'API 密钥' },
                { key: '/admin/scenarios', icon: <DeploymentUnitOutlined />, label: '应用场景' },
              ],
            },
            {
              type: 'group' as const,
              label: '内容',
              children: [
                { key: '/admin/knowledge', icon: <ReadOutlined />, label: '知识库' },
                { key: '/admin/skills', icon: <ToolOutlined />, label: 'Skill 管理' },
                { key: '/admin/docs', icon: <FileTextOutlined />, label: '文档管理' },
                { key: '/admin/attachments', icon: <PaperClipOutlined />, label: '附件管理' },
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
        {/* flex 列布局与工作台一致：子页根节点 flex:1 minHeight:0 撑满高度（见 shared/utils/pageLayout） */}
        <Content style={{ padding: '16px 24px 24px', overflow: 'auto', display: 'flex', flexDirection: 'column' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
