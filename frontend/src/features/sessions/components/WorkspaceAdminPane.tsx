// 会话页「工作区」视图：项目内节点固定工作区（worktree + 本地分支）与远端 feature 分支的统一管理。
// 工作区行 = 服务端按 <workspaceKey>@<owner> 聚合的会话桶；操作走会话级端点（收口/清理/收口记录）。
// 远端分支 = 服务端 git ls-remote 直查（无需节点在线），删除走服务端 push --delete。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Button, Checkbox, Modal, Segmented, Space, Tag, Tooltip, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { DeleteOutlined, FileTextOutlined, MergeOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  deleteRemoteBranch,
  finalizeSession,
  getFinalizeDetail,
  listProjectWorkspaces,
  listRemoteBranches,
  releaseWorkspace,
} from '../api'
import type { RemoteBranchRow, WorkspaceRow } from '../types'
import FitTable from '../../../shared/components/FitTable'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { usePermissions } from '../../auth/usePermissions'
import { getUserSnapshot, isAdmin } from '../../auth/authStore'
import { showFinalizeDetailModal } from './FinalizeDetailModal'

type SubView = 'workspaces' | 'branches'

const STATE_TAG: Record<string, { color: string; label: string }> = {
  OPEN: { color: 'blue', label: '占用中' },
  FINALIZED: { color: 'green', label: '已收口' },
  RELEASED: { color: 'default', label: '已清理' },
}

export default function WorkspaceAdminPane({ projectId }: { projectId: string }) {
  const [subView, setSubView] = useState<SubView>('workspaces')
  const [workspaces, setWorkspaces] = useState<WorkspaceRow[]>([])
  const [wsLoading, setWsLoading] = useState(true)
  const [branches, setBranches] = useState<RemoteBranchRow[]>([])
  const [branchErrors, setBranchErrors] = useState<string[]>([])
  const [brLoading, setBrLoading] = useState(false)
  // 分支视图懒加载（ls-remote 走外网 git，不拖慢工作区视图首屏）
  const [brLoaded, setBrLoaded] = useState(false)
  const [finalizeRow, setFinalizeRow] = useState<WorkspaceRow | null>(null)
  const [discard, setDiscard] = useState(false)
  // 与列表「更多-收口」一致：默认勾选删远端分支
  const [deleteBranch, setDeleteBranch] = useState(true)
  const [finalizing, setFinalizing] = useState(false)

  const me = getUserSnapshot()
  const { canAction } = usePermissions()
  const canFinalize = canAction('project.sessions.action.finalize')

  const loadWorkspaces = useCallback(async () => {
    setWsLoading(true)
    try {
      setWorkspaces(await listProjectWorkspaces(projectId))
    } catch (e) {
      showError(e, '工作区列表加载失败')
    } finally {
      setWsLoading(false)
    }
  }, [projectId])

  const loadBranches = useCallback(async () => {
    setBrLoading(true)
    try {
      const r = await listRemoteBranches(projectId)
      setBranches(r.branches)
      setBranchErrors(r.errors ?? [])
    } catch (e) {
      showError(e, '远端分支加载失败')
    } finally {
      setBrLoading(false)
      setBrLoaded(true)
    }
  }, [projectId])

  useEffect(() => {
    void loadWorkspaces()
  }, [loadWorkspaces])

  useEffect(() => {
    if (subView === 'branches' && !brLoaded) void loadBranches()
  }, [subView, brLoaded, loadBranches])

  // 远端分支 feature/req-<id> → 需求标题（用工作区行的需求映射，查不到就显示分支原文）
  const reqTitleById = useMemo(() => {
    const m = new Map<string, string>()
    for (const w of workspaces) if (w.requirementId && w.requirementTitle) m.set(w.requirementId, w.requirementTitle)
    return m
  }, [workspaces])

  /** 工作区归属校验（服务端同样强制；此处只做按钮禁用提示） */
  const ownOrAdmin = (r: WorkspaceRow) => !!me && (r.owner === me.username || isAdmin())

  const onShowDetail = async (row: WorkspaceRow) => {
    if (!row.detailSessionId) return
    try {
      const r = await getFinalizeDetail(row.detailSessionId)
      if (!r.detail) {
        message.info('无收口记录（未收口过，或收口时 runner 版本过低未回传明细）')
        return
      }
      showFinalizeDetailModal(r.detail, r.finalizedAt)
    } catch (e) {
      showError(e, '收口记录查询失败')
    }
  }

  const onFinalize = async () => {
    if (!finalizeRow) return
    setFinalizing(true)
    try {
      const ack = await finalizeSession(finalizeRow.actionSessionId, discard, deleteBranch)
      setFinalizeRow(null)
      setDiscard(false)
      setDeleteBranch(true)
      if (ack.detail) showFinalizeDetailModal(ack.detail)
      else message.success('收口完成：已合并到基线并推送')
      void loadWorkspaces()
    } catch (e) {
      showError(e, '收口失败（工作区已保留，可处理后重试）')
    } finally {
      setFinalizing(false)
    }
  }

  const onRelease = (row: WorkspaceRow) => {
    Modal.confirm({
      centered: true,
      title: '清理节点工作区？',
      content: (
        <Space direction="vertical" size={4}>
          <Typography.Text>
            将删除执行节点上的工作区（worktree + 本地分支），<Typography.Text strong>不合并到基线</Typography.Text>
            ——未收口的改动会丢弃（远端 feature 分支不受影响，可在下方「远端 feature 分支」另行删除）。
          </Typography.Text>
          <Typography.Text type="warning">
            需求级工作区被同需求多个会话共享（本行 {row.sessionCount} 个会话），清理会一并回收。如需保留改动请先「收口」。
          </Typography.Text>
        </Space>
      ),
      okText: '清理',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          const ack = await releaseWorkspace(row.actionSessionId)
          message.success(ack.detail ? `工作区已清理：${ack.detail}` : '工作区已清理')
          void loadWorkspaces()
        } catch (e) {
          showError(e, '清理失败（工作区已保留，可处理后重试）')
        }
      },
    })
  }

  const onDeleteBranch = (row: RemoteBranchRow) => {
    Modal.confirm({
      centered: true,
      title: '删除远端分支？',
      content: `将删除仓库 ${row.repoName} 的远端分支 ${row.branch}（节点本地 worktree/分支不受影响）。该操作不可恢复，删除前请确认改动已收口或不再需要。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteRemoteBranch(projectId, row.repoName, row.branch)
          message.success(`已删除 ${row.repoName} ${row.branch}`)
          void loadBranches()
        } catch (e) {
          showError(e, '删除远端分支失败')
        }
      },
    })
  }

  const workspaceColumns: ColumnsType<WorkspaceRow> = [
    { title: '分支', dataIndex: 'branch', render: (b?: string) => b ?? '—' },
    {
      title: '关联需求',
      dataIndex: 'requirementTitle',
      render: (_: unknown, r) => r.requirementTitle ?? r.requirementId ?? '—',
    },
    {
      title: '仓库',
      dataIndex: 'repoNames',
      width: 200,
      render: (names: string[]) => (
        <Space size={2} wrap>
          {(names ?? []).map((n, i) => (
            <Tag key={`${n}-${i}`} color={i === 0 ? 'blue' : 'default'} style={{ marginInlineEnd: 0 }}>
              {n}
            </Tag>
          ))}
        </Space>
      ),
    },
    { title: '节点', dataIndex: 'agentNodeId', width: 130 },
    { title: '归属', dataIndex: 'owner', width: 110 },
    {
      title: '状态',
      dataIndex: 'state',
      width: 90,
      render: (s: string) => {
        const t = STATE_TAG[s] ?? { color: 'default', label: s }
        return <Tag color={t.color} style={{ marginInlineEnd: 0 }}>{t.label}</Tag>
      },
    },
    { title: '会话数', dataIndex: 'sessionCount', width: 76 },
    {
      title: '最近更新',
      dataIndex: 'updatedAt',
      width: 150,
      render: (t?: string) => fmtTime(t),
    },
    {
      title: '操作',
      key: 'action',
      width: 190,
      render: (_, r) => (
        <Space size={4}>
          {r.detailSessionId && (
            <Button size="small" icon={<FileTextOutlined />} onClick={() => void onShowDetail(r)}>
              收口记录
            </Button>
          )}
          {r.state === 'OPEN' && canFinalize && (
            <Button
              size="small"
              type="primary"
              ghost
              icon={<MergeOutlined />}
              disabled={!ownOrAdmin(r)}
              title={ownOrAdmin(r) ? undefined : '仅工作区归属者或管理员可收口'}
              onClick={() => {
                setFinalizeRow(r)
                setDiscard(false)
                setDeleteBranch(true)
              }}
            >
              收口
            </Button>
          )}
          {r.state !== 'RELEASED' && canFinalize && (
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              disabled={!ownOrAdmin(r)}
              title={ownOrAdmin(r) ? undefined : '仅工作区归属者或管理员可清理'}
              onClick={() => onRelease(r)}
            >
              清理
            </Button>
          )}
        </Space>
      ),
    },
  ]

  const branchColumns: ColumnsType<RemoteBranchRow> = [
    { title: '仓库', dataIndex: 'repoName', width: 200 },
    { title: '分支', dataIndex: 'branch' },
    {
      title: '关联需求',
      key: 'requirement',
      render: (_, r) => {
        const m = /^feature\/req-(.+)$/.exec(r.branch)
        return m ? (reqTitleById.get(m[1]) ?? m[1]) : '—'
      },
    },
    {
      title: 'HEAD',
      dataIndex: 'sha',
      width: 110,
      render: (sha: string) => (
        <Tooltip title={sha}>
          <Typography.Text code>{sha.slice(0, 8)}</Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, r) =>
        canFinalize ? (
          <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onDeleteBranch(r)}>
            删除
          </Button>
        ) : null,
    },
  ]

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        管理执行节点上的固定工作区（worktree + 本地分支）与项目仓库的远端 feature 分支。
        「清理」只删节点本地工作区、不合并改动；「收口」先合并到基线再回收工作区；远端分支删除独立于两者。
      </Typography.Paragraph>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12, flexShrink: 0 }}>
        <Segmented
          value={subView}
          onChange={(v) => setSubView(v as SubView)}
          options={[
            { value: 'workspaces', label: '固定工作区' },
            { value: 'branches', label: '远端 feature 分支' },
          ]}
        />
        <Button
          icon={<ReloadOutlined />}
          onClick={() => (subView === 'workspaces' ? void loadWorkspaces() : void loadBranches())}
        >
          刷新
        </Button>
      </div>
      {subView === 'workspaces' ? (
        <FitTable<WorkspaceRow>
          rowKey={(r) => `${r.workspaceKey}@${r.owner}`}
          loading={wsLoading}
          columns={workspaceColumns}
          dataSource={workspaces}
          pagination={LIST_PAGINATION}
          locale={{ emptyText: '暂无工作区。从「对话」视图创建会话后，节点上的固定工作区会出现在这里。' }}
        />
      ) : (
        <>
          {branchErrors.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12, flexShrink: 0 }}
              message="部分仓库远端分支列举失败"
              description={branchErrors.map((e, i) => (
                <div key={i}>{e}</div>
              ))}
            />
          )}
          <FitTable<RemoteBranchRow>
            rowKey={(r) => `${r.repoName}:${r.branch}`}
            loading={brLoading}
            columns={branchColumns}
            dataSource={branches}
            pagination={LIST_PAGINATION}
            locale={{ emptyText: '无远端 feature 分支（或项目仓库为非 http(s) 地址不支持直查）。' }}
          />
        </>
      )}

      {/* 收口：与列表「更多-收口合并到基线」同款确认弹窗 */}
      <Modal
        title="收口合并到基线"
        open={!!finalizeRow}
        centered
        okText="执行收口"
        cancelText="取消"
        confirmLoading={finalizing}
        onOk={onFinalize}
        onCancel={() => {
          setFinalizeRow(null)
          setDiscard(false)
          setDeleteBranch(true)
        }}
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Typography.Text>
            将分支 <Typography.Text code>{finalizeRow?.branch}</Typography.Text> 合并到基线分支并推送远端，
            随后删除节点上的固定工作区（worktree）。合并存在冲突时会失败并保留工作区，可恢复会话解决冲突后重试。
          </Typography.Text>
          <Checkbox checked={discard} onChange={(e) => setDiscard(e.target.checked)}>
            丢弃未提交改动（reset --hard + clean -fd；仅清未提交文件，不解合并冲突）
          </Checkbox>
          <Checkbox checked={deleteBranch} onChange={(e) => setDeleteBranch(e.target.checked)}>
            删除远端 feature 分支（勾选后收口完成将不可再查看该会话 diff）
          </Checkbox>
        </Space>
      </Modal>
    </div>
  )
}
