// 阶段引导卡（CAP-14）：需求详情页的第一视觉焦点，回答「现在到哪了 / 接下来做什么 / 有什么等我确认」。
// 进度行沿用分段 Progress（中文化）；引导语与待确认提醒全部前端推导（overview + designs + split-draft，不改后端）；
// 主按钮复用 FlowActions（状态→阶段动作映射的唯一入口），DONE/CANCELLED 不渲染。
import { useEffect, useState } from 'react'
import { Button, Card, Progress, Space, Tooltip, Typography } from 'antd'
import { CheckCircleOutlined, WarningOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { getSplitDraft } from '../../api'
import FlowActions from './FlowActions'
import SplitDraftDrawer from './SplitDraftDrawer'
import { STATUS_FLOW, STATUS_LABEL, requirementStatusColor } from '../requirementMeta'
import type { Design, Requirement, RequirementOverview } from '../../types'

const STAGE_CHAIN = STATUS_FLOW.map((s) => STATUS_LABEL[s]).join(' → ')

export default function StageGuideCard({ requirement: r, overview, designs, onGotoTab, onChanged }: {
  requirement: Requirement
  overview: RequirementOverview
  designs: Design[]
  /** 切换下方 Tabs（如「去确认方案」→ designs） */
  onGotoTab: (key: string) => void
  onChanged: () => void
}) {
  const navigate = useNavigate()
  const [draftCount, setDraftCount] = useState(0)
  const [draftOpen, setDraftOpen] = useState(false)

  const status = r.status
  // 拆分草稿就绪探测：仅 DESIGNING 阶段需要；无草稿/接口报错均静默视为无
  useEffect(() => {
    if (status !== 'DESIGNING') return
    getSplitDraft(r.projectId, r.id)
      .then((d) => setDraftCount(d?.items?.length ?? 0))
      .catch(() => setDraftCount(0))
  }, [r.projectId, r.id, status, r.updatedAt])

  if (status === 'DONE' || status === 'CANCELLED') return null

  const idx = STATUS_FLOW.indexOf(status)
  const percent = ((idx + 1) / STATUS_FLOW.length) * 100

  // 需求级进行中会话（无 workItemId = 分析/拆分这类直挂需求的流程会话）
  const runningSession = overview.sessions.find(
    (s) => !s.workItemId && (s.status === 'RUNNING' || s.status === 'QUEUED'),
  )
  const hasAnalysis = overview.artifacts.some((a) => a.type === 'ANALYSIS')
  const draftDesigns = designs.filter((d) => d.status === 'DRAFT')
  const confirmedDesigns = designs.filter((d) => d.status === 'CONFIRMED')
  const wiRunning = overview.workItems.filter((w) => w.status === 'IN_PROGRESS').length
  const wiTodo = overview.workItems.filter((w) => w.status === 'TODO').length

  // 各阶段引导语（主按钮统一由 FlowActions 按状态给出；IN_PROGRESS 无流程动作，改给跳转按钮）
  let hint: React.ReactNode = ''
  let primary: React.ReactNode = <FlowActions requirement={r} onChanged={onChanged} />
  const warnings: React.ReactNode[] = []
  switch (status) {
    case 'DRAFT':
      hint = '先让 AI 分析影响面与复杂度，产出后再定方案；简单需求也可直接新建工作单元开干。'
      break
    case 'ANALYZING':
      if (runningSession) {
        hint = '分析会话进行中，完成后会通知你确认产出。'
        primary = (
          <Button onClick={() => navigate(`/sessions/${runningSession.id}`)}>查看会话</Button>
        )
      } else if (hasAnalysis) {
        hint = '分析就绪，下一步生成方案（AI）。'
      } else {
        hint = '尚无分析产物——可等会话完成，或直接生成方案。'
      }
      break
    case 'DESIGNING':
      if (runningSession) {
        hint = '方案/拆分会话进行中，完成后会通知你确认产出。'
        primary = (
          <Button onClick={() => navigate(`/sessions/${runningSession.id}`)}>查看会话</Button>
        )
      } else if (draftDesigns.length > 0) {
        hint = 'AI 方案已产出，先到「方案」确认，再拆分工作单元。'
      } else {
        hint = confirmedDesigns.length > 0
          ? '方案已确认，下一步让 AI 按方案拆分工作单元。'
          : '无方案也可直接拆分——让 AI 按需求内容拆分工作单元。'
      }
      if (draftDesigns.length > 0) {
        warnings.push(
          <Typography.Link key="design" onClick={() => onGotoTab('designs')}>
            <WarningOutlined style={{ color: '#faad14' }} /> 方案 v{draftDesigns[0].version} 待确认
          </Typography.Link>,
        )
      }
      if (draftCount > 0) {
        warnings.push(
          <Typography.Link key="draft" onClick={() => setDraftOpen(true)}>
            <WarningOutlined style={{ color: '#faad14' }} /> 拆分草稿待确认（{draftCount} 项）
          </Typography.Link>,
        )
      }
      break
    case 'IN_PROGRESS':
      hint = `工作单元执行中：${wiRunning} 个进行中 / ${wiTodo} 个待开始。逐个点「起会话」推进，全部完成后进入验收。`
      primary = <Button type="primary" onClick={() => onGotoTab('workItems')}>去执行工作单元</Button>
      break
    case 'ACCEPTANCE':
      hint = '工作单元已全部完成，确认产出无误后验收通过。'
      break
    default:
      break
  }

  return (
    <Card size="small" style={{ background: '#f6f9ff', borderColor: '#d6e4ff' }}>
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Tooltip title={STAGE_CHAIN}>
          <Space size={8} style={{ width: '100%' }}>
            <Progress
              type="line"
              steps={STATUS_FLOW.length}
              percent={percent}
              size="small"
              showInfo={false}
              strokeColor={requirementStatusColor(status)}
              style={{ width: 200, marginBottom: 0 }}
            />
            <Typography.Text strong style={{ fontSize: 13 }}>
              {STATUS_LABEL[status]}阶段 · 第 {idx + 1} 步/共 {STATUS_FLOW.length} 步
            </Typography.Text>
            {hasAnalysis && (
              <Typography.Text type="success" style={{ fontSize: 12 }}>
                <CheckCircleOutlined /> 分析就绪
              </Typography.Text>
            )}
          </Space>
        </Tooltip>
        <Space size={12} wrap>
          <Typography.Text style={{ fontSize: 13 }}>{hint}</Typography.Text>
          {primary}
        </Space>
        {warnings.length > 0 && <Space size={16} wrap>{warnings}</Space>}
      </Space>
      <SplitDraftDrawer
        projectId={r.projectId}
        requirementId={r.id}
        open={draftOpen}
        onClose={() => setDraftOpen(false)}
        onConfirmed={onChanged}
      />
    </Card>
  )
}
