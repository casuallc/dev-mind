// CAP-56 FR-06/FR-07 产物登记视图：登记 / 人工放行 / serve 自检 / 校准参数 + 准入闸门横幅。
//
// 这一页是整条链路的闸门：**没有 verified=true 的产物，消费方（知识库分诊）整体不可用**。
// 所以横幅永远在页首把闸门状态说清楚（放行的是哪一份、没放行的原因），而不是让人去猜
// 为什么分诊按钮是灰的。serve 自检打的是边车 /healthz，核对「登记的那份 == 正在服务的那份」——
// 槽位名对上不代表里面装的是这份（节点上有人改过 models.json 时就是这种情形）。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert,
  AutoComplete,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Radio,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ButtonProps } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  checkpointGate,
  createCheckpoint,
  deleteCheckpoint,
  getCheckpoint,
  listCheckpoints,
  serveCheck,
  unverifyCheckpoint,
  verifyCheckpoint,
} from '../api'
import type {
  CheckpointDetail,
  CheckpointGateView,
  CheckpointView as CheckpointRow,
  ServeCheckResult,
} from '../types'
import { fmtBytes, fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import FitTable from '../../../shared/components/FitTable'
import { CHECK_STATUS_COLOR, CP_KIND_LABEL } from './labCommon'
import { CalibrationBlock, JsonBlock, MetricsTable } from './ReportBlocks'

/** 边车（tools/laya-sidecar）已知的三个槽位名，允许自由输入（新槽位不必等前端发版） */
const KNOWN_SLOTS = ['typed-decisions', 'multilingual', 'english']

function TipButton({ tip, ...rest }: { tip?: string } & ButtonProps) {
  const btn = <Button {...rest} />
  if (!tip) return btn
  return (
    <Tooltip title={tip}>
      <span style={{ display: 'inline-block' }}>{btn}</span>
    </Tooltip>
  )
}

/** serve 自检结论（放行判断的实时证据：边车此刻到底在服务哪一份） */
export function ServeCheckBlock({ result }: { result: ServeCheckResult }) {
  return (
    <div style={{ marginBottom: 16 }}>
      <Space size={10} wrap style={{ marginBottom: 8 }}>
        <Typography.Text strong>serve 自检</Typography.Text>
        <Tag color={CHECK_STATUS_COLOR[result.status] ?? 'default'}>{result.status}</Tag>
        <Typography.Text type="secondary">{result.summary}</Typography.Text>
        <Typography.Text type="secondary">{fmtTime(result.checkedAt)}</Typography.Text>
      </Space>
      <Table
        rowKey="item"
        size="small"
        pagination={false}
        dataSource={result.checks ?? []}
        columns={[
          { title: '检查项', dataIndex: 'item', width: 120 },
          {
            title: '结论',
            dataIndex: 'status',
            width: 90,
            render: (s: string) => <Tag color={CHECK_STATUS_COLOR[s] ?? 'default'}>{s}</Tag>,
          },
          { title: '详情', dataIndex: 'detail' },
        ]}
      />
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        边车自报的原始来源见下方「自检原始应答」（我加载的到底是什么，以它自报为准）。
      </Typography.Text>
    </div>
  )
}

export default function CheckpointView({ refreshTick = 0, createTick = 0 }: { refreshTick?: number; createTick?: number }) {
  const [rows, setRows] = useState<CheckpointRow[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [slot, setSlot] = useState<string>()
  const [gate, setGate] = useState<CheckpointGateView | null>(null)
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [detailId, setDetailId] = useState<number | null>(null)
  const [verifyRow, setVerifyRow] = useState<{ row: CheckpointRow; unverify: boolean } | null>(null)
  const [checkResult, setCheckResult] = useState<{ row: CheckpointRow; result: ServeCheckResult } | null>(null)
  const [checking, setChecking] = useState<number | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listCheckpoints({ serveSlot: slot, page, size })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      showError(e, '加载产物登记失败')
    } finally {
      setLoading(false)
    }
  }, [slot, page, size])

  const loadGate = useCallback(async () => {
    try {
      setGate(await checkpointGate())
    } catch (e) {
      showError(e, '加载准入闸门状态失败')
    }
  }, [])

  useEffect(() => {
    load()
  }, [load, refreshTick])

  useEffect(() => {
    loadGate()
  }, [loadGate, refreshTick])

  const firstCreate = useRef(true)
  useEffect(() => {
    if (firstCreate.current) {
      firstCreate.current = false
      return
    }
    if (createTick > 0) setCreateOpen(true)
  }, [createTick])

  const refreshAll = async () => {
    await Promise.all([load(), loadGate()])
  }

  const onServeCheck = async (row: CheckpointRow) => {
    setChecking(row.id)
    try {
      const result = await serveCheck(row.id)
      setCheckResult({ row, result })
      await refreshAll()
    } catch (e) {
      showError(e, 'serve 自检失败')
    } finally {
      setChecking(null)
    }
  }

  const onDelete = async (id: number) => {
    try {
      await deleteCheckpoint(id)
      message.success('已删除产物登记')
      await refreshAll()
    } catch (e) {
      showError(e, '删除失败')
    }
  }

  const columns: ColumnsType<CheckpointRow> = [
    { title: '名称', dataIndex: 'name', ellipsis: true },
    {
      title: '槽位',
      dataIndex: 'serveSlot',
      width: 140,
      render: (v: string | null) => v ?? '—',
    },
    {
      title: '类型',
      dataIndex: 'kind',
      width: 110,
      render: (k: string, r) => <Tag color={k === 'BASE' ? 'blue' : 'purple'}>{r.kindLabel || CP_KIND_LABEL[k]}</Tag>,
    },
    {
      title: '来源路径',
      dataIndex: 'sourcePath',
      ellipsis: true,
      render: (v: string | null) => v ?? <Typography.Text type="secondary">未登记（不能用于评测/微调）</Typography.Text>,
    },
    {
      title: '指纹',
      width: 150,
      render: (_, r) =>
        r.fingerprintSha256 ? (
          <Tooltip title={`${r.fingerprintPath ?? ''} · ${fmtBytes(r.fingerprintBytes)}`}>
            <Typography.Text code>{r.fingerprintSha256.slice(0, 12)}…</Typography.Text>
          </Tooltip>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: '放行',
      width: 170,
      render: (_, r) =>
        r.verified ? (
          <Tooltip title={r.verifiedNote ?? ''}>
            <Tag color="green">
              已放行 · {r.verifiedBy ?? '—'} · {fmtTime(r.verifiedAt)}
            </Tag>
          </Tooltip>
        ) : (
          <Tag>未放行</Tag>
        ),
    },
    {
      title: '自检',
      width: 150,
      render: (_, r) =>
        r.serveCheckStatus ? (
          <Tooltip title={fmtTime(r.serveCheckedAt)}>
            <Tag color={CHECK_STATUS_COLOR[r.serveCheckStatus] ?? 'default'}>{r.serveCheckStatus}</Tag>
          </Tooltip>
        ) : (
          <Typography.Text type="secondary">未自检</Typography.Text>
        ),
    },
    {
      title: '指标/校准',
      width: 130,
      render: (_, r) => (
        <Space size={4}>
          {r.hasMetrics ? <Tag color="blue">指标</Tag> : <Tag>无指标</Tag>}
          {r.hasCalibration ? <Tag color="gold">校准</Tag> : null}
        </Space>
      ),
    },
    { title: '登记时间', dataIndex: 'createdAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 210,
      render: (_, r) => (
        <Space size={0}>
          <Button type="link" onClick={() => setDetailId(r.id)}>
            详情
          </Button>
          <Button type="link" loading={checking === r.id} onClick={() => onServeCheck(r)}>
            自检
          </Button>
          {r.verified ? (
            <Button type="link" onClick={() => setVerifyRow({ row: r, unverify: true })}>
              撤销放行
            </Button>
          ) : (
            <Button type="link" onClick={() => setVerifyRow({ row: r, unverify: false })}>
              放行
            </Button>
          )}
          <Popconfirm
            title="删除这份产物登记？"
            description="节点上的权重目录不会被删。已放行的要先撤销放行。"
            onConfirm={() => onDelete(r.id)}
            disabled={r.verified}
          >
            <TipButton type="link" danger tip={r.verified ? '已放行的产物要先撤销放行才能删' : undefined} disabled={r.verified}>
              删除
            </TipButton>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <>
      {gate &&
        (gate.open ? (
          <Alert
            type="success"
            showIcon
            style={{ marginBottom: 12 }}
            message="准入闸门已放行：消费方（知识库分诊）可用"
            description={
              gate.serving?.length
                ? `正在服务的产物：${gate.serving.map((c) => `${c.name}（${c.serveSlot ?? '未声明槽位'}）`).join('、')}`
                : '已放行（未列出在服务的产物）'
            }
          />
        ) : (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="准入闸门未放行：知识库分诊等消费方整体不可用"
            description={gate.reason ?? '没有任何已放行的产物'}
          />
        ))}

      <Space style={{ marginBottom: 12 }} wrap>
        <AutoComplete
          allowClear
          style={{ width: 220 }}
          placeholder="槽位：全部"
          value={slot}
          onChange={(v) => {
            setSlot(v || undefined)
            setPage(0)
          }}
          options={KNOWN_SLOTS.map((s) => ({ value: s }))}
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
            '还没有登记的产物——「登记产物」把官方基础模型或微调产物登记进来（来源路径必填），跑一次 serve 自检确认边车在服务它，再人工放行。',
        }}
      />

      {createOpen && (
        <RegisterModal
          onClose={() => setCreateOpen(false)}
          onDone={async (created) => {
            setCreateOpen(false)
            await refreshAll()
            setDetailId(created.id)
          }}
        />
      )}

      {verifyRow && (
        <VerifyModal
          row={verifyRow.row}
          unverify={verifyRow.unverify}
          onClose={() => setVerifyRow(null)}
          onDone={async () => {
            setVerifyRow(null)
            await refreshAll()
          }}
        />
      )}

      {checkResult && (
        <Modal
          title={`serve 自检 · ${checkResult.row.name}`}
          open
          width={900}
          footer={<Button onClick={() => setCheckResult(null)}>关闭</Button>}
          onCancel={() => setCheckResult(null)}
        >
          <ServeCheckBlock result={checkResult.result} />
          <Typography.Text strong>自检原始应答（边车 /healthz）</Typography.Text>
          <JsonBlock value={checkResult.result.report} maxHeight={260} />
        </Modal>
      )}

      {detailId != null && <CheckpointDetailDrawer id={detailId} onClose={() => setDetailId(null)} />}
    </>
  )
}

// ---------------- 登记产物 ----------------

const SOURCE_HINT = '节点上的权重目录绝对路径（微调产物）或 HF 仓库名（官方基础模型，如 convaiinnovations/laya）'

function RegisterModal({
  onClose,
  onDone,
}: {
  onClose: () => void
  onDone: (created: CheckpointRow) => Promise<void> | void
}) {
  const [form] = Form.useForm()
  const [busy, setBusy] = useState(false)
  const kind = Form.useWatch('kind', form) as string | undefined

  const onOk = async () => {
    const v = await form.validateFields()
    setBusy(true)
    try {
      const created = await createCheckpoint({
        name: v.name,
        serveSlot: v.serveSlot,
        kind: v.kind,
        sourcePath: v.sourcePath,
        nodeId: v.nodeId?.trim() || undefined,
        fingerprintPath: v.fingerprintPath?.trim() || undefined,
        fingerprintBytes: v.fingerprintBytes ?? undefined,
        fingerprintSha256: v.fingerprintSha256?.trim() || undefined,
        metricsJson: v.metricsJson?.trim() || undefined,
        note: v.note?.trim() || undefined,
      })
      message.success(`已登记「${created.name}」`)
      await onDone(created)
    } catch (e) {
      showError(e, '登记产物失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="登记产物" open width={720} onCancel={onClose} onOk={onOk} okText="登记" confirmLoading={busy} destroyOnHidden>
      <Form form={form} layout="vertical" preserve={false} initialValues={{ kind: 'BASE' }}>
        <Form.Item name="name" label="名称" rules={[{ required: true, message: '名称必填' }]} extra="同一份产物重训要换名字（名字唯一）">
          <Input placeholder="如 laya-base-typed 或 ft7-分诊回流" />
        </Form.Item>
        <Form.Item
          name="serveSlot"
          label="服务槽位"
          rules={[{ required: true, message: '槽位必填' }]}
          extra="边车上这个产物占哪个槽位（serve 自检按它核对来源）"
        >
          <AutoComplete options={KNOWN_SLOTS.map((s) => ({ value: s }))} placeholder="如 typed-decisions" />
        </Form.Item>
        <Form.Item name="kind" label="类型" rules={[{ required: true }]}>
          <Radio.Group
            options={[
              { value: 'BASE', label: '官方基础' },
              { value: 'FINETUNED', label: '微调产物' },
            ]}
          />
        </Form.Item>
        <Form.Item name="sourcePath" label="来源路径" rules={[{ required: true, message: '来源路径必填' }]} extra={SOURCE_HINT}>
          <Input placeholder="D:/laya/ft7 或 convaiinnovations/laya/typed-decisions" />
        </Form.Item>
        <Form.Item name="nodeId" label="所在节点">
          <Input placeholder="选填：权重放在哪个节点上" />
        </Form.Item>
        <Typography.Text type="secondary">
          {kind === 'FINETUNED'
            ? '微调产物必须带 sha256 指纹（不能放行——放行的是「这一份」权重，不是一个名字）'
            : '指纹选填：官方基础模型的权重一般不在本节点，可只登记来源仓库'}
        </Typography.Text>
        <Space size={12} style={{ display: 'flex', marginTop: 8 }} align="start">
          <Form.Item name="fingerprintPath" label="指纹文件路径" style={{ width: 300, marginBottom: 0 }}>
            <Input placeholder="如 D:/laya/ft7/model.safetensors" />
          </Form.Item>
          <Form.Item name="fingerprintBytes" label="字节数" style={{ width: 140, marginBottom: 0 }}>
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Space>
        <Form.Item name="fingerprintSha256" label="sha256" style={{ marginTop: 12, marginBottom: 12 }} extra="64 位十六进制">
          <Input placeholder="微调产物必填" />
        </Form.Item>
        <Form.Item name="metricsJson" label="指标 JSON" style={{ marginBottom: 12 }} extra="选填：手工登记时可直接粘一份指标报告">
          <Input.TextArea rows={3} />
        </Form.Item>
        <Form.Item name="note" label="备注" style={{ marginBottom: 0 }}>
          <Input />
        </Form.Item>
      </Form>
    </Modal>
  )
}

// ---------------- 放行 / 撤销放行 ----------------

function VerifyModal({
  row,
  unverify,
  onClose,
  onDone,
}: {
  row: CheckpointRow
  unverify: boolean
  onClose: () => void
  onDone: () => Promise<void> | void
}) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)

  const onOk = async () => {
    if (!text.trim()) {
      message.error(unverify ? '撤销原因必填' : '放行依据必填')
      return
    }
    setBusy(true)
    try {
      if (unverify) await unverifyCheckpoint(row.id, text.trim())
      else await verifyCheckpoint(row.id, text.trim())
      message.success(unverify ? '已撤销放行' : '已放行')
      await onDone()
    } catch (e) {
      showError(e, unverify ? '撤销放行失败' : '放行失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title={unverify ? `撤销放行「${row.name}」` : `放行「${row.name}」`}
      open
      onCancel={onClose}
      onOk={onOk}
      okText={unverify ? '撤销放行' : '放行'}
      confirmLoading={busy}
      okButtonProps={{ danger: unverify }}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">
        {unverify
          ? '撤销后闸门会重新关上：消费方（知识库分诊）随之不可用，直到放行另一份已验证的产物。'
          : '放行是人工确认的闸门：放行后消费方（知识库分诊）才能用模型。放行依据会留痕——请写清依据（如「回评 #12 准确率 0.71 > 多数类 0.46，三组对照均改善」）。'}
      </Typography.Paragraph>
      {!unverify && !row.verified && !row.serveCheckedAt && (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }} message="这份产物还没跑过 serve 自检：建议先自检确认边车在服务它" />
      )}
      <Input.TextArea
        rows={3}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder={unverify ? '撤销原因' : '放行依据'}
      />
    </Modal>
  )
}

// ---------------- 详情 ----------------

function CheckpointDetailDrawer({ id, onClose }: { id: number; onClose: () => void }) {
  const [detail, setDetail] = useState<CheckpointDetail | null>(null)

  useEffect(() => {
    getCheckpoint(id)
      .then(setDetail)
      .catch((e) => showError(e, '加载产物详情失败'))
  }, [id])

  const c = detail?.checkpoint
  const report = detail?.metrics
  return (
    <Drawer title={c ? `产物「${c.name}」` : '产物详情'} width={1000} open onClose={onClose}>
      {detail && c ? (
        <>
          <Descriptions size="small" column={3} style={{ marginBottom: 12 }}>
            <Descriptions.Item label="槽位">{c.serveSlot ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="类型">
              <Tag color={c.kind === 'BASE' ? 'blue' : 'purple'}>{c.kindLabel || CP_KIND_LABEL[c.kind]}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="放行">
              {c.verified ? (
                <Tag color="green">
                  {c.verifiedBy ?? '—'} · {fmtTime(c.verifiedAt)}
                </Tag>
              ) : (
                <Tag>未放行</Tag>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="来源路径" span={2}>
              {c.sourcePath ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="节点">{c.nodeId ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="指纹" span={3}>
              {c.fingerprintSha256 ? (
                <Space size={8}>
                  <Typography.Text code copyable>
                    {c.fingerprintSha256}
                  </Typography.Text>
                  <Typography.Text type="secondary">
                    {c.fingerprintPath ?? '—'} · {fmtBytes(c.fingerprintBytes)}
                  </Typography.Text>
                </Space>
              ) : (
                '未登记'
              )}
            </Descriptions.Item>
            {c.verifiedNote && (
              <Descriptions.Item label="放行依据" span={3}>
                {c.verifiedNote}
              </Descriptions.Item>
            )}
            <Descriptions.Item label="登记">
              {c.createdBy ?? '—'} · {fmtTime(c.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label="最近自检" span={2}>
              {c.serveCheckedAt ? `${fmtTime(c.serveCheckedAt)} · ${c.serveCheckStatus ?? ''}` : '未自检'}
            </Descriptions.Item>
            {c.note && (
              <Descriptions.Item label="备注" span={3}>
                {c.note}
              </Descriptions.Item>
            )}
          </Descriptions>

          {detail.serveCheck && <ServeCheckBlock result={detail.serveCheck} />}
          {Object.keys(report ?? {}).length > 0 ? (
            <>
              <Typography.Text strong>最近一次回评指标（提示：这是评测/微调当时的数字，不含后续训练）</Typography.Text>
              <div style={{ marginTop: 8 }}>
                <MetricsTable metrics={report?.metrics} baselines={report?.baselines} />
              </div>
              <Typography.Text strong>报告原文</Typography.Text>
              <JsonBlock value={report} maxHeight={240} />
            </>
          ) : (
            <Alert type="info" showIcon style={{ marginBottom: 12 }} message="这份产物还没有指标报告（跑一次评测或微调回评就会写进来）" />
          )}
          {detail.calibration && <CalibrationBlock calibration={detail.calibration} />}
        </>
      ) : (
        <Typography.Text type="secondary">加载中…</Typography.Text>
      )}
    </Drawer>
  )
}
