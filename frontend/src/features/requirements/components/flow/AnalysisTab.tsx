// 需求分析 Tab（CAP-38 FR-02）：独立阶段 Tab——阶段状态 + 开始/重新分析 + 跳过分析 + 分析文档预览。
// 流程不可逆引导：分析完成（有分析文档）或跳过后，「方案设计」Tab 的生成动作才解锁。
import { useState } from 'react'
import { Button, Card, Popconfirm, Space, Tag, Typography, message } from 'antd'
import { FileSearchOutlined } from '@ant-design/icons'
import { flowAnalyze, flowSkip } from '../../api'
import type { Requirement, RequirementOverview } from '../../types'
import { showError } from '../../../../shared/utils/showError'
import DocPreviewModal from './DocPreviewModal'
import { ACTIVE_SESSION_STATES, SessionTag, latestFlowSession } from './flowSessions'
import { useDesignActions } from './useDesignActions'

export default function AnalysisTab({ projectId, requirement, overview, onChanged }: {
  projectId: string
  requirement: Requirement
  overview: RequirementOverview
  onChanged: () => void
}) {
  const [busy, setBusy] = useState(false)
  const { preview, closePreview, previewDoc } = useDesignActions(projectId, requirement.id, onChanged)

  const doc = overview.docs
    .filter((d) => d.kind === 'analysis')
    .sort((a, b) => b.id - a.id)[0]
  const session = latestFlowSession(overview.sessions, '[flow:analyze]')
  const sessionActive = !!session && ACTIVE_SESSION_STATES.includes(session.status)
  const skipped = !!requirement.analysisSkipped

  const stage = doc
    ? { label: '已完成', color: 'success' }
    : skipped
      ? { label: '已跳过', color: 'default' }
      : sessionActive
        ? { label: '进行中', color: 'processing' }
        : { label: '未开始', color: 'default' }

  const run = async (okMsg: string, fn: () => Promise<unknown>) => {
    setBusy(true)
    try {
      await fn()
      message.success(okMsg)
      onChanged()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card
        size="small"
        title={<Space size={8}><FileSearchOutlined />需求分析<Tag color={stage.color}>{stage.label}</Tag></Space>}
        extra={
          <Space size={8}>
            <SessionTag s={session} />
            {doc && (
              <Button size="small" onClick={() => previewDoc(doc.id, `需求分析 · ${doc.title}`)}>
                查看分析（v{doc.currentVersion}）
              </Button>
            )}
            {!doc && !skipped && (
              <Popconfirm
                title="跳过需求分析？"
                description="跳过后不可恢复（流程不可逆），方案设计将直接解锁"
                okText="跳过"
                cancelText="返回"
                onConfirm={() => run('已跳过需求分析', () => flowSkip(projectId, requirement.id, 'analysis'))}
              >
                <Button size="small" disabled={sessionActive}>跳过分析</Button>
              </Popconfirm>
            )}
            <Button
              size="small"
              type={doc ? 'default' : 'primary'}
              loading={busy}
              disabled={sessionActive}
              onClick={() => run('分析会话已启动，完成后会通知你', () => flowAnalyze(projectId, requirement.id))}
            >
              {doc ? '重新分析' : '开始分析'}
            </Button>
          </Space>
        }
      >
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          AI 分析影响面/复杂度/风险，产出落成「需求分析」文档（重新分析在原文档追加新版本），结论自动注入后续方案设计与拆分。
          简单需求可跳过分析；分析完成或跳过后解锁方案设计。
        </Typography.Text>
      </Card>
      <DocPreviewModal preview={preview} onClose={closePreview} />
    </Space>
  )
}
