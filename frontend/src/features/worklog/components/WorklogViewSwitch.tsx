// 工作日志页视图切换器：工作日志主卡与会话/知识内嵌页共用同一 Segmented（布局约定：多视图切换放 Card title）。
import { Segmented, Space } from 'antd'

export type WorklogView = 'entries' | 'daily' | 'weekly' | 'sessions' | 'context'

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
          { value: 'sessions', label: '会话' },
          { value: 'context', label: '知识' },
        ]}
      />
    </Space>
  )
}
