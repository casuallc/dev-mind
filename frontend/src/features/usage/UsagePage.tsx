// CAP-67 用量统计页：总体卡（时段/用户筛选 + 指标）→ 每日趋势（纯 div 柱状图，零图表依赖）→
// 维度卡（按需求/项目/模型/用户 分组表 + Top 明细）。多区块页根容器走 pageRootScrollStyle。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Card, Col, Row, Segmented, Select, Space, Statistic, Table, Tag, Tooltip, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { listUsers } from '../auth/api'
import type { AuthUser } from '../auth/types'
import { isAdmin } from '../auth/authStore'
import { fmtCost, fmtTime, fmtTokens } from '../../shared/utils/format'
import { pageRootScrollStyle } from '../../shared/utils/pageLayout'
import { LIST_PAGINATION } from '../../shared/utils/table'
import { getUsageBreakdown, getUsageDaily, getUsageSummary, getUsageTop } from './api'
import type { UsageFilter } from './api'
import type { UsageBreakdownRow, UsageDailyPoint, UsageDim, UsageSummary, UsageTopRow } from './types'

type RangeKey = 'today' | '7d' | '30d' | 'all'

const RANGE_OPTIONS = [
  { label: '今日', value: 'today' },
  { label: '近7天', value: '7d' },
  { label: '近30天', value: '30d' },
  { label: '全部', value: 'all' },
]

/** 时段 → 筛选参数（from 含/to 不含，省略 to 即到当前；用量按 createdAt 归属） */
function rangeFilter(range: RangeKey): UsageFilter & { days: number } {
  if (range === 'all') return { days: 90 }
  const days = range === 'today' ? 1 : range === '7d' ? 7 : 30
  return { from: dayjs().startOf('day').subtract(days - 1, 'day').toISOString(), days }
}

const tokenSum = (r: { inputTokens: number; outputTokens: number }) => r.inputTokens + r.outputTokens

/** 纯 div 柱状图：无图表库依赖；每根柱 Tooltip 显示当日数值 */
function DailyBars({ points, metric }: { points: UsageDailyPoint[]; metric: 'cost' | 'tokens' }) {
  const values = points.map(p => (metric === 'cost' ? p.costUsd : p.tokens))
  const max = Math.max(...values, 0)
  const fmt = metric === 'cost' ? fmtCost : fmtTokens
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 2, height: 160, paddingTop: 8 }}>
      {points.map((p, i) => (
        <Tooltip
          key={p.date}
          title={`${p.date}：${fmt(values[i])}（${p.turns} 回合）`}
        >
          <div style={{ flex: 1, minWidth: 4, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', height: '100%' }}>
            <div
              style={{
                height: max > 0 ? `${Math.max((values[i] / max) * 100, values[i] > 0 ? 2 : 0)}%` : 0,
                background: 'var(--ant-color-primary, #1677ff)',
                borderRadius: '2px 2px 0 0',
                opacity: values[i] > 0 ? 1 : 0.15,
              }}
            />
            <div style={{ fontSize: 10, color: '#999', textAlign: 'center', whiteSpace: 'nowrap', overflow: 'hidden' }}>
              {points.length <= 10 || i % Math.ceil(points.length / 10) === 0 ? dayjs(p.date).format('MM-DD') : ''}
            </div>
          </div>
        </Tooltip>
      ))}
    </div>
  )
}

export default function UsagePage() {
  const navigate = useNavigate()
  const admin = isAdmin()
  const [range, setRange] = useState<RangeKey>('30d')
  const [userId, setUserId] = useState<string | undefined>()
  const [users, setUsers] = useState<AuthUser[]>([])
  const [loading, setLoading] = useState(false)
  const [summary, setSummary] = useState<UsageSummary | null>(null)
  const [daily, setDaily] = useState<UsageDailyPoint[]>([])
  const [metric, setMetric] = useState<'cost' | 'tokens'>('cost')
  const [dim, setDim] = useState<UsageDim | 'top'>('requirement')
  const [rows, setRows] = useState<UsageBreakdownRow[]>([])
  const [topRows, setTopRows] = useState<UsageTopRow[]>([])

  const load = useCallback(async () => {
    setLoading(true)
    const { days, ...filter } = rangeFilter(range)
    const f: UsageFilter = { ...filter, userId }
    try {
      const [s, d] = await Promise.all([getUsageSummary(f), getUsageDaily(days, f)])
      setSummary(s)
      setDaily(d)
      if (dim === 'top') {
        setTopRows(await getUsageTop(50, f))
      } else {
        setRows(await getUsageBreakdown(dim, f))
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载用量统计失败')
    } finally {
      setLoading(false)
    }
  }, [range, userId, dim])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    if (!admin) return
    listUsers().then(setUsers).catch(() => { /* 用户列表失败不挡主流程 */ })
  }, [admin])

  const dimOptions = useMemo(() => {
    const opts = [
      { label: '按需求', value: 'requirement' },
      { label: '按项目', value: 'project' },
      { label: '按模型', value: 'model' },
    ]
    if (admin) opts.push({ label: '按用户', value: 'user' })
    opts.push({ label: 'Top', value: 'top' })
    return opts
  }, [admin])

  const dimColumns: ColumnsType<UsageBreakdownRow> = useMemo(() => {
    const nameTitle = { requirement: '需求', project: '项目', model: '模型', user: '用户' }[dim as UsageDim] ?? '名称'
    const cols: ColumnsType<UsageBreakdownRow> = [
      {
        title: nameTitle,
        dataIndex: 'label',
        render: (label: string, r) =>
          dim === 'requirement' && r.key && r.projectId ? (
            <a onClick={() => navigate(`/projects/${r.projectId}/requirements/${r.key}`)}>{label}</a>
          ) : (
            label
          ),
      },
      { title: '会话数', dataIndex: 'sessionCount', width: 90, align: 'right' },
      { title: '问答数', dataIndex: 'chatCount', width: 90, align: 'right' },
      { title: '回合', dataIndex: 'turnCount', width: 90, align: 'right' },
      { title: '成本', dataIndex: 'costUsd', width: 110, align: 'right', render: (v: number) => fmtCost(v) },
      {
        title: '总 tokens', key: 'tokens', width: 110, align: 'right',
        render: (_, r) => fmtTokens(tokenSum(r)),
      },
      { title: '输入', dataIndex: 'inputTokens', width: 100, align: 'right', render: (v: number) => fmtTokens(v) },
      { title: '输出', dataIndex: 'outputTokens', width: 100, align: 'right', render: (v: number) => fmtTokens(v) },
      {
        title: '缓存读', dataIndex: 'cacheReadTokens', width: 100, align: 'right', render: (v: number) => fmtTokens(v),
      },
      {
        title: '缓存写', dataIndex: 'cacheCreationTokens', width: 100, align: 'right', render: (v: number) => fmtTokens(v),
      },
    ]
    return cols
  }, [dim, navigate])

  const topColumns: ColumnsType<UsageTopRow> = [
    {
      title: '来源', dataIndex: 'source', width: 80,
      render: (v: string) => (v === 'CHAT' ? <Tag>问答</Tag> : <Tag color="blue">会话</Tag>),
    },
    { title: '标题', dataIndex: 'title', ellipsis: true, render: (v: string | null) => v || '-' },
    {
      title: '需求', dataIndex: 'requirementTitle', ellipsis: true,
      render: (v: string | null, r) =>
        v && r.projectId && r.requirementId ? (
          <a onClick={() => navigate(`/projects/${r.projectId}/requirements/${r.requirementId}`)}>{v}</a>
        ) : (
          v || '-'
        ),
    },
    { title: '模型', dataIndex: 'model', width: 140, ellipsis: true, render: (v: string | null) => v || '默认' },
    { title: '创建人', dataIndex: 'createdBy', width: 110, render: (v: string | null) => v || '-' },
    { title: '回合', dataIndex: 'turnCount', width: 80, align: 'right' },
    { title: '成本', dataIndex: 'costUsd', width: 100, align: 'right', render: (v: number) => fmtCost(v) },
    {
      title: '总 tokens', key: 'tokens', width: 100, align: 'right',
      render: (_, r) => fmtTokens(tokenSum(r)),
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 160, render: (v: string | null) => fmtTime(v) },
  ]

  return (
    <div style={pageRootScrollStyle}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card
          title="用量统计"
          loading={loading && !summary}
          extra={
            <Space>
              <Segmented options={RANGE_OPTIONS} value={range} onChange={v => setRange(v as RangeKey)} />
              {admin && (
                <Select
                  allowClear
                  showSearch
                  placeholder="全部用户"
                  style={{ width: 160 }}
                  value={userId}
                  onChange={setUserId}
                  options={users.map(u => ({ label: `${u.displayName || u.username}（${u.username}）`, value: u.username }))}
                  optionFilterProp="label"
                />
              )}
              <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading} />
            </Space>
          }
        >
          {summary && (
            <Row gutter={[24, 16]}>
              <Col flex="auto"><Statistic title="总成本" value={fmtCost(summary.costUsd)} /></Col>
              <Col flex="auto"><Statistic title="总 tokens" value={fmtTokens(summary.inputTokens + summary.outputTokens)} /></Col>
              <Col flex="auto"><Statistic title="输入" value={fmtTokens(summary.inputTokens)} /></Col>
              <Col flex="auto"><Statistic title="输出" value={fmtTokens(summary.outputTokens)} /></Col>
              <Col flex="auto"><Statistic title="缓存读" value={fmtTokens(summary.cacheReadTokens)} /></Col>
              <Col flex="auto"><Statistic title="缓存写" value={fmtTokens(summary.cacheCreationTokens)} /></Col>
              <Col flex="auto"><Statistic title="回合" value={summary.turnCount} /></Col>
              <Col flex="auto"><Statistic title="会话数" value={summary.sessionCount} /></Col>
              <Col flex="auto"><Statistic title="问答数" value={summary.chatCount} /></Col>
            </Row>
          )}
        </Card>

        <Card
          title={
            <Segmented
              options={[{ label: '成本', value: 'cost' }, { label: 'Tokens', value: 'tokens' }]}
              value={metric}
              onChange={v => setMetric(v as 'cost' | 'tokens')}
            />
          }
        >
          <DailyBars points={daily} metric={metric} />
        </Card>

        <Card
          title={<Segmented options={dimOptions} value={dim} onChange={v => setDim(v as UsageDim | 'top')} />}
        >
          {dim === 'top' ? (
            <Table<UsageTopRow>
              rowKey={r => `${r.source}:${r.id}`}
              size="small"
              loading={loading}
              columns={topColumns}
              dataSource={topRows}
              pagination={LIST_PAGINATION}
            />
          ) : (
            <Table<UsageBreakdownRow>
              rowKey={r => `${dim}:${r.key ?? '-'}`}
              size="small"
              loading={loading}
              columns={dimColumns}
              dataSource={rows}
              pagination={LIST_PAGINATION}
            />
          )}
        </Card>
      </Space>
    </div>
  )
}
