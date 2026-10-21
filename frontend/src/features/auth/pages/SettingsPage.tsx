// 设置页（/settings/:tab，顶部一级导航「设置」）：账号相关的个人偏好统一收口在这里。
// 布局对齐工作日志（布局约定第 1/2/3 条）：Card 头部 =「页面名 + Segmented 页内视图」，
// 操作按钮一律随视图进 Card extra，各视图只管 body（说明文字 + 表格/表单）。
import { Button, Card, Space } from 'antd'
import { PlusOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons'
import { useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import PlatformAccountsPanel, { type PlatformAccountsPanelHandle } from '../components/PlatformAccountsPanel'
import ProfilePanel from '../components/ProfilePanel'
import JiraPushTemplatesPanel, {
  type JiraPushTemplatesPanelHandle,
} from '../../integrations/components/JiraPushTemplatesPanel'
import WorklogSettingsPanel, {
  type WorklogSettingsPanelHandle,
} from '../../worklog/components/WorklogSettingsPanel'
import SettingsViewSwitch, { asSettingsTab, type SettingsTab } from '../components/SettingsViewSwitch'
import { pageCardBodyScrollStyle, pageCardStyle } from '../../../shared/utils/pageLayout'

export default function SettingsPage() {
  const { tab } = useParams<{ tab: string }>()
  const navigate = useNavigate()
  const view = asSettingsTab(tab)
  // 工具栏在 extra 里，刷新/新建/保存要触发面板内部动作 → 面板以 ref 暴露最小句柄
  const accountsRef = useRef<PlatformAccountsPanelHandle>(null)
  const templatesRef = useRef<JiraPushTemplatesPanelHandle>(null)
  const worklogRef = useRef<WorklogSettingsPanelHandle>(null)
  const [savingWorklog, setSavingWorklog] = useState(false)

  const onSaveWorklog = async () => {
    setSavingWorklog(true)
    try {
      await worklogRef.current?.save()
    } finally {
      setSavingWorklog(false)
    }
  }

  const extraByView: Partial<Record<SettingsTab, React.ReactNode>> = {
    accounts: (
      <Button icon={<ReloadOutlined />} onClick={() => accountsRef.current?.reload()}>
        刷新
      </Button>
    ),
    'jira-templates': (
      <>
        <Button icon={<ReloadOutlined />} onClick={() => templatesRef.current?.reload()}>
          刷新
        </Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => templatesRef.current?.openCreate()}>
          新建模板
        </Button>
      </>
    ),
    worklog: (
      <>
        <Button icon={<ReloadOutlined />} onClick={() => worklogRef.current?.reload()}>
          刷新
        </Button>
        <Button type="primary" icon={<SaveOutlined />} loading={savingWorklog} onClick={onSaveWorklog}>
          保存
        </Button>
      </>
    ),
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title={<SettingsViewSwitch value={view} onChange={(v) => navigate(`/settings/${v}`)} />}
      extra={<Space>{extraByView[view]}</Space>}
    >
      {view === 'profile' && <ProfilePanel />}
      {view === 'accounts' && <PlatformAccountsPanel ref={accountsRef} />}
      {view === 'jira-templates' && <JiraPushTemplatesPanel ref={templatesRef} />}
      {view === 'worklog' && <WorklogSettingsPanel ref={worklogRef} />}
    </Card>
  )
}
