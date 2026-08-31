// 流程阶段动作（CAP-14）：按需求当前状态给出下一步操作，产出就绪后由人确认推进。
// 只做触发与提示；状态/门禁校验以后端为准（失败直接弹出后端消息）。
import { useState } from 'react'
import { Button, Space, message } from 'antd'
import {
  ApartmentOutlined,
  FileSearchOutlined,
  FileDoneOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons'
import { flowAnalyze, flowDesign, flowSplit } from '../../api'
import type { Requirement } from '../../types'
import SplitDraftDrawer from './SplitDraftDrawer'

export default function FlowActions({ requirement, onChanged }: {
  requirement: Requirement
  onChanged: () => void
}) {
  const [busy, setBusy] = useState<string | null>(null)
  const [draftOpen, setDraftOpen] = useState(false)
  const pid = requirement.projectId
  const rid = requirement.id

  const run = async (key: string, label: string, fn: () => Promise<{ id: string }>) => {
    setBusy(key)
    try {
      await fn()
      message.success(`${label}会话已启动，完成后会通知你确认产出`)
      onChanged()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusy(null)
    }
  }

  const status = requirement.status
  const canAnalyze = status === 'DRAFT' || status === 'ANALYZING'
  const canDesign = status === 'ANALYZING' || status === 'DESIGNING'
  const canSplit = status === 'ANALYZING' || status === 'DESIGNING'
  if (!canAnalyze && !canDesign && !canSplit) {
    return null
  }

  return (
    <Space wrap size={8}>
      {canAnalyze && (
        <Button
          size="small"
          type="primary"
          icon={<FileSearchOutlined />}
          loading={busy === 'analyze'}
          onClick={() => run('analyze', '分析', () => flowAnalyze(pid, rid))}
        >
          {status === 'DRAFT' ? '开始分析' : '重新分析'}
        </Button>
      )}
      {canDesign && (
        <Button
          size="small"
          icon={<FileDoneOutlined />}
          loading={busy === 'design'}
          onClick={() => run('design', '方案设计', () => flowDesign(pid, rid))}
        >
          生成方案（AI）
        </Button>
      )}
      {canSplit && (
        <Button
          size="small"
          icon={<ApartmentOutlined />}
          loading={busy === 'split'}
          onClick={() => run('split', '拆分', () => flowSplit(pid, rid))}
        >
          AI 拆分工作单元
        </Button>
      )}
      {(status === 'ANALYZING' || status === 'DESIGNING') && (
        <Button size="small" icon={<PlayCircleOutlined />} onClick={() => setDraftOpen(true)}>
          拆分草稿
        </Button>
      )}
      <SplitDraftDrawer
        projectId={pid}
        requirementId={rid}
        open={draftOpen}
        onClose={() => setDraftOpen(false)}
        onConfirmed={onChanged}
      />
    </Space>
  )
}
