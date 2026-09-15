// CAP-39：会话「更多」操作（原会话详情页迁入工作台）：已注入上下文 / 沉淀经验 / 清理 worktree。
// CAP-42：收口合并到基线（固定工作区手动收口：合并+push+删 worktree）。
import { useState } from 'react'
import { Button, Checkbox, Descriptions, Dropdown, message, Modal, Space, Table, Tag, Typography } from 'antd'
import {
  BulbOutlined,
  DeleteOutlined,
  FileSearchOutlined,
  MergeOutlined,
  MoreOutlined,
} from '@ant-design/icons'
import { finalizeSession, removeWorktree } from '../api'
import type { SessionSummary } from '../types'
import { getSessionContext } from '../../scenarios/api'
import type { ContextSnapshot } from '../../scenarios/types'
import SedimentExperienceModal from '../../knowledge/components/SedimentExperienceModal'
import { getUserSnapshot, isAdmin } from '../../auth/authStore'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

export default function SessionMoreActions({
  session,
  canSuspend,
  onChanged,
}: {
  session: SessionSummary
  canSuspend: boolean
  onChanged: () => void
}) {
  const [contextOpen, setContextOpen] = useState(false)
  const [ctx, setCtx] = useState<ContextSnapshot | null>(null)
  const [ctxLoading, setCtxLoading] = useState(false)
  const [sedimentOpen, setSedimentOpen] = useState(false)
  const [finalizeOpen, setFinalizeOpen] = useState(false)
  const [discard, setDiscard] = useState(false)
  const [finalizing, setFinalizing] = useState(false)

  // CAP-42：固定工作区会话（workspaceState 非空）才显示收口入口；
  // 已收口 / 会话运行中 / 非创建者且非管理员 → 禁用
  const me = getUserSnapshot()
  const canFinalize =
    !!session.workspaceState &&
    session.workspaceState !== 'FINALIZED' &&
    !canSuspend &&
    (!!me && (session.createdBy === me.username || isAdmin()))

  // 点击时才拉取（404 = 未挂场景/无注入快照，提示即可）
  const openContext = async () => {
    setCtxLoading(true)
    try {
      setCtx(await getSessionContext(session.id))
      setContextOpen(true)
    } catch {
      message.info('该会话无注入上下文（未挂场景或无装配快照）')
    } finally {
      setCtxLoading(false)
    }
  }

  const onFinalize = async () => {
    setFinalizing(true)
    try {
      const ack = await finalizeSession(session.id, discard)
      message.success(ack.detail ? `收口完成：${ack.detail}` : '收口完成：已合并到基线并推送')
      setFinalizeOpen(false)
      setDiscard(false)
      onChanged()
    } catch (e) {
      // 失败（冲突/脏工作区/push 失败）透传 runner 脱敏错误，工作区保留可重试
      showError(e, '收口失败（工作区已保留，可处理后重试）')
    } finally {
      setFinalizing(false)
    }
  }

  const onRemoveWorktree = () => {
    Modal.confirm({
      centered: true,
      title: '删除 worktree？',
      content: '删除会话对应的 git worktree（不影响会话记录与事件历史）。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await removeWorktree(session.id)
          message.success('已删除 worktree')
          onChanged()
        } catch (e) {
          showError(e, '删除失败')
        }
      },
    })
  }

  return (
    <>
      <Dropdown
        trigger={['click']}
        menu={{
          items: [
            ...(session.workspaceState
              ? [
                  {
                    key: 'finalize',
                    icon: <MergeOutlined />,
                    label: '收口合并到基线',
                    disabled: !canFinalize,
                  },
                ]
              : []),
            { key: 'context', icon: <FileSearchOutlined />, label: '已注入上下文' },
            { key: 'sediment', icon: <BulbOutlined />, label: '沉淀经验' },
            {
              key: 'worktree',
              icon: <DeleteOutlined />,
              label: '清理 worktree',
              disabled: !session.worktreePath || canSuspend,
              danger: true,
            },
          ],
          onClick: ({ key }) => {
            if (key === 'finalize') setFinalizeOpen(true)
            else if (key === 'context') openContext()
            else if (key === 'sediment') setSedimentOpen(true)
            else if (key === 'worktree') onRemoveWorktree()
          },
        }}
      >
        <Button size="small" icon={<MoreOutlined />} loading={ctxLoading}>
          更多
        </Button>
      </Dropdown>

      {/* CAP-42：收口合并到基线（runner 逐库执行：合并会话分支→push 基线+会话分支→删 worktree） */}
      <Modal
        title="收口合并到基线"
        open={finalizeOpen}
        centered
        okText="执行收口"
        cancelText="取消"
        confirmLoading={finalizing}
        onOk={onFinalize}
        onCancel={() => {
          setFinalizeOpen(false)
          setDiscard(false)
        }}
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Typography.Text>
            将会话分支合并到基线分支并推送远端，随后删除节点上的固定工作区（worktree）。
            合并存在冲突时会失败并保留工作区，可恢复会话解决冲突后重试。
          </Typography.Text>
          <Checkbox checked={discard} onChange={(e) => setDiscard(e.target.checked)}>
            丢弃未提交改动（reset --hard + clean -fd；仅清未提交文件，不解合并冲突）
          </Checkbox>
        </Space>
      </Modal>

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
                  render: (k: string) => ({ knowledge: '知识', skill: 'Skill', doc: '文档', attachment: '附件' })[k] ?? k,
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
    </>
  )
}
