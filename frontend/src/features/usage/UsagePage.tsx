// CAP-67 用量统计页：总体卡（时段/用户筛选 + 指标）→ 每日趋势（纯 div 柱状图，零图表依赖）→
// 维度卡（按需求/项目/模型/用户 分组表 + Top 明细）。
// 仅作为 /home 的页内视图嵌入（/usage 重定向到 /home?view=usage）：外层 Card body 统一滚动，本页根不再自带滚动容器。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Card, Col, Row, Segmented, Select, Space, Statistic, Table, Tag, Tooltip, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { listUsers } from '../auth/api'
import type { AuthUser } from '../auth/types'
import { isAdmin } from '../auth/authStore'
import { fmtTime, fmtTokens } from '../../shared/utils/format'
import { LIST_PAGINATION } from '../../shared/utils/table'
import { getUsageBreakdown, getUsageDaily, getUsageHourly, getUsageRecords, getUsageSummary, getUsageTop } from './api'
import type { UsageFilter } from './api'
import type {
  UsageBreakdownRow,
  UsageDailyPoint,
  UsageDim,
  UsageHourlyPoint,
  UsageRecordRow,
  UsageSummary,
  UsageTopRow,
} from './types'

type RangeKey = 'today' | '7d' | '30d' | 'all'

const RANGE_OPTIONS = [
  { label: '今日', value: 'today' },
  { label: '近7天', value: '7d' },
  { label: '近30天', value: '30d' },
  { label: '全部', value: 'all' },
]

/** 自动刷新档位（秒），0=关 */
const REFRESH_OPTIONS = [
  { label: '刷新:关', value: 0 },
  { label: '30s', value: 30 },
  { label: '60s', value: 60 },
]

/** 时段 → 筛选参数（from 含/to 不含，省略 to 即到当前；用量按 createdAt 归属） */
function rangeFilter(range: RangeKey): UsageFilter & { days: number } {
  if (range === 'all') return { days: 90 }
  const days = range === 'today' ? 1 : range === '7d' ? 7 : 30
  return { from: dayjs().startOf('day').subtract(days - 1, 'day').toISOString(), days }
}

const tokenSum = (r: { inputTokens: number; outputTokens: number }) => r.inputTokens + r.outputTokens

/** 趋势柱通用数据点：label 横轴标签（日='MM-DD'/小时='HH:00'），hint 为 Tooltip 全文 */
export interface BarPoint {
  key: string
  label: string
  value: number
  hint: string
}

/** 纯 div 柱状图：无图表库依赖；每根柱 Tooltip 显示 hint。数据源无关（日/小时皆可用） */
function DailyBars({ bars }: { bars: BarPoint[] }) {
  const max = Math.max(...bars.map(b => b.value), 0)
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 2, height: 160, paddingTop: 8 }}>
      {bars.map((b, i) => (
        <Tooltip key={b.key} title={`${b.label}：${b.hint}`}>
          <div style={{ flex: 1, minWidth: 4, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', height: '100%' }}>
            <div
              style={{
                height: max > 0 ? `${Math.max((b.value / max) * 100, b.value > 0 ? 2 : 0)}%` : 0,
                background: 'var(--ant-color-primary, #1677ff)',
                borderRadius: '2px 2px 0 0',
                opacity: b.value > 0 ? 1 : 0.15,
              }}
            />
            <div style={{ fontSize: 10, color: '#999', textAlign: 'center', whiteSpace: 'nowrap', overflow: 'hidden' }}>
              {bars.length <= 10 || i % Math.ceil(bars.length / 10) === 0 ? b.label : ''}
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
  const [model, setModel] = useState<string | undefined>()
  const [modelOptions, setModelOptions] = useState<string[]>([])
  const [users, setUsers] = useState<AuthUser[]>([])
  const [refreshSec, setRefreshSec] = useState(0)
  const [lastSync, setLastSync] = useState<dayjs.Dayjs | null>(null)
  const [loading, setLoading] = useState(false)
  const [summary, setSummary] = useState<UsageSummary | null>(null)
  const [daily, setDaily] = useState<UsageDailyPoint[]>([])
  const [hourly, setHourly] = useState<UsageHourlyPoint[]>([])
  const [metric, setMetric] = useState<'tokens' | 'turns'>('tokens')
  const [dim, setDim] = useState<UsageDim | 'top' | 'records'>('requirement')
  const [rows, setRows] = useState<UsageBreakdownRow[]>([])
  const [topRows, setTopRows] = useState<UsageTopRow[]>([])
  // 请求日志视图：服务端分页受控（LIST_PAGINATION 仅供全量加载的表）
  const [recRows, setRecRows] = useState<UsageRecordRow[]>([])
  const [recTotal, setRecTotal] = useState(0)
  const [recPage, setRecPage] = useState(1)
  const [recSize, setRecSize] = useState(20)
  const [recSource, setRecSource] = useState<'SESSION' | 'CHAT' | undefined>()
  const [recLoading, setRecLoading] = useState(false)

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    const { days, ...filter } = rangeFilter(range)
    const f: UsageFilter = { ...filter, userId, model }
    try {
      // 今日走逐回合账本的按小时趋势（上线前无账本数据，24 桶全零属已知口径）；
      // 其余时段走累计列的每日趋势
      const [s, d] = await Promise.all([
        getUsageSummary(f),
        range === 'today' ? getUsageHourly(f) : getUsageDaily(days, f),
      ])
      setSummary(s)
      if (range === 'today') setHourly(d as UsageHourlyPoint[])
      else setDaily(d as UsageDailyPoint[])
      if (dim === 'top') {
        setTopRows(await getUsageTop(50, f))
      } else if (dim !== 'records') {
        setRows(await getUsageBreakdown(dim, f))
      }
      setLastSync(dayjs())
      // 模型下拉的候选值（不带 model 过滤的全量分组；空模型「默认」桶不可筛选故排除）
      if (!silent) {
        getUsageBreakdown('model', { ...filter, userId })
          .then(list => setModelOptions(list.map(r => r.key).filter((k): k is string => !!k)))
          .catch(() => { /* 候选失败不挡主流程 */ })
      }
    } catch (e) {
      if (!silent) message.error(e instanceof Error ? e.message : '加载用量统计失败')
    } finally {
      if (!silent) setLoading(false)
    }
  }, [range, userId, model, dim])

  const loadRecords = useCallback(async (silent = false) => {
    if (dim !== 'records') return
    if (!silent) setRecLoading(true)
    const { days: _days, ...filter } = rangeFilter(range)
    try {
      const pv = await getUsageRecords({
        ...filter, userId, model, source: recSource, page: recPage - 1, size: recSize,
      })
      setRecRows(pv.items)
      setRecTotal(pv.total)
      if (!silent) setLastSync(dayjs())
    } catch (e) {
      if (!silent) message.error(e instanceof Error ? e.message : '加载请求日志失败')
    } finally {
      if (!silent) setRecLoading(false)
    }
  }, [dim, range, userId, model, recSource, recPage, recSize])

  useEffect(() => { void load() }, [load])
  useEffect(() => { void loadRecords() }, [loadRecords])

  // 筛选变更回第一页（页码与筛选同帧变更时 React 合并为一次加载）
  useEffect(() => { setRecPage(1) }, [range, userId, model, recSource])

  // 自动刷新：静默加载不闪 loading；页签隐藏时跳过（切回后下一拍自然补上）；
  // 档位变更/切视图/卸载都会经依赖清理旧定时器
  useEffect(() => {
    if (refreshSec <= 0) return
    const timer = setInterval(() => {
      if (!document.hidden) {
        void load(true)
        void loadRecords(true)
      }
    }, refreshSec * 1000)
    return () => clearInterval(timer)
  }, [refreshSec, load, loadRecords])

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
    opts.push({ label: '请求日志', value: 'records' })
    return opts
  }, [admin])

  /** 趋势柱数据：今日=按小时（账本），其余=按日（累计列）；两口径并存见 load 注释 */
  const bars = useMemo<BarPoint[]>(() => {
    const hint = (tokens: number, turns: number) =>
      metric === 'tokens' ? `${fmtTokens(tokens)}（${turns} 回合）` : `${turns} 回合（${fmtTokens(tokens)}）`
    if (range === 'today') {
      return hourly.map(h => ({
        key: h.hour,
        label: `${h.hour}:00`,
        value: metric === 'tokens' ? h.tokens : h.turns,
        hint: hint(h.tokens, h.turns),
      }))
    }
    return daily.map(p => ({
      key: p.date,
      label: dayjs(p.date).format('MM-DD'),
      value: metric === 'tokens' ? p.tokens : p.turns,
      hint: hint(p.tokens, p.turns),
    }))
  }, [range, hourly, daily, metric])

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
    {
      title: '总 tokens', key: 'tokens', width: 100, align: 'right',
      render: (_, r) => fmtTokens(tokenSum(r)),
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 160, render: (v: string | null) => fmtTime(v) },
  ]

  const recColumns: ColumnsType<UsageRecordRow> = [
    { title: '时间', dataIndex: 'createdAt', width: 160, render: (v: string | null) => fmtTime(v) },
    {
      title: '来源', dataIndex: 'source', width: 80,
      render: (v: string) => (v === 'CHAT' ? <Tag>问答</Tag> : <Tag color="blue">会话</Tag>),
    },
    {
      title: '标题', dataIndex: 'title', ellipsis: true,
      render: (v: string | null, r) => {
        const text = v || '-'
        // 深链对齐 /sessions?sid= 先例；问答侧 ?cid= 由 ChatsBoard 消费
        const href = r.source === 'CHAT' ? `/chats?cid=${r.sessionId}` : `/sessions?sid=${r.sessionId}`
        return <a onClick={() => navigate(href)}>{text}</a>
      },
    },
    { title: '模型', dataIndex: 'model', width: 140, ellipsis: true, render: (v: string | null) => v || '默认' },
    { title: '创建人', dataIndex: 'owner', width: 110, render: (v: string | null) => v || '-' },
    {
      title: '耗时', dataIndex: 'durationMs', width: 90, align: 'right',
      render: (v: number | null) => (v == null ? '-' : `${(v / 1000).toFixed(1)}s`),
    },
    { title: '输入', dataIndex: 'inputTokens', width: 90, align: 'right', render: (v: number | null) => (v == null ? '-' : fmtTokens(v)) },
    { title: '输出', dataIndex: 'outputTokens', width: 90, align: 'right', render: (v: number | null) => (v == null ? '-' : fmtTokens(v)) },
    { title: '缓存读', dataIndex: 'cacheReadTokens', width: 90, align: 'right', render: (v: number | null) => (v == null ? '-' : fmtTokens(v)) },
    { title: '缓存写', dataIndex: 'cacheCreationTokens', width: 90, align: 'right', render: (v: number | null) => (v == null ? '-' : fmtTokens(v)) },
    {
      title: '总 tokens', key: 'tokens', width: 100, align: 'right',
      render: (_, r) => (r.inputTokens == null && r.outputTokens == null ? '-' : fmtTokens((r.inputTokens ?? 0) + (r.outputTokens ?? 0))),
    },
    {
      title: '速度', dataIndex: 'tokensPerSec', width: 110, align: 'right',
      render: (v: number | null) => (v == null ? '-' : `${v.toFixed(1)} tok/s`),
    },
  ]

  return (
    <div>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Card
          title="用量统计"
          loading={loading && !summary}
          extra={
            <Space>
              <Segmented options={RANGE_OPTIONS} value={range} onChange={v => setRange(v as RangeKey)} />
              <Select
                allowClear
                showSearch
                placeholder="全部模型"
                style={{ width: 150 }}
                value={model}
                onChange={setModel}
                options={modelOptions.map(m => ({ label: m, value: m }))}
              />
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
              <Segmented
                options={REFRESH_OPTIONS}
                value={refreshSec}
                onChange={v => setRefreshSec(v as number)}
              />
              {lastSync && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  上次同步 {lastSync.format('HH:mm:ss')}
                </Typography.Text>
              )}
              <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading} />
            </Space>
          }
        >
          {summary && (
            <Row gutter={[24, 16]}>
              <Col flex="auto"><Statistic title="总 tokens" value={fmtTokens(summary.inputTokens + summary.outputTokens)} /></Col>
              <Col flex="auto"><Statistic title="输入" value={fmtTokens(summary.inputTokens)} /></Col>
              <Col flex="auto"><Statistic title="输出" value={fmtTokens(summary.outputTokens)} /></Col>
              <Col flex="auto"><Statistic title="缓存读" value={fmtTokens(summary.cacheReadTokens)} /></Col>
              <Col flex="auto"><Statistic title="缓存写" value={fmtTokens(summary.cacheCreationTokens)} /></Col>
              {/* 缓存命中率 = 缓存读/(输入+缓存读)，前端派生零后端改动；分母 0（无记录）显示 - */}
              <Col flex="auto">
                <Statistic
                  title="缓存命中率"
                  value={
                    summary.inputTokens + summary.cacheReadTokens > 0
                      ? `${((summary.cacheReadTokens / (summary.inputTokens + summary.cacheReadTokens)) * 100).toFixed(1)}%`
                      : '-'
                  }
                />
              </Col>
              <Col flex="auto"><Statistic title="回合" value={summary.turnCount} /></Col>
              <Col flex="auto"><Statistic title="会话数" value={summary.sessionCount} /></Col>
              <Col flex="auto"><Statistic title="问答数" value={summary.chatCount} /></Col>
            </Row>
          )}
        </Card>

        <Card
          title={
            <Segmented
              options={[{ label: 'Tokens', value: 'tokens' }, { label: '请求数', value: 'turns' }]}
              value={metric}
              onChange={v => setMetric(v as 'tokens' | 'turns')}
            />
          }
        >
          <DailyBars bars={bars} />
        </Card>

        <Card
          title={<Segmented options={dimOptions} value={dim} onChange={v => setDim(v as UsageDim | 'top' | 'records')} />}
          extra={
            dim === 'records' ? (
              <Select
                allowClear
                placeholder="全部来源"
                style={{ width: 120 }}
                value={recSource}
                onChange={setRecSource}
                options={[
                  { label: '会话', value: 'SESSION' },
                  { label: '问答', value: 'CHAT' },
                ]}
              />
            ) : undefined
          }
        >
          {dim === 'records' ? (
            <Table<UsageRecordRow>
              rowKey={r => String(r.id)}
              size="small"
              loading={recLoading}
              columns={recColumns}
              dataSource={recRows}
              pagination={{
                current: recPage,
                pageSize: recSize,
                total: recTotal,
                showSizeChanger: true,
                showTotal: t => `共 ${t} 条`,
              }}
              onChange={p => {
                setRecPage(p.current ?? 1)
                setRecSize(p.pageSize ?? 20)
              }}
            />
          ) : dim === 'top' ? (
            <Table<UsageTopRow>
              key="top"
              rowKey={r => `${r.source}:${r.id}`}
              size="small"
              loading={loading}
              columns={topColumns}
              dataSource={topRows}
              pagination={LIST_PAGINATION}
            />
          ) : (
            <Table<UsageBreakdownRow>
              key={dim}
              // 未归属桶 key 为 null，须用 label 兜底：会话/问答两桶同 key 会撞 rowKey，
              // React 重复 key 协调漏删先出现的行纤维 → 切 tab 后残留行永久累积（224 实测）
              rowKey={r => `${dim}:${r.key ?? r.label}`}
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
