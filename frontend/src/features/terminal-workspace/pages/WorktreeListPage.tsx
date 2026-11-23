// CAP-115 工作区终端列表页：终端工作区（可直接 bash 操作的轻量工作区）聚合 +
// 会话固定工作区只读总览（收口/清理操作留在「会话 → 工作区」视图）。
import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useSyncExternalStore } from 'react'
import { Button, Card, Form, Input, Modal, Segmented, Space, Tag, Tooltip, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { LinkOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import FitTable from '../../../shared/components/FitTable'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { getCurrentProjectId, subscribeCurrentProject } from '../../../app/currentProjectStore'
import { usePermissions } from '../../auth/usePermissions'
import { listProjectWorkspaces } from '../../sessions/api'
import type { WorkspaceRow } from '../../sessions/types'
import {
  attachTerminalWorkspace,
  createTerminalWorkspace,
  fetchFinalizeDetail,
  listTerminalWorkspaces,
  unlockTerminalWorkspace,
} from '../api'
import FinalizeWorkspaceModal from '../components/FinalizeWorkspaceModal'
import ReleaseWorkspaceModal from '../components/ReleaseWorkspaceModal'
import { showFinalizeDetail } from '../components/finalizeDetail'
import type { TerminalWorkspaceView } from '../types'

type SubView = 'terminal' | 'session'

const STATE_TAG: Record<string, { color: string; label: string }> = {
  OPEN: { color: 'blue', label: '使用中' },
  FINALIZED: { color: 'green', label: '已收口' },
  RELEASED: { color: 'default', label: '已释放' },
}

const SESSION_STATE_TAG: Record<string, { color: string; label: string }> = {
  OPEN: { color: 'blue', label: '占用中' },
  FINALIZED: { color: 'green', label: '已收口' },
  RELEASED: { color: 'default', label: '已清理' },
}

export default function WorktreeListPage() {
  const navigate = useNavigate()
  const projectId = useSyncExternalStore(subscribeCurrentProject, getCurrentProjectId)
  const { canAction } = usePermissions()
  const canManage = canAction('project.terminal.action.manage')
  const canFinalize = canAction('project.terminal.action.finalize')

  const [subView, setSubView] = useState<SubView>('terminal')
  const [rows, setRows] = useState<TerminalWorkspaceView[]>([])
  const [loading, setLoading] = useState(true)
  const [sessionRows, setSessionRows] = useState<WorkspaceRow[]>([])
  const [sessionLoading, setSessionLoading] = useState(false)
  const [sessionLoaded, setSessionLoaded] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [attachOpen, setAttachOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  /** M3 收口/释放弹窗目标工作区 */
  const [finalizeTarget, setFinalizeTarget] = useState<TerminalWorkspaceView | null>(null)
  const [releaseTarget, setReleaseTarget] = useState<TerminalWorkspaceView | null>(null)
  const [createForm] = Form.useForm<{ baseBranch?: string; agentNodeId?: string }>()
  const [attachForm] = Form.useForm<{ workspaceKey: string; baseBranch?: string; agentNodeId?: string }>()

  const load = useCallback(async () => {
    if (!projectId) return
    setLoading(true)
    try {
      setRows(await listTerminalWorkspaces(projectId))
    } catch (e) {
      showError(e, '终端工作区列表加载失败')
    } finally {
      setLoading(false)
    }
  }, [projectId])

  const loadSessions = useCallback(async () => {
    if (!projectId) return
    setSessionLoading(true)
    try {
      setSessionRows(await listProjectWorkspaces(projectId))
    } catch (e) {
      showError(e, '会话工作区加载失败')
    } finally {
      setSessionLoading(false)
      setSessionLoaded(true)
    }
  }, [projectId])

  useEffect(() => {
    void load()
  }, [load])

  // 会话工作区视图懒加载
  useEffect(() => {
    if (subView === 'session' && !sessionLoaded) void loadSessions()
  }, [subView, sessionLoaded, loadSessions])

  const onCreate = async () => {
    if (!projectId) return
    const v = await createForm.validateFields()
    setSubmitting(true)
    try {
      const w = await createTerminalWorkspace({
        projectId,
        baseBranch: v.baseBranch?.trim() || null,
        agentNodeId: v.agentNodeId?.trim() || null,
      })
      message.success(`终端工作区已创建：${w.workspaceKey}`)
      setCreateOpen(false)
      createForm.resetFields()
      void load()
    } catch (e) {
      showError(e, '创建终端工作区失败')
    } finally {
      setSubmitting(false)
    }
  }

  const onAttach = async () => {
    if (!projectId) return
    const v = await attachForm.validateFields()
    setSubmitting(true)
    try {
      const w = await attachTerminalWorkspace({
        workspaceKey: v.workspaceKey.trim(),
        projectId,
        baseBranch: v.baseBranch?.trim() || null,
        agentNodeId: v.agentNodeId?.trim() || null,
      })
      message.success(`已挂接工作区：${w.workspaceKey}`)
      setAttachOpen(false)
      attachForm.resetFields()
      void load()
    } catch (e) {
      showError(e, '挂接失败（键不存在/已被占用）')
    } finally {
      setSubmitting(false)
    }
  }

  const onUnlock = (row: TerminalWorkspaceView) => {
    Modal.confirm({
      centered: true,
      title: '解除占用锁？',
      content: `工作区 ${row.workspaceKey} 正被 ${row.lockOwner} 占用。解锁后对方的终端会话将被巡检挂起，确认解锁？`,
      okText: '解锁',
      cancelText: '取消',
      onOk: async () => {
        try {
          await unlockTerminalWorkspace(row.id)
          message.success('已解锁')
          void load()
        } catch (e) {
          showError(e, '解锁失败')
        }
      },
    })
  }

  /** M3 收口记录查看（detail 空 = 未收口过） */
  const onShowFinalizeDetail = async (row: TerminalWorkspaceView) => {
    try {
      const r = await fetchFinalizeDetail(row.id)
      if (r.detail) {
        showFinalizeDetail(r.detail, r.finalizedAt)
      } else {
        message.info('该工作区无收口记录')
      }
    } catch (e) {
      showError(e, '收口记录加载失败')
    }
  }

  const terminalColumns: ColumnsType<TerminalWorkspaceView> = [
    {
      title: '工作区键',
      dataIndex: 'workspaceKey',
      width: 180,
      render: (k: string) => <Typography.Text code>{k}</Typography.Text>,
    },
    { title: '分支', dataIndex: 'branch', render: (b?: string) => b ?? '—' },
    {
      title: '关联需求',
      dataIndex: 'requirementId',
      width: 140,
      render: (v?: string | null) => v ?? '—',
    },
    { title: '节点', dataIndex: 'agentNodeId', width: 120 },
    { title: '归属', dataIndex: 'owner', width: 110 },
    {
      title: '状态',
      dataIndex: 'state',
      width: 150,
      render: (s: string, r) => {
        const t = STATE_TAG[s] ?? { color: 'default', label: s }
        return (
          <Space size={4}>
            <Tag color={t.color} style={{ marginInlineEnd: 0 }}>{t.label}</Tag>
            {r.lockOwner && (
              <Tooltip title={`${r.lockOwner} 于 ${fmtTime(r.lockAt)} 加锁`}>
                <Tag color="orange" style={{ marginInlineEnd: 0 }}>占用：{r.lockOwner}</Tag>
              </Tooltip>
            )}
          </Space>
        )
      },
    },
    {
      title: '最近活跃',
      dataIndex: 'lastActiveAt',
      width: 150,
      render: (t?: string | null) => fmtTime(t ?? undefined),
    },
    {
      title: '创建',
      dataIndex: 'createdAt',
      width: 150,
      render: (t: string, r) => (
        <Tooltip title={`由 ${r.createdBy} 创建`}>{fmtTime(t)}</Tooltip>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 240,
      render: (_, r) => (
        <Space size={4}>
          {r.state === 'OPEN' && (
            <Button size="small" type="primary" ghost onClick={() => navigate(`/terminal-workspaces/${r.id}`)}>
              打开
            </Button>
          )}
          {r.lockOwner && canManage && (
            <Button size="small" onClick={() => onUnlock(r)}>
              解锁
            </Button>
          )}
          {r.state === 'OPEN' && canFinalize && (
            <Button size="small" onClick={() => setFinalizeTarget(r)}>
              收口
            </Button>
          )}
          {r.state !== 'RELEASED' && canFinalize && (
            <Button size="small" danger onClick={() => setReleaseTarget(r)}>
              释放
            </Button>
          )}
          {r.finalizedAt && (
            <Button size="small" type="link" onClick={() => void onShowFinalizeDetail(r)}>
              收口记录
            </Button>
          )}
        </Space>
      ),
    },
  ]

  const sessionColumns: ColumnsType<WorkspaceRow> = [
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
    { title: '节点', dataIndex: 'agentNodeId', width: 120 },
    { title: '归属', dataIndex: 'owner', width: 110 },
    {
      title: '状态',
      dataIndex: 'state',
      width: 90,
      render: (s: string) => {
        const t = SESSION_STATE_TAG[s] ?? { color: 'default', label: s }
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
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>工作区终端</span>
          <Segmented
            value={subView}
            onChange={(v) => setSubView(v as SubView)}
            options={[
              { value: 'terminal', label: '终端工作区' },
              { value: 'session', label: '会话工作区' },
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => (subView === 'terminal' ? void load() : void loadSessions())}
          >
            刷新
          </Button>
          {subView === 'terminal' && canManage && (
            <>
              <Button icon={<LinkOutlined />} onClick={() => setAttachOpen(true)}>
                挂接既有
              </Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
                新建终端工作区
              </Button>
            </>
          )}
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        终端工作区 = 节点上的 git worktree，打开后直接用 bash 做轻量代码改动（无需起 AI 会话）；
        会话工作区为 AI 会话占用的固定工作区（只读总览，收口/清理在「会话 → 工作区」视图操作）。
      </Typography.Paragraph>
      {subView === 'terminal' ? (
        <FitTable<TerminalWorkspaceView>
          rowKey="id"
          loading={loading}
          columns={terminalColumns}
          dataSource={rows}
          pagination={LIST_PAGINATION}
          locale={{
            emptyText: canManage
              ? '暂无终端工作区。点右上角「新建终端工作区」在默认节点物化一块 worktree，或「挂接既有」登记已有的 req-/sid- 工作区。'
              : '暂无终端工作区。',
          }}
        />
      ) : (
        <FitTable<WorkspaceRow>
          rowKey={(r) => `${r.workspaceKey}@${r.owner}`}
          loading={sessionLoading}
          columns={sessionColumns}
          dataSource={sessionRows}
          pagination={LIST_PAGINATION}
          locale={{ emptyText: '暂无会话工作区。从「会话」页创建会话后，节点上的固定工作区会出现在这里。' }}
        />
      )}

      <Modal
        title="新建终端工作区"
        open={createOpen}
        centered
        okText="创建"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={() => void onCreate()}
        onCancel={() => setCreateOpen(false)}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          在当前项目的默认节点上物化一块 git worktree（term-&lt;短id&gt; 键、terminal/ 前缀分支），
          物化成功即登记，可直接打开终端操作。
        </Typography.Paragraph>
        <Form form={createForm} layout="vertical">
          <Form.Item name="baseBranch" label="基线分支（留空 = 项目默认基线）">
            <Input placeholder="main" allowClear />
          </Form.Item>
          <Form.Item name="agentNodeId" label="执行节点（留空 = 平台默认节点）">
            <Input placeholder="节点 id" allowClear />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="挂接既有工作区"
        open={attachOpen}
        centered
        okText="挂接"
        cancelText="取消"
        confirmLoading={submitting}
        onOk={() => void onAttach()}
        onCancel={() => setAttachOpen(false)}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          把节点上已存在的工作区登记进来（req-&lt;需求id&gt; / sid-&lt;会话id&gt; / term-&lt;短id&gt;）。
          同键已有 OPEN 登记或与进行中会话互斥时会拒绝。
        </Typography.Paragraph>
        <Form form={attachForm} layout="vertical">
          <Form.Item
            name="workspaceKey"
            label="工作区键"
            rules={[{ required: true, message: '请输入工作区键' }]}
          >
            <Input placeholder="req-fthw8xio" allowClear />
          </Form.Item>
          <Form.Item name="baseBranch" label="基线分支（留空 = 项目默认基线）">
            <Input placeholder="main" allowClear />
          </Form.Item>
          <Form.Item name="agentNodeId" label="执行节点（留空 = 平台默认节点）">
            <Input placeholder="节点 id" allowClear />
          </Form.Item>
        </Form>
      </Modal>

      {/* M3 收口 / 释放确认弹窗（目标行） */}
      {finalizeTarget && (
        <FinalizeWorkspaceModal
          ws={finalizeTarget}
          open
          onCancel={() => setFinalizeTarget(null)}
          onDone={() => {
            setFinalizeTarget(null)
            void load()
          }}
        />
      )}
      {releaseTarget && (
        <ReleaseWorkspaceModal
          ws={releaseTarget}
          open
          onCancel={() => setReleaseTarget(null)}
          onDone={() => {
            setReleaseTarget(null)
            void load()
          }}
        />
      )}
    </Card>
  )
}
