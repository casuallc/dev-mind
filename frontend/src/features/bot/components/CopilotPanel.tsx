// CAP-76 机器人运维页「智能体」视图：网关开关、端点、工具模式、预算与保留期。
// 只读展示 application.yml 的运行时值（配置保存能力留待后续评估，避免与 yml 双事实源）。
import { useCallback, useEffect, useState } from 'react'
import { Alert, Descriptions, Space, Tag, Typography } from 'antd'
import { getCopilotConfig, getCopilotHealth } from '../api'
import type { CopilotConfigView, CopilotHealthView } from '../types'
import { showError } from '../../../shared/utils/showError'
import { pagePaneScrollStyle } from '../../../shared/utils/pageLayout'

export default function CopilotPanel({ refreshTick = 0 }: { refreshTick?: number }) {
  const [config, setConfig] = useState<CopilotConfigView | null>(null)
  const [health, setHealth] = useState<CopilotHealthView | null>(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [c, h] = await Promise.all([getCopilotConfig(), getCopilotHealth()])
      setConfig(c)
      setHealth(h)
    } catch (e) {
      showError(e, '加载智能体配置失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load, refreshTick])

  return (
    <div style={pagePaneScrollStyle}>
      <Space direction="vertical" style={{ width: '100%' }} size={16}>
        <Alert
          type="info"
          showIcon
          message="运行参数来自 application.yml"
          description="此处只读展示当前生效值；修改请编辑配置（application.yml / application-local.yml）并重启服务。"
        />

        <Space wrap>
          {health ? (
            <>
              <Tag color={health.enabled ? 'success' : 'default'}>
                {health.enabled ? '智能体已启用' : '智能体已关闭'}
              </Tag>
              <Tag color={health.endpointConfigured ? 'blue' : 'warning'}>
                {health.endpointConfigured ? '端点已配置' : '端点未配置'}
              </Tag>
              <Tag>{health.tools} 个工具</Tag>
              <Tag color={health.policyPresent ? 'blue' : 'default'}>
                {health.policyPresent ? '数据权限已接入' : '仅 RBAC'}
              </Tag>
              <Tag color={health.pendingActions > 0 ? 'processing' : 'default'}>
                待确认 {health.pendingActions}
              </Tag>
              <Tag color={health.executingActions > 0 ? 'processing' : 'default'}>
                执行中 {health.executingActions}
              </Tag>
            </>
          ) : (
            <Tag>{loading ? '加载中…' : '健康状态不可用'}</Tag>
          )}
        </Space>

        {health?.endpointFailure && (
          <Alert type="warning" showIcon message="端点配置不完整" description={health.endpointFailure} />
        )}
        {health && !health.policyPresent && (
          <Alert
            type="info"
            showIcon
            message="数据权限未启用"
            description="当前仅按角色（RBAC）过滤工具；CAP-62 ProjectAccessChecker 接入后自动收紧到项目级。"
          />
        )}
        {config?.allowDefaultActorWrites && (
          <Alert
            type="error"
            showIcon
            message="允许默认身份执行写操作"
            description="devmind.copilot.allow-default-actor-writes=true：未绑定个人身份的用户也能让智能体执行写操作，请确认这是预期配置。"
          />
        )}

        {config && (
          <Descriptions title="运行时配置" column={2} bordered size="small">
            <Descriptions.Item label="开关">{config.enabled ? '已启用' : '已关闭'}</Descriptions.Item>
            <Descriptions.Item label="CHAT 端点">
              {config.endpointId ? config.endpointId : '平台默认端点'}
            </Descriptions.Item>
            <Descriptions.Item label="工具模式">{config.toolMode}</Descriptions.Item>
            <Descriptions.Item label="单轮最大步数">{config.maxSteps}</Descriptions.Item>
            <Descriptions.Item label="每步最大工具调用">{config.maxToolCallsPerStep}</Descriptions.Item>
            <Descriptions.Item label="单轮最大写操作">{config.maxWritesPerTurn}</Descriptions.Item>
            <Descriptions.Item label="单轮时限">{config.turnTimeoutSeconds}s</Descriptions.Item>
            <Descriptions.Item label="模型调用超时">{config.modelTimeoutSeconds}s</Descriptions.Item>
            <Descriptions.Item label="工具超时">{config.toolTimeoutSeconds}s</Descriptions.Item>
            <Descriptions.Item label="确认单有效期">{config.confirmTtlMinutes} 分钟</Descriptions.Item>
            <Descriptions.Item label="确认预览分段">
              {config.confirmPreviewChunkChars} 字 × {config.confirmPreviewMaxChunks} 段
            </Descriptions.Item>
            <Descriptions.Item label="每人每分钟轮次">{config.maxTurnsPerMinute}</Descriptions.Item>
            <Descriptions.Item label="模型并发上限">{config.maxConcurrentModel}</Descriptions.Item>
            <Descriptions.Item label="进度卡">{config.replyProgressCard ? '开' : '关'}</Descriptions.Item>
            <Descriptions.Item label="允许默认身份写入">
              {config.allowDefaultActorWrites ? '是' : '否'}
            </Descriptions.Item>
            <Descriptions.Item label="审计保留期">{config.auditRetentionDays} 天</Descriptions.Item>
            <Descriptions.Item label="状态收敛 cron">{config.actionSweepCron || '关闭'}</Descriptions.Item>
            <Descriptions.Item label="清理 cron">{config.cleanupCron || '关闭'}</Descriptions.Item>
          </Descriptions>
        )}

        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          工具契约、对话审计与确认单在「智能体运维」页查看。
        </Typography.Paragraph>
      </Space>
    </div>
  )
}
