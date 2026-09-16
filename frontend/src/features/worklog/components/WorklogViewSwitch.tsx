// 工作日志页视图切换器：工作日志主卡与会话/知识内嵌页共用同一 Segmented（布局约定：多视图切换放 Card title）。
// 「对话/对话列表」（SessionsBoard）与「知识条目/文档/Skills」（ProjectContextPage，值=资产 kind）
// 全部提为顶层视图，title 里只放这一个并列 Segmented，内层切换器受控隐藏。
import { Segmented, Space } from 'antd'

export type WorklogView =
  | 'entries'
  | 'daily'
  | 'weekly'
  | 'chat'
  | 'list'
  | 'knowledge'
  | 'doc'
  | 'skill'

/** 会话工作台视图（整页渲染 SessionsBoard，值即其 chat/list 视图） */
export const isSessionsView = (v: WorklogView) => v === 'chat' || v === 'list'
/** 知识视图（整页渲染 ProjectContextPage，值即其资产 kind） */
export const isContextView = (v: WorklogView) => v === 'knowledge' || v === 'doc' || v === 'skill'

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
          { value: 'list', label: '对话列表' },
          { value: 'knowledge', label: '知识条目' },
          { value: 'doc', label: '文档' },
          { value: 'skill', label: 'Skills' },
        ]}
      />
    </Space>
  )
}
