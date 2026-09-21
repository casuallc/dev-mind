// 需求分析 Tab（CAP-52）：**只读展示**——规划会话产出的分析文档预览 + 会话状态。
// 起会话/跳过阶段的入口已收敛到需求详情页头卡「开启 AI 规划」：三份产出（分析/方案/工作单元）
// 由同一个会话一次产出，要改就整段重跑，所以这里不再有「开始/重新分析/跳过分析」。
import { Button, Card, Space, Tag, Typography } from 'antd'
import { FileSearchOutlined } from '@ant-design/icons'
import type { Requirement, RequirementOverview } from '../../types'
import DocPreviewModal from './DocPreviewModal'
import { ACTIVE_SESSION_STATES, SessionTag, latestFlowSession } from './flowSessions'
import { useDesignActions } from './useDesignActions'

export default function AnalysisTab({ projectId, requirement, overview, onChanged }: {
  projectId: string
  requirement: Requirement
  overview: RequirementOverview
  onChanged: () => void
}) {
  const { preview, closePreview, previewDoc } = useDesignActions(projectId, requirement.id, onChanged)

  const doc = overview.docs
    .filter((d) => d.kind === 'analysis')
    .sort((a, b) => b.id - a.id)[0]
  // 规划会话（CAP-52）与存量分析会话（CAP-38）都算这个 Tab 的来源
  const session = latestFlowSession(overview.sessions, '[flow:plan]')
    ?? latestFlowSession(overview.sessions, '[flow:analyze]')
  const sessionActive = !!session && ACTIVE_SESSION_STATES.includes(session.status)

  const stage = doc
    ? { label: '已完成', color: 'success' }
    : sessionActive
      ? { label: '规划进行中', color: 'processing' }
      : { label: '未开始', color: 'default' }

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
          </Space>
        }
      >
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          AI 规划会话产出的影响面/复杂度/风险分析（重新规划在原文档追加新版本），结论已注入同一会话里的方案与工作单元拆分。
          起会话请用页面右上角「开启 AI 规划」。
        </Typography.Text>
      </Card>
      <DocPreviewModal preview={preview} onClose={closePreview} />
    </Space>
  )
}
