// 流程阶段动作（CAP-14）：按需求当前状态给出「主按钮 + 下拉收次要操作」，产出就绪后由人确认推进。
// 主按钮=当前阶段下一步（DRAFT 开始分析 / ANALYZING 生成方案 / DESIGNING AI 拆分 / ACCEPTANCE 验收通过）。
// 只做触发与提示；状态/门禁校验以后端为准（失败直接弹出后端消息）。
import { useState } from 'react'
import { Button, Dropdown, Modal, message } from 'antd'
import {
  ApartmentOutlined,
  CheckOutlined,
  DownOutlined,
  FileSearchOutlined,
  FileDoneOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons'
import type { MenuProps } from 'antd'
import { flowAnalyze, flowDesign, flowSplit, updateRequirementStatus } from '../../api'
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

  // 人工验收（CAP-13）：ACCEPTANCE→DONE 是终态翻转，非起会话，不走 run()
  const confirmAccept = () => {
    Modal.confirm({
      centered: true,
      title: '验收通过？',
      content: `「${requirement.code} ${requirement.title}」将标记为 DONE，工作单元与关联记录保留。`,
      okText: '验收通过',
      cancelText: '返回',
      onOk: async () => {
        try {
          await updateRequirementStatus(pid, rid, 'DONE')
          message.success(`${requirement.code} → DONE`)
          onChanged()
        } catch (e) {
          message.error((e as Error).message)
        }
      },
    })
  }

  const status = requirement.status
  const analyze = { key: 'analyze', label: status === 'DRAFT' ? '开始分析' : '重新分析', icon: <FileSearchOutlined />, fn: () => flowAnalyze(pid, rid) }
  const design = { key: 'design', label: '生成方案（AI）', icon: <FileDoneOutlined />, fn: () => flowDesign(pid, rid) }
  const split = { key: 'split', label: 'AI 拆分工作单元', icon: <ApartmentOutlined />, fn: () => flowSplit(pid, rid) }

  // 主操作与次要操作按状态映射；IN_PROGRESS/DONE/CANCELLED 无流程动作
  let primary: { label: string; icon: React.ReactNode; loading: boolean; onClick: () => void } | null = null
  let menuItems: MenuProps['items'] = []
  switch (status) {
    case 'DRAFT':
      primary = { label: analyze.label, icon: analyze.icon, loading: busy === 'analyze', onClick: () => run('analyze', '分析', analyze.fn) }
      break
    case 'ANALYZING':
      primary = { label: design.label, icon: design.icon, loading: busy === 'design', onClick: () => run('design', '方案设计', design.fn) }
      menuItems = [
        { key: 'analyze', label: analyze.label, icon: analyze.icon },
        { key: 'split', label: split.label, icon: split.icon },
        { key: 'draft', label: '拆分草稿', icon: <PlayCircleOutlined /> },
      ]
      break
    case 'DESIGNING':
      primary = { label: split.label, icon: split.icon, loading: busy === 'split', onClick: () => run('split', '拆分', split.fn) }
      menuItems = [
        { key: 'design', label: design.label, icon: design.icon },
        { key: 'analyze', label: analyze.label, icon: analyze.icon },
        { key: 'draft', label: '拆分草稿', icon: <PlayCircleOutlined /> },
      ]
      break
    case 'ACCEPTANCE':
      primary = { label: '验收通过', icon: <CheckOutlined />, loading: false, onClick: confirmAccept }
      break
    default:
      return null
  }

  const onMenuClick: MenuProps['onClick'] = ({ key }) => {
    if (key === 'analyze') run('analyze', '分析', analyze.fn)
    else if (key === 'design') run('design', '方案设计', design.fn)
    else if (key === 'split') run('split', '拆分', split.fn)
    else if (key === 'draft') setDraftOpen(true)
  }

  return (
    <>
      {menuItems.length > 0 ? (
        <Dropdown.Button
          type="primary"
          icon={<DownOutlined />}
          loading={primary.loading}
          menu={{ items: menuItems, onClick: onMenuClick }}
          onClick={primary.onClick}
        >
          {primary.icon} {primary.label}
        </Dropdown.Button>
      ) : (
        <Button type="primary" icon={primary.icon} loading={primary.loading} onClick={primary.onClick}>
          {primary.label}
        </Button>
      )}
      <SplitDraftDrawer
        projectId={pid}
        requirementId={rid}
        open={draftOpen}
        onClose={() => setDraftOpen(false)}
        onConfirmed={onChanged}
      />
    </>
  )
}
