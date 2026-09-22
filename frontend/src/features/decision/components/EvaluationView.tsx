// CAP-56 FR-03 评测运行视图：发起（选 checkpoint × 数据集 × 节点）+ 列表 + 报告详情
// （指标 + 两条基线 + 与基线 checkpoint 的逐题胜负 + 温度校准前后 ECE）+ 实时日志。
//
// 「未冻结的评测集不能发起评测」是后端硬约束（400），这里提前把未冻结的集置灰：
// 让用户在点下去之前就知道该先冻结，而不是提交后读一条错误。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ButtonProps } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  deleteEvaluation,
  getEvaluation,
  listCheckpoints,
  listDatasets,
  listEvaluations,
  triggerEvaluation,
} from '../api'
import type { CheckpointView, DatasetView, EvalDetail, EvalTriggerRequest, EvalView } from '../types'
import { durationMs, fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import FitTable from '../../../shared/components/FitTable'
import LabLogDrawer from './LabLogDrawer'
import { ReportStatusTag, RunStatusTag, runActive } from './labCommon'
import {
  CalibrationBlock,
  CaseGroupMetricsTable,
  CommandBlock,
  CompareBlock,
  JsonBlock,
  MetricsTable,
  PerItemTable,
  WarningsAlert,
  num,
  pct,
} from './ReportBlocks'

/** 灰按钮的 Tooltip 要套 span（disabled 的 button 不派发鼠标事件，Tooltip 认不到目标） */
function TipButton({ tip, ...rest }: { tip?: string } & ButtonProps) {
  const btn = <Button {...rest} />
  if (!tip) return btn
  return (
    <Tooltip title={tip}>
      <span style={{ display: 'inline-block' }}>{btn}</span>
    </Tooltip>
  )
}

/** 列表头条：准确率与两条基线并排，一眼看出"比瞎猜好多少" */
function Headline({ h }: { h: Record<string, number | null> }) {
  const items: Array<[string, string]> = [
    ['准确率', pct(h?.accuracy)],
    ['软准确率', pct(h?.softAccuracy)],
    ['随机', pct(h?.random)],
    ['多数类', pct(h?.majority)],
    ['MAE', num(h?.mae)],
  ]
  if (h?.win != null || h?.lose != null) {
    items.push(['胜负', `${num(h?.win, 0)}/${num(h?.lose, 0)}/${num(h?.tie, 0)}`])
  }
  if (h?.eceBefore != null) {
    items.push(['ECE 前→后', `${num(h?.eceBefore)}→${num(h?.eceAfter)}`])
  }
  return (
    <Space size={10} wrap>
      {items.map(([k, v]) => (
        <Typography.Text key={k} type={v === '—' ? 'secondary' : undefined}>
          {k} {v}
        </Typography.Text>
      ))}
    </Space>
  )
}

export default function EvaluationView({ refreshTick = 0, createTick = 0 }: { refreshTick?: number; createTick?: number }) {
  const [rows, setRows] = useState<EvalView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [datasetId, setDatasetId] = useState<number>()
  const [datasets, setDatasets] = useState<DatasetView[]>([])
  const [loading, setLoading] = useState(false)
  const [detailId, setDetailId] = useState<number | null>(null)
  const [logRow, setLogRow] = useState<EvalView | null>(null)
  const [triggerOpen, setTriggerOpen] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listEvaluations({ datasetId, page, size })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      showError(e, '加载评测运行失败')
    } finally {
      setLoading(false)
    }
  }, [datasetId, page, size])

  useEffect(() => {
    load()
  }, [load, refreshTick])

  // 数据集下拉（发起时要选，列表筛选也用）
  useEffect(() => {
    listDatasets({ size: 200 })
      .then((r) => setDatasets(r.items))
      .catch(() => setDatasets([]))
  }, [refreshTick])

  // 有未终态的运行时轮询（WS 只管日志，列表状态靠这个刷新）
  useEffect(() => {
    if (!rows.some((r) => runActive(r.status))) return
    const timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [rows, load])

  const firstCreate = useRef(true)
  useEffect(() => {
    if (firstCreate.current) {
      firstCreate.current = false
      return
    }
    if (createTick > 0) setTriggerOpen(true)
  }, [createTick])

  const onDelete = async (id: number) => {
    try {
      await deleteEvaluation(id)
      message.success('已删除评测运行')
      await load()
    } catch (e) {
      showError(e, '删除失败')
    }
  }

  const columns: ColumnsType<EvalView> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '数据集', dataIndex: 'datasetLabel', ellipsis: true, render: (v: string | null) => v ?? '—' },
    {
      title: '被测 checkpoint',
      ellipsis: true,
      render: (_, r) => (
        <Space size={6}>
          <span>{r.checkpointLabel ?? `#${r.checkpointId}`}</span>
          {r.serveSlot && <Tag>{r.serveSlot}</Tag>}
        </Space>
      ),
    },
    {
      title: '基线',
      dataIndex: 'baseCheckpointLabel',
      width: 140,
      ellipsis: true,
      render: (v: string | null) => v ?? <Typography.Text type="secondary">无对照</Typography.Text>,
    },
    { title: '节点', dataIndex: 'nodeId', width: 120, render: (v: string | null) => v ?? '自动' },
    {
      title: '状态',
      width: 110,
      render: (_, r) => <RunStatusTag status={r.status} />,
    },
    {
      title: '报告',
      width: 200,
      render: (_, r) => (
        <Tooltip title={r.errorSummary ?? ''}>
          <ReportStatusTag status={r.reportStatus} label={r.reportLabel} />
        </Tooltip>
      ),
    },
    { title: '头条指标', width: 340, render: (_, r) => <Headline h={r.headline ?? {}} /> },
    {
      title: '耗时',
      width: 100,
      render: (_, r) => (r.finishedAt ? durationMs(r.startedAt, r.finishedAt) : '—'),
    },
    { title: '发起时间', dataIndex: 'createdAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 170,
      render: (_, r) => (
        <Space size={0}>
          <Button type="link" onClick={() => setDetailId(r.id)}>
            报告
          </Button>
          <Button type="link" onClick={() => setLogRow(r)}>
            日志
          </Button>
          <Popconfirm
            title="删除这次评测运行？"
            description="删除的是运行记录与报告；运行中的不可删。"
            onConfirm={() => onDelete(r.id)}
            disabled={r.status === 'RUNNING'}
          >
            <TipButton type="link" danger tip={r.status === 'RUNNING' ? '运行中不可删除' : undefined} disabled={r.status === 'RUNNING'}>
              删除
            </TipButton>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear
          placeholder="数据集：全部"
          style={{ width: 260 }}
          value={datasetId}
          onChange={(v) => {
            setDatasetId(v)
            setPage(0)
          }}
          options={datasets.map((d) => ({ value: d.id, label: `${d.name} v${d.version}` }))}
        />
      </Space>
      <FitTable
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
        locale={{
          emptyText:
            '还没有评测运行——先建一个含对照组的评测集并冻结，再「发起评测」在节点上跑一遍 base 模型看它到底有多好（报告中永远带随机与多数类两条基线）。',
        }}
      />

      {triggerOpen && (
        <TriggerModal
          datasets={datasets}
          onClose={() => setTriggerOpen(false)}
          onDone={async (id) => {
            setTriggerOpen(false)
            setPage(0)
            await load()
            setDetailId(id)
          }}
        />
      )}

      {detailId != null && <EvalDetailDrawer id={detailId} onClose={() => setDetailId(null)} />}

      <LabLogDrawer
        open={logRow != null}
        kind="evaluations"
        id={logRow?.id ?? null}
        status={logRow?.status ?? ''}
        title={logRow ? `评测 #${logRow.id} 日志` : ''}
        onClose={() => setLogRow(null)}
        onFinished={load}
      />
    </>
  )
}

// ---------------- 发起评测 ----------------

function TriggerModal({
  datasets,
  onClose,
  onDone,
}: {
  datasets: DatasetView[]
  onClose: () => void
  onDone: (id: number) => Promise<void> | void
}) {
  const [form] = Form.useForm<EvalTriggerRequest>()
  const [checkpoints, setCheckpoints] = useState<CheckpointView[]>([])
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    listCheckpoints({ size: 200 })
      .then((r) => setCheckpoints(r.items))
      .catch(() => setCheckpoints([]))
  }, [])

  const frozenDatasets = datasets.filter((d) => d.frozen)
  // 没有 sourcePath 的产物在节点上找不到权重（后端 400）——提前置灰
  const usable = checkpoints.filter((c) => !!c.sourcePath)

  const onOk = async () => {
    const v = await form.validateFields()
    setBusy(true)
    try {
      const created = await triggerEvaluation({
        ...v,
        nodeId: v.nodeId?.trim() || undefined,
        requiredLabels: v.requiredLabels?.trim() || undefined,
        pythonPath: v.pythonPath?.trim() || undefined,
        outputPath: v.outputPath?.trim() || undefined,
        baseCheckpointId: v.baseCheckpointId || undefined,
      })
      message.success(`已发起评测 #${created.id}`)
      await onDone(created.id)
    } catch (e) {
      showError(e, '发起评测失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="发起评测" open width={720} onCancel={onClose} onOk={onOk} okText="发起" confirmLoading={busy} destroyOnHidden>
      <Form form={form} layout="vertical" preserve={false} initialValues={{ fitTemperature: true, timeoutSec: 3600 }}>
        <Form.Item
          name="checkpointId"
          label="被测 checkpoint"
          rules={[{ required: true, message: '请选择被测 checkpoint' }]}
          extra="只有登记了来源路径的产物能跑（节点上要能找到权重目录）"
        >
          <Select
            placeholder="选一个产物"
            options={usable.map((c) => ({ value: c.id, label: `${c.name}（${c.kindLabel}${c.serveSlot ? ' · ' + c.serveSlot : ''}）` }))}
            notFoundContent="没有可用产物：先去「Checkpoint 登记」登记一份（含来源路径）"
          />
        </Form.Item>
        <Form.Item
          name="datasetId"
          label="评测集"
          rules={[{ required: true, message: '请选择评测集' }]}
          extra="只能选已冻结的集（未冻结的集没有固定的题面版本，指标不可比）"
        >
          <Select
            placeholder="选一个已冻结的评测集"
            options={datasets.map((d) => ({
              value: d.id,
              label: `${d.name} v${d.version} · ${d.itemCount} 条${d.frozen ? '' : '（未冻结）'}`,
              disabled: !d.frozen,
            }))}
            notFoundContent={
              frozenDatasets.length ? '没有已冻结的评测集' : '还没有评测集：先去「评测集」建一个并冻结'
            }
          />
        </Form.Item>
        <Form.Item name="baseCheckpointId" label="对照 checkpoint" extra="给了就出逐题胜负（这一栏是「微调到底有没有变好」的证据）">
          <Select
            allowClear
            placeholder="不选 = 不跑对照"
            options={usable.map((c) => ({ value: c.id, label: c.name }))}
          />
        </Form.Item>
        <Form.Item name="fitTemperature" label="拟合温度校准" valuePropName="checked" extra="用 held-out 方式拟合 (题型,选项数) 温度，报校准前后 ECE">
          <Switch />
        </Form.Item>
        <Typography.Text type="secondary">高级（留空按节点与平台默认）</Typography.Text>
        <Space size={12} style={{ display: 'flex', marginTop: 8 }} align="start">
          <Form.Item name="nodeId" label="节点" style={{ width: 200, marginBottom: 0 }}>
            <Input placeholder="留空 = 自动路由" />
          </Form.Item>
          <Form.Item name="requiredLabels" label="节点标签" style={{ width: 200, marginBottom: 0 }}>
            <Input placeholder="如 gpu,T4" />
          </Form.Item>
          <Form.Item name="timeoutSec" label="超时（秒）" style={{ width: 140, marginBottom: 0 }}>
            <InputNumber min={60} max={86400} style={{ width: '100%' }} />
          </Form.Item>
        </Space>
        <Space size={12} style={{ display: 'flex', marginTop: 12 }} align="start">
          <Form.Item name="pythonPath" label="python 路径" style={{ width: 260, marginBottom: 0 }}>
            <Input placeholder="留空 = 节点默认" />
          </Form.Item>
          <Form.Item name="outputPath" label="产出目录" style={{ width: 300, marginBottom: 0 }}>
            <Input placeholder="留空 = 节点临时目录（写校准参数用）" />
          </Form.Item>
        </Space>
      </Form>
    </Modal>
  )
}

// ---------------- 报告详情 ----------------

function EvalDetailDrawer({ id, onClose }: { id: number; onClose: () => void }) {
  const [detail, setDetail] = useState<EvalDetail | null>(null)

  useEffect(() => {
    getEvaluation(id)
      .then(setDetail)
      .catch((e) => showError(e, '加载评测报告失败'))
  }, [id])

  const v = detail?.view
  return (
    <Drawer title={v ? `评测 #${v.id} 报告` : '评测报告'} width={1080} open onClose={onClose}>
      {detail && v ? (
        <>
          <Descriptions size="small" column={3} style={{ marginBottom: 12 }}>
            <Descriptions.Item label="状态">
              <RunStatusTag status={v.status} />
            </Descriptions.Item>
            <Descriptions.Item label="报告">
              <ReportStatusTag status={v.reportStatus} label={v.reportLabel} />
            </Descriptions.Item>
            <Descriptions.Item label="节点">{v.nodeId ?? '自动'}</Descriptions.Item>
            <Descriptions.Item label="数据集">{v.datasetLabel ?? `#${v.datasetId}`}</Descriptions.Item>
            <Descriptions.Item label="被测产物">{v.checkpointLabel ?? `#${v.checkpointId}`}</Descriptions.Item>
            <Descriptions.Item label="基线产物">{v.baseCheckpointLabel ?? '无对照'}</Descriptions.Item>
            <Descriptions.Item label="题面版本">{v.questionSetVersion ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="样本数">{num(v.itemCount, 0)}</Descriptions.Item>
            <Descriptions.Item label="退出码">{v.exitCode ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="发起">
              {v.createdBy ?? '—'} · {fmtTime(v.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label="开始">{fmtTime(v.startedAt)}</Descriptions.Item>
            <Descriptions.Item label="结束">
              {fmtTime(v.finishedAt)}
              {v.finishedAt ? `（耗时 ${durationMs(v.startedAt, v.finishedAt)}）` : ''}
            </Descriptions.Item>
          </Descriptions>
          {v.errorSummary && <Alert type="error" showIcon style={{ marginBottom: 12 }} message={v.errorSummary} />}
          <WarningsAlert warnings={detail.report?.warnings} />
          <MetricsTable metrics={detail.report?.metrics} baselines={detail.report?.baselines} />
          {detail.compare && <CompareBlock compare={detail.compare} />}
          {detail.calibration && <CalibrationBlock calibration={detail.calibration} />}
          <CaseGroupMetricsTable rows={detail.byCaseGroup ?? []} />
          <PerItemTable rows={detail.perItem ?? []} />
          <Typography.Text strong>命令行</Typography.Text>
          <CommandBlock text={detail.commandText} />
          <Typography.Text strong>报告原文</Typography.Text>
          <JsonBlock value={detail.report} maxHeight={240} />
        </>
      ) : (
        <Typography.Text type="secondary">加载中…</Typography.Text>
      )}
    </Drawer>
  )
}
