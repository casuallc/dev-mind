import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button, Card, Segmented, Space } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import InteractionsPanel from '../components/InteractionsPanel'
import BindingsPanel from '../components/BindingsPanel'
import UserMappingsPanel from '../components/UserMappingsPanel'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'

/**
 * CAP-75 M3 机器人运维页（仅 ADMIN）：飞书等 IM 机器人的运行观测与治理。
 * 页内 Segmented 三视图（随 URL ?view=… 走，刷新后停在原页签）——
 * 「交互日志」（全量留痕，含门禁拒绝/去重）、「群绑定」（会话→项目/问答，可解绑）、
 * 「用户映射」（飞书 open_id ↔ 平台用户，可解绑）。
 */
type BotView = 'interactions' | 'bindings' | 'mappings'
const VIEWS: BotView[] = ['interactions', 'bindings', 'mappings']

export default function BotOpsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const rawView = searchParams.get('view') as BotView | null
  const view: BotView = rawView && VIEWS.includes(rawView) ? rawView : 'interactions'
  const switchView = (v: string | number) =>
    setSearchParams(v === 'interactions' ? {} : { view: String(v) }, { replace: true })
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
            onChange={switchView}
            options={[
              { value: 'interactions', label: '交互日志' },
              { value: 'bindings', label: '群绑定' },
              { value: 'mappings', label: '用户映射' },
            ]}
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
    </Card>
  )
}
