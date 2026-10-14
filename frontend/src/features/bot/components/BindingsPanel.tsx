// CAP-75 M3 群绑定面板：bot_bindings 全量列表（群/单聊 → 项目/问答会话/操作身份），支持解绑。
// 解绑后该会话下一条消息会按门禁与懒绑定规则重新建绑定。
import { useCallback, useEffect, useState } from 'react'
import { Modal, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { listBotBindings, unbindBotBinding } from '../api'
import type { BotBinding } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const CHANNEL_LABEL: Record<string, string> = { feishu: '飞书' }

export default function BindingsPanel({ refreshTick = 0 }: { refreshTick?: number }) {
  const [rows, setRows] = useState<BotBinding[]>([])
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await listBotBindings())
    } catch (e) {
      showError(e, '加载群绑定失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load, refreshTick])

  const onUnbind = (row: BotBinding) => {
    Modal.confirm({
      title: `解绑会话 ${row.externalChatId}？`,
      content: '解绑后该群/单聊与项目、问答会话的关联被清除；下一条消息会重新建立默认绑定。',
      centered: true,
      okText: '解绑',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () =>
        unbindBotBinding(row.id)
          .then(() => {
            message.success('已解绑')
            load()
          })
          .catch((e) => showError(e, '解绑失败')),
    })
  }

  const columns: ColumnsType<BotBinding> = [
    {
      title: '渠道',
      dataIndex: 'channel',
      width: 90,
      render: (c: string) => <Tag>{CHANNEL_LABEL[c] ?? c}</Tag>,
    },
    {
      title: '会话 ID',
      dataIndex: 'externalChatId',
      width: 220,
      ellipsis: true,
      render: (s: string) => <Typography.Text code>{s}</Typography.Text>,
    },
    {
      title: '类型',
      dataIndex: 'chatType',
      width: 90,
      render: (t?: string) => (t === 'group' ? '群聊' : t === 'p2p' ? '单聊' : t || '-'),
    },
    {
      title: '绑定项目',
      width: 180,
      render: (_, r) =>
        r.projectName
          ? `${r.projectName}（${r.projectId}）`
          : <Typography.Text type="secondary">未绑定项目</Typography.Text>,
    },
    {
      title: '问答会话',
      dataIndex: 'chatSessionId',
      width: 140,
      ellipsis: true,
      render: (s?: string) => s || <Typography.Text type="secondary">-</Typography.Text>,
    },
    {
      title: '操作身份',
      dataIndex: 'actor',
      width: 120,
      render: (s?: string) => s || <Typography.Text type="secondary">默认</Typography.Text>,
    },
    {
      title: '首个触发者',
      dataIndex: 'ownerSenderId',
      width: 160,
      ellipsis: true,
      render: (s?: string) => s || '-',
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 160, render: (t) => fmtTime(t) },
    {
      title: '操作',
      width: 80,
      render: (_, r) => (
        <Typography.Link type="danger" onClick={() => onUnbind(r)}>
          解绑
        </Typography.Link>
      ),
    },
  ]

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        机器人各群/单聊会话的绑定关系：项目（/bind）、问答会话与默认操作身份。
      </Typography.Paragraph>
      <FitTable<BotBinding>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '暂无绑定。群内 @机器人 或私聊机器人发第一条消息后自动创建。' }}
      />
    </div>
  )
}
