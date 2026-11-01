// CAP-104 FR-21 测试统计：概览条固定，趋势 / 失败 / Flaky / 慢用例 / 环境对比按视角切换，单表撑满。
// 通过率口径 = PASS / (PASS+FAIL+ERROR)；BLOCKED/SKIP 不计入分母但单独展示。
import { ReloadOutlined } from '@ant-design/icons'
import {
  Button,
  Flex,
  InputNumber,
  Progress,
  Segmented,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { getTestStats, listPlans } from '../api'
import type {
  FailureCategory,
  StatsEnvironment,
  StatsFailure,
  StatsFlaky,
  StatsTrendPoint,
  TestPlan,
  TestStats,
} from '../types'
import type { ProjectEnvironment } from '../../projects/types'
import { listEnvironments } from '../../projects/api'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { FAILURE_CATEGORY_COLOR, RESULT_COLOR } from '../constants'

const num = (v: unknown): number => {
  if (typeof v === 'number') return v
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

const fmtMs = (v: unknown) => {
  if (v == null) return '-'
  const ms = num(v)
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}

const rateColor = (v: number) => (v >= 90 ? '#52c41a' : v >= 60 ? '#fa8c16' : '#ff4d4f')

const STATS_VIEWS = ['trend', 'failures', 'flaky', 'slow', 'environments'] as const
type StatsView = (typeof STATS_VIEWS)[number]

const STATS_VIEW_OPTIONS: { value: StatsView; label: string }[] = [
  { value: 'trend', label: '趋势' },
  { value: 'failures', label: '失败' },
  { value: 'flaky', label: 'Flaky' },
  { value: 'slow', label: '慢用例' },
  { value: 'environments', label: '环境对比' },
]

const VIEW_HINTS: Record<Exclude<StatsView, 'flaky'>, string> = {
  trend: '按时间桶聚合，最近的在前',
  failures: '按失败 + 异常次数降序，最多 10 条',
  slow: '按 P95 耗时降序，最多 10 条',
  environments: '按运行数降序，最多 10 个环境',
}

function PassRate({ value }: { value: number | null }) {
  if (value == null) return <Typography.Text type="secondary">-</Typography.Text>
  return <Progress percent={value} size="small" strokeColor={rateColor(value)} format={(p) => `${p}%`} />
}

function StatusTag({ value }: { value: string | null }) {
  if (!value) return <>-</>
  return <Tag color={RESULT_COLOR[value as keyof typeof RESULT_COLOR]}>{value}</Tag>
}

function SummaryItem({ label, value, color }: { label: string; value: ReactNode; color?: string }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'baseline', gap: 6, whiteSpace: 'nowrap' }}>
      <span style={{ color: 'rgba(0, 0, 0, 0.45)', fontSize: 12 }}>{label}</span>
      <span style={{ fontWeight: 600, color }}>{value}</span>
    </span>
  )
}

const groupDivider = <span style={{ width: 1, height: 14, background: '#e8e8e8' }} />

export default function StatsPanel({ projectId, onOpenRun }: {
  projectId: string
  onOpenRun?: (runId: number) => void
}) {
  const [view, setView] = useState<StatsView>('trend')
  const [days, setDays] = useState(30)
  const [granularity, setGranularity] = useState<'DAY' | 'WEEK'>('DAY')
  const [planId, setPlanId] = useState<number | undefined>()
  const [environmentId, setEnvironmentId] = useState<number | undefined>()
  const [minSamples, setMinSamples] = useState(5)
  const [data, setData] = useState<TestStats | null>(null)
  const [loading, setLoading] = useState(false)
  const [plans, setPlans] = useState<TestPlan[]>([])
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])

  useEffect(() => {
    listPlans(projectId).then(setPlans).catch(() => setPlans([]))
    listEnvironments(projectId).then(setEnvironments).catch(() => setEnvironments([]))
  }, [projectId])

  const load = () => {
    setLoading(true)
    getTestStats(projectId, { days, granularity, minSamples, planId, environmentId })
      .then(setData)
      .catch((e) => showError(e, '加载测试统计失败'))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, days, granularity, planId, environmentId, minSamples])

  const overview = data?.overview ?? {}
  const duration = data?.duration ?? {}
  const trendRows = useMemo(() => [...(data?.trend ?? [])].reverse(), [data?.trend])

  const trendColumns: ColumnsType<StatsTrendPoint> = [
    { title: '时间桶', dataIndex: 'bucket', width: 120 },
    { title: '运行数', dataIndex: 'runs', width: 80, align: 'right' },
    { title: '用例', dataIndex: 'total', width: 80, align: 'right' },
    {
      title: '通过', dataIndex: 'passed', width: 80, align: 'right',
      render: (v: number) => <span style={{ color: '#52c41a' }}>{v}</span>,
    },
    {
      title: '失败', dataIndex: 'failed', width: 80, align: 'right',
      render: (v: number) => <span style={{ color: v ? '#ff4d4f' : undefined }}>{v}</span>,
    },
    {
      title: '异常', dataIndex: 'errors', width: 80, align: 'right',
      render: (v: number) => <span style={{ color: v ? '#c41d7f' : undefined }}>{v}</span>,
    },
    {
      title: '阻塞', dataIndex: 'blocked', width: 80, align: 'right',
      render: (v: number) => <span style={{ color: v ? '#d48806' : undefined }}>{v}</span>,
    },
    { title: '跳过', dataIndex: 'skipped', width: 80, align: 'right' },
    { title: '通过率', dataIndex: 'passRate', width: 180, render: (v: number | null) => <PassRate value={v} /> },
  ]

  const failureColumns: ColumnsType<StatsFailure> = [
    { title: '用例', key: 'name', ellipsis: true, render: (_, f) => f.name || f.caseKey || `#${f.caseId ?? '-'}` },
    { title: '失败', dataIndex: 'failures', width: 70, align: 'right' },
    { title: '异常', dataIndex: 'errors', width: 70, align: 'right' },
    {
      title: '分类', dataIndex: 'failureCategory', width: 140,
      render: (v: string | null) => (v
        ? <Tag color={FAILURE_CATEGORY_COLOR[v as FailureCategory]}>{v}</Tag>
        : '-'),
    },
    { title: '最近状态', dataIndex: 'lastStatus', width: 90, render: (v: string | null) => <StatusTag value={v} /> },
    { title: '最近发生', dataIndex: 'lastAt', width: 170, render: (v: string | null) => (v ? fmtTime(v) : '-') },
    {
      title: '最近运行', dataIndex: 'lastRunId', width: 90,
      render: (v: number | null) => (v == null
        ? '-'
        : onOpenRun
          ? <Button size="small" onClick={() => onOpenRun(v)}>#{v}</Button>
          : `#${v}`),
    },
    { title: '最近错误', dataIndex: 'lastError', ellipsis: true, render: (v: string | null) => v || '-' },
  ]

  const flakyColumns: ColumnsType<StatsFlaky> = [
    { title: '用例', key: 'name', ellipsis: true, render: (_, f) => f.name || f.caseKey || `#${f.caseId ?? '-'}` },
    { title: '样本', dataIndex: 'samples', width: 70, align: 'right' },
    { title: '通过', dataIndex: 'passes', width: 70, align: 'right' },
    { title: '失败', dataIndex: 'failures', width: 70, align: 'right' },
    { title: '阻塞', dataIndex: 'blocked', width: 70, align: 'right' },
    {
      title: '失败率', dataIndex: 'failRate', width: 90, align: 'right',
      render: (v: number | null) => (v == null ? '-' : <span style={{ color: v > 0 ? '#ff4d4f' : undefined }}>{v}%</span>),
    },
    {
      title: '重试通过', dataIndex: 'retryPass', width: 100,
      render: (v: boolean) => (v ? <Tag color="orange">是</Tag> : '-'),
    },
    { title: '最近状态', dataIndex: 'lastStatus', width: 90, render: (v: string | null) => <StatusTag value={v} /> },
    { title: '最近发生', dataIndex: 'lastAt', width: 170, render: (v: string | null) => (v ? fmtTime(v) : '-') },
  ]

  const envColumns: ColumnsType<StatsEnvironment> = [
    { title: '环境', key: 'name', ellipsis: true, render: (_, e) => e.name || (e.environmentId ? `#${e.environmentId}` : '未指定') },
    { title: '运行数', dataIndex: 'runs', width: 80, align: 'right' },
    { title: '用例', dataIndex: 'total', width: 80, align: 'right' },
    { title: '通过', dataIndex: 'passed', width: 80, align: 'right' },
    { title: '失败', dataIndex: 'failed', width: 80, align: 'right' },
    { title: '异常', dataIndex: 'errors', width: 80, align: 'right' },
    { title: '阻塞', dataIndex: 'blocked', width: 80, align: 'right' },
    { title: '通过率', dataIndex: 'passRate', width: 180, render: (v: number | null) => <PassRate value={v} /> },
    { title: 'P95', dataIndex: 'p95Ms', width: 100, align: 'right', render: (v: number | null) => fmtMs(v) },
  ]

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        聚合统计窗口内的运行结果：趋势看整体走势，失败 / Flaky / 慢用例定位问题，环境对比看跨环境差异。
        通过率 = 通过 /（通过 + 失败 + 异常），阻塞 / 跳过不计入分母但单独展示。
      </Typography.Paragraph>

      <Flex justify="space-between" wrap gap={8} style={{ marginBottom: 12 }}>
        <Space wrap>
          <Select<number>
            style={{ width: 120 }}
            value={days}
            onChange={setDays}
            options={[7, 14, 30, 90, 180].map((d) => ({ value: d, label: `近 ${d} 天` }))}
          />
          <Select<'DAY' | 'WEEK'>
            style={{ width: 100 }}
            value={granularity}
            onChange={setGranularity}
            options={[{ value: 'DAY', label: '按天' }, { value: 'WEEK', label: '按周' }]}
          />
          <Select<number>
            style={{ width: 200 }}
            allowClear
            placeholder="全部方案"
            value={planId}
            onChange={setPlanId}
            options={plans.map((p) => ({ value: p.id, label: p.name }))}
          />
          <Select<number>
            style={{ width: 180 }}
            allowClear
            placeholder="全部环境"
            value={environmentId}
            onChange={setEnvironmentId}
            options={environments.map((e) => ({ value: e.id, label: e.name }))}
          />
        </Space>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
      </Flex>

      <div style={{
        display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '8px 20px',
        padding: '8px 12px', marginBottom: 12,
        background: '#fafafa', border: '1px solid #f0f0f0', borderRadius: 8, fontSize: 13,
      }}>
        <Space size={16} wrap>
          <SummaryItem label="运行" value={num(overview.runs)} />
          <SummaryItem label="用例" value={num(overview.cases)} />
          <SummaryItem label="去重用例" value={num(overview.distinctCases)} />
        </Space>
        {groupDivider}
        <Space size={16} wrap>
          <SummaryItem label="通过" value={num(overview.passed)} color="#52c41a" />
          <SummaryItem label="失败" value={num(overview.failed)} color="#ff4d4f" />
          <SummaryItem label="异常" value={num(overview.errors)} color="#c41d7f" />
          <SummaryItem label="阻塞" value={num(overview.blocked)} color="#d48806" />
          <SummaryItem label="跳过" value={num(overview.skipped)} />
        </Space>
        {groupDivider}
        <Space size={16} wrap>
          <SummaryItem
            label="通过率"
            value={overview.passRate == null ? '-' : `${overview.passRate}%`}
            color={overview.passRate == null ? undefined : rateColor(overview.passRate)}
          />
          <SummaryItem label="平均耗时" value={fmtMs(overview.avgDurationMs)} />
          <SummaryItem label="P50 / P95" value={`${fmtMs(duration.p50Ms)} / ${fmtMs(duration.p95Ms)}`} />
        </Space>
        <span style={{ marginLeft: 'auto', fontSize: 12, color: 'rgba(0, 0, 0, 0.45)' }}>
          窗口 {fmtTime(data?.from)} ~ {fmtTime(data?.to)}
        </span>
      </div>

      <Flex justify="space-between" align="center" wrap gap={8} style={{ marginBottom: 12 }}>
        <Segmented
          value={view}
          onChange={(v) => setView(v as StatsView)}
          options={STATS_VIEW_OPTIONS}
        />
        {view === 'flaky' ? (
          <Space size={8}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>最小样本</Typography.Text>
            <InputNumber
              min={1}
              max={100}
              value={minSamples}
              onChange={(v) => setMinSamples(v ?? 5)}
              style={{ width: 80 }}
            />
          </Space>
        ) : (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{VIEW_HINTS[view]}</Typography.Text>
        )}
      </Flex>

      {view === 'trend' && (
        <FitTable<StatsTrendPoint>
          rowKey="bucket"
          loading={loading}
          columns={trendColumns}
          dataSource={trendRows}
          pagination={{ ...LIST_PAGINATION, hideOnSinglePage: true }}
          scroll={{ x: 900 }}
          locale={{ emptyText: '窗口内暂无运行数据：到「运行历史」页签新建一次运行后再看趋势' }}
        />
      )}

      {view === 'failures' && (
        <FitTable<StatsFailure>
          rowKey={(f) => String(f.caseId ?? f.name ?? f.caseKey)}
          loading={loading}
          columns={failureColumns}
          dataSource={data?.topFailures ?? []}
          pagination={false}
          scroll={{ x: 960 }}
          locale={{ emptyText: '窗口内没有失败或异常用例' }}
        />
      )}

      {view === 'flaky' && (
        <FitTable<StatsFlaky>
          rowKey={(f) => String(f.caseId ?? f.name ?? f.caseKey)}
          loading={loading}
          columns={flakyColumns}
          dataSource={data?.flaky ?? []}
          pagination={{ ...LIST_PAGINATION, hideOnSinglePage: true }}
          scroll={{ x: 960 }}
          locale={{ emptyText: `窗口内没有样本 ≥ ${minSamples} 的 flaky 用例` }}
        />
      )}

      {view === 'slow' && (
        <FitTable
          rowKey={(r: { caseId: number | null; name: string | null }) => `${r.caseId}-${r.name}`}
          loading={loading}
          dataSource={duration.slowCases ?? []}
          pagination={false}
          scroll={{ x: 600 }}
          locale={{ emptyText: '窗口内没有可统计耗时的用例' }}
          columns={[
            { title: '用例', key: 'name', ellipsis: true, render: (_, r) => r.name || (r.caseId ? `#${r.caseId}` : '-') },
            { title: '样本', dataIndex: 'samples', width: 100, align: 'right' },
            { title: 'P95', dataIndex: 'p95Ms', width: 120, align: 'right', render: (v: number) => fmtMs(v) },
          ]}
        />
      )}

      {view === 'environments' && (
        <FitTable<StatsEnvironment>
          rowKey={(e) => String(e.environmentId ?? 'none')}
          loading={loading}
          columns={envColumns}
          dataSource={data?.environments ?? []}
          pagination={false}
          scroll={{ x: 900 }}
          locale={{ emptyText: '窗口内暂无环境维度数据' }}
        />
      )}
    </div>
  )
}
