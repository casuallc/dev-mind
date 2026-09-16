// 工作日志页视图切换器：工作日志主卡与会话/知识内嵌页共用同一 Segmented（布局约定：多视图切换放 Card title）。
// 「对话/对话列表」（SessionsBoard）与「知识条目/文档/Skills」（ProjectContextPage，值=资产 kind）
// 全部提为顶层视图；Segmented 包在 ScrollRow 里——页签超宽时横向滑动，不被 extra 挤压截断。
import { Segmented } from 'antd'
import ScrollRow from '../../../shared/components/ScrollRow'

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
  // 根节点必须 width:100% + minWidth:0：Card title 是 flex:1 + overflow:hidden，
  // ScrollRow 要靠这个确定的外边界计算可滚区间（Space 是 inline-flex 撑不满，不能用）
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, width: '100%', minWidth: 0 }}>
      <span style={{ flex: 'none' }}>工作日志</span>
      <ScrollRow activeSelector=".ant-segmented-item-selected">
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
      </ScrollRow>
    </div>
  )
}
