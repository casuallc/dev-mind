// CAP-56 报告渲染件：指标 / 两条基线 / 逐题胜负 / 逐题明细 / 温度校准 / 训练过程。
// 评测与微调回评共用同一套（微调报告只是多出 split/train 两段）。
//
// 一条贯穿全文件的规矩：**缺项显示「—」，不显示 0**。报告的键就是"测出来了没有"的凭据，
// 把"没测"渲染成 0（准确率 0、误差 0）会让人读出与事实相反的结论——训练脚本侧同一口径
// （见 tools/laya-sidecar/lab/_rl_common.py 的 aggregate）。
import { Alert, Descriptions, Space, Table, Tag, Tooltip, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type {
  CaseGroupMetric,
  LabCalibration,
  LabCompare,
  MetricBlock,
  PerItem,
  ReportMetrics,
} from '../types'
import { CASE_GROUP_LABEL, CaseGroupTag, questionLabel } from './labCommon'

/** 数值 → 文本；null/非有限数 → 「—」 */
export function num(v: unknown, digits = 4): string {
  if (typeof v !== 'number' || !Number.isFinite(v)) return '—'
  const s = v.toFixed(digits)
  return s.replace(/\.?0+$/, '') || '0'
}

/** 比率 → 百分比文本 */
export function pct(v: unknown, digits = 1): string {
  if (typeof v !== 'number' || !Number.isFinite(v)) return '—'
  return `${(v * 100).toFixed(digits)}%`
}

/** gold / pred 值 → 文本（题面值可能是布尔、下标、字符串或数组） */
export function valText(v: unknown): string {
  if (v == null) return '—'
  if (typeof v === 'boolean') return v ? '是' : '否'
  if (Array.isArray(v)) return v.length ? v.map(valText).join('、') : '—'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

/** 报告原文 / 命令行的逐字回放（排错要看原值，不做美化重排） */
export function JsonBlock({ value, maxHeight = 320 }: { value: unknown; maxHeight?: number }) {
  return (
    <pre
      style={{
        background: '#f6f6f6',
        padding: 12,
        borderRadius: 4,
        fontSize: 12,
        whiteSpace: 'pre-wrap',
        marginTop: 8,
        marginBottom: 16,
        maxHeight,
        overflow: 'auto',
      }}
    >
      {JSON.stringify(value ?? {}, null, 2)}
    </pre>
  )
}

export function CommandBlock({ text }: { text: string | null }) {
  if (!text) return <Typography.Text type="secondary">未记录命令行</Typography.Text>
  return <JsonBlock value={text} maxHeight={160} />
}

const QTYPE_LABEL: Record<string, string> = { choice: '选项题', score: '等级题', noul: '是否题' }

type MetricRow = { key: string } & MetricBlock

/** 逐题型指标 + 两条基线。基线永远与指标并排看：单独的"准确率 0.72"读不出好坏 */
export function MetricsTable({
  metrics,
  baselines,
}: {
  metrics?: ReportMetrics
  baselines?: { random?: number; majority?: number }
}) {
  const rows: MetricRow[] = (['choice', 'score', 'noul'] as const)
    .map((k) => ({ key: k, ...(metrics?.[k] ?? {}) }))
    .filter((r) => metrics?.[r.key as 'choice'] != null)
  if (!rows.length) {
    return <Typography.Text type="secondary">报告里没有任何题型指标（样本为空，或脚本未回传 metrics）</Typography.Text>
  }
  const columns: ColumnsType<MetricRow> = [
    { title: '题型', dataIndex: 'key', width: 90, render: (k: string) => QTYPE_LABEL[k] ?? k },
    { title: '题数', width: 70, render: (_, r) => num(r.questions, 0) },
    { title: '准确率', width: 90, render: (_, r) => pct(r.accuracy) },
    {
      title: '软准确率',
      width: 100,
      render: (_, r) => (
        <Tooltip title="预测分布与 gold 分布的内积：比准确率多出的信息是「差一点」与「差很远」的区别">
          {pct(r.softAccuracy)}
        </Tooltip>
      ),
    },
    { title: 'Brier', width: 90, render: (_, r) => num(r.brier) },
    {
      title: 'ECE',
      width: 90,
      render: (_, r) => (
        <Tooltip title="置信度与正确率的偏差：校准前它越大，说明模型「说得越肯定」越不可信">
          {num(r.ece)}
        </Tooltip>
      ),
    },
    { title: 'MAE', width: 80, render: (_, r) => num(r.mae) },
    { title: '±1 级', width: 80, render: (_, r) => pct(r.within1Level) },
    { title: '平均置信度', width: 100, render: (_, r) => pct(r.avgConfidence) },
    {
      title: '预测分布',
      ellipsis: true,
      render: (_, r) => {
        const answered = r.answered
        if (!answered || !Object.keys(answered).length) return <Typography.Text type="secondary">—</Typography.Text>
        const text = Object.entries(answered)
          .map(([k, n]) => `${k}×${n}`)
          .join('、')
        return <Tooltip title={text}>{text}</Tooltip>
      },
    },
  ]
  return (
    <>
      <Descriptions size="small" column={4} style={{ marginBottom: 8 }}>
        <Descriptions.Item label="样本数">{num(metrics?.items, 0)}</Descriptions.Item>
        <Descriptions.Item label="题数">{num(metrics?.questions, 0)}</Descriptions.Item>
        <Descriptions.Item label="随机基线">
          <Tooltip title="每题均匀瞎猜（1/选项数）的平均值——什么都不做的下限">{pct(baselines?.random)}</Tooltip>
        </Descriptions.Item>
        <Descriptions.Item label="多数类基线">
          <Tooltip title="按题取该题在所有样本里最常见的 gold 来答——「只会答最常见那个」的下限">
            {pct(baselines?.majority)}
          </Tooltip>
        </Descriptions.Item>
        <Descriptions.Item label="延迟 p50">{num(metrics?.latencyMs?.p50, 1)} ms</Descriptions.Item>
        <Descriptions.Item label="延迟 p95">{num(metrics?.latencyMs?.p95, 1)} ms</Descriptions.Item>
      </Descriptions>
      <Table<MetricRow>
        rowKey="key"
        size="small"
        pagination={false}
        dataSource={rows}
        columns={columns}
        style={{ marginBottom: 16 }}
      />
    </>
  )
}

/** 与基线 checkpoint 的逐题胜负 */
export function CompareBlock({ compare }: { compare: LabCompare }) {
  const total = (compare.win ?? 0) + (compare.lose ?? 0) + (compare.tie ?? 0)
  return (
    <div style={{ marginBottom: 16 }}>
      <Space size={12} wrap style={{ marginBottom: 8 }}>
        <Typography.Text strong>对照基线</Typography.Text>
        <Tag color="green">胜 {num(compare.win, 0)}</Tag>
        <Tag color="red">负 {num(compare.lose, 0)}</Tag>
        <Tag>平 {num(compare.tie, 0)}</Tag>
        {total > 0 && (
          <Typography.Text type="secondary">
            净胜率 {pct(((compare.win ?? 0) - (compare.lose ?? 0)) / total)}
          </Typography.Text>
        )}
        <Typography.Text type="secondary">
          基线 checkpoint：{compare.baselineCheckpoint ?? '—'}
          {compare.baselineSlot ? `（槽位 ${compare.baselineSlot}）` : ''}
        </Typography.Text>
      </Space>
      <MetricsTable metrics={compare.baselineMetrics} />
    </div>
  )
}

/** 温度校准：held-out 拟合，报校准前后的 ECE */
export function CalibrationBlock({ calibration }: { calibration: LabCalibration }) {
  const buckets = Object.entries(calibration.temperature ?? {})
  return (
    <div style={{ marginBottom: 16 }}>
      <Space size={12} wrap style={{ marginBottom: 8 }}>
        <Typography.Text strong>温度校准</Typography.Text>
        <Typography.Text type="secondary">
          {calibration.mode === 'heldout' ? 'held-out（一半拟合、一半评估）' : calibration.mode ?? '—'}
          {calibration.source ? ` · 数据来源 ${calibration.source}` : ''}
        </Typography.Text>
      </Space>
      {calibration.note && (
        <Alert type="info" showIcon style={{ marginBottom: 8 }} message={calibration.note} />
      )}
      <Descriptions size="small" column={4} style={{ marginBottom: 8 }}>
        <Descriptions.Item label="拟合/评估条数">
          {num(calibration.fitItems, 0)} / {num(calibration.evalItems, 0)}
        </Descriptions.Item>
        <Descriptions.Item label="ECE 校准前">{num(calibration.before?.ece)}</Descriptions.Item>
        <Descriptions.Item label="ECE 校准后">{num(calibration.after?.ece)}</Descriptions.Item>
        <Descriptions.Item label="全量 ECE（前→后）">
          {num(calibration.allItems?.before?.ece)} → {num(calibration.allItems?.after?.ece)}
        </Descriptions.Item>
      </Descriptions>
      {buckets.length > 0 && (
        <Table
          rowKey="key"
          size="small"
          pagination={false}
          dataSource={buckets.map(([k, v]) => ({ key: k, bucket: k, t: v }))}
          columns={[
            { title: '桶（题型:选项数）', dataIndex: 'bucket' },
            { title: '温度 T', dataIndex: 't', render: (v: number) => num(v, 4) },
          ]}
        />
      )}
    </div>
  )
}

/** 对照组分解：CAP-55 那次退化在总准确率上只是"偏低"，摊开才看得见 */
export function CaseGroupMetricsTable({ rows }: { rows: CaseGroupMetric[] }) {
  if (!rows.length) return null
  return (
    <div style={{ marginBottom: 16 }}>
      <Typography.Text strong>按对照组分解</Typography.Text>
      <Table
        rowKey="caseGroup"
        size="small"
        pagination={false}
        dataSource={rows}
        style={{ marginTop: 8 }}
        columns={[
          { title: '对照组', dataIndex: 'caseGroup', render: (g: string) => <CaseGroupTag value={g} /> },
          { title: '样本', dataIndex: 'items', width: 80 },
          { title: '题数', dataIndex: 'questions', width: 80 },
          { title: '准确率', width: 100, render: (_, r) => pct(r.accuracy) },
        ]}
      />
    </div>
  )
}

/** 逐题明细（客户端分页：一次评测几百题，全在报告里） */
export function PerItemTable({ rows }: { rows: PerItem[] }) {
  if (!rows.length) return null
  const columns: ColumnsType<PerItem> = [
    { title: '样本', dataIndex: 'id', width: 80 },
    { title: '组', dataIndex: 'caseGroup', width: 130, render: (g: string) => <CaseGroupTag value={g} /> },
    {
      title: '题',
      dataIndex: 'question',
      width: 120,
      render: (q: string) => <Tooltip title={q}>{questionLabel(q)}</Tooltip>,
    },
    { title: '类型', dataIndex: 'qtype', width: 80, render: (t: string) => QTYPE_LABEL[t] ?? t },
    { title: 'gold', dataIndex: 'gold', width: 100, render: (v: unknown) => valText(v) },
    { title: '预测', dataIndex: 'pred', width: 100, render: (v: unknown) => valText(v) },
    {
      title: '对错',
      dataIndex: 'correct',
      width: 80,
      render: (ok: boolean | null) =>
        ok == null ? '—' : ok ? <Tag color="green">对</Tag> : <Tag color="red">错</Tag>,
    },
    { title: '置信度', dataIndex: 'confidence', width: 90, render: (v: number | null) => pct(v) },
    { title: '延迟', dataIndex: 'latencyMs', width: 90, render: (v: number | null) => `${num(v, 1)} ms` },
  ]
  return (
    <div style={{ marginBottom: 16 }}>
      <Typography.Text strong>逐题明细</Typography.Text>
      <Table<PerItem>
        rowKey={(r) => `${r.id}-${r.question}`}
        size="small"
        style={{ marginTop: 8 }}
        dataSource={rows}
        columns={columns}
        pagination={{ defaultPageSize: 20, showSizeChanger: true, showTotal: (t) => `共 ${t} 题` }}
      />
    </div>
  )
}

const TRAIN_LABEL: Record<string, string> = {
  steps: '步数',
  epochs: '轮数',
  questions: '训练题数',
  items: '训练样本数',
  batch: '批大小',
  groupSize: '组大小',
  exploration: '探索标准差',
  temperature: '采样温度',
  learningRate: '学习率',
  seed: '随机种子',
  finalLoss: '最终损失',
  meanReward: '平均奖励',
  lastReward: '末次奖励',
  durationSec: '训练耗时(秒)',
  encoderFrozen: '冻结编码器',
  worldSize: '进程数',
}

/** 训练过程（微调报告独有）：数字原样展示，收敛与否由人看 */
export function TrainBlock({ train }: { train: Record<string, unknown> }) {
  const entries = Object.entries(train)
  if (!entries.length) return null
  return (
    <div style={{ marginBottom: 16 }}>
      <Typography.Text strong>训练过程</Typography.Text>
      <Descriptions size="small" column={4} style={{ marginTop: 8 }}>
        {entries.map(([k, v]) => (
          <Descriptions.Item key={k} label={TRAIN_LABEL[k] ?? k}>
            {typeof v === 'number' ? num(v, 6) : valText(v)}
          </Descriptions.Item>
        ))}
      </Descriptions>
    </div>
  )
}

/** 执行包带来的提醒（样本量不足、题面覆盖不够等） */
export function WarningsAlert({ warnings }: { warnings?: string[] }) {
  if (!warnings?.length) return null
  return (
    <Alert
      type="warning"
      showIcon
      style={{ marginBottom: 12 }}
      message="报告提醒"
      description={
        <ul style={{ margin: 0, paddingLeft: 18 }}>
          {warnings.map((w) => (
            <li key={w}>{w}</li>
          ))}
        </ul>
      }
    />
  )
}

/** 对照组标签（外部只需要 label 时用） */
export const caseGroupLabel = (g: string) => CASE_GROUP_LABEL[g] ?? g
