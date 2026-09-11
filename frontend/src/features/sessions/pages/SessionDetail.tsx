// 会话详情：头部信息 + 完整操作（含沉淀经验/清理 worktree）+ 对话面板（shared ChatPanel 复用）。
import { useCallback, useEffect, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  Descriptions,
  Empty,
  message,
  Modal,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd'
import {
  BulbOutlined,
  CaretRightOutlined,
  DeleteOutlined,
  DiffOutlined,
  FileSearchOutlined,
  PauseOutlined,
  PoweroffOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { getSession, removeWorktree } from '../api'
import type { SessionSummary } from '../types'
import { getSessionContext } from '../../scenarios/api'
import type { ContextSnapshot } from '../../scenarios/types'
import { stateColor, ACTIVE_STATES } from '../stateMeta'
import { useSessionActions } from '../hooks/useSessionActions'
import ChatPanel from '../../../shared/chat/ChatPanel'
import type { StreamMeta } from '../../../shared/chat/types'
import SessionDiffModal from '../components/SessionDiffModal'
import SedimentExperienceModal from '../../knowledge/components/SedimentExperienceModal'
import { fmtTime } from '../../../shared/utils/format'
import { pageRootScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

export default function SessionDetail() {
  const { id } = useParams<{ id: string }>()
  const [session, setSession] = useState<SessionSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [sedimentOpen, setSedimentOpen] = useState(false)
  const [contextOpen, setContextOpen] = useState(false)
  // CAP-33 FR-07：已注入上下文快照（404 = 未挂场景/无注入，静默为空）
  const [ctx, setCtx] = useState<ContextSnapshot | null>(null)
  const [streamMeta, setStreamMeta] = useState<StreamMeta>({ connected: false, fatal: false })

  const loadSession = useCallback(async () => {
    if (!id) return
    try {
      setSession(await getSession(id))
    } catch (e) {
      showError(e, '加载会话失败')
    } finally {
      setLoading(false)
    }
    getSessionContext(id)
      .then(setCtx)
      .catch(() => setCtx(null))
  }, [id])

  useEffect(() => {
    setLoading(true)
    loadSession()
  }, [loadSession])

  const onUpdated = useCallback(
    (s?: SessionSummary) => {
      if (s) setSession(s)
      else loadSession()
    },
    [loadSession],
  )
  const { onFinish, onSuspend, onResume, onKill, diff } = useSessionActions(id, onUpdated)

  const onRemoveWorktree = useCallback(() => {
    if (!id) return
    Modal.confirm({
      centered: true,
      title: '删除 worktree？',
      content: '删除会话对应的 git worktree（不影响会话记录与事件历史）。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await removeWorktree(id)
          message.success('已删除 worktree')
          setSession(await getSession(id))
        } catch (e) {
          showError(e, '删除失败')
        }
      },
    })
  }, [id])

  const canSuspend = !!session && ACTIVE_STATES.includes(session.state)
  // SUSPENDED=恢复；DONE/FAILED/TERMINATED=继续对话（claude --resume 带历史重拉起）
  const canResume = !!session && ['SUSPENDED', 'DONE', 'FAILED', 'TERMINATED'].includes(session.state)

  if (loading) {
    return (
      <Card>
        <Spin />
      </Card>
    )
  }
  if (!session) {
    return (
      <Card>
        <Empty description="会话不存在" />
      </Card>
    )
  }

  return (
    <Space direction="vertical" size={12} style={{ width: '100%', ...pageRootScrollStyle }}>
      {/* 头部信息 */}
      <Card
        size="small"
        title={
          <Space>
            <Typography.Text code>{session.id}</Typography.Text>
            <Tag color={stateColor[session.state] ?? 'default'}>{session.state}</Tag>
            {ctx?.scenarioCode && (
              <Tag color="blue">场景：{ctx.scenarioName ?? ctx.scenarioCode}</Tag>
            )}
            <Badge
              status={streamMeta.connected ? 'success' : streamMeta.fatal ? 'default' : 'processing'}
              text={streamMeta.connected ? '实时' : streamMeta.fatal ? '历史(终态)' : '连接中…'}
            />
          </Space>
        }
        extra={
          <Space>
            {canSuspend && (
              <Button size="small" icon={<StopOutlined />} onClick={onFinish}>
                结束
              </Button>
            )}
            {canSuspend && (
              <Button size="small" icon={<PauseOutlined />} onClick={onSuspend}>
                挂起
              </Button>
            )}
            {canResume && (
              <Button size="small" icon={<CaretRightOutlined />} onClick={onResume}>
                {session.state === 'SUSPENDED' ? '恢复' : '继续对话'}
              </Button>
            )}
            <Button size="small" icon={<DiffOutlined />} loading={diff.loading} onClick={diff.show}>
              Diff
            </Button>
            {ctx && (
              <Button size="small" icon={<FileSearchOutlined />} onClick={() => setContextOpen(true)}>
                已注入上下文
              </Button>
            )}
            <Button size="small" icon={<BulbOutlined />} onClick={() => setSedimentOpen(true)}>
              沉淀经验
            </Button>
            {session.worktreePath && !canSuspend && (
              <Button size="small" icon={<DeleteOutlined />} onClick={onRemoveWorktree}>
                清理 worktree
              </Button>
            )}
            {canSuspend && (
              <Button size="small" danger icon={<StopOutlined />} onClick={onKill}>
                终止
              </Button>
            )}
          </Space>
        }
      >
        <Descriptions size="small" column={{ xs: 1, sm: 2, md: 4 }}>
          <Descriptions.Item label="项目">{session.projectId}</Descriptions.Item>
          <Descriptions.Item label="PID">{session.pid ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="模型">{session.model || '默认'}</Descriptions.Item>
          <Descriptions.Item label="执行节点">{session.agentNodeId || '本机（历史）'}</Descriptions.Item>
          <Descriptions.Item label="创建">{fmtTime(session.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="完成">{fmtTime(session.finishedAt)}</Descriptions.Item>
          <Descriptions.Item label="Worktree" span={2}>
            <Typography.Text code copyable style={{ fontSize: 12 }}>
              {session.worktreePath || '-'}
            </Typography.Text>
          </Descriptions.Item>
        </Descriptions>
        {session.summary && (
          <Typography.Paragraph style={{ marginBottom: 0 }}>
            <Typography.Text strong>摘要：</Typography.Text>
            <Typography.Text>{session.summary}</Typography.Text>
          </Typography.Paragraph>
        )}
      </Card>

      {/* 对话（授权条 + 消息流 + 输入） */}
      <Card
        size="small"
        title="对话"
        extra={
          <Button size="small" icon={<PoweroffOutlined />} onClick={loadSession}>
            刷新
          </Button>
        }
      >
        <ChatPanel
          summary={{ ...session, topic: session.taskSpec }}
          apiBase="/sessions"
          onChanged={loadSession}
          onStreamMeta={setStreamMeta}
        />
      </Card>

      <SessionDiffModal open={diff.open} diff={diff.data} onClose={diff.close} />

      {/* 已注入上下文（CAP-33 FR-07 快照：场景 + 三层来源清单） */}
      <Modal
        title={`已注入上下文${ctx?.scenarioName ? `：${ctx.scenarioName}` : ''}`}
        open={contextOpen}
        onCancel={() => setContextOpen(false)}
        footer={null}
        width={720}
      >
        {ctx && (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="场景">{ctx.scenarioCode ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="装配时间">{fmtTime(ctx.assembledAt)}</Descriptions.Item>
              <Descriptions.Item label="包大小">{ctx.package.totalBytes} 字节</Descriptions.Item>
              <Descriptions.Item label="条目数">{ctx.package.entries}</Descriptions.Item>
            </Descriptions>
            <Typography.Text type="secondary">任务：{ctx.renderedTaskSpecPreview}</Typography.Text>
            <Table
              rowKey={(r) => `${r.kind}:${r.ref}`}
              size="small"
              pagination={false}
              dataSource={ctx.items}
              columns={[
                {
                  title: '类型',
                  dataIndex: 'kind',
                  width: 70,
                  render: (k: string) => ({ knowledge: '知识', skill: 'Skill', doc: '文档' })[k] ?? k,
                },
                { title: '名称', dataIndex: 'name', ellipsis: true },
                { title: '范围', dataIndex: 'scope', width: 90, render: (s?: string) => s ?? '-' },
                {
                  title: '来源',
                  dataIndex: 'source',
                  width: 90,
                  render: (s: string) => (
                    <Tag color={s === 'scenario' ? 'blue' : s === 'project-auto' ? 'green' : 'orange'}>
                      {({ scenario: '场景绑定', 'project-auto': '项目自动', request: '请求追加' })[s] ?? s}
                    </Tag>
                  ),
                },
              ]}
            />
          </Space>
        )}
      </Modal>

      {/* 沉淀经验（CAP-04） */}
      <SedimentExperienceModal
        open={sedimentOpen}
        onClose={() => setSedimentOpen(false)}
        sessionId={session.id}
        projectId={session.projectId}
        defaultTitle={session.summary ? `会话 ${session.id.slice(0, 6)} 经验沉淀` : undefined}
      />
    </Space>
  )
}
