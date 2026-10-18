// CAP-76 FR-30/32 智能体运维页（仅 ADMIN）：工具目录 / 对话审计 / 确认单 三视图，
// 顶部健康条只报告装配与积压（不调用模型）。
import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button, Card, Segmented, Space, Tag, Tooltip, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { getCopilotHealth } from '../api'
import type { CopilotHealth } from '../types'
import ToolsPanel from '../components/ToolsPanel'
import TurnsPanel from '../components/TurnsPanel'
import ActionsPanel from '../components/ActionsPanel'
import { showError } from '../../../shared/utils/showError'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'

type CopilotView = 'tools' | 'turns' | 'actions'
const VIEWS: CopilotView[] = ['tools', 'turns', 'actions']

export default function CopilotOpsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const rawView = searchParams.get('view') as CopilotView | null
  const view: CopilotView = rawView && VIEWS.includes(rawView) ? rawView : 'tools'
  const switchView = (v: string | number) =>
    setSearchParams(v === 'tools' ? {} : { view: String(v) }, { replace: true })

  const [refreshTick, setRefreshTick] = useState(0)
  const [health, setHealth] = useState<CopilotHealth | null>(null)

  const loadHealth = useCallback(async () => {
    try {
      setHealth(await getCopilotHealth())
    } catch (e) {
      showError(e, '加载智能体健康状态失败')
    }
  }, [])

  useEffect(() => { loadHealth() }, [loadHealth, refreshTick])

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>智能体运维</span>
          <Segmented
            value={view}
            onChange={switchView}
            options={[
              { value: 'tools', label: '工具目录' },
              { value: 'turns', label: '对话审计' },
              { value: 'actions', label: '确认单' },
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
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
          平台智能体网关的工具契约、对话审计与写操作确认单。开关与端点等运行参数在机器人运维页「智能体」视图查看。
        </Typography.Paragraph>
        <Space style={{ marginBottom: 12 }} wrap>
          {health ? (
            <>
              <Tag color={health.enabled ? 'success' : 'default'}>
                {health.enabled ? '智能体已启用' : '智能体已关闭'}
              </Tag>
              <Tooltip title={health.endpointFailure || (health.endpointConfigured ? 'CHAT 端点配置完整' : '未配置 CHAT 端点')}>
                <Tag color={health.endpointConfigured ? 'blue' : 'warning'}>
                  {health.endpointConfigured ? '端点已配置' : '端点未配置'}
                </Tag>
              </Tooltip>
              <Tag>{health.tools} 个工具</Tag>
              <Tag color={health.policyPresent ? 'blue' : 'default'}>
                {health.policyPresent ? '数据权限已接入' : '仅 RBAC（数据权限未启用）'}
              </Tag>
              <Tag color={health.pendingActions > 0 ? 'processing' : 'default'}>
                待确认 {health.pendingActions}
              </Tag>
              <Tag color={health.pendingToolConfirmations > 0 ? 'processing' : 'default'}>
                工具待确认 {health.pendingToolConfirmations}
              </Tag>
              <Tag color={health.executingActions > 0 ? 'processing' : 'default'}>
                执行中 {health.executingActions}
              </Tag>
            </>
          ) : (
            <Tag>健康状态加载中…</Tag>
          )}
        </Space>
        {view === 'tools' && <ToolsPanel refreshTick={refreshTick} />}
        {view === 'turns' && <TurnsPanel refreshTick={refreshTick} />}
        {view === 'actions' && <ActionsPanel refreshTick={refreshTick} />}
      </div>
    </Card>
  )
}
