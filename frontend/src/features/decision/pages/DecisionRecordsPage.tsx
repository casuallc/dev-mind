// CAP-55 FR-07 决策记录（模型建议 vs 人工裁决）：按能力筛选、逐题比对、导出 laya 训练 JSONL。
// 布局遵循 docs/core/前端内容区布局约定.md：Card 标题，extra 放操作按钮，FitTable 表内滚动。
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, DatePicker, Descriptions, Drawer, Input, Space, Table, Tag, Typography, message } from 'antd'
import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { exportDecisionRecords, getDecisionRecord, listDecisionRecords } from '../api'
import type { DecisionAnswer, DecisionRecord, DecisionRecordDetail } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'

/** 人工动作 → 中文（后端给的是机器值 adopt:global / adopt:project / reject） */
const ACTION_LABEL: Record<string, string> = {
  'adopt:global': '采纳到全局',
  'adopt:project': '采纳到项目',
  reject: '拒绝',
}

const actionText = (a: string | null) => (a ? ACTION_LABEL[a] ?? a : '未裁决')

/** 模型答案压成一行：choice 给选项值、score 给等级、noul 给是否（置信度另列） */
function answerText(a: DecisionAnswer | undefined): string {
  if (!a) return '—'
  if (a.type === 'choice') return a.choice ?? '—'
  if (a.type === 'score') return a.score == null ? '—' : String(a.score)
  if (a.type === 'noul') return a.noul == null ? '—' : a.noul >= 0.5 ? '是' : '否'
  return '—'
}

/** 人工 gold 值（题 id → 值）：与模型答案同一题面口径，直接可比 */
function goldText(gold: Record<string, unknown>, key: string): string {
  const v = gold[key]
  return v == null ? '—' : String(v)
}

function confidenceTag(a: DecisionAnswer | undefined) {
  if (!a || a.confidence == null) return null
  // 置信度只作展示参考（温度未校准前不构成自动执行依据），所以不按高低上色
  return <Typography.Text type="secondary">{Math.round(a.confidence * 100)}%</Typography.Text>
}

export default function DecisionRecordsPage() {
  const [rows, setRows] = useState<DecisionRecord[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [loading, setLoading] = useState(false)
  const [capability, setCapability] = useState('')
  const [since, setSince] = useState<string>('')
  const [exporting, setExporting] = useState(false)
  const [detail, setDetail] = useState<DecisionRecordDetail | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listDecisionRecords({ capability: capability.trim() || undefined, since: since || undefined, page, size })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      showError(e, '加载决策记录失败')
    } finally {
      setLoading(false)
    }
  }, [capability, since, page, size])

  useEffect(() => {
    load()
  }, [load])

  const openDetail = async (r: DecisionRecord) => {
    try {
      setDetail(await getDecisionRecord(r.id))
    } catch (e) {
      showError(e, '加载详情失败')
    }
  }

  const onExport = async () => {
    setExporting(true)
    try {
      const name = await exportDecisionRecords({
        capability: capability.trim() || undefined,
        since: since || undefined,
      })
      message.success(`已导出 ${name}`)
    } catch (e) {
      showError(e, '导出训练集失败')
    } finally {
      setExporting(false)
    }
  }

  const columns: ColumnsType<DecisionRecord> = [
    { title: '能力', dataIndex: 'capability', width: 160, ellipsis: true, render: (c: string) => <Tag>{c}</Tag> },
    { title: '对象', dataIndex: 'refId', width: 90 },
    {
      title: '模型建议',
      key: 'model',
      ellipsis: true,
      render: (_, r) => {
        if (r.degraded) return <Tag color="orange">降级：{r.degradedReason || '未拿到建议'}</Tag>
        const keys = Object.keys(r.answers ?? {})
        if (!keys.length) return '—'
        return (
          <Space size={8} wrap>
            {keys.map((k) => (
              <span key={k}>
                <Typography.Text type="secondary">{k}</Typography.Text>{' '}
                <Typography.Text>{answerText(r.answers[k])}</Typography.Text>{' '}
                {confidenceTag(r.answers[k])}
              </span>
            ))}
          </Space>
        )
      },
    },
    {
      title: '人工裁决',
      key: 'human',
      width: 190,
      render: (_, r) =>
        r.humanAction ? (
          <Space size={6}>
            <Tag color={r.humanAction === 'reject' ? 'default' : 'green'}>{actionText(r.humanAction)}</Tag>
            {Object.entries(r.gold ?? {}).map(([k, v]) => (
              <Typography.Text key={k} type="secondary">
                {k}={String(v)}
              </Typography.Text>
            ))}
          </Space>
        ) : (
          <Typography.Text type="secondary">未裁决</Typography.Text>
        ),
    },
    {
      title: '结果比对',
      key: 'agreement',
      width: 120,
      render: (_, r) => {
        const items = Object.entries(r.agreement ?? {})
        if (!items.length) return <Typography.Text type="secondary">—</Typography.Text>
        const agree = items.filter(([, ok]) => ok).length
        return (
          <Tag color={agree === items.length ? 'green' : agree === 0 ? 'red' : 'gold'}>
            {agree}/{items.length} 一致
          </Tag>
        )
      },
    },
    {
      title: '状态',
      key: 'state',
      width: 140,
      render: (_, r) => (
        <Space size={6}>
          {r.trainable ? <Tag color="blue">可训练</Tag> : <Tag>不可训练</Tag>}
          <Typography.Text type="secondary">{r.latencyMs} ms</Typography.Text>
        </Space>
      ),
    },
    { title: '建议时间', dataIndex: 'suggestedAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 90,
      render: (_, r) => <Button size="small" onClick={() => openDetail(r)}>查看</Button>,
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="决策记录"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<DownloadOutlined />} loading={exporting} onClick={onExport}>
            导出训练集
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        每次 AI 分诊的建议与人工裁决都留痕在这里：模型建议 vs 人工裁决逐题比对，「导出训练集」按当前筛选导出
        laya 微调用的 JSONL（只含 state/questions/gold 三字段，未裁决或 gold 落不上题面的行自动跳过）。
      </Typography.Paragraph>
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          allowClear
          placeholder="能力（如 kb-proposal-triage）"
          style={{ width: 260 }}
          onSearch={(v) => {
            setCapability(v)
            setPage(0)
          }}
        />
        <DatePicker
          allowClear
          placeholder="建议时间不早于"
          value={since ? dayjs(since) : null}
          onChange={(d) => {
            setSince(d ? d.format('YYYY-MM-DD') : '')
            setPage(0)
          }}
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
            '暂无决策记录——在「知识库 · 经验提案」里对提案做一次 AI 分诊或采纳/拒绝，这里就会出现对应的模型建议与人工裁决。',
        }}
      />

      <Drawer
        title={detail ? `决策记录 · ${detail.record.capability} #${detail.record.refId}` : ''}
        open={detail != null}
        onClose={() => setDetail(null)}
        width={860}
      >
        {detail && <DetailBody detail={detail} />}
      </Drawer>
    </Card>
  )
}

function DetailBody({ detail }: { detail: DecisionRecordDetail }) {
  const r = detail.record
  const questions = detail.questions ?? {}
  const answerKeys = Object.keys(r.answers ?? {})
  const allKeys = [...new Set([...Object.keys(questions), ...answerKeys, ...Object.keys(r.gold ?? {})])]

  return (
    <div>
      <Descriptions size="small" column={2} style={{ marginBottom: 16 }}>
        <Descriptions.Item label="能力">{r.capability}</Descriptions.Item>
        <Descriptions.Item label="对象">{r.refId}</Descriptions.Item>
        <Descriptions.Item label="建议时间">{fmtTime(r.suggestedAt)}</Descriptions.Item>
        <Descriptions.Item label="裁决时间">{r.decidedAt ? fmtTime(r.decidedAt) : '未裁决'}</Descriptions.Item>
        <Descriptions.Item label="裁决人">{r.decidedBy ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="人工动作">{actionText(r.humanAction)}</Descriptions.Item>
        <Descriptions.Item label="模型" span={2}>
          {r.model || '—'}
          {r.routingReason ? <Typography.Text type="secondary">（{r.routingReason}）</Typography.Text> : null}
          <Typography.Text type="secondary"> · {r.latencyMs} ms</Typography.Text>
        </Descriptions.Item>
        {r.degraded && (
          <Descriptions.Item label="降级原因" span={2}>
            <Typography.Text type="warning">{r.degradedReason || '未拿到建议'}</Typography.Text>
          </Descriptions.Item>
        )}
      </Descriptions>

      <Typography.Text strong>模型建议 vs 人工裁决</Typography.Text>
      <Table
        rowKey="key"
        size="small"
        style={{ marginTop: 8, marginBottom: 16 }}
        pagination={false}
        dataSource={allKeys.map((k) => ({ key: k }))}
        columns={[
          { title: '题', dataIndex: 'key', width: 160, ellipsis: true },
          { title: '类型', width: 80, render: (_, { key }) => questions[key]?.type ?? '—' },
          {
            title: '模型建议',
            render: (_, { key }) => {
              const a = r.answers?.[key]
              if (!a) return <Typography.Text type="secondary">未答</Typography.Text>
              return (
                <Space size={6}>
                  <span>{answerText(a)}</span>
                  {confidenceTag(a)}
                </Space>
              )
            },
          },
          {
            title: '人工裁决',
            width: 140,
            render: (_, { key }) =>
              key in (r.gold ?? {}) ? goldText(r.gold, key) : <Typography.Text type="secondary">未裁决</Typography.Text>,
          },
          {
            title: '一致性',
            width: 90,
            render: (_, { key }) => {
              const ok = (r.agreement ?? {})[key]
              if (ok == null) return <Typography.Text type="secondary">—</Typography.Text>
              return ok ? <Tag color="green">一致</Tag> : <Tag color="red">不一致</Tag>
            },
          },
        ]}
      />

      <Typography.Text strong>题面（当初发给模型的 questions）</Typography.Text>
      <JsonBlock value={questions} />
      <Typography.Text strong>输入（当初发给模型的 state 快照）</Typography.Text>
      <JsonBlock value={detail.state} />
    </div>
  )
}

/** state/questions 逐字回放：原样 JSON 比任何"美化重排"都可靠（训练集喂的就是这份） */
function JsonBlock({ value }: { value: unknown }) {
  return (
    <pre style={{ background: '#f6f6f6', padding: 12, borderRadius: 4, fontSize: 12, whiteSpace: 'pre-wrap', marginTop: 8, marginBottom: 16 }}>
      {JSON.stringify(value ?? {}, null, 2)}
    </pre>
  )
}
