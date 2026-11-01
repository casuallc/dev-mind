// CAP-104 M2/M3 方案运行详情：WS 实时进度、方案项/子运行/用例下钻、取消/重跑、
// 证据包导出、基线固化与回归对比。
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Input,
  Modal,
  Progress,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useRef, useState } from 'react'
import {
  cancelPlanRun,
  compareBaseline,
  createBaseline,
  deleteBaseline,
  downloadPlanRunEvidenceZip,
  getPlanRun,
  getPlanRunReport,
  listBaselines,
  listPlanRunCases,
  rerunPlanRun,
} from '../api'
import type {
  BaselineCaseDiff,
  BaselineComparison,
  PlanCaseResult,
  PlanChildRun,
  PlanItemResult,
  TestBaseline,
  TestPlanRun,
} from '../types'
import {
  CONCLUSION_COLOR,
  FAILURE_CATEGORY_COLOR,
  RESULT_COLOR,
  STATUS_COLOR,
} from '../constants'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'
import { durationMs, fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

interface Props {
  planRunId: number | null
  onClose: () => void
  onOpenChild: (runId: number) => void
  onOpenPlanRun: (id: number) => void
  canRunPlan: boolean
  canManageBaseline: boolean
  canExportEvidence: boolean
}

const num = (v: unknown): number => {
  if (typeof v === 'number') return v
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

const activeStatus = (s: string | null | undefined) => s === 'QUEUED' || s === 'RUNNING'

export default function PlanRunDetailDrawer({
  planRunId, onClose, onOpenChild, onOpenPlanRun,
  canRunPlan, canManageBaseline, canExportEvidence,
}: Props) {
  const [d, setD] = useState<TestPlanRun | null>(null)
  const [cases, setCases] = useState<PlanCaseResult[]>([])
  const [loading, setLoading] = useState(false)
  const [connected, setConnected] = useState(false)
  const [logLines, setLogLines] = useState<string[]>([])
  const [tab, setTab] = useState('items')
  const [report, setReport] = useState<string | null>(null)
  const [baselines, setBaselines] = useState<TestBaseline[]>([])
  const [baselineModal, setBaselineModal] = useState(false)
  const [baselineName, setBaselineName] = useState('')
  const [baselineVersion, setBaselineVersion] = useState('')
  const [creatingBaseline, setCreatingBaseline] = useState(false)
  const [compareId, setCompareId] = useState<number | undefined>()
  const [comparison, setComparison] = useState<BaselineComparison | null>(null)
  const [comparing, setComparing] = useState(false)
  const [rerunning, setRerunning] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const logRef = useRef<HTMLPreElement | null>(null)

  const loadBaselines = (run: TestPlanRun) => {
    listBaselines(run.projectId, run.planId).then(setBaselines).catch(() => setBaselines([]))
  }

  const reload = async (id: number, runBaselines = false) => {
    const [run, cs] = await Promise.all([getPlanRun(id), listPlanRunCases(id)])
    setD(run)
    setCases(cs)
    if (runBaselines) loadBaselines(run)
  }

  useEffect(() => {
    if (!planRunId) {
      setD(null)
      setCases([])
      setLogLines([])
      setReport(null)
      setComparison(null)
      setBaselines([])
      return
    }
    let cancelled = false
    let ws: WebSocket | null = null
    setLoading(true)
    setLogLines([])
    setComparison(null)
    setReport(null)
    reload(planRunId, true)
      .then(() => {
        if (cancelled) return
      })
      .catch((e) => {
        if (!cancelled) showError(e, '加载方案运行失败')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    getPlanRun(planRunId).then((run) => {
      if (cancelled || !activeStatus(run.status)) return
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      ws = new WebSocket(`${proto}://${location.host}/ws/test-plan-runs/plan-${run.id}/stream`)
      ws.onopen = () => setConnected(true)
      ws.onmessage = (msg) => {
        try {
          const f = JSON.parse(msg.data)
          if (f.type === 'snapshot') {
            setD((cur) => (cur ? {
              ...cur,
              status: f.status ?? cur.status,
              conclusion: f.conclusion ?? cur.conclusion,
              summary: f.summary ?? cur.summary,
              items: f.items ?? cur.items,
              children: f.children ?? cur.children,
            } : cur))
          } else if (f.type === 'log' && typeof f.line === 'string') {
            setLogLines((cur) => (cur.length >= 2000 ? [...cur.slice(-1999), f.line] : [...cur, f.line]))
          } else if (f.type === 'done') {
            setConnected(false)
            ws?.close()
            reload(run.id, true).catch(() => {})
          }
        } catch {
          /* 忽略坏帧 */
        }
      }
      ws.onclose = () => setConnected(false)
    }).catch(() => {})
    return () => {
      cancelled = true
      ws?.close()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [planRunId])

  useEffect(() => {
    const el = logRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [logLines])

  const summary = d?.summary ?? {}
  const total = num(summary.total)
  const casePassed = num(summary.casePassed)
  const caseFailed = num(summary.caseFailed)
  const caseBlocked = num(summary.caseBlocked)
  const caseErrors = num(summary.caseErrors)
  const passRate = summary.passRate == null ? null : num(summary.passRate)
  const running = activeStatus(d?.status)
  const terminal = d != null && !running

  const onCancel = () => {
    if (!d) return
    Modal.confirm({
      centered: true,
      title: `取消方案运行 #${d.id}？`,
      content: '已运行的用例保留结果，未启动的方案项将被跳过，不可恢复。',
      okText: '取消运行',
      okButtonProps: { danger: true },
      cancelText: '返回',
      onOk: async () => {
        setCancelling(true)
        try {
          const latest = await cancelPlanRun(d.id)
          setD(latest)
          message.success('已请求取消')
        } catch (e) {
          showError(e, '取消失败')
        } finally {
          setCancelling(false)
        }
      },
    })
  }

  const onRerun = async (failedOnly: boolean) => {
    if (!d) return
    setRerunning(true)
    try {
      const next = await rerunPlanRun(d.id, failedOnly ? { failedOnly: true } : {})
      message.success(`已创建重跑 #${next.id}`)
      onOpenPlanRun(next.id)
    } catch (e) {
      showError(e, '重跑失败')
    } finally {
      setRerunning(false)
    }
  }

  const onExport = async () => {
    if (!d) return
    try {
      await downloadPlanRunEvidenceZip(d.id)
    } catch (e) {
      showError(e, '导出证据包失败')
    }
  }

  const openReport = async () => {
    if (!d) return
    try {
      setReport(await getPlanRunReport(d.id))
    } catch (e) {
      showError(e, '读取报告失败')
    }
  }

  const openBaselineModal = () => {
    if (!d) return
    setBaselineName(`${d.planName} ${fmtTime(d.finishedAt ?? d.createdAt)}`)
    setBaselineVersion('')
    setBaselineModal(true)
  }

  const onCreateBaseline = async () => {
    if (!d) return
    if (!baselineName.trim()) {
      message.warning('请填写基线名称')
      return
    }
    setCreatingBaseline(true)
    try {
      await createBaseline(d.id, { name: baselineName.trim(), productVersion: baselineVersion.trim() || undefined })
      setBaselineModal(false)
      message.success('基线已创建')
      loadBaselines(d)
    } catch (e) {
      showError(e, '创建基线失败')
    } finally {
      setCreatingBaseline(false)
    }
  }

  const onDeleteBaseline = (b: TestBaseline) => {
    Modal.confirm({
      centered: true,
      title: `删除基线「${b.name}」？`,
      content: '仅删除基线快照，不影响历史运行；不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteBaseline(b.id)
        message.success('已删除')
        if (d) loadBaselines(d)
        if (compareId === b.id) setComparison(null)
      },
    })
  }

  const onCompare = async () => {
    if (!d || compareId == null) {
      message.warning('请选择基线')
      return
    }
    setComparing(true)
    try {
      const result = await compareBaseline(compareId, d.id)
      setComparison(result)
    } catch (e) {
      showError(e, '基线对比失败')
    } finally {
      setComparing(false)
    }
  }

  const itemColumns: ColumnsType<PlanItemResult> = [
    { title: '序号', dataIndex: 'seq', width: 60 },
    {
      title: '方案项', key: 'ref', ellipsis: true,
      render: (_, i) => (
        <Space size={6}>
          <Tag>{i.refType}</Tag>
          <span>{i.refName ?? `#${i.refId}`}</span>
          {i.parallelGroup && <Tag color="blue">组 {i.parallelGroup}</Tag>}
        </Space>
      ),
    },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v: string) => <Tag color={STATUS_COLOR[v as keyof typeof STATUS_COLOR]}>{v}</Tag>,
    },
    {
      title: '结论', dataIndex: 'conclusion', width: 90,
      render: (v: string | null) => (v ? <Tag color={CONCLUSION_COLOR[v as keyof typeof CONCLUSION_COLOR]}>{v}</Tag> : '-'),
    },
    { title: '尝试', dataIndex: 'attempts', width: 60, render: (v: number | null) => v ?? '-' },
    {
      title: '用例', key: 'cases', width: 160,
      render: (_, i) => (
        <span style={{ fontSize: 12 }}>
          {i.caseCount ?? 0} 项 · <span style={{ color: '#52c41a' }}>{i.passed ?? 0} 过</span>{' '}
          <span style={{ color: Number(i.failed ?? 0) ? '#ff4d4f' : undefined }}>{i.failed ?? 0} 败</span>{' '}
          <span style={{ color: '#d48806' }}>{i.blocked ?? 0} 阻</span>
        </span>
      ),
    },
    { title: '耗时', dataIndex: 'durationMs', width: 90, render: (v: number | null) => (v != null ? `${v}ms` : '-') },
    { title: '节点/分片', dataIndex: 'shardKey', width: 110, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '错误', dataIndex: 'error', ellipsis: true, render: (v: string | null) => v || '-' },
  ]

  const childColumns: ColumnsType<PlanChildRun> = [
    { title: 'ID', dataIndex: 'id', width: 70, render: (v: number) => `#${v}` },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: keyof typeof STATUS_COLOR) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
    },
    {
      title: '结论', dataIndex: 'conclusion', width: 90,
      render: (v: string | null) => (v ? <Tag color={CONCLUSION_COLOR[v as keyof typeof CONCLUSION_COLOR]}>{v}</Tag> : '-'),
    },
    { title: '节点', dataIndex: 'agentNodeId', width: 110, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '分片', dataIndex: 'shardKey', width: 110, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '尝试', dataIndex: 'attempt', width: 60, render: (v: number | null) => v ?? '-' },
    {
      title: '用例', key: 'summary', width: 150,
      render: (_, c) => (
        <span style={{ fontSize: 12 }}>
          {c.summary?.total ?? 0} 项 · <span style={{ color: '#52c41a' }}>{c.summary?.passed ?? 0} 过</span>{' '}
          <span style={{ color: Number(c.summary?.failed ?? 0) ? '#ff4d4f' : undefined }}>{c.summary?.failed ?? 0} 败</span>
        </span>
      ),
    },
    { title: '耗时', key: 'dur', width: 90, render: (_, c) => durationMs(c.startedAt, c.finishedAt) },
    {
      title: '操作', key: 'act', width: 80,
      render: (_, c) => <Button size="small" onClick={() => onOpenChild(c.id)}>详情</Button>,
    },
  ]

  const caseColumns: ColumnsType<PlanCaseResult> = [
    { title: '用例', key: 'name', ellipsis: true, render: (_, c) => c.name || c.caseKey || `#${c.caseId}` },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (v: string) => <Tag color={RESULT_COLOR[v as keyof typeof RESULT_COLOR]}>{v}</Tag>,
    },
    {
      title: '失败分类', dataIndex: 'failureCategory', width: 110,
      render: (v: string | null) => (v ? <Tag color={FAILURE_CATEGORY_COLOR[v as keyof typeof FAILURE_CATEGORY_COLOR]}>{v}</Tag> : '-'),
    },
    { title: '版本', dataIndex: 'version', width: 60, render: (v: number | null) => (v != null ? `v${v}` : '-') },
    { title: '节点', dataIndex: 'nodeId', width: 110, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '分片', dataIndex: 'shardKey', width: 110, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '耗时', dataIndex: 'durationMs', width: 90, render: (v: number | null) => (v != null ? `${v}ms` : '-') },
    { title: '错误', dataIndex: 'error', ellipsis: true, render: (v: string | null) => v || '-' },
    {
      title: '操作', key: 'act', width: 90,
      render: (_, c) => <Button size="small" onClick={() => onOpenChild(c.runId)}>子运行</Button>,
    },
  ]

  const diffColumns: ColumnsType<BaselineCaseDiff> = [
    { title: '用例', key: 'name', ellipsis: true, render: (_, c) => c.name || c.caseKey || `#${c.caseId ?? '-'}` },
    { title: '基线', dataIndex: 'baselineStatus', width: 90, render: (v: string | null) => v || '-' },
    { title: '当前', dataIndex: 'currentStatus', width: 90, render: (v: string | null) => v || '-' },
    { title: '版本', key: 'v', width: 110, render: (_, c) => `${c.baselineVersion ?? '-'} → ${c.currentVersion ?? '-'}` },
    { title: '错误', dataIndex: 'error', ellipsis: true, render: (v: string | null) => v || '-' },
  ]

  const diffSection = (title: string, color: string, rows: BaselineCaseDiff[]) => (
    <div style={{ marginTop: 12 }}>
      <Space size={6} style={{ marginBottom: 6 }}>
        <Tag color={color}>{title}</Tag>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{rows.length} 项</Typography.Text>
      </Space>
      <Table<BaselineCaseDiff>
        rowKey={(c) => `${c.caseId}-${c.caseKey}-${c.currentRunId}`}
        size="small"
        columns={diffColumns}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: '无' }}
      />
    </div>
  )

  const baselineColumns: ColumnsType<TestBaseline> = [
    { title: 'ID', dataIndex: 'id', width: 70, render: (v: number) => `#${v}` },
    { title: '名称', dataIndex: 'name', ellipsis: true },
    { title: '产品版本', dataIndex: 'productVersion', width: 120, render: (v: string | null) => v || '-' },
    { title: '用例数', dataIndex: 'caseCount', width: 80 },
    { title: '来源运行', dataIndex: 'planRunId', width: 100, render: (v: number) => `#${v}` },
    { title: '创建人', dataIndex: 'createdBy', width: 110, render: (v: string | null) => v || '-' },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => fmtTime(v) },
    {
      title: '操作', key: 'act', width: 150,
      render: (_, b) => (
        <Space size={4}>
          <Button size="small" onClick={() => setCompareId(b.id)}>选择对比</Button>
          {canManageBaseline && <Button size="small" danger onClick={() => onDeleteBaseline(b)}>删除</Button>}
        </Space>
      ),
    },
  ]

  return (
    <Drawer
      title={d ? (
        <Space wrap>
          <span>方案运行 #{d.id}</span>
          <Tag color={STATUS_COLOR[d.status]}>{d.status}</Tag>
          {d.conclusion && <Tag color={CONCLUSION_COLOR[d.conclusion]}>{d.conclusion}</Tag>}
          <Tag>{d.mode}</Tag>
          {connected && <Tag color="cyan">实时</Tag>}
        </Space>
      ) : '方案运行详情'}
      width={DRAWER_SCREEN}
      open={planRunId != null}
      onClose={onClose}
      loading={loading}
    >
      {d && (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {d.errorSummary && <Alert type="error" showIcon message={d.errorSummary} />}
          <Descriptions size="small" column={4}>
            <Descriptions.Item label="方案">{d.planName} <Tag>v{d.planVersion}</Tag></Descriptions.Item>
            <Descriptions.Item label="需求">{d.requirementId || '-'}</Descriptions.Item>
            <Descriptions.Item label="环境">{d.environmentId ? `#${d.environmentId}` : '-'}</Descriptions.Item>
            <Descriptions.Item label="baseUrl">{d.baseUrl || '-'}</Descriptions.Item>
            <Descriptions.Item label="开始/结束" span={2}>
              {d.startedAt ? fmtTime(d.startedAt) : '-'} → {d.finishedAt ? fmtTime(d.finishedAt) : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="报告文档">{d.reportDocId ? `#${d.reportDocId}` : '-'}</Descriptions.Item>
            <Descriptions.Item label="触发">{d.triggeredBy || '-'}</Descriptions.Item>
          </Descriptions>

          <Space wrap>
            <Typography.Text strong style={{ fontSize: 13 }}>用例 {total} 项</Typography.Text>
            <span style={{ fontSize: 12 }}>
              <span style={{ color: '#52c41a' }}>{casePassed} 过</span>{' '}
              <span style={{ color: caseFailed ? '#ff4d4f' : undefined }}>{caseFailed} 败</span>{' '}
              <span style={{ color: '#d48806' }}>{caseBlocked} 阻</span>{' '}
              <span style={{ color: '#c41d7f' }}>{caseErrors} 错</span>
            </span>
            {passRate != null && <Progress type="circle" size={46} percent={passRate}
              format={(p) => `${p}%`} strokeColor={passRate >= 90 ? '#52c41a' : passRate >= 60 ? '#fa8c16' : '#ff4d4f'} />}
          </Space>

          <Space wrap>
            <Button size="small" onClick={openReport}>报告</Button>
            {canExportEvidence && <Button size="small" onClick={onExport}>证据包</Button>}
            <Button size="small" onClick={() => reload(d.id, true)}>刷新</Button>
            {canRunPlan && running && (
              <Button size="small" danger loading={cancelling} onClick={onCancel}>取消运行</Button>
            )}
            {canRunPlan && terminal && (
              <>
                <Button size="small" loading={rerunning} onClick={() => onRerun(false)}>全量重跑</Button>
                {(caseFailed > 0 || caseErrors > 0 || num(summary.failed) > 0) && (
                  <Button size="small" loading={rerunning} onClick={() => onRerun(true)}>失败项重跑</Button>
                )}
              </>
            )}
            {canManageBaseline && terminal && (
              <Button size="small" onClick={openBaselineModal}>创建基线</Button>
            )}
          </Space>

          {(connected || logLines.length > 0) && (
            <pre
              ref={logRef}
              style={{
                background: '#0f1115', color: '#d0d7de', padding: 12, borderRadius: 6,
                fontSize: 12, lineHeight: 1.6, maxHeight: 220, overflow: 'auto',
                whiteSpace: 'pre-wrap', wordBreak: 'break-all', margin: 0,
              }}
            >
              {logLines.join('\n') || '等待输出…'}
            </pre>
          )}

          <Tabs
            activeKey={tab}
            onChange={setTab}
            items={[
              {
                key: 'items',
                label: `方案项（${d.items?.length ?? 0}）`,
                children: (
                  <Table<PlanItemResult>
                    rowKey="itemId"
                    size="small"
                    columns={itemColumns}
                    dataSource={d.items ?? []}
                    pagination={false}
                    expandable={{
                      expandedRowRender: (i) => i.error
                        ? <Typography.Text type="danger" style={{ fontSize: 12 }}>{i.error}</Typography.Text>
                        : <Typography.Text type="secondary" style={{ fontSize: 12 }}>无错误</Typography.Text>,
                      rowExpandable: (i) => !!i.error,
                    }}
                  />
                ),
              },
              {
                key: 'children',
                label: `子运行（${d.children?.length ?? 0}）`,
                children: (
                  <Table<PlanChildRun>
                    rowKey="id"
                    size="small"
                    columns={childColumns}
                    dataSource={d.children ?? []}
                    pagination={false}
                    expandable={{
                      expandedRowRender: (c) => c.errorSummary
                        ? <Typography.Text type="danger" style={{ fontSize: 12 }}>{c.errorSummary}</Typography.Text>
                        : <Typography.Text type="secondary" style={{ fontSize: 12 }}>无错误</Typography.Text>,
                      rowExpandable: (c) => !!c.errorSummary,
                    }}
                  />
                ),
              },
              {
                key: 'cases',
                label: `用例结果（${cases.length}）`,
                children: (
                  <Table<PlanCaseResult>
                    rowKey={(c) => `${c.runId}-${c.caseId}-${c.nodeId ?? ''}`}
                    size="small"
                    columns={caseColumns}
                    dataSource={cases}
                    pagination={{ pageSize: 50, hideOnSinglePage: true }}
                    locale={{ emptyText: '暂无用例结果' }}
                  />
                ),
              },
              {
                key: 'baselines',
                label: `基线（${baselines.length}）`,
                children: (
                  <Space direction="vertical" size={12} style={{ width: '100%' }}>
                    <Space wrap>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        基线固化本方案的用例版本与状态，用于后续运行的回归对比。
                      </Typography.Text>
                      {canManageBaseline && terminal && (
                        <Button size="small" onClick={openBaselineModal}>创建基线</Button>
                      )}
                    </Space>
                    <Table<TestBaseline>
                      rowKey="id"
                      size="small"
                      columns={baselineColumns}
                      dataSource={baselines}
                      pagination={{ pageSize: 10, hideOnSinglePage: true }}
                      locale={{ emptyText: '暂无基线' }}
                      rowSelection={{
                        type: 'radio',
                        selectedRowKeys: compareId != null ? [compareId] : [],
                        onChange: (keys) => setCompareId(Number(keys[0])),
                        getCheckboxProps: () => ({ disabled: false }),
                      }}
                    />
                    <Space wrap>
                      <Button size="small" type="primary" ghost disabled={compareId == null}
                        loading={comparing} onClick={onCompare}>与所选基线对比</Button>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {compareId != null ? `已选基线 #${compareId}` : '请先选择要对比的基线'}
                      </Typography.Text>
                    </Space>
                    {comparison && (
                      <div>
                        <Alert
                          type={comparison.newFailures.length ? 'warning' : 'success'}
                          showIcon
                          message={`通过率 ${num(comparison.baselineSummary.passRate)}% → ${num(comparison.currentSummary.passRate)}%`
                            + `（${comparison.passRateDelta >= 0 ? '+' : ''}${comparison.passRateDelta}）`
                            + ` · 耗时变化 ${comparison.durationDeltaMs >= 0 ? '+' : ''}${comparison.durationDeltaMs}ms`}
                          description={`新增失败 ${comparison.newFailures.length} · 已修复 ${comparison.fixed.length} · 持续失败 ${comparison.persistentFailures.length} · 新增用例 ${comparison.newCases.length} · 移除用例 ${comparison.removedCases.length}`}
                        />
                        {diffSection('新增失败', 'red', comparison.newFailures)}
                        {diffSection('已修复', 'green', comparison.fixed)}
                        {diffSection('持续失败', 'orange', comparison.persistentFailures)}
                        {diffSection('新增用例', 'blue', comparison.newCases)}
                        {diffSection('移除用例', 'default', comparison.removedCases)}
                      </div>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        </Space>
      )}

      <Modal
        centered
        title="方案运行报告"
        open={report != null}
        onCancel={() => setReport(null)}
        footer={null}
        width={900}
        destroyOnHidden
      >
        <pre style={{
          background: '#0f1115', color: '#d0d7de', padding: 12, borderRadius: 6,
          fontSize: 12, lineHeight: 1.6, maxHeight: '70vh', overflow: 'auto',
          whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0,
        }}>
          {report || '（空）'}
        </pre>
      </Modal>

      <Modal
        centered
        title="创建基线"
        open={baselineModal}
        onCancel={() => setBaselineModal(false)}
        onOk={onCreateBaseline}
        okText="创建"
        confirmLoading={creatingBaseline}
        width={520}
        destroyOnHidden
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Input placeholder="基线名称" value={baselineName} onChange={(e) => setBaselineName(e.target.value)} />
          <Input placeholder="产品版本（可选，如 1.2.0）" value={baselineVersion}
            onChange={(e) => setBaselineVersion(e.target.value)} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            基线来源为当前方案运行 #{d?.id}；创建后可在后续运行中做新增失败/修复对比。
          </Typography.Text>
        </Space>
      </Modal>
    </Drawer>
  )
}
