// CAP-76 FR-30 确认单审计：PENDING/EXECUTING/SUCCEEDED/FAILED/CANCELLED/EXPIRED 全量分页，
// 管理员可对 PENDING 兜底取消（走同一状态机，不绕过原子消费）。
import { useCallback, useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { Button, Descriptions, Drawer, Input, Modal, Select, Space, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { cancelCopilotAction, listCopilotActions } from '../api'
import type { CopilotAction } from '../types'
import { ACTION_STATUS_OPTIONS, ActionStatusBadge, RISK_OPTIONS, RiskTag } from './tags'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { fmtTime } from '../../../shared/utils/format'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

const CHANNEL_LABEL: Record<string, string> = { feishu: '飞书', fake: '假渠道' }

export default function ActionsPanel({ refreshTick = 0 }: { refreshTick?: number }) {
  const [channelDraft, setChannelDraft] = useState('')
  const [status, setStatus] = useState<string>()
  const [risk, setRisk] = useState<string>()
  const [actorDraft, setActorDraft] = useState('')
  const [keywordDraft, setKeywordDraft] = useState('')
  const [filters, setFilters] = useState<{ channel?: string; actor?: string; keyword?: string }>({})
  const [rows, setRows] = useState<CopilotAction[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<CopilotAction | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listCopilotActions({
        channel: filters.channel,
        status,
        risk,
        actor: filters.actor,
        keyword: filters.keyword,
        page,
        size,
      })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      showError(e, '加载确认单失败')
    } finally {
      setLoading(false)
    }
  }, [filters, status, risk, page, size])

  useEffect(() => { load() }, [load, refreshTick])

  const cancel = (row: CopilotAction) => {
    Modal.confirm({
      title: '强制取消确认单',
      content: `确认取消 ${row.shortCode}（${row.toolName}）？该操作不会执行。`,
      okText: '取消确认单',
      okButtonProps: { danger: true },
      cancelText: '返回',
      onOk: async () => {
        try {
          const r = await cancelCopilotAction(row.id)
          if (r.ok) {
            message.success('已取消')
            load()
          } else {
            message.warning(r.message || '取消失败')
          }
        } catch (e) {
          showError(e, '取消失败')
        }
      },
    })
  }

  const columns: ColumnsType<CopilotAction> = [
    { title: '创建时间', dataIndex: 'createdAt', width: 160, render: (t) => fmtTime(t) },
    {
      title: '短码',
      dataIndex: 'shortCode',
      width: 110,
      render: (c: string) => <Typography.Text code>{c}</Typography.Text>,
    },
    {
      title: '工具',
      dataIndex: 'toolName',
      width: 200,
      render: (n: string, r) => <a onClick={(e) => { e.stopPropagation(); setDetail(r) }}>{n}</a>,
    },
    { title: '风险', dataIndex: 'risk', width: 80, render: (r: string) => <RiskTag risk={r} /> },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: string) => <ActionStatusBadge status={s} />,
    },
    { title: '执行身份', dataIndex: 'actor', width: 110, render: (s?: string) => s || '-' },
    {
      title: '渠道',
      dataIndex: 'channel',
      width: 90,
      render: (c: string) => <Tag>{CHANNEL_LABEL[c] ?? c}</Tag>,
    },
    { title: '过期时间', dataIndex: 'expiresAt', width: 160, render: (t?: string) => fmtTime(t) },
    {
      title: '操作',
      key: 'op',
      width: 90,
      render: (_, r) =>
        r.status === 'PENDING' ? (
          <Button danger type="link" onClick={(e) => { e.stopPropagation(); cancel(r) }}>
            取消
          </Button>
        ) : (
          '-'
        ),
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
          options={ACTION_STATUS_OPTIONS}
        />
        <Select
          allowClear placeholder="风险" style={{ width: 110 }}
          value={risk} onChange={(v) => { setRisk(v); setPage(0) }}
          options={RISK_OPTIONS}
        />
        <Input.Search
          allowClear placeholder="执行身份" style={{ width: 150 }}
          value={actorDraft}
          onChange={(e) => setActorDraft(e.target.value)}
          onSearch={(v) => { setFilters((f) => ({ ...f, actor: v.trim() || undefined })); setPage(0) }}
        />
        <Input.Search
          allowClear placeholder="关键词（短码/工具名）" style={{ width: 220 }}
          value={keywordDraft}
          onChange={(e) => setKeywordDraft(e.target.value)}
          onSearch={(v) => { setFilters((f) => ({ ...f, keyword: v.trim() || undefined })); setPage(0) }}
        />
      </Space>
      <FitTable<CopilotAction>
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
        onRow={(r) => ({ onClick: () => setDetail(r), style: { cursor: 'pointer' } })}
        locale={{ emptyText: '暂无确认单。智能体提出写操作后需用户确认，才会在这里留下记录。' }}
      />

      <Drawer title="确认单详情" open={!!detail} onClose={() => setDetail(null)} width={DRAWER_DEFAULT}>
        {detail && (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="确认单 id" span={2}>
                <Typography.Text code>{detail.id}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="短码">
                <Typography.Text code>{detail.shortCode}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="状态"><ActionStatusBadge status={detail.status} /></Descriptions.Item>
              <Descriptions.Item label="工具">{detail.toolName}</Descriptions.Item>
              <Descriptions.Item label="风险"><RiskTag risk={detail.risk} /></Descriptions.Item>
              <Descriptions.Item label="执行身份">{detail.actor || '-'}</Descriptions.Item>
              <Descriptions.Item label="渠道">{CHANNEL_LABEL[detail.channel] ?? detail.channel}</Descriptions.Item>
              <Descriptions.Item label="创建时间">{fmtTime(detail.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="过期时间">{fmtTime(detail.expiresAt)}</Descriptions.Item>
              <Descriptions.Item label="执行时间">{fmtTime(detail.executedAt)}</Descriptions.Item>
              <Descriptions.Item label="错误" span={2}>{detail.error || '-'}</Descriptions.Item>
            </Descriptions>
            <Typography.Text strong>预览文本</Typography.Text>
            <pre style={preStyle}>{detail.previewText || '(无)'}</pre>
            <Typography.Text strong>参数</Typography.Text>
            <pre style={preStyle}>{prettyJson(detail.argsJson)}</pre>
            <Typography.Text strong>执行结果</Typography.Text>
            <pre style={preStyle}>{prettyJson(detail.resultJson)}</pre>
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

function prettyJson(raw?: string | null): string {
  if (!raw) return '(无)'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}
