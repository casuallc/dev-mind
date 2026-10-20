// CAP-76 FR-36 计划视图：submit_plan 确认单 + 步骤进度（RUNNING/WAITING/OK/FAILED）。
// 存在 EXECUTING 计划时每 5s 轮询一次（异步执行期间进度可见），无进行中计划不轮询。
import { useCallback, useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { Descriptions, Drawer, Input, Popover, Progress, Select, Space, Steps, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { getCopilotPlan, listCopilotPlans } from '../api'
import type { CopilotPlan, CopilotPlanStep } from '../types'
import { ACTION_STATUS_OPTIONS, ActionStatusBadge, RiskTag, StepStatusBadge } from './tags'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { fmtTime } from '../../../shared/utils/format'
import { DRAWER_FORM } from '../../../shared/utils/overlay'

export default function PlansPanel({ refreshTick = 0 }: { refreshTick?: number }) {
  const [status, setStatus] = useState<string>()
  const [actorDraft, setActorDraft] = useState('')
  const [keywordDraft, setKeywordDraft] = useState('')
  const [filters, setFilters] = useState<{ actor?: string; keyword?: string }>({})
  const [rows, setRows] = useState<CopilotPlan[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [loading, setLoading] = useState(false)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [detail, setDetail] = useState<(CopilotPlan & { steps: CopilotPlanStep[] }) | null>(null)

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      const r = await listCopilotPlans({
        status,
        actor: filters.actor,
        keyword: filters.keyword,
        page,
        size,
      })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      if (!silent) showError(e, '加载计划失败')
    } finally {
      if (!silent) setLoading(false)
    }
  }, [filters, status, page, size])

  useEffect(() => { load() }, [load, refreshTick])

  // 有 EXECUTING 计划时 5s 轮询（静默，不闪 loading）
  useEffect(() => {
    if (!rows.some((r) => r.status === 'EXECUTING')) return
    const timer = setInterval(() => load(true), 5000)
    return () => clearInterval(timer)
  }, [rows, load])

  const openDetail = useCallback(async (id: string) => {
    setDetailId(id)
    setDetail(null)
    try {
      const r = await getCopilotPlan(id)
      setDetail(r.plan)
    } catch (e) {
      showError(e, '加载计划详情失败')
    }
  }, [])

  const columns: ColumnsType<CopilotPlan> = [
    { title: '创建时间', dataIndex: 'createdAt', width: 160, render: (t) => fmtTime(t) },
    {
      title: '目标',
      dataIndex: 'goal',
      ellipsis: true,
      render: (g: string, r) => (
        <a onClick={(e) => { e.stopPropagation(); openDetail(r.id) }}>{g || '（未填写目标）'}</a>
      ),
    },
    {
      title: '进度',
      key: 'progress',
      width: 170,
      render: (_, r) => (
        <Space size={8}>
          <Progress
            type="circle" size={28}
            percent={r.totalSteps > 0 ? Math.round((r.completedSteps / r.totalSteps) * 100) : 0}
            status={r.status === 'FAILED' ? 'exception' : undefined}
          />
          <span>{r.completedSteps}/{r.totalSteps} 步</span>
        </Space>
      ),
    },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <ActionStatusBadge status={s} /> },
    { title: '风险', dataIndex: 'risk', width: 80, render: (r: string) => <RiskTag risk={r} /> },
    { title: '执行身份', dataIndex: 'actor', width: 110, render: (s?: string) => s || '-' },
    { title: '短码', dataIndex: 'shortCode', width: 110, render: (c: string) => <Typography.Text code>{c}</Typography.Text> },
    { title: '执行时间', dataIndex: 'executedAt', width: 160, render: (t?: string) => fmtTime(t) },
  ]

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear placeholder="状态" style={{ width: 110 }}
          value={status} onChange={(v) => { setStatus(v); setPage(0) }}
          options={ACTION_STATUS_OPTIONS}
        />
        <Input.Search
          allowClear placeholder="执行身份" style={{ width: 150 }}
          value={actorDraft}
          onChange={(e) => setActorDraft(e.target.value)}
          onSearch={(v) => { setFilters((f) => ({ ...f, actor: v.trim() || undefined })); setPage(0) }}
        />
        <Input.Search
          allowClear placeholder="关键词（短码/目标）" style={{ width: 220 }}
          value={keywordDraft}
          onChange={(e) => setKeywordDraft(e.target.value)}
          onSearch={(v) => { setFilters((f) => ({ ...f, keyword: v.trim() || undefined })); setPage(0) }}
        />
      </Space>
      <FitTable<CopilotPlan>
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
        onRow={(r) => ({ onClick: () => openDetail(r.id), style: { cursor: 'pointer' } })}
        locale={{ emptyText: '暂无计划。智能体把多步操作打包成 submit_plan 并经确认后，会在这里看到逐步进度。' }}
      />

      <Drawer
        title={detail ? `计划：${detail.goal || '（未填写目标）'}` : '计划详情'}
        open={!!detailId}
        onClose={() => { setDetailId(null); setDetail(null) }}
        width={DRAWER_FORM}
      >
        {detail && (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="状态"><ActionStatusBadge status={detail.status} /></Descriptions.Item>
              <Descriptions.Item label="进度">{detail.completedSteps}/{detail.totalSteps} 步</Descriptions.Item>
              <Descriptions.Item label="执行身份">{detail.actor || '-'}</Descriptions.Item>
              <Descriptions.Item label="风险"><RiskTag risk={detail.risk} /></Descriptions.Item>
              <Descriptions.Item label="创建时间">{fmtTime(detail.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="收口时间">{fmtTime(detail.executedAt)}</Descriptions.Item>
              {detail.error && (
                <Descriptions.Item label="错误" span={2}>{detail.error}</Descriptions.Item>
              )}
            </Descriptions>
            <Typography.Text strong>步骤</Typography.Text>
            <Steps
              direction="vertical"
              size="small"
              current={-1}
              items={detail.steps.map((s) => ({
                title: (
                  <Space size={8}>
                    <span>步骤 {s.seq} · {s.title || s.tool}</span>
                    <StepStatusBadge status={s.status} />
                    {s.await && <Tag color="purple">等 {s.await.kind} 终态</Tag>}
                  </Space>
                ),
                status: stepStatusOf(s),
                description: (
                  <Space direction="vertical" size={4} style={{ paddingBottom: 8 }}>
                    {s.resultSummary && <Typography.Text type="secondary">{s.resultSummary}</Typography.Text>}
                    {s.error && <Typography.Text type="danger">{s.error}</Typography.Text>}
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {s.startedAt ? `开始 ${fmtTime(s.startedAt)}` : ''}
                      {s.durationMs != null && s.durationMs > 0 ? ` · 耗时 ${(s.durationMs / 1000).toFixed(1)}s` : ''}
                      {s.argsJson && (
                        <Popover
                          content={<pre style={preStyle}>{prettyJson(s.argsJson)}</pre>}
                          title="步骤参数"
                          trigger="click"
                        >
                          {' · '}<a>参数</a>
                        </Popover>
                      )}
                    </Typography.Text>
                  </Space>
                ),
              }))}
            />
          </Space>
        )}
      </Drawer>
    </div>
  )
}

function stepStatusOf(s: CopilotPlanStep): 'finish' | 'error' | 'process' | 'wait' {
  switch (s.status) {
    case 'OK': return 'finish'
    case 'FAILED': return 'error'
    case 'RUNNING':
    case 'WAITING': return 'process'
    default: return 'wait'
  }
}

const preStyle: CSSProperties = {
  background: '#f6f6f6',
  padding: 12,
  borderRadius: 4,
  fontSize: 12,
  whiteSpace: 'pre-wrap',
  margin: 0,
  maxWidth: 480,
}

function prettyJson(raw?: string | null): string {
  if (!raw) return '(无)'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}
