// CAP-76 FR-30 对话审计：每轮消息一行（OK/FAILED/REJECTED/PENDING_CONFIRM），
// 服务端真分页；点行开抽屉看用户/助手全文、工具调用链与关联确认单。
import { useCallback, useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { Descriptions, Drawer, Input, Select, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { getCopilotTurn, listCopilotTurns } from '../api'
import type { CopilotAction, CopilotToolCall, CopilotTurn, CopilotTurnDetail } from '../types'
import { ActionStatusBadge, RiskTag, TURN_STATUS_OPTIONS, TurnStatusBadge } from './tags'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { fmtTime } from '../../../shared/utils/format'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

const CHANNEL_LABEL: Record<string, string> = { feishu: '飞书', fake: '假渠道' }

export default function TurnsPanel({ refreshTick = 0 }: { refreshTick?: number }) {
  const [channelDraft, setChannelDraft] = useState('')
  const [status, setStatus] = useState<string>()
  const [actorDraft, setActorDraft] = useState('')
  const [keywordDraft, setKeywordDraft] = useState('')
  const [filters, setFilters] = useState<{ channel?: string; actor?: string; keyword?: string }>({})
  const [rows, setRows] = useState<CopilotTurn[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<CopilotTurnDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listCopilotTurns({
        channel: filters.channel,
        status,
        actor: filters.actor,
        keyword: filters.keyword,
        page,
        size,
      })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      showError(e, '加载对话审计失败')
    } finally {
      setLoading(false)
    }
  }, [filters, status, page, size])

  useEffect(() => { load() }, [load, refreshTick])

  const openDetail = async (id: string) => {
    setDetailLoading(true)
    try {
      setDetail(await getCopilotTurn(id))
    } catch (e) {
      showError(e, '加载轮次详情失败')
    } finally {
      setDetailLoading(false)
    }
  }

  const columns: ColumnsType<CopilotTurn> = [
    { title: '时间', dataIndex: 'createdAt', width: 160, render: (t) => fmtTime(t) },
    {
      title: '渠道',
      dataIndex: 'channel',
      width: 90,
      render: (c: string) => <Tag>{CHANNEL_LABEL[c] ?? c}</Tag>,
    },
    { title: '执行身份', dataIndex: 'actor', width: 110, render: (s?: string) => s || '-' },
    {
      title: '发起',
      dataIndex: 'triggerType',
      width: 100,
      render: (s?: string) => (s === 'CONFIRM' ? '确认执行' : s === 'CARD_ACTION' ? '卡片操作' : '用户消息'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: string) => <TurnStatusBadge status={s} />,
    },
    {
      title: '步数',
      dataIndex: 'steps',
      width: 70,
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      width: 90,
      render: (d?: number) => (d != null ? `${d} ms` : '-'),
    },
    {
      title: '用户输入',
      dataIndex: 'userText',
      ellipsis: true,
      render: (t: string | undefined, r) => (t ? <a onClick={() => openDetail(r.id)}>{t}</a> : '-'),
    },
  ]

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          allowClear placeholder="渠道（feishu/fake）" style={{ width: 160 }}
          value={channelDraft}
          onChange={(e) => setChannelDraft(e.target.value)}
          onSearch={(v) => { setFilters((f) => ({ ...f, channel: v.trim() || undefined })); setPage(0) }}
        />
        <Select
          allowClear placeholder="状态" style={{ width: 110 }}
          value={status} onChange={(v) => { setStatus(v); setPage(0) }}
          options={TURN_STATUS_OPTIONS}
        />
        <Input.Search
          allowClear placeholder="执行身份" style={{ width: 150 }}
          value={actorDraft}
          onChange={(e) => setActorDraft(e.target.value)}
          onSearch={(v) => { setFilters((f) => ({ ...f, actor: v.trim() || undefined })); setPage(0) }}
        />
        <Input.Search
          allowClear placeholder="关键词（用户输入）" style={{ width: 240 }}
          value={keywordDraft}
          onChange={(e) => setKeywordDraft(e.target.value)}
          onSearch={(v) => { setFilters((f) => ({ ...f, keyword: v.trim() || undefined })); setPage(0) }}
        />
      </Space>
      <FitTable<CopilotTurn>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
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
        locale={{ emptyText: '暂无智能体对话记录。机器人在智能体模式下处理消息后自动留痕。' }}
      />

      <Drawer title="轮次详情" open={!!detail || detailLoading} onClose={() => setDetail(null)} width={DRAWER_DEFAULT}>
        {detail?.turn && (
          <Space direction="vertical" style={{ width: '100%' }} size={16}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="轮次 id" span={2}>
                <Typography.Text code>{detail.turn.id}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="时间">{fmtTime(detail.turn.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="渠道">{CHANNEL_LABEL[detail.turn.channel] ?? detail.turn.channel}</Descriptions.Item>
              <Descriptions.Item label="执行身份">{detail.turn.actor || '-'}</Descriptions.Item>
              <Descriptions.Item label="发送者">{detail.turn.senderId || '-'}</Descriptions.Item>
              <Descriptions.Item label="状态"><TurnStatusBadge status={detail.turn.status} /></Descriptions.Item>
              <Descriptions.Item label="触发">
                {detail.turn.triggerType === 'CONFIRM'
                  ? '确认执行'
                  : detail.turn.triggerType === 'CARD_ACTION'
                    ? '卡片操作'
                    : '用户消息'}
              </Descriptions.Item>
              <Descriptions.Item label="模型">{detail.turn.model || '-'}</Descriptions.Item>
              <Descriptions.Item label="模式">{detail.turn.toolMode || '-'}</Descriptions.Item>
              <Descriptions.Item label="步数">{detail.turn.steps}</Descriptions.Item>
              <Descriptions.Item label="耗时">{detail.turn.durationMs} ms</Descriptions.Item>
              <Descriptions.Item label="Token">
                输入 {detail.turn.promptTokens} / 输出 {detail.turn.completionTokens}
              </Descriptions.Item>
              <Descriptions.Item label="错误" span={2}>{detail.turn.error || '-'}</Descriptions.Item>
            </Descriptions>

            <Typography.Text strong>用户输入</Typography.Text>
            <pre style={preStyle}>{detail.turn.userText || '(无)'}</pre>
            <Typography.Text strong>助手回答</Typography.Text>
            <pre style={preStyle}>{detail.turn.assistantText || '(无)'}</pre>

            <Typography.Text strong>工具调用（{detail.toolCalls.length}）</Typography.Text>
            <Table<CopilotToolCall>
              rowKey="id"
              pagination={false}
              dataSource={detail.toolCalls}
              columns={[
                { title: '工具', dataIndex: 'toolName', width: 180, render: (n: string) => <Typography.Text code>{n}</Typography.Text> },
                { title: '风险', dataIndex: 'risk', width: 80, render: (r: string) => <RiskTag risk={r} /> },
                { title: '状态', dataIndex: 'status', width: 100 },
                { title: '耗时', dataIndex: 'durationMs', width: 90, render: (d: number) => `${d} ms` },
                { title: '结果/错误', dataIndex: 'resultSummary', ellipsis: true, render: (s: string | undefined, r) => s || r.error || '-' },
              ]}
            />

            <Typography.Text strong>关联确认单（{detail.actions.length}）</Typography.Text>
            <Table<CopilotAction>
              rowKey="id"
              pagination={false}
              dataSource={detail.actions}
              columns={[
                { title: '短码', dataIndex: 'shortCode', width: 100, render: (c: string) => <Typography.Text code>{c}</Typography.Text> },
                { title: '工具', dataIndex: 'toolName', width: 180 },
                { title: '风险', dataIndex: 'risk', width: 80, render: (r: string) => <RiskTag risk={r} /> },
                { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <ActionStatusBadge status={s} /> },
                { title: '过期时间', dataIndex: 'expiresAt', width: 160, render: (t?: string) => fmtTime(t) },
              ]}
            />
          </Space>
        )}
      </Drawer>
    </div>
  )
}

const preStyle: CSSProperties = {
  background: '#f6f6f6',
  padding: 12,
  borderRadius: 4,
  fontSize: 12,
  whiteSpace: 'pre-wrap',
  margin: 0,
}
