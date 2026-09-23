// 智能决策（laya 专属功能）布局（/admin/laya/:tab）：决策记录 / 决策实验室 / 分类服务实例 /
// 安装包 / 在线试分类 五个原独立后台菜单合并为 Tab 子路由（同 ProjectSettingsLayout 模式：
// 子路由各自独立加载，子页保留自己的页面 Card 与 extra 操作按钮，本壳只管 Tab 切换）。
import { Card, Tabs } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'

const SUB_TABS = [
  { key: 'records', label: '决策记录' },
  { key: 'lab', label: '决策实验室' },
  { key: 'instances', label: '服务实例' },
  { key: 'packages', label: '安装包' },
  { key: 'playground', label: '在线试分类' },
]

export default function LayaLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  // /admin/laya 会被 index 路由重定向到 records，这里兜底保证高亮合法
  const seg = location.pathname.split('/').pop() ?? ''
  const activeKey = SUB_TABS.some((t) => t.key === seg) ? seg : 'records'

  return (
    // 根容器 div flex 列（不用 antd Space，理由同 ProjectSettingsLayout：.ant-space-item 不是 flex 项，子页 Card 撑不开）
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, flex: 1, minHeight: 0 }}>
      <Card size="small" style={{ flexShrink: 0 }} styles={{ body: { padding: '0 12px' } }}>
        <Tabs
          activeKey={activeKey}
          onChange={(k) => navigate(`/admin/laya/${k}`)}
          items={SUB_TABS}
          tabBarStyle={{ marginBottom: 0 }}
        />
      </Card>
      <Outlet />
    </div>
  )
}
