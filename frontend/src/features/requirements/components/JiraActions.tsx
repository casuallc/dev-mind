// 「Jira 操作」下拉（CAP-19 FR-08）：仅 JIRA 来源需求渲染，动态列出 issue 当前可用的
// 工作流转换（名称随实例工作流），确认后回写 Jira；本地需求状态不受影响。
// 拉取失败（无关联/集成禁用/权限不足）或无可用转换时静默不渲染。
import { useEffect, useState } from 'react'
import { Dropdown, Modal, Space, Tag, message } from 'antd'
import { DownOutlined, SwapOutlined } from '@ant-design/icons'
import type { MenuProps } from 'antd'
import { listJiraTransitions, transitionJiraIssue } from '../api'
import type { JiraTransition, Requirement } from '../types'

export default function JiraActions({ requirement, onChanged }: {
  requirement: Requirement
  onChanged: () => void
}) {
  const [transitions, setTransitions] = useState<JiraTransition[] | null>(null)
  const [busy, setBusy] = useState(false)
  const pid = requirement.projectId
  const rid = requirement.id

  useEffect(() => {
    if (requirement.source !== 'JIRA') return
    listJiraTransitions(pid, rid)
      .then(setTransitions)
      .catch(() => setTransitions(null)) // 静默降级：无关联/权限不足时不显示入口
  }, [pid, rid, requirement.source, requirement.remoteStatus])

  if (!transitions || transitions.length === 0) return null

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

  const items: MenuProps['items'] = transitions.map((t) => ({
    key: t.id,
    label: (
      <Space size={6}>
        <span>{t.name}</span>
        {t.toStatus && <Tag style={{ fontSize: 11, lineHeight: '16px', marginInlineEnd: 0 }}>{t.toStatus}</Tag>}
      </Space>
    ),
  }))

  return (
    <Dropdown.Button
      icon={<DownOutlined />}
      loading={busy}
      menu={{ items, onClick: ({ key }) => {
        const t = transitions.find((x) => x.id === key)
        if (t) confirmTransit(t)
      } }}
      onClick={() => confirmTransit(transitions[0])}
    >
      <SwapOutlined /> Jira 操作
    </Dropdown.Button>
  )
}
