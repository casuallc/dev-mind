// CAP-86 v3 报表页：跨库质量报表 = 生命周期 / 近重复 / 反馈 / 检索评测 / 自动增强。
// 视图切换由页面 Card title 内的 Segmented 承载；重报表手动触发，轻报表进视图即加载。
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  InputNumber,
  Modal,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ExperimentOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons'
import {
  duplicateReport,
  evalReport as fetchEvalReport,
  getEnrichConfig,
  lifecycleReport,
  listBases,
  listEnrichEntries,
  negativeFeedbackTop,
  reviewEntry,
  runEnrich,
  runEntryEnrich,
} from '../api'
import type {
  DocType,
  DuplicateItem,
  DuplicateReport,
  EnrichConfig,
  EnrichEntry,
  EvalCaseResult,
  EvalReport,
  KnowledgeBase,
  LifecycleItem,
  LifecycleReport,
  NegativeFeedbackDoc,
  NegativeFeedbackReport,
} from '../types'
import { DOC_TYPE_LABEL } from '../types'
import FitTable from '../../../shared/components/FitTable'
import KnowledgeEntryDrawer from './KnowledgeEntryDrawer'
import { fmtTime } from '../../../shared/utils/format'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { showError } from '../../../shared/utils/showError'
import { pagePaneScrollStyle } from '../../../shared/utils/pageLayout'

/** 表格类视图（生命周期/反馈/增强）的区块容器：工具行固定、FitTable 撑满剩余高度 */
const sectionFlexStyle = { flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' } as const

type LifecycleGroupKey = 'expired' | 'expiring' | 'stale' | 'missingOwner'

const LIFECYCLE_LABEL: Record<LifecycleGroupKey, string> = {
  expired: '已过期',
  expiring: '即将过期',
  stale: '长期未复核',
  missingOwner: '缺失负责人',
}

const pct = (v: number) => `${Math.round(v * 1000) / 10}%`

// ---------------- 生命周期 ----------------

function LifecycleSection({
  kbName,
  onOpenEntry,
}: {
  kbName: Map<number, string>
  onOpenEntry: (entryId: number) => void
}) {
  const [lifecycle, setLifecycle] = useState<LifecycleReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [group, setGroup] = useState<LifecycleGroupKey>('expired')
  const [expiringDays, setExpiringDays] = useState(14)
  const [staleDays, setStaleDays] = useState(180)
  const [reviewingId, setReviewingId] = useState<number | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setLifecycle(await lifecycleReport(expiringDays, staleDays))
    } catch (e) {
      showError(e, '加载生命周期报告失败')
    } finally {
      setLoading(false)
    }
  }, [expiringDays, staleDays])

  useEffect(() => {
    load()
  }, [load])

  const onReview = (item: LifecycleItem) => {
    Modal.confirm({
      centered: true,
      title: `登记「${item.name}」的复核？`,
      content: '将把最近复核时间更新为当前时间，并移出长期未复核分组。',
      okText: '登记复核',
      cancelText: '取消',
      onOk: async () => {
        setReviewingId(item.id)
        try {
          await reviewEntry(item.id)
          message.success(`已登记复核：${item.name}`)
          load()
        } catch (e) {
          showError(e, '登记复核失败')
        } finally {
          setReviewingId(null)
        }
      },
    })
  }

  const items: LifecycleItem[] = lifecycle ? lifecycle[group] : []
  const counts: Record<LifecycleGroupKey, number> = {
    expired: lifecycle?.expiredCount ?? 0,
    expiring: lifecycle?.expiringCount ?? 0,
    stale: lifecycle?.staleCount ?? 0,
    missingOwner: lifecycle?.missingOwnerCount ?? 0,
  }

  const columns: ColumnsType<LifecycleItem> = [
    {
      title: '文档',
      key: 'name',
      ellipsis: true,
      render: (_, r) => (
        <Space direction="vertical" size={0}>
          <Typography.Link onClick={() => onOpenEntry(r.id)}>{r.name}</Typography.Link>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {kbName.get(r.kbId ?? -1) ?? (r.kbId ? `#${r.kbId}` : '未归属知识库')}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'docType',
      width: 100,
      render: (t: DocType | null) => (t ? <Tag color="blue">{DOC_TYPE_LABEL[t] ?? t}</Tag> : '-'),
    },
    {
      title: '负责人',
      dataIndex: 'owner',
      width: 110,
      render: (v: string | null) => (v ? v : <Typography.Text type="danger">未指定</Typography.Text>),
    },
    {
      title: '有效期至',
      dataIndex: 'validTo',
      width: 165,
      render: (v: string | null) => {
        if (!v) return <Typography.Text type="secondary">长期</Typography.Text>
        const overdue = new Date(v).getTime() < Date.now()
        return <Typography.Text type={overdue ? 'danger' : undefined}>{fmtTime(v)}</Typography.Text>
      },
    },
    {
      title: '最近复核',
      dataIndex: 'lastReviewedAt',
      width: 165,
      render: (v: string | null) => (v ? fmtTime(v) : <Typography.Text type="warning">未复核</Typography.Text>),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 165,
      render: (v: string) => fmtTime(v),
    },
    {
      title: '操作',
      key: 'review',
      width: 90,
      render: (_, r) => (
        <Button size="small" loading={reviewingId === r.id} onClick={() => onReview(r)}>
          去复核
        </Button>
      ),
    },
  ]

  return (
    <div style={sectionFlexStyle}>
      <Space style={{ marginBottom: 12, flexShrink: 0 }} wrap>
        <Typography.Text type="secondary">即将过期阈值（天）</Typography.Text>
        <InputNumber
          min={1}
          max={365}
          value={expiringDays}
          onChange={(v) => setExpiringDays(v ?? 14)}
          style={{ width: 90 }}
        />
        <Typography.Text type="secondary">未复核阈值（天）</Typography.Text>
        <InputNumber
          min={30}
          max={3650}
          value={staleDays}
          onChange={(v) => setStaleDays(v ?? 180)}
          style={{ width: 100 }}
        />
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
          刷新报告
        </Button>
        {lifecycle && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            生成于 {fmtTime(lifecycle.generatedAt)} · 每组最多展示 {lifecycle.limit} 条
          </Typography.Text>
        )}
      </Space>
      <Segmented
        value={group}
        onChange={(v) => setGroup(v as LifecycleGroupKey)}
        options={(Object.keys(LIFECYCLE_LABEL) as LifecycleGroupKey[]).map((k) => ({
          value: k,
          label: `${LIFECYCLE_LABEL[k]}（${counts[k]}）`,
        }))}
        style={{ marginBottom: 12, flexShrink: 0 }}
      />
      {lifecycle && (
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 0, flexShrink: 0 }}>
          四组互相独立：同一篇文档可能同时出现在「已过期」与「缺失负责人」。复核后记录复核时间，长期未复核以复核时间（未复核则取更新时间）计算。
        </Typography.Paragraph>
      )}
      <FitTable<LifecycleItem>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={items}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '该分组没有需要处理的文档。' }}
      />
    </div>
  )
}

// ---------------- 近重复文档 ----------------

function DuplicatesSection({
  kbName,
  onOpenEntry,
}: {
  kbName: Map<number, string>
  onOpenEntry: (entryId: number) => void
}) {
  const [duplicates, setDuplicates] = useState<DuplicateReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [threshold, setThreshold] = useState(3)
  const [maxEntries, setMaxEntries] = useState(2000)

  // SimHash 扫描重，手动触发不自动加载
  const load = useCallback(async () => {
    setLoading(true)
    try {
      setDuplicates(await duplicateReport(threshold, maxEntries))
    } catch (e) {
      showError(e, '加载重复文档报告失败')
    } finally {
      setLoading(false)
    }
  }, [threshold, maxEntries])

  const columns: ColumnsType<DuplicateItem> = [
    {
      title: '文档',
      key: 'name',
      ellipsis: true,
      render: (_, r) => (
        <Space direction="vertical" size={0}>
          <Typography.Link onClick={() => onOpenEntry(r.id)}>{r.name}</Typography.Link>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {kbName.get(r.kbId ?? -1) ?? (r.kbId ? `#${r.kbId}` : '未归属知识库')}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'docType',
      width: 100,
      render: (t: DocType | null) => (t ? <Tag color="blue">{DOC_TYPE_LABEL[t] ?? t}</Tag> : '-'),
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 165, render: (v: string) => fmtTime(v) },
    {
      title: '指纹',
      dataIndex: 'simHash',
      width: 160,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
    },
  ]

  return (
    <div style={pagePaneScrollStyle}>
      <Space style={{ marginBottom: 12 }} wrap>
        <Typography.Text type="secondary">汉明距离阈值</Typography.Text>
        <InputNumber
          min={0}
          max={16}
          value={threshold}
          onChange={(v) => setThreshold(v ?? 3)}
          style={{ width: 80 }}
        />
        <Typography.Text type="secondary">最多扫描</Typography.Text>
        <InputNumber
          min={100}
          max={5000}
          step={100}
          value={maxEntries}
          onChange={(v) => setMaxEntries(v ?? 2000)}
          style={{ width: 100 }}
        />
        <Button icon={<ExperimentOutlined />} loading={loading} onClick={load}>
          扫描近重复
        </Button>
        {duplicates && (
          <>
            <Tag color="blue">扫描 {duplicates.scanned} 篇</Tag>
            <Tag color="orange">发现 {duplicates.groups.length} 组</Tag>
            {duplicates.remainingWithoutHash > 0 && (
              <Tooltip title="下次扫描会继续补算指纹，每次最多补 2000 篇">
                <Tag>待补指纹 {duplicates.remainingWithoutHash}</Tag>
              </Tooltip>
            )}
          </>
        )}
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
        口径：内容 SimHash 的汉明距离小于等于阈值即视为近重复；只报告不合并，请人工确认后保留权威版本、下线其余文档。
      </Typography.Paragraph>
      {loading && !duplicates ? (
        <Empty description="正在扫描…" />
      ) : duplicates && duplicates.groups.length > 0 ? (
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          {duplicates.groups.map((g, i) => (
            <Card
              key={`${i}-${g.entries[0]?.id}`}
              size="small"
              title={
                <Space size={8}>
                  <span>近重复组 {i + 1}</span>
                  <Tag color="orange">最大距离 {g.maxDistance}</Tag>
                  <Tag>{g.entries.length} 篇</Tag>
                </Space>
              }
            >
              <Table<DuplicateItem>
                rowKey="id"
                columns={columns}
                dataSource={g.entries}
                pagination={false}
              />
            </Card>
          ))}
        </Space>
      ) : (
        <Empty description={duplicates ? '未发现近重复文档（阈值为汉明距离，越小越严格）。' : '点击「扫描近重复」生成报告。'} />
      )}
    </div>
  )
}

// ---------------- 反馈治理 ----------------

function FeedbackSection({
  kbName,
  onOpenEntry,
}: {
  kbName: Map<number, string>
  onOpenEntry: (entryId: number) => void
}) {
  const [feedback, setFeedback] = useState<NegativeFeedbackReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [days, setDays] = useState(30)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setFeedback(await negativeFeedbackTop(days, 20))
    } catch (e) {
      showError(e, '加载反馈统计失败')
    } finally {
      setLoading(false)
    }
  }, [days])

  useEffect(() => {
    load()
  }, [load])

  const columns: ColumnsType<NegativeFeedbackDoc> = [
    {
      title: '文档',
      key: 'doc',
      ellipsis: true,
      render: (_, r) => (
        <Space direction="vertical" size={2}>
          <Typography.Link onClick={() => onOpenEntry(r.entryId)}>
            {r.entryName ?? `文档 #${r.entryId}`}
          </Typography.Link>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {r.kbId ? kbName.get(r.kbId) ?? `库 #${r.kbId}` : '（库已删除）'}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '负面 / 总反馈',
      width: 140,
      render: (_, r) => (
        <Space size={6}>
          <Tag color="red">{r.negativeCount} 负面</Tag>
          <Typography.Text type="secondary">共 {r.totalCount}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '负面率',
      dataIndex: 'negativeRate',
      width: 100,
      render: (v: number) => (
        <Tag color={v >= 0.5 ? 'red' : v >= 0.3 ? 'orange' : 'default'}>{Math.round(v * 100)}%</Tag>
      ),
    },
    {
      title: '主要原因',
      key: 'reasons',
      render: (_, r) =>
        r.reasons.length > 0 ? (
          <Space size={4} wrap>
            {r.reasons.map((x) => (
              <Tag key={x.reason} color="volcano">
                {x.reason} ×{x.count}
              </Tag>
            ))}
          </Space>
        ) : (
          '-'
        ),
    },
    {
      title: '最近负面时间',
      dataIndex: 'lastNegativeAt',
      width: 165,
      render: (v: string | null) => (v ? fmtTime(v) : '-'),
    },
  ]

  return (
    <div style={sectionFlexStyle}>
      <Space style={{ marginBottom: 12, flexShrink: 0 }} wrap>
        <Select
          value={days}
          onChange={setDays}
          style={{ width: 110 }}
          options={[
            { value: 7, label: '近 7 天' },
            { value: 30, label: '近 30 天' },
            { value: 90, label: '近 90 天' },
          ]}
        />
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
          刷新
        </Button>
        {feedback && (
          <>
            <Tag color="blue">反馈总数 {feedback.feedbackTotal}</Tag>
            <Tag color="red">无用 {feedback.negativeTotal}</Tag>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              统计自 {fmtTime(feedback.since)}
            </Typography.Text>
          </>
        )}
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginTop: 0, flexShrink: 0 }}>
        聚合知识会话里「无用」反馈命中的文档，按负面数排序；到文档详情查看分块并补充/重写内容后重建索引。
      </Typography.Paragraph>
      <FitTable<NegativeFeedbackDoc>
        rowKey={(r) => String(r.entryId)}
        loading={loading}
        columns={columns}
        dataSource={feedback?.docs ?? []}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '区间内没有负面反馈。' }}
      />
    </div>
  )
}

// ---------------- 检索评测 ----------------

function EvalSection({ bases }: { bases: KnowledgeBase[] }) {
  const [evalResult, setEvalResult] = useState<EvalReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [topK, setTopK] = useState(10)
  const [kbId, setKbId] = useState<number | undefined>(undefined)

  // 评测要跑完整数据集，手动触发不自动加载
  const load = useCallback(async () => {
    setLoading(true)
    try {
      setEvalResult(await fetchEvalReport(topK, kbId))
    } catch (e) {
      showError(e, '运行检索评测失败')
    } finally {
      setLoading(false)
    }
  }, [topK, kbId])

  const columns: ColumnsType<EvalCaseResult> = [
    {
      title: '题目',
      key: 'question',
      ellipsis: true,
      render: (_, r) => (
        <Space direction="vertical" size={0}>
          <Typography.Text ellipsis style={{ maxWidth: 420 }} title={r.question}>
            {r.question}
          </Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {r.id} · 期望 {r.expectedEntryNames.join(' / ') || '无'}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '可回答',
      dataIndex: 'answerable',
      width: 80,
      render: (v: boolean) => (v ? <Tag color="blue">是</Tag> : <Tag>否</Tag>),
    },
    {
      title: '命中',
      dataIndex: 'hit',
      width: 80,
      render: (v: boolean) => (v ? <Tag color="green">命中</Tag> : <Tag color="red">未命中</Tag>),
    },
    {
      title: '首个相关排名',
      dataIndex: 'firstRelevantRank',
      width: 120,
      render: (v: number) => (v > 0 ? `#${v}` : '-'),
    },
    {
      title: '引用精确率',
      dataIndex: 'citationPrecision',
      width: 110,
      render: (v: number) => pct(v),
    },
    {
      title: '拒答',
      dataIndex: 'refused',
      width: 90,
      render: (v: boolean, r) => {
        const good = r.answerable ? !v : v
        return <Tag color={good ? 'green' : 'orange'}>{v ? '已拒答' : '未拒答'}</Tag>
      },
    },
  ]

  return (
    <div style={pagePaneScrollStyle}>
      <Space style={{ marginBottom: 12 }} wrap>
        <Typography.Text type="secondary">Top-K</Typography.Text>
        <Select
          value={topK}
          onChange={setTopK}
          style={{ width: 90 }}
          options={[5, 10, 20, 30].map((v) => ({ value: v, label: String(v) }))}
        />
        <Select
          allowClear
          placeholder="全部知识库"
          style={{ minWidth: 200 }}
          value={kbId}
          onChange={setKbId}
          options={bases.map((b) => ({ value: b.id, label: b.name }))}
        />
        <Button icon={<ExperimentOutlined />} loading={loading} onClick={load}>
          运行评测
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          指标来自 tests/knowledge_eval/dataset.json：Recall/MRR/引用精确率只统计可回答题，拒答率只统计不可回答题。
        </Typography.Text>
      </Space>
      {!evalResult ? (
        <Empty description="点击「运行评测」生成报告。" />
      ) : (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {evalResult.degradedReason && evalResult.degradedReason !== 'NONE' && (
            <Alert
              type={evalResult.degradedReason.startsWith('DATASET') ? 'error' : 'warning'}
              showIcon
              message={evalResult.degradedReason.startsWith('DATASET') ? '评测数据集不可用' : '检索通道降级'}
              description={evalResult.degradedReason}
            />
          )}
          <Row gutter={12}>
            <Col span={6}>
              <Card size="small">
                <Statistic title={`Recall@${evalResult.topK}`} value={evalResult.recallAtK * 100} precision={1} suffix="%" />
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic title="MRR" value={evalResult.mrr} precision={3} />
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic title="引用精确率" value={evalResult.citationPrecision * 100} precision={1} suffix="%" />
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic title="拒答率（不可回答题）" value={evalResult.refusalRate * 100} precision={1} suffix="%" />
              </Card>
            </Col>
          </Row>
          <Space size={12} wrap>
            <Typography.Text type="secondary">
              数据集 {evalResult.dataset} · {evalResult.datasetPath}
            </Typography.Text>
            <Tag color="blue">可回答 {evalResult.answerableCount}</Tag>
            <Tag>不可回答 {evalResult.unanswerableCount}</Tag>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              生成于 {fmtTime(evalResult.generatedAt)}
            </Typography.Text>
          </Space>
          <Table<EvalCaseResult>
            rowKey="id"
            loading={loading}
            columns={columns}
            dataSource={evalResult.cases}
            pagination={LIST_PAGINATION}
            expandable={{
              rowExpandable: (r) => r.hits.length > 0 || r.expectedChunkContains.length > 0,
              expandedRowRender: (r) => (
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  {r.hits.map((h) => (
                    <Space key={h.rank} size={8}>
                      <Tag color="default">#{h.rank}</Tag>
                      <Typography.Text>{h.title}</Typography.Text>
                      {h.docType && <Tag color="blue">{DOC_TYPE_LABEL[h.docType] ?? h.docType}</Tag>}
                      {h.relevant && <Tag color="green">相关</Tag>}
                    </Space>
                  ))}
                  {r.expectedChunkContains.length > 0 && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      期望片段：{r.expectedChunkContains.join('；')}
                    </Typography.Text>
                  )}
                </Space>
              ),
            }}
          />
        </Space>
      )}
    </div>
  )
}

// ---------------- 自动增强 ----------------

function EnrichSection({
  bases,
  onOpenEntry,
}: {
  bases: KnowledgeBase[]
  onOpenEntry: (entryId: number) => void
}) {
  const [enrichConfig, setEnrichConfig] = useState<EnrichConfig | null>(null)
  const [entries, setEntries] = useState<EnrichEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [kbId, setKbId] = useState<number | undefined>(undefined)
  const [limit, setLimit] = useState(100)
  const [enrichingId, setEnrichingId] = useState<number | null>(null)
  const [batchEnriching, setBatchEnriching] = useState(false)

  const kbName = new Map<number, string>(bases.map((b) => [b.id, b.name]))

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setEntries(await listEnrichEntries(kbId, limit))
    } catch (e) {
      showError(e, '加载增强状态失败')
    } finally {
      setLoading(false)
    }
  }, [kbId, limit])

  useEffect(() => {
    getEnrichConfig()
      .then(setEnrichConfig)
      .catch((e) => showError(e, '加载增强配置失败'))
    load()
  }, [load])

  const onBatchEnrich = () => {
    const scope = kbId ? `知识库「${kbName.get(kbId) ?? kbId}」` : '全部知识库'
    Modal.confirm({
      centered: true,
      title: `立即增强${scope}（最多 ${limit} 条）？`,
      content: '增强会改写文档的分类、摘要与标签（人工锁定条目不受影响），并重建索引。',
      okText: '增强',
      cancelText: '取消',
      onOk: async () => {
        setBatchEnriching(true)
        try {
          const r = await runEnrich(kbId, limit)
          message.success(`已提交自动增强任务（最多 ${r.limit} 条），稍后刷新查看结果`)
          window.setTimeout(() => load(), 1500)
        } catch (e) {
          showError(e, '提交自动增强失败')
        } finally {
          setBatchEnriching(false)
        }
      },
    })
  }

  const onEnrichEntry = (e: EnrichEntry) => {
    Modal.confirm({
      centered: true,
      title: `补全「${e.name}」的元数据？`,
      content: e.autoEnrich
        ? '会重新生成分类、摘要与标签，并重建索引。'
        : '该条目为人工锁定；补全会恢复自动增强并覆盖现有分类、摘要与标签。',
      okText: '补全',
      cancelText: '取消',
      onOk: async () => {
        setEnrichingId(e.id)
        try {
          await runEntryEnrich(e.id)
          message.success(`已提交补全任务：${e.name}`)
          window.setTimeout(() => load(), 1500)
        } catch (err) {
          showError(err, '提交补全任务失败')
        } finally {
          setEnrichingId(null)
        }
      },
    })
  }

  const statusTag = (e: EnrichEntry) => {
    if (!e.autoEnrich) {
      return (
        <Tooltip title="已人工修正分类/摘要/标签，自动增强不再覆盖；点「补全」可恢复">
          <Tag>人工锁定</Tag>
        </Tooltip>
      )
    }
    if (e.enrichError) {
      return (
        <Tooltip title={e.enrichError}>
          <Tag color="red">失败</Tag>
        </Tooltip>
      )
    }
    const fresh = e.enrichedAt && (!e.updatedAt || new Date(e.enrichedAt).getTime() >= new Date(e.updatedAt).getTime())
    return fresh ? <Tag color="green">已增强</Tag> : <Tag color="gold">待增强</Tag>
  }

  const columns: ColumnsType<EnrichEntry> = [
    {
      title: '文档',
      key: 'name',
      ellipsis: true,
      render: (_, r) => <Typography.Link onClick={() => onOpenEntry(r.id)}>{r.name}</Typography.Link>,
    },
    {
      title: '知识库',
      key: 'kb',
      width: 150,
      ellipsis: true,
      render: (_, r) => <Tag color="purple">{kbName.get(r.kbId ?? -1) ?? `#${r.kbId}`}</Tag>,
    },
    {
      title: '类型',
      dataIndex: 'docType',
      width: 100,
      render: (t: DocType | null) => (t ? <Tag color="blue">{DOC_TYPE_LABEL[t] ?? t}</Tag> : '-'),
    },
    { title: '增强状态', key: 'status', width: 110, render: (_, r) => statusTag(r) },
    {
      title: '最近增强',
      dataIndex: 'enrichedAt',
      width: 165,
      render: (v: string | null) => (v ? fmtTime(v) : '-'),
    },
    {
      title: '失败原因',
      dataIndex: 'enrichError',
      ellipsis: true,
      render: (v: string | null) =>
        v ? (
          <Tooltip title={v}>
            <Typography.Text type="danger" style={{ fontSize: 12 }}>
              {v}
            </Typography.Text>
          </Tooltip>
        ) : (
          '-'
        ),
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, r) => (
        <Button size="small" loading={enrichingId === r.id} onClick={() => onEnrichEntry(r)}>
          补全
        </Button>
      ),
    },
  ]

  return (
    <div style={sectionFlexStyle}>
      <Descriptions
        size="small"
        column={4}
        style={{ marginBottom: 12, flexShrink: 0 }}
        items={[
          {
            key: 'enabled',
            label: '自动排队',
            children: enrichConfig ? (
              enrichConfig.enabled ? (
                <Tag color="green">已开启</Tag>
              ) : (
                <Tag>已关闭（可手工触发）</Tag>
              )
            ) : (
              '-'
            ),
          },
          {
            key: 'endpoint',
            label: 'CHAT 端点',
            children: enrichConfig?.endpointId ? `#${enrichConfig.endpointId}` : '平台默认',
          },
          {
            key: 'batch',
            label: '单批上限',
            children: enrichConfig ? `${enrichConfig.batchLimit} 条` : '-',
          },
          {
            key: 'input',
            label: '正文截断',
            children: enrichConfig ? `${enrichConfig.maxInputChars} 字` : '-',
          },
        ]}
      />
      <Space style={{ marginBottom: 12, flexShrink: 0 }} wrap>
        <Select
          allowClear
          placeholder="全部知识库"
          style={{ minWidth: 200 }}
          value={kbId}
          onChange={setKbId}
          options={bases.map((b) => ({ value: b.id, label: b.name }))}
        />
        <InputNumber
          min={1}
          max={200}
          value={limit}
          onChange={(v) => setLimit(v ?? 100)}
          addonAfter="条"
          style={{ width: 130 }}
        />
        <Button type="primary" icon={<ThunderboltOutlined />} loading={batchEnriching} onClick={onBatchEnrich}>
          立即增强
        </Button>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
          刷新
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          增强为异步任务；人工修正过的条目默认不覆盖，点「补全」可恢复自动模式。
        </Typography.Text>
      </Space>
      <FitTable<EnrichEntry>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={entries}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '暂无条目。' }}
      />
    </div>
  )
}

// ---------------- 面板 ----------------

export type ReportsView = 'lifecycle' | 'duplicates' | 'feedback' | 'eval' | 'enrich'

export const REPORT_VIEW_OPTIONS: { value: ReportsView; label: string }[] = [
  { value: 'lifecycle', label: '生命周期' },
  { value: 'duplicates', label: '近重复' },
  { value: 'feedback', label: '答案反馈' },
  { value: 'eval', label: '检索评测' },
  { value: 'enrich', label: '自动增强' },
]

export default function KnowledgeReportsPanel({ view }: { view: ReportsView }) {
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [entryDetail, setEntryDetail] = useState<{ entryId: number } | null>(null)

  useEffect(() => {
    listBases()
      .then(setBases)
      .catch((e) => showError(e, '加载知识库失败'))
  }, [])

  const kbName = useMemo(() => {
    const map = new Map<number, string>()
    for (const b of bases) map.set(b.id, b.name)
    return map
  }, [bases])

  const openEntry = (entryId: number) => setEntryDetail({ entryId })

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      {view === 'lifecycle' && <LifecycleSection kbName={kbName} onOpenEntry={openEntry} />}
      {view === 'duplicates' && <DuplicatesSection kbName={kbName} onOpenEntry={openEntry} />}
      {view === 'feedback' && <FeedbackSection kbName={kbName} onOpenEntry={openEntry} />}
      {view === 'eval' && <EvalSection bases={bases} />}
      {view === 'enrich' && <EnrichSection bases={bases} onOpenEntry={openEntry} />}
      <KnowledgeEntryDrawer
        entryId={entryDetail?.entryId}
        kbNames={kbName}
        onClose={() => setEntryDetail(null)}
      />
    </div>
  )
}
