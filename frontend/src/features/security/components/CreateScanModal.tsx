// 发起扫描弹窗（FR-01）：仓库/分支/引擎多选/执行节点。
// 表单 ≤4 字段用 Modal（布局约定）。
import { Checkbox, Input, Modal, Select, Space, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import { createScan } from '../api'
import { listRepos } from '../../projects/api'
import type { ProjectRepo } from '../../projects/types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { showError } from '../../../shared/utils/showError'

interface Props {
  projectId: string
  open: boolean
  onClose: () => void
  onCreated: () => void
}

const ENGINE_OPTIONS = [
  { value: 'sca', label: 'SCA 依赖漏洞（dependency-check）' },
  { value: 'sast', label: 'SAST 代码扫描（semgrep）' },
  { value: 'secrets', label: '密钥泄漏（gitleaks）' },
]

export default function CreateScanModal({ projectId, open, onClose, onCreated }: Props) {
  const [repos, setRepos] = useState<ProjectRepo[]>([])
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [repoId, setRepoId] = useState<number | undefined>()
  const [branch, setBranch] = useState('')
  const [engines, setEngines] = useState<string[]>(['sca', 'sast', 'secrets'])
  const [agentNodeId, setAgentNodeId] = useState<string | undefined>()
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!open) return
    listRepos(projectId)
      .then((rs) => {
        const code = rs.filter((r) => r.role === 'CODE')
        setRepos(code)
        setRepoId(code.find((r) => r.primary)?.id ?? code[0]?.id)
      })
      .catch(() => {})
    listAgentNodes()
      .then((ns) => setNodes(ns.filter((n) => n.status === 'ONLINE')))
      .catch(() => {})
  }, [open, projectId])

  const onSubmit = async () => {
    if (engines.length === 0) {
      message.warning('至少勾选一个扫描引擎')
      return
    }
    setSubmitting(true)
    try {
      const s = await createScan(projectId, {
        repoId,
        branch: branch.trim() || undefined,
        engines,
        agentNodeId,
      })
      message.success(`扫描 #${s.id} 已发起`)
      setBranch('')
      onCreated()
      onClose()
    } catch (e) {
      showError(e)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title="发起安全扫描"
      open={open}
      onCancel={onClose}
      onOk={onSubmit}
      okText="发起扫描"
      confirmLoading={submitting}
      width={520}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <div>
          <Typography.Text type="secondary">仓库（留空 = 主仓库）</Typography.Text>
          <Select<number>
            style={{ width: '100%' }}
            value={repoId}
            onChange={setRepoId}
            options={repos.map((r) => ({
              value: r.id,
              label: `${r.name}${r.primary ? ' · 主仓库' : ''}`,
            }))}
          />
        </div>
        <Input
          placeholder="分支（留空 = 仓库默认分支）"
          value={branch}
          onChange={(e) => setBranch(e.target.value)}
        />
        <div>
          <Typography.Text type="secondary">扫描引擎</Typography.Text>
          <div>
            <Checkbox.Group options={ENGINE_OPTIONS} value={engines} onChange={(v) => setEngines(v as string[])} />
          </div>
        </div>
        <div>
          <Typography.Text type="secondary">执行节点（引擎二进制须在节点可用，可在后台节点管理做工具自检）</Typography.Text>
          <Select<string>
            style={{ width: '100%' }}
            allowClear
            placeholder="留空 = 默认路由（项目默认 → 平台默认）"
            value={agentNodeId}
            onChange={setAgentNodeId}
            options={nodes.map((n) => ({
              value: String(n.id),
              label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}`,
            }))}
          />
        </div>
      </Space>
    </Modal>
  )
}
