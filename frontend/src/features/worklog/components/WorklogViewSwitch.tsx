// 工作日志页视图切换器：工作日志主卡与会话/知识内嵌页共用同一 Segmented（布局约定：多视图切换放 Card title）。
// 会话的「对话/列表」提为顶层视图（WorklogPage 受控传给 SessionsBoard），避免 title 里叠两层 Segmented。
import { Segmented, Space } from 'antd'

export type WorklogView = 'entries' | 'daily' | 'weekly' | 'chat' | 'list' | 'context'

/** 会话类视图（整页渲染 SessionsBoard） */
export const isSessionsView = (v: WorklogView) => v === 'chat' || v === 'list'

export default function WorklogViewSwitch({
  value,
  onChange,
}: {
  value: WorklogView
  onChange: (v: WorklogView) => void
}) {
  return (
    <Space size={12}>
      <span>工作日志</span>
      <Segmented
        value={value}
        onChange={(v) => onChange(v as WorklogView)}
        options={[
          { value: 'entries', label: '工作条目' },
          { value: 'daily', label: '日报' },
          { value: 'weekly', label: '周报' },
          { value: 'chat', label: '对话' },
          { value: 'list', label: '列表' },
          { value: 'context', label: '知识' },
        ]}
      />
    </Space>
  )
}
