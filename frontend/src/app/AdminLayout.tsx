import { Layout, Menu } from 'antd'
import type { MenuProps } from 'antd'
import {
  ApiOutlined,
  BarChartOutlined,
  BellOutlined,
  CommentOutlined,
  DashboardOutlined,
  ExperimentOutlined,
  FileTextOutlined,
  ForkOutlined,
  HeartOutlined,
  InteractionOutlined,
  FolderOutlined,
  KeyOutlined,
  PaperClipOutlined,
  PartitionOutlined,
  ReadOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  ScheduleOutlined,
  ThunderboltOutlined,
  ToolOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useEffect } from 'react'
import AppHeader from './AppHeader'
import { menuSelectedKey } from './menuSelectedKey'
import { startNotificationStream, stopNotificationStream } from '../features/notifications/store'

const { Sider, Content } = Layout

type AdminMenuItem = NonNullable<MenuProps['items']>[number]

/** 后台侧边菜单（单一数据源：菜单渲染、选中高亮、顶栏标题均取自此） */
const ADMIN_MENU_ITEMS: AdminMenuItem[] = [
  { key: '/admin/dashboard', icon: <DashboardOutlined />, label: '指挥中心' },
  {
    type: 'group' as const,
    label: '项目与资源',
    children: [
      // 项目管理为六视图聚合页：项目/经验提案/命令模板/代码库/场景/执行审计
      { key: '/admin/projects', icon: <FolderOutlined />, label: '项目管理' },
      { key: '/admin/docs', icon: <FileTextOutlined />, label: '文档管理' },
      { key: '/admin/attachments', icon: <PaperClipOutlined />, label: '附件管理' },
      { key: '/admin/skills', icon: <ToolOutlined />, label: '技能管理' },
      { key: '/admin/agent/nodes', icon: <RobotOutlined />, label: 'Agent 节点' },
    ],
  },
  {
    type: 'group' as const,
    label: '知识库',
    children: [
      // CAP-86 v3 两区模型：知识库管内容（喂什么），知识运营管质量（现在该做什么），报表独立入口
      { key: '/admin/knowledge', icon: <ReadOutlined />, label: '知识库' },
      { key: '/admin/knowledge/ops', icon: <HeartOutlined />, label: '知识运营' },
      { key: '/admin/knowledge/reports', icon: <BarChartOutlined />, label: '报表' },
    ],
  },
  {
    type: 'group' as const,
    label: '智能体',
    children: [
      { key: '/admin/bot', icon: <CommentOutlined />, label: '机器人运维' },
      { key: '/admin/copilot', icon: <InteractionOutlined />, label: '智能体运维' },
    ],
  },
  {
    type: 'group' as const,
    label: '智能决策',
    children: [
      { key: '/admin/laya/instances', icon: <PartitionOutlined />, label: '服务实例' },
      { key: '/admin/laya/records', icon: <ForkOutlined />, label: '决策记录' },
      { key: '/admin/laya/lab', icon: <ExperimentOutlined />, label: '决策实验室' },
    ],
  },
  {
    type: 'group' as const,
    label: '系统',
    children: [
      { key: '/admin/tasks', icon: <ScheduleOutlined />, label: '任务调度' },
      { key: '/admin/integrations', icon: <ApiOutlined />, label: '平台集成' },
      { key: '/admin/models', icon: <ThunderboltOutlined />, label: '模型接入' },
      { key: '/admin/keys', icon: <KeyOutlined />, label: 'API 密钥' },
      { key: '/admin/users', icon: <SafetyCertificateOutlined />, label: '用户管理' },
      { key: '/admin/notifications', icon: <BellOutlined />, label: '通知管理' },
    ],
  },
]

/** 按菜单 key 递归查 label（分组/分割线跳过），供顶栏展示当前选中菜单名 */
function menuLabel(items: AdminMenuItem[], key: string): string | undefined {
  for (const item of items) {
    if (!item) continue
    if ('key' in item && item.key === key && 'label' in item && typeof item.label === 'string') {
      return item.label
    }
    if ('children' in item && item.children) {
      const hit = menuLabel(item.children as AdminMenuItem[], key)
      if (hit) return hit
    }
  }
  return undefined
}

/** 管理后台布局（仅 ADMIN，由 RequireAdmin 守卫）：指挥中心 / 项目与资源 / 智能体 / 智能决策 / 系统。 */
export default function AdminLayout() {
  const navigate = useNavigate()
  const location = useLocation()

  // 管理员同样接收全局通知实时流
  useEffect(() => {
    startNotificationStream()
    return () => stopNotificationStream()
  }, [])

  const selectedKey = menuSelectedKey(location.pathname)
  const title = location.pathname.startsWith('/admin/knowledge/bases/')
    ? '知识库详情'
    : (menuLabel(ADMIN_MENU_ITEMS, selectedKey) ?? '后台管理')

  return (
    <Layout style={{ height: '100vh' }}>
      {/* 菜单组增多后总高可能超视口：Sider 内部滚动，不溢出整页（layout 回归断言文档不溢出） */}
      <Sider theme="light" width={200} style={{ borderRight: '1px solid #f0f0f0', overflowY: 'auto', overflowX: 'hidden' }}>
        <div style={{ padding: '16px', fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
          <img src="/logo.svg" alt="Dev-Mind" width={22} height={22} />
          Dev-Mind 后台
        </div>
        <Menu
          theme="light"
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
          items={ADMIN_MENU_ITEMS}
        />
      </Sider>
      <Layout>
        <AppHeader title={title} />
        {/* flex 列布局与工作台一致：子页根节点 flex:1 minHeight:0 撑满高度（见 shared/utils/pageLayout） */}
        <Content style={{ padding: '16px 24px 24px', overflow: 'auto', display: 'flex', flexDirection: 'column' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
