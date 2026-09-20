// 设置页视图切换器（布局约定：多视图切换放 Card title，禁 Card 内套 Tabs）。
// Segmented 包在 ScrollRow 里——页签超宽时横向滑动，不被 extra 挤压截断（参考实现 = /worklog 页签）。
import { Segmented } from 'antd'
import ScrollRow from '../../../shared/components/ScrollRow'

/** 页内视图 = 路由 /settings/:tab 的 tab 段（可分享/可收藏），未知值回落 profile */
export const SETTINGS_TAB_KEYS = ['profile', 'accounts', 'jira-templates', 'worklog'] as const
export type SettingsTab = (typeof SETTINGS_TAB_KEYS)[number]

const TAB_LABELS: Record<SettingsTab, string> = {
  profile: '个人信息',
  // CAP-35：第三方平台账号绑定（原 /me/accounts 单页）
  accounts: '第三方账号',
  // CAP-47 FR-10：个人 Jira 推送模板（按 Jira 项目 + 任务类型）
  'jira-templates': 'Jira 推送模板',
  // CAP-28/41：工作日志偏好（原工作日志页「工时设置」Modal）
  worklog: '工作日志',
}

export function asSettingsTab(raw: string | undefined): SettingsTab {
  return (SETTINGS_TAB_KEYS as readonly string[]).includes(raw ?? '') ? (raw as SettingsTab) : 'profile'
}

export default function SettingsViewSwitch({
  value,
  onChange,
}: {
  value: SettingsTab
  onChange: (v: SettingsTab) => void
}) {
  // 根节点必须 width:100% + minWidth:0：Card title 是 flex:1 + overflow:hidden，
  // ScrollRow 要靠这个确定的外边界计算可滚区间（Space 是 inline-flex 撑不满，不能用）
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, width: '100%', minWidth: 0 }}>
      <span style={{ flex: 'none' }}>设置</span>
      <ScrollRow activeSelector=".ant-segmented-item-selected">
        <Segmented
          value={value}
          onChange={(v) => onChange(v as SettingsTab)}
          options={SETTINGS_TAB_KEYS.map((k) => ({ value: k, label: TAB_LABELS[k] }))}
        />
      </ScrollRow>
    </div>
  )
}
