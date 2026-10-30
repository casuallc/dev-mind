// 工作日志页视图切换器：工作日志主卡与会话内嵌页共用同一 Segmented（布局约定：多视图切换放 Card title）。
// 「对话/对话列表」（SessionsBoard）提为顶层视图；Segmented 包在 ScrollRow 里——页签超宽时横向滑动，不被 extra 挤压截断。
import { Segmented } from 'antd'
import ScrollRow from '../../../shared/components/ScrollRow'
import { usePermissions } from '../../auth/usePermissions'

export type WorklogView =
  | 'entries'
  | 'plan'
  | 'goals'
  | 'daily'
  | 'weekly'
  | 'chat'
  | 'list'

/** CAP-103 工作日志各视图的权限节点（与后端权限目录一致） */
export const WORKLOG_VIEW_PERMISSIONS: Record<WorklogView, string> = {
  entries: 'top.worklog.tab.entries',
  plan: 'top.worklog.tab.plan',
  goals: 'top.worklog.tab.goals',
  daily: 'top.worklog.tab.daily',
  weekly: 'top.worklog.tab.weekly',
  chat: 'top.worklog.tab.chat',
  list: 'top.worklog.tab.list',
}

export const WORKLOG_VIEWS = Object.keys(WORKLOG_VIEW_PERMISSIONS) as WorklogView[]

/** 会话工作台视图（整页渲染 SessionsBoard，值即其 chat/list 视图） */
export const isSessionsView = (v: WorklogView) => v === 'chat' || v === 'list'

const LABELS: Record<WorklogView, string> = {
  entries: '工作条目',
  plan: '计划',
  goals: '目标',
  daily: '日报',
  weekly: '周报',
  chat: '对话',
  list: '对话列表',
}

export default function WorklogViewSwitch({
  value,
  onChange,
}: {
  value: WorklogView
  onChange: (v: WorklogView) => void
}) {
  const { can } = usePermissions()
  // 根节点必须 width:100% + minWidth:0：Card title 是 flex:1 + overflow:hidden，
  // ScrollRow 要靠这个确定的外边界计算可滚区间（Space 是 inline-flex 撑不满，不能用）
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, width: '100%', minWidth: 0 }}>
      <span style={{ flex: 'none' }}>工作日志</span>
      <ScrollRow activeSelector=".ant-segmented-item-selected">
        <Segmented
          value={value}
          onChange={(v) => onChange(v as WorklogView)}
          options={WORKLOG_VIEWS.filter((v) => can(WORKLOG_VIEW_PERMISSIONS[v])).map((v) => ({
            value: v,
            label: LABELS[v],
          }))}
        />
      </ScrollRow>
    </div>
  )
}
