// CAP-56 FR-04/FR-05 微调任务视图：发起（训练集 / 回评集 / 基座 / 输出路径 / 超参 / 节点标签）
// + 列表 + 实时日志抽屉 + 结束自动回评的结果。
//
// 一次微调不是"跑完就完"：训练成功只说明脚本没崩，**学得怎么样要看回评**。所以列表里
// 产物与回评是一列——postLabel 说清了「产物登记了没、回评触发了没」，没做到就直说原因（postError），
// 不让"训练成功"这三个字掩盖"这次微调其实没有可比的结论"。
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
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ButtonProps } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  deleteFinetune,
  getFinetune,
  listCheckpoints,
  listDatasets,
  listFinetunes,
  triggerFinetune,
} from '../api'
import type { CheckpointView, DatasetView, FinetuneDetail, FinetuneTriggerRequest, FinetuneView } from '../types'
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
  TrainBlock,
  WarningsAlert,
  num,
  pct,
} from './ReportBlocks'

function TipButton({ tip, ...rest }: { tip?: string } & ButtonProps) {
  const btn = <Button {...rest} />
  if (!tip) return btn
  return (
    <Tooltip title={tip}>
      <span style={{ display: 'inline-block' }}>{btn}</span>
    </Tooltip>
  )
}

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

export default function FinetuneView({ refreshTick = 0, createTick = 0 }: { refreshTick?: number; createTick?: number }) {
  const [rows, setRows] = useState<FinetuneView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [datasets, setDatasets] = useState<DatasetView[]>([])
  const [loading, setLoading] = useState(false)
  const [detailId, setDetailId] = useState<number | null>(null)
  const [logRow, setLogRow] = useState<FinetuneView | null>(null)
  const [triggerOpen, setTriggerOpen] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listFinetunes({ page, size })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      showError(e, '加载微调任务失败')
    } finally {
      setLoading(false)
    }
  }, [page, size])

  useEffect(() => {
    load()
  }, [load, refreshTick])

  useEffect(() => {
    listDatasets({ size: 200 })
      .then((r) => setDatasets(r.items))
      .catch(() => setDatasets([]))
  }, [refreshTick])

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
      await deleteFinetune(id)
      message.success('已删除微调任务')
      await load()
    } catch (e) {
      showError(e, '删除失败')
    }
  }

  const columns: ColumnsType<FinetuneView> = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '训练集', dataIndex: 'datasetLabel', ellipsis: true, render: (v: string | null) => v ?? '—' },
    {
      title: '回评集',
      dataIndex: 'evalDatasetLabel',
      ellipsis: true,
      render: (v: string | null) => v ?? '—',
    },
    {
      title: '基座',
      ellipsis: true,
      render: (_, r) => (
        <Space size={6}>
          <span>{r.baseCheckpointLabel ?? (r.baseCheckpointId != null ? `#${r.baseCheckpointId}` : '—')}</span>
          {r.serveSlot && <Tag>{r.serveSlot}</Tag>}
        </Space>
      ),
    },
    {
      title: '切分',
      width: 150,
      render: (_, r) => (
        <Typography.Text type="secondary">
          训练 {num(r.trainCount, 0)} / 验证 {num(r.valCount, 0)}
          {r.trainRatio != null ? `（${pct(r.trainRatio, 0)}）` : ''}
        </Typography.Text>
      ),
    },
    { title: '状态', width: 110, render: (_, r) => <RunStatusTag status={r.status} /> },
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
      title: '产物与回评',
      width: 280,
      render: (_, r) => (
        <>
          {r.postLabel && <div>{r.postLabel}</div>}
          {r.postError && (
            <Tooltip title={r.postError}>
              <Typography.Text type="warning">{r.postError}</Typography.Text>
            </Tooltip>
          )}
          {!r.postLabel && !r.postError && <Typography.Text type="secondary">—</Typography.Text>}
          {r.outputPath && (
            <div>
              <Typography.Text type="secondary" ellipsis style={{ fontSize: 12 }}>
                {r.outputPath}
              </Typography.Text>
            </div>
          )}
        </>
      ),
    },
    { title: '发起时间', dataIndex: 'createdAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 170,
      render: (_, r) => (
        <Space size={0}>
          <Button type="link" onClick={() => setDetailId(r.id)}>
            详情
          </Button>
          <Button type="link" onClick={() => setLogRow(r)}>
            日志
          </Button>
          <Popconfirm
            title="删除这次微调任务？"
            description="删除的是任务记录与报告；节点上的权重目录不会被删。运行中的不可删。"
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
            '还没有微调任务——用回流集（已裁决的决策记录收编而成）在 GPU 节点上跑一次 RLCD 微调，训练结束会自动登记产物并回评，指标与指纹都留在平台上（权重留在节点）。',
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

      {detailId != null && <FinetuneDetailDrawer id={detailId} onClose={() => setDetailId(null)} />}

      <LabLogDrawer
        open={logRow != null}
        kind="finetunes"
        id={logRow?.id ?? null}
        status={logRow?.status ?? ''}
        title={logRow ? `微调 #${logRow.id} 日志` : ''}
        onClose={() => setLogRow(null)}
        onFinished={load}
      />
    </>
  )
}

// ---------------- 发起微调 ----------------

function TriggerModal({
  datasets,
  onClose,
  onDone,
}: {
  datasets: DatasetView[]
  onClose: () => void
  onDone: (id: number) => Promise<void> | void
}) {
  const [form] = Form.useForm<FinetuneTriggerRequest>()
  const [checkpoints, setCheckpoints] = useState<CheckpointView[]>([])
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    listCheckpoints({ size: 200 })
      .then((r) => setCheckpoints(r.items))
      .catch(() => setCheckpoints([]))
  }, [])

  // 基座必须既能找到权重（sourcePath）又声明了服务槽位（训练脚本按槽位写回产物配置）
  const bases = checkpoints.filter((c) => !!c.sourcePath && !!c.serveSlot)
  const datasetOptions = datasets.map((d) => ({
    value: d.id,
    label: `${d.name} v${d.version} · ${d.itemCount} 条${d.frozen ? '' : '（未冻结）'}`,
    disabled: !d.frozen,
  }))

  const onOk = async () => {
    const v = await form.validateFields()
    setBusy(true)
    try {
      const created = await triggerFinetune({
        ...v,
        nodeId: v.nodeId?.trim() || undefined,
        requiredLabels: v.requiredLabels?.trim() || undefined,
        pythonPath: v.pythonPath?.trim() || undefined,
        launcher: v.launcher?.trim() || undefined,
      })
      message.success(`已发起微调 #${created.id}`)
      await onDone(created.id)
    } catch (e) {
      showError(e, '发起微调失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="发起微调" open width={820} onCancel={onClose} onOk={onOk} okText="发起" confirmLoading={busy} destroyOnHidden>
      <Form
        form={form}
        layout="vertical"
        preserve={false}
        initialValues={{ trainRatio: 0.8, epochs: 3, batchSize: 8, trainSeed: 42, splitSeed: 42, timeoutSec: 7200 }}
      >
        <Form.Item
          name="datasetId"
          label="训练集"
          rules={[{ required: true, message: '请选择训练集' }]}
          extra="训练集用回流集（线上裁决过的真实样本）；回评集要与它分开，否则是在训练集上考试"
        >
          <Select placeholder="选一个已冻结的评测集" options={datasetOptions} />
        </Form.Item>
        <Form.Item
          name="evalDatasetId"
          label="回评集"
          rules={[
            { required: true, message: '请选择回评集' },
            ({ getFieldValue }) => ({
              validator: (_, value) =>
                value == null || value !== getFieldValue('datasetId')
                  ? Promise.resolve()
                  : Promise.reject(new Error('回评集不能与训练集相同')),
            }),
          ]}
        >
          <Select placeholder="选一个已冻结的评测集" options={datasetOptions} />
        </Form.Item>
        <Form.Item
          name="baseCheckpointId"
          label="基座 checkpoint"
          rules={[{ required: true, message: '请选择基座 checkpoint' }]}
          extra="只有登记了来源路径与服务槽位的产物能作为基座"
        >
          <Select
            placeholder="选一个产物"
            options={bases.map((c) => ({ value: c.id, label: `${c.name}（${c.kindLabel} · ${c.serveSlot}）` }))}
            notFoundContent="没有可用基座：先去「Checkpoint 登记」登记一份（含来源路径与服务槽位）"
          />
        </Form.Item>
        <Form.Item
          name="outputPath"
          label="产出目录（节点上的绝对路径）"
          rules={[{ required: true, message: '产出目录必填' }]}
          extra="权重留在节点：这个目录是训练产物落盘处，平台只回传指标与指纹"
        >
          <Input placeholder="如 /data/laya/ft-run1 或 D:/laya/ft-run1" />
        </Form.Item>
        <Space size={12} style={{ display: 'flex' }} align="start">
          <Form.Item name="epochs" label="轮数" style={{ width: 100, marginBottom: 0 }}>
            <InputNumber min={1} max={100} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="batchSize" label="批大小" style={{ width: 100, marginBottom: 0 }}>
            <InputNumber min={1} max={512} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="learningRate" label="学习率" style={{ width: 140, marginBottom: 0 }}>
            <InputNumber min={1e-8} max={1} step={1e-5} style={{ width: '100%' }} placeholder="默认 1e-4" />
          </Form.Item>
          <Form.Item name="trainRatio" label="训练占比" style={{ width: 120, marginBottom: 0 }}>
            <InputNumber min={0.05} max={0.95} step={0.05} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="timeoutSec" label="超时（秒）" style={{ width: 130, marginBottom: 0 }}>
            <InputNumber min={60} max={86400} style={{ width: '100%' }} />
          </Form.Item>
        </Space>
        <Space size={12} style={{ display: 'flex', marginTop: 12 }} align="start">
          <Form.Item name="trainSeed" label="训练种子" style={{ width: 120, marginBottom: 0 }}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="splitSeed" label="切分种子" style={{ width: 120, marginBottom: 0 }}>
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="nodeId" label="节点" style={{ width: 160, marginBottom: 0 }}>
            <Input placeholder="留空 = 自动路由" />
          </Form.Item>
          <Form.Item name="requiredLabels" label="节点标签" style={{ width: 160, marginBottom: 0 }}>
            <Input placeholder="如 gpu,T4" />
          </Form.Item>
          <Form.Item name="pythonPath" label="python 路径" style={{ width: 200, marginBottom: 0 }}>
            <Input placeholder="留空 = 节点默认" />
          </Form.Item>
          <Form.Item
            name="launcher"
            label="启动器"
            style={{ width: 240, marginBottom: 0 }}
            extra="多卡时填 torchrun 前缀"
          >
            <Input placeholder="如 torchrun --nproc_per_node=2" />
          </Form.Item>
        </Space>
      </Form>
    </Modal>
  )
}

// ---------------- 详情 / 报告 ----------------

function FinetuneDetailDrawer({ id, onClose }: { id: number; onClose: () => void }) {
  const [detail, setDetail] = useState<FinetuneDetail | null>(null)

  useEffect(() => {
    getFinetune(id)
      .then(setDetail)
      .catch((e) => showError(e, '加载微调详情失败'))
  }, [id])

  const v = detail?.view
  return (
    <Drawer title={v ? `微调 #${v.id} 详情` : '微调详情'} width={1080} open onClose={onClose}>
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
            <Descriptions.Item label="训练集">{v.datasetLabel ?? `#${v.datasetId}`}</Descriptions.Item>
            <Descriptions.Item label="回评集">{v.evalDatasetLabel ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="基座">{v.baseCheckpointLabel ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="切分">
              训练 {num(v.trainCount, 0)} / 验证 {num(v.valCount, 0)} · 种子 {num(v.splitSeed, 0)} · 占比{' '}
              {pct(v.trainRatio, 0)}
            </Descriptions.Item>
            <Descriptions.Item label="超参">
              轮数 {num(v.epochs, 0)} · 批 {num(v.batchSize, 0)} · 学习率 {num(v.learningRate, 8)}
            </Descriptions.Item>
            <Descriptions.Item label="启动器">{v.launcher || '默认'}</Descriptions.Item>
            <Descriptions.Item label="产出目录" span={2}>
              {v.outputPath ?? '—'}
            </Descriptions.Item>
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
          {v.postLabel && <Alert type="success" showIcon style={{ marginBottom: 12 }} message={v.postLabel} />}
          {v.postError && <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={v.postError} />}
          {v.errorSummary && <Alert type="error" showIcon style={{ marginBottom: 12 }} message={v.errorSummary} />}
          <WarningsAlert warnings={detail.report?.warnings} />
          <TrainBlock train={detail.train ?? detail.report?.train ?? {}} />
          <MetricsTable metrics={detail.report?.metrics} baselines={detail.report?.baselines} />
          {detail.report?.compare && <CompareBlock compare={detail.report.compare} />}
          {detail.calibration && <CalibrationBlock calibration={detail.calibration} />}
          <CaseGroupMetricsTable rows={detail.report?.byCaseGroup ?? []} />
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
