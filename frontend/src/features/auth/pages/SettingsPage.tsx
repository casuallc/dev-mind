// 个人设置页（/me/settings/:tab）：账号相关的个人偏好统一收口在这里，
// 子 tab 可扩展（个人信息 / 第三方账号 / Jira 推送模板…）。入口在 Header 用户下拉。
import { Card, Tabs } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import PlatformAccountsPanel from '../components/PlatformAccountsPanel'
import ProfilePanel from '../components/ProfilePanel'
import JiraPushTemplatesPanel from '../../integrations/components/JiraPushTemplatesPanel'
import { pageCardBodyScrollStyle, pageCardStyle } from '../../../shared/utils/pageLayout'

const TAB_KEYS = ['profile', 'accounts', 'jira-templates'] as const
type TabKey = (typeof TAB_KEYS)[number]

function asTab(raw: string | undefined): TabKey {
  return (TAB_KEYS as readonly string[]).includes(raw ?? '') ? (raw as TabKey) : 'profile'
}

export default function SettingsPage() {
  const { tab } = useParams<{ tab: string }>()
  const navigate = useNavigate()
  return (
    <Card style={pageCardStyle} styles={{ body: pageCardBodyScrollStyle }} title="个人设置">
      <Tabs
        activeKey={asTab(tab)}
        onChange={(k) => navigate(`/me/settings/${k}`)}
        items={[
          { key: 'profile', label: '个人信息', children: <ProfilePanel /> },
          // CAP-35：第三方平台账号绑定（原 /me/accounts 单页）
          { key: 'accounts', label: '第三方账号', children: <PlatformAccountsPanel /> },
          // CAP-47 FR-10：个人 Jira 推送模板（按 Jira 项目 + 任务类型）
          { key: 'jira-templates', label: 'Jira 推送模板', children: <JiraPushTemplatesPanel /> },
        ]}
      />
    </Card>
  )
}
