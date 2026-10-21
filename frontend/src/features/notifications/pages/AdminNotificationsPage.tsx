// 后台「通知管理」页（仅 ADMIN，/admin/notifications）：CAP-06 FR-07 通知设置自个人设置迁入——
// 通知通道为平台级全局配置（notification_channels 无属主），归后台「系统」组统一管理。
// 页内视图（Card 头部 Segmented）：通道（开关/阈值/Webhook 配置 + 测试发送）/ 防打扰（免打扰与静默）。
import { useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button, Card, Segmented, Space } from 'antd'
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons'
import NotificationChannelsPanel, {
  type NotificationChannelsPanelHandle,
} from '../components/NotificationChannelsPanel'
import NotificationPrefsPanel, {
  type NotificationPrefsPanelHandle,
} from '../components/NotificationPrefsPanel'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'

type NotificationsView = 'channels' | 'prefs'
const VIEWS: NotificationsView[] = ['channels', 'prefs']

export default function AdminNotificationsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const rawView = searchParams.get('view') as NotificationsView | null
  const view: NotificationsView = rawView && VIEWS.includes(rawView) ? rawView : 'channels'
  const switchView = (v: string | number) =>
    setSearchParams(v === 'channels' ? {} : { view: String(v) }, { replace: true })

  // 工具栏在 extra 里，刷新/保存要触发面板内部动作 → 面板以 ref 暴露最小句柄
  const channelsRef = useRef<NotificationChannelsPanelHandle>(null)
  const prefsRef = useRef<NotificationPrefsPanelHandle>(null)
  const [saving, setSaving] = useState(false)

  const onSave = async () => {
    setSaving(true)
    try {
      await (view === 'channels' ? channelsRef.current?.save() : prefsRef.current?.save())
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title={
        <Space size={12}>
          <span>通知管理</span>
          <Segmented
            value={view}
            onChange={switchView}
            options={[
              { value: 'channels', label: '通知通道' },
              { value: 'prefs', label: '防打扰' },
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => (view === 'channels' ? channelsRef.current?.reload() : prefsRef.current?.reload())}
          >
            刷新
          </Button>
          <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={onSave}>
            保存
          </Button>
        </Space>
      }
    >
      {view === 'channels' && <NotificationChannelsPanel ref={channelsRef} />}
      {view === 'prefs' && <NotificationPrefsPanel ref={prefsRef} />}
    </Card>
  )
}
