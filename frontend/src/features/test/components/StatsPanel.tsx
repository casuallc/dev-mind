// CAP-104 FR-21 测试统计：趋势 / Top 失败 / flaky / 耗时 / 环境对比。
// 通过率口径 = PASS / (PASS+FAIL+ERROR)；BLOCKED/SKIP 不计入分母但单独展示。
import {
  Button,
  InputNumber,
  Progress,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import { getTestStats, listPlans } from '../api'
import type {
  StatsEnvironment,
  StatsFailure,
  StatsFlaky,
  StatsTrendPoint,
  TestPlan,
  TestStats,
} from '../types'
import type { ProjectEnvironment } from '../../projects/types'
import { listEnvironments } from '../../projects/api'
import { pagePaneScrollStyle } from '../../../shared/utils/pageLayout'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

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

export default function StatsPanel({ projectId }: { projectId: string }) {
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

  const trendColumns: ColumnsType<StatsTrendPoint> = [
    { title: '时间桶', dataIndex: 'bucket', width: 130 },
    { title: '运行数', dataIndex: 'runs', width: 80 },
    { title: '用例', dataIndex: 'total', width: 80 },
    { title: '通过', dataIndex: 'passed', width: 80 },
    { title: '失败', dataIndex: 'failed', width: 80 },
    { title: '异常', dataIndex: 'errors', width: 80 },
    { title: '阻塞', dataIndex: 'blocked', width: 80 },
    { title: '跳过', dataIndex: 'skipped', width: 80 },
    {
      title: '通过率', dataIndex: 'passRate', width: 180,
      render: (v: number | null) => (v == null ? '-' : (
        <Progress percent={v} size="small" strokeColor={v >= 90 ? '#52c41a' : v >= 60 ? '#fa8c16' : '#ff4d4f'} />
      )),
    },
  ]

  const failureColumns: ColumnsType<StatsFailure> = [
    { title: '用例', key: 'name', ellipsis: true, render: (_, f) => f.name || f.caseKey || `#${f.caseId ?? '-'}` },
    { title: '失败', dataIndex: 'failures', width: 70 },
    { title: '异常', dataIndex: 'errors', width: 70 },
    { title: '分类', dataIndex: 'failureCategory', width: 110, render: (v: string | null) => (v ? <Tag>{v}</Tag> : '-') },
    { title: '最近状态', dataIndex: 'lastStatus', width: 90, render: (v: string | null) => (v ? <Tag>{v}</Tag> : '-') },
    { title: '最近发生', dataIndex: 'lastAt', width: 170, render: (v: string | null) => (v ? fmtTime(v) : '-') },
    { title: '最近错误', dataIndex: 'lastError', ellipsis: true, render: (v: string | null) => v || '-' },
  ]

  const flakyColumns: ColumnsType<StatsFlaky> = [
    { title: '用例', key: 'name', ellipsis: true, render: (_, f) => f.name || f.caseKey || `#${f.caseId ?? '-'}` },
    { title: '样本', dataIndex: 'samples', width: 70 },
    { title: '通过', dataIndex: 'passes', width: 70 },
    { title: '失败', dataIndex: 'failures', width: 70 },
    { title: '阻塞', dataIndex: 'blocked', width: 70 },
    { title: '失败率', dataIndex: 'failRate', width: 90, render: (v: number | null) => (v == null ? '-' : `${v}%`) },
    {
      title: '重试后通过', dataIndex: 'retryPass', width: 100,
      render: (v: boolean) => (v ? <Tag color="orange">是</Tag> : '-'),
    },
    { title: '最近状态', dataIndex: 'lastStatus', width: 90, render: (v: string | null) => (v ? <Tag>{v}</Tag> : '-') },
    { title: '最近发生', dataIndex: 'lastAt', width: 170, render: (v: string | null) => (v ? fmtTime(v) : '-') },
  ]

  const envColumns: ColumnsType<StatsEnvironment> = [
    { title: '环境', key: 'name', ellipsis: true, render: (_, e) => e.name || (e.environmentId ? `#${e.environmentId}` : '未指定') },
    { title: '运行数', dataIndex: 'runs', width: 80 },
    { title: '用例', dataIndex: 'total', width: 80 },
    { title: '通过', dataIndex: 'passed', width: 80 },
    { title: '失败', dataIndex: 'failed', width: 80 },
    { title: '异常', dataIndex: 'errors', width: 80 },
    { title: '阻塞', dataIndex: 'blocked', width: 80 },
    {
      title: '通过率', dataIndex: 'passRate', width: 180,
      render: (v: number | null) => (v == null ? '-' : (
        <Progress percent={v} size="small" strokeColor={v >= 90 ? '#52c41a' : v >= 60 ? '#fa8c16' : '#ff4d4f'} />
      )),
    },
    { title: 'P95', dataIndex: 'p95Ms', width: 100, render: (v: number | null) => fmtMs(v) },
  ]

  return (
    <div style={pagePaneScrollStyle}>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
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
          <span style={{ fontSize: 12 }}>flaky 最小样本</span>
          <InputNumber min={1} max={100} value={minSamples} onChange={(v) => setMinSamples(v ?? 5)} style={{ width: 80 }} />
          <Button onClick={load} loading={loading}>刷新</Button>
        </Space>

        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          通过率 = PASS / (PASS + FAIL + ERROR)，BLOCKED / SKIP 不计入分母但单独展示；窗口 {data ? `${fmtTime(data.from)} ~ ${fmtTime(data.to)}` : '-'}。
        </Typography.Text>

        <Space wrap size={16} style={{ fontSize: 13 }}>
          <span>运行 <Typography.Text strong>{num(overview.runs)}</Typography.Text></span>
          <span>用例 <Typography.Text strong>{num(overview.cases)}</Typography.Text></span>
          <span>去重用例 <Typography.Text strong>{num(overview.distinctCases)}</Typography.Text></span>
          <span style={{ color: '#52c41a' }}>通过 {num(overview.passed)}</span>
          <span style={{ color: '#ff4d4f' }}>失败 {num(overview.failed)}</span>
          <span style={{ color: '#c41d7f' }}>异常 {num(overview.errors)}</span>
          <span style={{ color: '#d48806' }}>阻塞 {num(overview.blocked)}</span>
          <span>跳过 {num(overview.skipped)}</span>
          <span>通过率 <Typography.Text strong>{overview.passRate == null ? '-' : `${overview.passRate}%`}</Typography.Text></span>
          <span>平均耗时 <Typography.Text strong>{fmtMs(overview.avgDurationMs)}</Typography.Text></span>
          <span>
            耗时 P50/P95 <Typography.Text strong>{fmtMs(duration.p50Ms)} / {fmtMs(duration.p95Ms)}</Typography.Text>
          </span>
        </Space>

        <Typography.Text strong style={{ fontSize: 13 }}>趋势（{data?.granularity ?? granularity}）</Typography.Text>
        <Table<StatsTrendPoint>
          rowKey="bucket"
          size="small"
          loading={loading}
          columns={trendColumns}
          dataSource={data?.trend ?? []}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: '窗口内暂无运行数据' }}
        />

        <Typography.Text strong style={{ fontSize: 13 }}>Top 失败用例</Typography.Text>
        <Table<StatsFailure>
          rowKey={(f) => `${f.caseId}-${f.caseKey}`}
          size="small"
          loading={loading}
          columns={failureColumns}
          dataSource={data?.topFailures ?? []}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          locale={{ emptyText: '暂无失败' }}
        />

        <Typography.Text strong style={{ fontSize: 13 }}>Flaky 用例（样本 ≥ {minSamples}）</Typography.Text>
        <Table<StatsFlaky>
          rowKey={(f) => `${f.caseId}-${f.caseKey}`}
          size="small"
          loading={loading}
          columns={flakyColumns}
          dataSource={data?.flaky ?? []}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          locale={{ emptyText: '暂无 flaky 用例' }}
        />

        <Typography.Text strong style={{ fontSize: 13 }}>慢用例 P95</Typography.Text>
        <Table
          rowKey={(r: { caseId: number | null; name: string | null }) => `${r.caseId}-${r.name}`}
          size="small"
          loading={loading}
          dataSource={duration.slowCases ?? []}
          pagination={false}
          locale={{ emptyText: '暂无耗时数据' }}
          columns={[
            { title: '用例', key: 'name', ellipsis: true, render: (_, r) => r.name || (r.caseId ? `#${r.caseId}` : '-') },
            { title: '样本', dataIndex: 'samples', width: 80 },
            { title: 'P95', dataIndex: 'p95Ms', width: 100, render: (v: number) => fmtMs(v) },
          ]}
        />

        <Typography.Text strong style={{ fontSize: 13 }}>环境对比</Typography.Text>
        <Table<StatsEnvironment>
          rowKey={(e) => String(e.environmentId ?? 'none')}
          size="small"
          loading={loading}
          columns={envColumns}
          dataSource={data?.environments ?? []}
          pagination={false}
          locale={{ emptyText: '暂无环境数据' }}
        />
      </Space>
    </div>
  )
}
