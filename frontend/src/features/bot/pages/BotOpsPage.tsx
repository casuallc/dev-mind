import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button, Card, Segmented, Space } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import InteractionsPanel from '../components/InteractionsPanel'
import BindingsPanel from '../components/BindingsPanel'
import UserMappingsPanel from '../components/UserMappingsPanel'
import CopilotPanel from '../components/CopilotPanel'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import { usePermittedView } from '../../auth/usePermissions'

/**
 * CAP-75 M3 机器人运维页（仅 ADMIN）：飞书等 IM 机器人的运行观测与治理。
 * 页内 Segmented 视图（随 URL ?view=… 走，刷新后停在原页签）——
 * 「交互日志」（全量留痕，含门禁拒绝/去重）、「群绑定」（会话→项目/问答，可解绑）、
 * 「用户映射」（飞书 open_id ↔ 平台用户，可解绑）、「智能体」（CAP-76 网关运行时配置只读视图）。
 */
type BotView = 'interactions' | 'bindings' | 'mappings' | 'copilot'
const VIEWS: BotView[] = ['interactions', 'bindings', 'mappings', 'copilot']
const VIEW_LABELS: Record<BotView, string> = {
  interactions: '交互日志',
  bindings: '群绑定',
  mappings: '用户映射',
  copilot: '智能体',
}
const VIEW_PERMISSIONS: Record<BotView, string> = {
  interactions: 'admin.bot.tab.interactions',
  bindings: 'admin.bot.tab.bindings',
  mappings: 'admin.bot.tab.mappings',
  copilot: 'admin.bot.tab.copilot',
}

export default function BotOpsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const rawView = searchParams.get('view') as BotView | null
  const view: BotView = rawView && VIEWS.includes(rawView) ? rawView : 'interactions'
  const switchView = (v: BotView) =>
    setSearchParams(v === 'interactions' ? {} : { view: v }, { replace: true })
  const views = usePermittedView(VIEWS, (v) => VIEW_PERMISSIONS[v], view, switchView)
  // 各面板内部自管加载，刷新信号用 tick 递增（分页语义下面板不轮询）
  const [refreshTick, setRefreshTick] = useState(0)

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>机器人运维</span>
          <Segmented
            value={view}
            onChange={(v) => switchView(v as BotView)}
            options={views.map((v) => ({ value: v, label: VIEW_LABELS[v] }))}
          />
        </Space>
      }
      extra={
        <Button icon={<ReloadOutlined />} onClick={() => setRefreshTick((t) => t + 1)}>
          刷新
        </Button>
      }
    >
      {view === 'interactions' && <InteractionsPanel refreshTick={refreshTick} />}
      {view === 'bindings' && <BindingsPanel refreshTick={refreshTick} />}
      {view === 'mappings' && <UserMappingsPanel refreshTick={refreshTick} />}
      {view === 'copilot' && <CopilotPanel refreshTick={refreshTick} />}
    </Card>
  )
}
