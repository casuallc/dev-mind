// CAP-85 FR-31 知识治理控制台：同步源 / 生命周期 / 重复文档 / 自动增强 / 检索评测 / 反馈治理。
// 只读报告 + 少量人工动作（运行同步、登记复核、补全元数据），合并原「反馈治理」入口。
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import {
  DeleteOutlined,
  EditOutlined,
  ExperimentOutlined,
  PlusOutlined,
  ReloadOutlined,
  SyncOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import {
  createSyncSource,
  deleteSyncSource,
  duplicateReport,
  evalReport as fetchEvalReport,
  getEnrichConfig,
  lifecycleReport,
  listEnrichEntries,
  listSyncSources,
  negativeFeedbackTop,
  reviewEntry,
  runEnrich,
  runEntryEnrich,
  runSyncSource,
  updateSyncSource,
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
  KnowledgeSyncSource,
  KnowledgeSyncSourceInput,
  LifecycleItem,
  LifecycleReport,
  NegativeFeedbackDoc,
  NegativeFeedbackReport,
  SyncSourceKind,
  SyncStats,
} from '../types'
import { DOC_TYPE_LABEL, DOC_TYPE_OPTIONS } from '../types'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { showError } from '../../../shared/utils/showError'

type GovernanceTab = 'sync' | 'lifecycle' | 'duplicates' | 'enrich' | 'eval' | 'feedback'
type LifecycleGroupKey = 'expired' | 'expiring' | 'stale' | 'missingOwner'

const SYNC_STATUS: Record<string, { color: string; text: string }> = {
  RUNNING: { color: 'processing', text: '运行中' },
  OK: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
}

const LIFECYCLE_LABEL: Record<LifecycleGroupKey, string> = {
  expired: '已过期',
  expiring: '即将过期',
  stale: '长期未复核',
  missingOwner: '缺失负责人',
}

const cfgStr = (config: Record<string, unknown>, key: string) => {
  const v = config[key]
  return v == null ? '' : String(v)
}

const cfgNum = (config: Record<string, unknown>, key: string) => {
  const n = Number(config[key])
  return Number.isFinite(n) && n > 0 ? n : undefined
}

const cfgStrArray = (config: Record<string, unknown>, key: string) => {
  const v = config[key]
  if (Array.isArray(v)) return v.map((x) => String(x))
  if (typeof v === 'string' && v.trim()) {
    return v
      .split(',')
      .map((x) => x.trim())
      .filter(Boolean)
  }
  return []
}

const pct = (v: number) => `${Math.round(v * 1000) / 10}%`

interface SyncFormValues {
  kbId: number
  kind: SyncSourceKind
  name?: string
  enabled: boolean
  repoPath?: string
  ref?: string
  docsPath?: string
  integrationId?: number
  target?: string
  docType?: DocType
  tags?: string[]
}

export default function GovernanceConsole({
  bases,
  onOpenEntry,
}: {
  bases: KnowledgeBase[]
  onOpenEntry: (entryId: number) => void
}) {
  const [tab, setTab] = useState<GovernanceTab>('sync')
  const kbName = useMemo(() => {
    const map = new Map<number, string>()
    for (const b of bases) map.set(b.id, b.name)
    return map
  }, [bases])

  // ---------------- 同步源 ----------------
  const [sources, setSources] = useState<KnowledgeSyncSource[]>([])
  const [sourcesLoading, setSourcesLoading] = useState(false)
  const [syncModalOpen, setSyncModalOpen] = useState(false)
  const [editingSync, setEditingSync] = useState<KnowledgeSyncSource | null>(null)
  const [syncSaving, setSyncSaving] = useState(false)
  const [runningSync, setRunningSync] = useState<number | null>(null)
  const [syncForm] = Form.useForm<SyncFormValues>()
  const syncKind = Form.useWatch('kind', syncForm) ?? 'GIT'

  const loadSync = useCallback(async (silent = false) => {
    if (!silent) setSourcesLoading(true)
    try {
      setSources(await listSyncSources())
    } catch (e) {
      if (!silent) showError(e, '加载同步源失败')
    } finally {
      if (!silent) setSourcesLoading(false)
    }
  }, [])

  // 同步是异步任务：停留在同步源页签时低频轮询，RUNNING/统计无需手动刷新
  useEffect(() => {
    if (tab !== 'sync') return
    loadSync()
    const timer = window.setInterval(() => loadSync(true), 5000)
    return () => window.clearInterval(timer)
  }, [tab, loadSync])

  const openCreateSync = () => {
    setEditingSync(null)
    syncForm.resetFields()
    syncForm.setFieldsValue({ kind: 'GIT', enabled: true, ref: 'HEAD', docsPath: 'docs' })
    setSyncModalOpen(true)
  }

  const openEditSync = (s: KnowledgeSyncSource) => {
    setEditingSync(s)
    syncForm.resetFields()
    syncForm.setFieldsValue({
      kbId: s.kbId,
      kind: s.kind,
      name: s.name,
      enabled: s.enabled,
      repoPath: cfgStr(s.config, 'repoPath') || undefined,
      ref: cfgStr(s.config, 'ref') || 'HEAD',
      docsPath: cfgStr(s.config, 'docsPath') || 'docs',
      integrationId: cfgNum(s.config, 'integrationId'),
      target: cfgStr(s.config, 'target') || undefined,
      docType: (cfgStr(s.config, 'docType') || undefined) as DocType | undefined,
      tags: cfgStrArray(s.config, 'tags'),
    })
    setSyncModalOpen(true)
  }

  const onSaveSync = async () => {
    const v = await syncForm.validateFields()
    const config: Record<string, unknown> = { tags: v.tags ?? [] }
    if (v.docType) config.docType = v.docType
    if (v.kind === 'GIT') {
      config.repoPath = v.repoPath
      config.ref = v.ref || 'HEAD'
      config.docsPath = v.docsPath || 'docs'
    } else {
      config.integrationId = v.integrationId
      config.target = v.target
    }
    const input: KnowledgeSyncSourceInput = {
      kbId: v.kbId,
      kind: v.kind,
      name: v.name,
      config,
      enabled: v.enabled,
    }
    setSyncSaving(true)
    try {
      if (editingSync) {
        await updateSyncSource(editingSync.id, input)
        message.success('同步源已更新')
      } else {
        await createSyncSource(input)
        message.success('同步源已创建')
      }
      setSyncModalOpen(false)
      loadSync()
    } catch (e) {
      showError(e, '保存同步源失败')
    } finally {
      setSyncSaving(false)
    }
  }

  const onRunSync = async (s: KnowledgeSyncSource) => {
    setRunningSync(s.id)
    try {
      await runSyncSource(s.id)
      message.success(`已提交同步任务：${s.name}`)
      window.setTimeout(() => loadSync(true), 1200)
    } catch (e) {
      showError(e, '提交同步任务失败')
    } finally {
      setRunningSync(null)
    }
  }

  const onDeleteSync = async (s: KnowledgeSyncSource) => {
    try {
      await deleteSyncSource(s.id)
      message.success('同步源已删除（已入库文档保留）')
      loadSync()
    } catch (e) {
      showError(e, '删除同步源失败')
    }
  }

  const syncConfigText = (s: KnowledgeSyncSource) =>
    s.kind === 'GIT'
      ? `${cfgStr(s.config, 'repoPath') || '-'} · ${cfgStr(s.config, 'docsPath') || '.'} @ ${cfgStr(s.config, 'ref') || 'HEAD'}`
      : `集成 #${cfgStr(s.config, 'integrationId') || '-'} · ${cfgStr(s.config, 'target') || '-'}`

  const syncStats = (stats: SyncStats) => {
    const items: { label: string; n: number; color: string }[] = [
      { label: '新建', n: stats?.created ?? 0, color: 'green' },
      { label: '更新', n: stats?.updated ?? 0, color: 'blue' },
      { label: '未变', n: stats?.unchanged ?? 0, color: 'default' },
      { label: '停用', n: stats?.deprecated ?? 0, color: 'orange' },
      { label: '失败', n: stats?.failed ?? 0, color: 'red' },
    ]
    const shown = items.filter((x) => x.n > 0)
    return shown.length > 0 ? (
      <Space size={4} wrap>
        {shown.map((x) => (
          <Tag key={x.label} color={x.color}>
            {x.label} {x.n}
          </Tag>
        ))}
      </Space>
    ) : (
      <Typography.Text type="secondary">-</Typography.Text>
    )
  }

  const syncColumns: ColumnsType<KnowledgeSyncSource> = [
    {
      title: '同步源',
      key: 'name',
      width: 220,
      render: (_, r) => (
        <Space direction="vertical" size={2}>
          <Space size={6}>
            <Tag color={r.kind === 'GIT' ? 'geekblue' : 'cyan'}>{r.kind === 'GIT' ? 'Git' : '飞书'}</Tag>
            <span>{r.name}</span>
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {kbName.get(r.kbId) ?? `知识库 #${r.kbId}`}
            {!r.enabled && ' · 已停用'}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '配置',
      key: 'config',
      ellipsis: true,
      render: (_, r) => (
        <Tooltip title={JSON.stringify(r.config)}>
          <Typography.Text style={{ fontSize: 12 }} ellipsis>
            {syncConfigText(r)}
          </Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: '状态',
      key: 'status',
      width: 110,
      render: (_, r) => {
        const m = r.lastStatus ? SYNC_STATUS[r.lastStatus] : null
        return m ? <Tag color={m.color}>{m.text}</Tag> : <Tag>未运行</Tag>
      },
    },
    {
      title: '最近结果',
      key: 'stats',
      width: 210,
      render: (_, r) => syncStats(r.lastStats),
    },
    {
      title: '最近同步',
      key: 'lastSyncAt',
      width: 190,
      render: (_, r) => (
        <Space direction="vertical" size={0}>
          <Typography.Text style={{ fontSize: 12 }}>{r.lastSyncAt ? fmtTime(r.lastSyncAt) : '-'}</Typography.Text>
          {r.lastError && (
            <Tooltip title={r.lastError}>
              <Typography.Text type="danger" ellipsis style={{ fontSize: 12, maxWidth: 170 }}>
                {r.lastError}
              </Typography.Text>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 190,
      render: (_, r) => (
        <Space size={2}>
          <Tooltip title={r.enabled ? '立即同步一次' : '同步源已停用'}>
            <Button
              size="small"
              type="link"
              icon={<SyncOutlined />}
              disabled={!r.enabled || r.lastStatus === 'RUNNING'}
              loading={runningSync === r.id}
              onClick={() => onRunSync(r)}
            >
              运行
            </Button>
          </Tooltip>
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEditSync(r)}>
            编辑
          </Button>
          <Popconfirm
            title="删除该同步源？"
            description="已入库文档会保留，只是不再继续同步。"
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => onDeleteSync(r)}
          >
            <Button size="small" type="link" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ]

  // ---------------- 生命周期 ----------------
  const [lifecycle, setLifecycle] = useState<LifecycleReport | null>(null)
  const [lifecycleLoading, setLifecycleLoading] = useState(false)
  const [lifecycleGroup, setLifecycleGroup] = useState<LifecycleGroupKey>('expired')
  const [expiringDays, setExpiringDays] = useState(14)
  const [staleDays, setStaleDays] = useState(180)
  const [reviewingId, setReviewingId] = useState<number | null>(null)

  const loadLifecycle = useCallback(async () => {
    setLifecycleLoading(true)
    try {
      setLifecycle(await lifecycleReport(expiringDays, staleDays))
    } catch (e) {
      showError(e, '加载生命周期报告失败')
    } finally {
      setLifecycleLoading(false)
    }
  }, [expiringDays, staleDays])

  useEffect(() => {
    if (tab === 'lifecycle') loadLifecycle()
  }, [tab, loadLifecycle])

  const onReview = async (item: LifecycleItem) => {
    setReviewingId(item.id)
    try {
      await reviewEntry(item.id)
      message.success(`已登记复核：${item.name}`)
      loadLifecycle()
    } catch (e) {
      showError(e, '登记复核失败')
    } finally {
      setReviewingId(null)
    }
  }

  const lifecycleItems: LifecycleItem[] = lifecycle ? lifecycle[lifecycleGroup] : []
  const lifecycleCounts: Record<LifecycleGroupKey, number> = {
    expired: lifecycle?.expiredCount ?? 0,
    expiring: lifecycle?.expiringCount ?? 0,
    stale: lifecycle?.staleCount ?? 0,
    missingOwner: lifecycle?.missingOwnerCount ?? 0,
  }

  const lifecycleColumns: ColumnsType<LifecycleItem> = [
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
        <Button size="small" type="link" loading={reviewingId === r.id} onClick={() => onReview(r)}>
          去复核
        </Button>
      ),
    },
  ]

  // ---------------- 近重复文档 ----------------
  const [duplicates, setDuplicates] = useState<DuplicateReport | null>(null)
  const [duplicatesLoading, setDuplicatesLoading] = useState(false)
  const [dupThreshold, setDupThreshold] = useState(3)
  const [dupMaxEntries, setDupMaxEntries] = useState(2000)

  const loadDuplicates = useCallback(async () => {
    setDuplicatesLoading(true)
    try {
      setDuplicates(await duplicateReport(dupThreshold, dupMaxEntries))
    } catch (e) {
      showError(e, '加载重复文档报告失败')
    } finally {
      setDuplicatesLoading(false)
    }
  }, [dupThreshold, dupMaxEntries])

  useEffect(() => {
    if (tab === 'duplicates' && duplicates === null) loadDuplicates()
  }, [tab, duplicates, loadDuplicates])

  const duplicateColumns: ColumnsType<DuplicateItem> = [
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

  // ---------------- 自动增强 ----------------
  const [enrichConfig, setEnrichConfig] = useState<EnrichConfig | null>(null)
  const [enrichEntries, setEnrichEntries] = useState<EnrichEntry[]>([])
  const [enrichLoading, setEnrichLoading] = useState(false)
  const [enrichKbId, setEnrichKbId] = useState<number | undefined>(undefined)
  const [enrichLimit, setEnrichLimit] = useState(100)
  const [enrichingId, setEnrichingId] = useState<number | null>(null)
  const [batchEnriching, setBatchEnriching] = useState(false)

  const loadEnrich = useCallback(async () => {
    setEnrichLoading(true)
    try {
      setEnrichEntries(await listEnrichEntries(enrichKbId, enrichLimit))
    } catch (e) {
      showError(e, '加载增强状态失败')
    } finally {
      setEnrichLoading(false)
    }
  }, [enrichKbId, enrichLimit])

  useEffect(() => {
    if (tab !== 'enrich') return
    getEnrichConfig()
      .then(setEnrichConfig)
      .catch((e) => showError(e, '加载增强配置失败'))
    loadEnrich()
  }, [tab, loadEnrich])

  const onBatchEnrich = async () => {
    setBatchEnriching(true)
    try {
      const r = await runEnrich(enrichKbId, enrichLimit)
      message.success(`已提交自动增强任务（最多 ${r.limit} 条），稍后刷新查看结果`)
      window.setTimeout(() => loadEnrich(), 1500)
    } catch (e) {
      showError(e, '提交自动增强失败')
    } finally {
      setBatchEnriching(false)
    }
  }

  const onEnrichEntry = async (e: EnrichEntry) => {
    setEnrichingId(e.id)
    try {
      await runEntryEnrich(e.id)
      message.success(`已提交补全任务：${e.name}`)
      window.setTimeout(() => loadEnrich(), 1500)
    } catch (err) {
      showError(err, '提交补全任务失败')
    } finally {
      setEnrichingId(null)
    }
  }

  const enrichStatusTag = (e: EnrichEntry) => {
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

  const enrichColumns: ColumnsType<EnrichEntry> = [
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
    { title: '增强状态', key: 'status', width: 110, render: (_, r) => enrichStatusTag(r) },
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
        <Button size="small" type="link" loading={enrichingId === r.id} onClick={() => onEnrichEntry(r)}>
          补全
        </Button>
      ),
    },
  ]

  // ---------------- 检索评测 ----------------
  const [evalResult, setEvalResult] = useState<EvalReport | null>(null)
  const [evalLoading, setEvalLoading] = useState(false)
  const [evalTopK, setEvalTopK] = useState(10)
  const [evalKbId, setEvalKbId] = useState<number | undefined>(undefined)

  const loadEval = useCallback(async () => {
    setEvalLoading(true)
    try {
      setEvalResult(await fetchEvalReport(evalTopK, evalKbId))
    } catch (e) {
      showError(e, '运行检索评测失败')
    } finally {
      setEvalLoading(false)
    }
  }, [evalTopK, evalKbId])

  useEffect(() => {
    if (tab === 'eval' && evalResult === null) loadEval()
  }, [tab, evalResult, loadEval])

  const evalColumns: ColumnsType<EvalCaseResult> = [
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

  const evalPane = evalResult && (
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
        size="small"
        loading={evalLoading}
        columns={evalColumns}
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
  )

  // ---------------- 反馈治理 ----------------
  const [feedback, setFeedback] = useState<NegativeFeedbackReport | null>(null)
  const [feedbackLoading, setFeedbackLoading] = useState(false)
  const [feedbackDays, setFeedbackDays] = useState(30)

  const loadFeedback = useCallback(async () => {
    setFeedbackLoading(true)
    try {
      setFeedback(await negativeFeedbackTop(feedbackDays, 20))
    } catch (e) {
      showError(e, '加载反馈统计失败')
    } finally {
      setFeedbackLoading(false)
    }
  }, [feedbackDays])

  useEffect(() => {
    if (tab === 'feedback') loadFeedback()
  }, [tab, loadFeedback])

  const feedbackColumns: ColumnsType<NegativeFeedbackDoc> = [
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

  // ---------------- 渲染 ----------------
  const paneStyle = { height: '100%', overflow: 'auto', paddingRight: 4 } as const

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <style>{`.gov-tabs-fill{flex:1;min-height:0;display:flex;flex-direction:column}
.gov-tabs-fill .ant-tabs-content-holder{flex:1;min-height:0}
.gov-tabs-fill .ant-tabs-content,.gov-tabs-fill .ant-tabs-tabpane{height:100%}`}</style>
      <Tabs
        className="gov-tabs-fill"
        activeKey={tab}
        onChange={(k) => setTab(k as GovernanceTab)}
        items={[
          {
            key: 'sync',
            label: '同步源',
            children: (
              <div style={paneStyle}>
                <Space style={{ marginBottom: 12 }}>
                  <Button icon={<ReloadOutlined />} onClick={() => loadSync()}>
                    刷新
                  </Button>
                  <Button type="primary" icon={<PlusOutlined />} onClick={openCreateSync}>
                    新建同步源
                  </Button>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    Git 只读服务器本地副本，飞书同步沿用集成凭证；删除只停同步，已入库文档保留。
                  </Typography.Text>
                </Space>
                <Table<KnowledgeSyncSource>
                  rowKey="id"
                  size="small"
                  loading={sourcesLoading}
                  columns={syncColumns}
                  dataSource={sources}
                  pagination={false}
                  locale={{ emptyText: '暂无同步源。新建后手动运行，或等待后续接入定时同步。' }}
                />
              </div>
            ),
          },
          {
            key: 'lifecycle',
            label: '生命周期',
            children: (
              <div style={paneStyle}>
                <Space style={{ marginBottom: 12 }} wrap>
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
                  <Button icon={<ReloadOutlined />} loading={lifecycleLoading} onClick={loadLifecycle}>
                    刷新报告
                  </Button>
                  {lifecycle && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      生成于 {fmtTime(lifecycle.generatedAt)} · 每组最多展示 {lifecycle.limit} 条
                    </Typography.Text>
                  )}
                </Space>
                <Segmented
                  value={lifecycleGroup}
                  onChange={(v) => setLifecycleGroup(v as LifecycleGroupKey)}
                  options={(Object.keys(LIFECYCLE_LABEL) as LifecycleGroupKey[]).map((k) => ({
                    value: k,
                    label: `${LIFECYCLE_LABEL[k]}（${lifecycleCounts[k]}）`,
                  }))}
                  style={{ marginBottom: 12 }}
                />
                {lifecycle && (
                  <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 0 }}>
                    四组互相独立：同一篇文档可能同时出现在「已过期」与「缺失负责人」。复核后记录复核时间，长期未复核以复核时间（未复核则取更新时间）计算。
                  </Typography.Paragraph>
                )}
                <Table<LifecycleItem>
                  rowKey="id"
                  size="small"
                  loading={lifecycleLoading}
                  columns={lifecycleColumns}
                  dataSource={lifecycleItems}
                  pagination={LIST_PAGINATION}
                  locale={{ emptyText: '该分组没有需要处理的文档。' }}
                />
              </div>
            ),
          },
          {
            key: 'duplicates',
            label: '重复文档',
            children: (
              <div style={paneStyle}>
                <Space style={{ marginBottom: 12 }} wrap>
                  <Typography.Text type="secondary">汉明距离阈值</Typography.Text>
                  <InputNumber
                    min={0}
                    max={16}
                    value={dupThreshold}
                    onChange={(v) => setDupThreshold(v ?? 3)}
                    style={{ width: 80 }}
                  />
                  <Typography.Text type="secondary">最多扫描</Typography.Text>
                  <InputNumber
                    min={100}
                    max={5000}
                    step={100}
                    value={dupMaxEntries}
                    onChange={(v) => setDupMaxEntries(v ?? 2000)}
                    style={{ width: 100 }}
                  />
                  <Button icon={<ExperimentOutlined />} loading={duplicatesLoading} onClick={loadDuplicates}>
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
                {duplicatesLoading && !duplicates ? (
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
                          size="small"
                          columns={duplicateColumns}
                          dataSource={g.entries}
                          pagination={false}
                        />
                      </Card>
                    ))}
                  </Space>
                ) : (
                  <Empty description="未发现近重复文档（阈值为汉明距离，越小越严格）。" />
                )}
              </div>
            ),
          },
          {
            key: 'enrich',
            label: '自动增强',
            children: (
              <div style={paneStyle}>
                <Card size="small" style={{ marginBottom: 12 }}>
                  <Descriptions
                    size="small"
                    column={4}
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
                </Card>
                <Space style={{ marginBottom: 12 }} wrap>
                  <Select
                    allowClear
                    placeholder="全部知识库"
                    style={{ minWidth: 200 }}
                    value={enrichKbId}
                    onChange={setEnrichKbId}
                    options={bases.map((b) => ({ value: b.id, label: b.name }))}
                  />
                  <InputNumber
                    min={1}
                    max={200}
                    value={enrichLimit}
                    onChange={(v) => setEnrichLimit(v ?? 100)}
                    addonAfter="条"
                    style={{ width: 130 }}
                  />
                  <Button type="primary" icon={<ThunderboltOutlined />} loading={batchEnriching} onClick={onBatchEnrich}>
                    立即增强
                  </Button>
                  <Button icon={<ReloadOutlined />} loading={enrichLoading} onClick={loadEnrich}>
                    刷新
                  </Button>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    增强为异步任务；人工修正过的条目默认不覆盖，点「补全」可恢复自动模式。
                  </Typography.Text>
                </Space>
                <Table<EnrichEntry>
                  rowKey="id"
                  size="small"
                  loading={enrichLoading}
                  columns={enrichColumns}
                  dataSource={enrichEntries}
                  pagination={LIST_PAGINATION}
                  locale={{ emptyText: '暂无条目。' }}
                />
              </div>
            ),
          },
          {
            key: 'eval',
            label: '检索评测',
            children: (
              <div style={paneStyle}>
                <Space style={{ marginBottom: 12 }} wrap>
                  <Typography.Text type="secondary">Top-K</Typography.Text>
                  <Select
                    value={evalTopK}
                    onChange={setEvalTopK}
                    style={{ width: 90 }}
                    options={[5, 10, 20, 30].map((v) => ({ value: v, label: String(v) }))}
                  />
                  <Select
                    allowClear
                    placeholder="全部知识库"
                    style={{ minWidth: 200 }}
                    value={evalKbId}
                    onChange={setEvalKbId}
                    options={bases.map((b) => ({ value: b.id, label: b.name }))}
                  />
                  <Button icon={<ExperimentOutlined />} loading={evalLoading} onClick={loadEval}>
                    运行评测
                  </Button>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    指标来自 tests/knowledge_eval/dataset.json：Recall/MRR/引用精确率只统计可回答题，拒答率只统计不可回答题。
                  </Typography.Text>
                </Space>
                {evalPane ?? <Empty description="点击「运行评测」生成报告。" />}
              </div>
            ),
          },
          {
            key: 'feedback',
            label: '反馈治理',
            children: (
              <div style={paneStyle}>
                <Space style={{ marginBottom: 12 }} wrap>
                  <Select
                    value={feedbackDays}
                    onChange={setFeedbackDays}
                    style={{ width: 110 }}
                    options={[
                      { value: 7, label: '近 7 天' },
                      { value: 30, label: '近 30 天' },
                      { value: 90, label: '近 90 天' },
                    ]}
                  />
                  <Button icon={<ReloadOutlined />} loading={feedbackLoading} onClick={loadFeedback}>
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
                <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
                  聚合知识会话里「无用」反馈命中的文档，按负面数排序；到文档详情查看分块并补充/重写内容后重建索引。
                </Typography.Paragraph>
                <FitTable<NegativeFeedbackDoc>
                  rowKey={(r) => String(r.entryId)}
                  loading={feedbackLoading}
                  columns={feedbackColumns}
                  dataSource={feedback?.docs ?? []}
                  pagination={LIST_PAGINATION}
                  locale={{ emptyText: '区间内没有负面反馈。' }}
                />
              </div>
            ),
          },
        ]}
      />

      <Modal
        open={syncModalOpen}
        title={editingSync ? `编辑同步源 · ${editingSync.name}` : '新建同步源'}
        okText="保存"
        cancelText="取消"
        confirmLoading={syncSaving}
        width={640}
        destroyOnHidden
        onOk={onSaveSync}
        onCancel={() => setSyncModalOpen(false)}
      >
        <Form form={syncForm} layout="vertical">
          <Form.Item name="kbId" label="目标知识库" rules={[{ required: true, message: '请选择知识库' }]}>
            <Select
              placeholder="选择入库的知识库"
              options={bases.map((b) => ({ value: b.id, label: b.name }))}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item name="kind" label="同步类型" rules={[{ required: true }]}>
            <Segmented
              options={[
                { value: 'GIT', label: 'Git 本地副本' },
                { value: 'FEISHU', label: '飞书空间/目录' },
              ]}
            />
          </Form.Item>
          <Form.Item name="name" label="名称">
            <Input placeholder={syncKind === 'GIT' ? '如：平台文档仓库' : '如：运维 wiki'} />
          </Form.Item>
          {syncKind === 'GIT' ? (
            <>
              <Form.Item
                name="repoPath"
                label="仓库绝对路径（服务器本地）"
                rules={[{ required: true, message: '请填写仓库绝对路径' }]}
              >
                <Input placeholder="/data/repos/platform-docs" />
              </Form.Item>
              <Space size={12} style={{ display: 'flex' }}>
                <Form.Item name="ref" label="分支/标签/Commit" style={{ flex: 1 }}>
                  <Input placeholder="HEAD" />
                </Form.Item>
                <Form.Item name="docsPath" label="文档子目录" style={{ flex: 1 }}>
                  <Input placeholder="docs" />
                </Form.Item>
              </Space>
            </>
          ) : (
            <>
              <Form.Item
                name="integrationId"
                label="飞书集成 ID"
                rules={[{ required: true, message: '请填写飞书集成 ID' }]}
              >
                <InputNumber min={1} style={{ width: '100%' }} placeholder="集成配置中的 ID" />
              </Form.Item>
              <Form.Item
                name="target"
                label="同步目标"
                rules={[{ required: true, message: '请填写 wiki 空间或云盘文件夹标识' }]}
              >
                <Input placeholder="wiki 空间 ID / 云盘文件夹 token" />
              </Form.Item>
            </>
          )}
          <Form.Item name="docType" label="默认文档类型（识别不出时使用）">
            <Select allowClear placeholder="按目录规则自动识别" options={DOC_TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="tags" label="附加标签">
            <Select mode="tags" open={false} placeholder="回车添加标签" />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
