// CAP-75 M3 交互日志面板：bot 底座处理过的每条事件留痕（OK/FAILED/DEDUPED/REJECTED）。
// 服务端真分页 + 渠道/状态/关键词过滤；整行可点击开抽屉看入站/出站全文（落库已截断 2000/4000）。
import { useCallback, useEffect, useState } from 'react'
import { Badge, Descriptions, Drawer, Input, Select, Space, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { listBotInteractions } from '../api'
import type { BotInteraction } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_WIDE } from '../../../shared/utils/overlay'

const STATUS_LABEL: Record<string, string> = {
  OK: '成功',
  FAILED: '失败',
  DEDUPED: '去重',
  REJECTED: '拒绝',
}

const STATUS_COLOR: Record<string, string> = {
  OK: 'success',
  FAILED: 'error',
  DEDUPED: 'default',
  REJECTED: 'warning',
}

const CHANNEL_LABEL: Record<string, string> = { feishu: '飞书' }

export default function InteractionsPanel({ refreshTick = 0 }: { refreshTick?: number }) {
  const [channel, setChannel] = useState<string>()
  const [status, setStatus] = useState<string>()
  // 关键词双 state：输入草稿不触发查询，回车/点搜索才落定
  const [keywordDraft, setKeywordDraft] = useState('')
  const [keyword, setKeyword] = useState('')
  const [rows, setRows] = useState<BotInteraction[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<BotInteraction | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listBotInteractions({ channel, status, keyword: keyword || undefined, page, size })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      showError(e, '加载交互日志失败')
    } finally {
      setLoading(false)
    }
  }, [channel, status, keyword, page, size])

  useEffect(() => { load() }, [load, refreshTick])

  const columns: ColumnsType<BotInteraction> = [
    { title: '时间', dataIndex: 'createdAt', width: 160, render: (t) => fmtTime(t) },
    {
      title: '渠道',
      dataIndex: 'channel',
      width: 90,
      render: (c: string) => <Tag>{CHANNEL_LABEL[c] ?? c}</Tag>,
    },
    {
      title: '指令',
      dataIndex: 'command',
      width: 100,
      render: (c?: string) => (c ? <Typography.Text code>{c}</Typography.Text> : '-'),
    },
    {
      title: '发送者',
      dataIndex: 'senderId',
      width: 160,
      ellipsis: true,
      render: (s?: string) => s || '-',
    },
    { title: '执行身份', dataIndex: 'actor', width: 110, render: (s?: string) => s || '-' },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: string) => (
        <Badge
          status={(STATUS_COLOR[s] ?? 'default') as 'success'}
          text={STATUS_LABEL[s] ?? s}
        />
      ),
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      width: 90,
      render: (d?: number) => (d != null ? `${d} ms` : '-'),
    },
    {
      title: '入站',
      dataIndex: 'inputText',
      ellipsis: true,
      render: (t?: string) => t || '-',
    },
  ]

  return (
    // 卡片 body 是 flex 列（pageCardBodyFlexStyle）：筛选行固定，表格由 FitTable 实测高度、表内滚动
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear placeholder="渠道" style={{ width: 120 }}
          value={channel} onChange={(v) => { setChannel(v); setPage(0) }}
          options={Object.entries(CHANNEL_LABEL).map(([value, label]) => ({ label, value }))}
        />
        <Select
          allowClear placeholder="状态" style={{ width: 110 }}
          value={status} onChange={(v) => { setStatus(v); setPage(0) }}
          options={Object.entries(STATUS_LABEL).map(([value, label]) => ({ label, value }))}
        />
        <Input.Search
          allowClear placeholder="关键词（入站/出站/发送者）" style={{ width: 260 }}
          value={keywordDraft}
          onChange={(e) => setKeywordDraft(e.target.value)}
          onSearch={(v) => { setKeyword(v.trim()); setPage(0) }}
        />
      </Space>
      <FitTable<BotInteraction>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        onRow={(r) => ({ onClick: () => setDetail(r), style: { cursor: 'pointer' } })}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(s !== size ? 0 : p - 1)
            setSize(s)
          },
        }}
        locale={{ emptyText: '暂无交互记录。机器人收到并处理消息（含被门禁拒绝/去重命中）后自动留痕。' }}
      />

      <Drawer title="交互详情" open={!!detail} onClose={() => setDetail(null)} width={DRAWER_WIDE}>
        {detail && (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="时间">{fmtTime(detail.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="渠道">{CHANNEL_LABEL[detail.channel] ?? detail.channel}</Descriptions.Item>
              <Descriptions.Item label="指令">
                <Typography.Text code>{detail.command || '-'}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="状态">{STATUS_LABEL[detail.status] ?? detail.status}</Descriptions.Item>
              <Descriptions.Item label="会话类型">
                {detail.chatType === 'group' ? '群聊' : detail.chatType === 'p2p' ? '单聊' : detail.chatType || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="会话 ID">{detail.externalChatId}</Descriptions.Item>
              <Descriptions.Item label="发送者">{detail.senderId || '-'}</Descriptions.Item>
              <Descriptions.Item label="执行身份">{detail.actor || '-'}</Descriptions.Item>
              <Descriptions.Item label="耗时">
                {detail.durationMs != null ? `${detail.durationMs} ms` : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="错误">{detail.error || '-'}</Descriptions.Item>
            </Descriptions>
            <Typography.Text strong>入站</Typography.Text>
            <pre style={{ background: '#f6f6f6', padding: 12, borderRadius: 4, fontSize: 12, whiteSpace: 'pre-wrap' }}>
              {detail.inputText ?? '(无)'}
            </pre>
            <Typography.Text strong>出站回复</Typography.Text>
            <pre style={{ background: '#f6f6f6', padding: 12, borderRadius: 4, fontSize: 12, whiteSpace: 'pre-wrap' }}>
              {detail.replyText ?? '(无)'}
            </pre>
          </Space>
        )}
      </Drawer>
    </div>
  )
}
