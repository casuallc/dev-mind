// 「Jira 操作」下拉（CAP-19 FR-08 / CAP-27）：仅 JIRA 来源需求渲染，动态列出 issue 当前可用的
// 工作流转换（名称随实例工作流），确认后回写 Jira；菜单底部另有「登记工时」（把工时写进 Jira worklog，
// 默认带出 AI 实际耗时换算的小时数）。本地需求状态不受影响。
// 拉取失败（无关联/集成禁用/权限不足）时静默不渲染。
import { useEffect, useState } from 'react'
import { Dropdown, Input, InputNumber, Modal, Space, Tag, message } from 'antd'
import { ClockCircleOutlined, DownOutlined, SwapOutlined } from '@ant-design/icons'
import type { MenuProps } from 'antd'
import { listJiraTransitions, logJiraWork, transitionJiraIssue } from '../api'
import type { JiraTransition, Requirement } from '../types'

export default function JiraActions({ requirement, onChanged }: {
  requirement: Requirement
  onChanged: () => void
}) {
  const [transitions, setTransitions] = useState<JiraTransition[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [worklogOpen, setWorklogOpen] = useState(false)
  const [hours, setHours] = useState(1)
  const [comment, setComment] = useState('')
  const pid = requirement.projectId
  const rid = requirement.id

  useEffect(() => {
    if (requirement.source !== 'JIRA') return
    listJiraTransitions(pid, rid)
      .then(setTransitions)
      .catch(() => setTransitions(null)) // 静默降级：无关联/权限不足时不显示入口
  }, [pid, rid, requirement.source, requirement.remoteStatus])

  if (!transitions) return null

  const confirmTransit = (t: JiraTransition) => {
    Modal.confirm({
      centered: true,
      title: `执行 Jira 转换「${t.name}」？`,
      content: (
        <Space direction="vertical" size={4}>
          <span>
            将对 Jira issue <b>{requirement.externalKey}</b> 执行「{t.name}」
            {t.toStatus ? <>，目标状态 <Tag>{t.toStatus}</Tag></> : null}。
          </span>
          <span style={{ color: '#888' }}>只回写 Jira 远端，本地需求状态不受影响。</span>
        </Space>
      ),
      okText: '执行转换',
      cancelText: '返回',
      onOk: async () => {
        setBusy(true)
        try {
          const r = await transitionJiraIssue(pid, rid, t.id)
          message.success(`${requirement.externalKey} 已执行「${t.name}」${r.remoteStatus ? ` → ${r.remoteStatus}` : ''}`)
          onChanged()
        } catch (e) {
          message.error((e as Error).message)
        } finally {
          setBusy(false)
        }
      },
    })
  }

  /** 打开登记工时弹窗：默认带出 AI 实际耗时（0.25h 取整），无耗时默认 1h */
  const openWorklog = () => {
    const agent = requirement.agentSeconds
    setHours(agent && agent > 0 ? Math.max(0.25, Math.round(agent / 900) / 4) : 1)
    setComment('')
    setWorklogOpen(true)
  }

  const submitWorklog = async () => {
    setBusy(true)
    try {
      await logJiraWork(pid, rid, hours, comment.trim())
      message.success(`${requirement.externalKey} 已登记工时 ${hours}h`)
      setWorklogOpen(false)
      onChanged()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const items: MenuProps['items'] = [
    ...transitions.map((t) => ({
      key: t.id,
      label: (
        <Space size={6}>
          <span>{t.name}</span>
          {t.toStatus && <Tag style={{ fontSize: 11, lineHeight: '16px', marginInlineEnd: 0 }}>{t.toStatus}</Tag>}
        </Space>
      ),
    })),
    ...(transitions.length > 0 ? [{ type: 'divider' as const }] : []),
    { key: '__worklog', icon: <ClockCircleOutlined />, label: '登记工时' },
  ]

  return (
    <>
      <Dropdown.Button
        icon={<DownOutlined />}
        loading={busy}
        menu={{ items, onClick: ({ key }) => {
          if (key === '__worklog') return openWorklog()
          const t = transitions.find((x) => x.id === key)
          if (t) confirmTransit(t)
        } }}
        onClick={() => (transitions.length > 0 ? confirmTransit(transitions[0]) : openWorklog())}
      >
        <SwapOutlined /> Jira 操作
      </Dropdown.Button>
      <Modal
        centered
        open={worklogOpen}
        title={`登记工时 → ${requirement.externalKey}`}
        okText="登记"
        cancelText="返回"
        confirmLoading={busy}
        onOk={submitWorklog}
        onCancel={() => setWorklogOpen(false)}
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <div>
            工时（小时）{' '}
            <InputNumber
              min={0.25}
              step={0.25}
              value={hours}
              onChange={(v) => setHours(v ?? 0.25)}
              style={{ width: 120 }}
            />
            {requirement.agentSeconds ? (
              <span style={{ color: '#888', marginLeft: 8 }}>
                默认 = AI 实际耗时
              </span>
            ) : null}
          </div>
          <Input
            placeholder="备注（可选，写入 Jira worklog comment）"
            value={comment}
            maxLength={200}
            onChange={(e) => setComment(e.target.value)}
          />
          <span style={{ color: '#888' }}>只回写 Jira 远端（timeSpent 随即刷新），本地需求状态不受影响。</span>
        </Space>
      </Modal>
    </>
  )
}
