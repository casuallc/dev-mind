// CAP-86 v3 知识运营中心：跨库待办队列 + 召回验证。
// 与「知识库」（内容管理）分工：这里回答「现在该处理什么」，所有动作行级发起并二次确认。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router-dom'
import {
  Button,
  Card,
  Empty,
  Modal,
  Segmented,
  Space,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ReloadOutlined } from '@ant-design/icons'
import {
  lifecycleReport,
  listBases,
  listEnrichEntries,
  negativeFeedbackTop,
  reviewEntry,
  runEntryEnrich,
} from '../api'
import { listProjects } from '../../projects/api'
import type {
  EnrichEntry,
  KnowledgeBase,
  KnowledgeSearchV2Hit,
  LifecycleReport,
  NegativeFeedbackReport,
} from '../types'
import KnowledgeSearchView from '../components/KnowledgeSearchView'
import KnowledgeEntryDrawer from '../components/KnowledgeEntryDrawer'
import type { Project } from '../../projects/types'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { showError } from '../../../shared/utils/showError'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'

type OpsTab = 'tasks' | 'search'
type TodoKind = 'review' | 'enrich' | 'feedback'
type TodoFilter = 'all' | TodoKind

interface TodoRow {
  key: string
  kind: TodoKind
  entryId: number
  name: string
  kbId: number | null
  detail: string
  time: string | null
}

const KIND_META: Record<TodoKind, { color: string; text: string; action: string }> = {
  review: { color: 'gold', text: '待复核', action: '登记复核' },
  enrich: { color: 'red', text: '增强失败', action: '重试增强' },
  feedback: { color: 'volcano', text: '负面反馈', action: '查看文档' },
}

const TODO_FILTER_OPTIONS: { value: TodoFilter; label: string }[] = [
  { value: 'all', label: '全部' },
  { value: 'review', label: '待复核' },
  { value: 'enrich', label: '增强失败' },
  { value: 'feedback', label: '负面反馈' },
]

export default function KnowledgeOpsPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const tabParam = searchParams.get('tab')
  const tab: OpsTab = tabParam === 'search' ? 'search' : 'tasks'
  const kbIdRaw = Number(searchParams.get('kbId'))
  const kbId = Number.isFinite(kbIdRaw) && kbIdRaw > 0 ? kbIdRaw : undefined
  const viewParam = searchParams.get('view')
  const reportsView = ['lifecycle', 'duplicates', 'feedback', 'eval', 'enrich'].includes(viewParam ?? '')
    ? viewParam
    : null

  const update = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(searchParams)
    for (const [key, value] of Object.entries(patch)) {
      if (value === undefined) next.delete(key)
      else next.set(key, value)
    }
    setSearchParams(next, { replace: true })
  }

  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [lifecycle, setLifecycle] = useState<LifecycleReport | null>(null)
  const [enrichEntries, setEnrichEntries] = useState<EnrichEntry[]>([])
  const [feedback, setFeedback] = useState<NegativeFeedbackReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [todoFilter, setTodoFilter] = useState<TodoFilter>('all')
  const [entryDetail, setEntryDetail] = useState<{
    entryId: number
    hit?: KnowledgeSearchV2Hit
    focusChunkId?: number
  } | null>(null)

  useEffect(() => {
    listBases()
      .then(setBases)
      .catch((e) => showError(e, '加载知识库失败'))
    listProjects()
      .then(setProjects)
      .catch(() => undefined)
  }, [])

  const kbName = useMemo(() => {
    const map = new Map<number, string>()
    for (const b of bases) map.set(b.id, b.name)
    return map
  }, [bases])

  const loadTodos = useCallback(async () => {
    setLoading(true)
    const [lc, en, fb] = await Promise.allSettled([
      lifecycleReport(14, 180),
      listEnrichEntries(kbId, 200),
      negativeFeedbackTop(30, 20),
    ])
    if (lc.status === 'fulfilled') setLifecycle(lc.value)
    if (en.status === 'fulfilled') setEnrichEntries(en.value)
    if (fb.status === 'fulfilled') setFeedback(fb.value)
    const failed = [lc, en, fb].find((r) => r.status === 'rejected')
    if (failed) showError((failed as PromiseRejectedResult).reason, '部分待办数据加载失败')
    setLoading(false)
  }, [kbId])

  useEffect(() => {
    loadTodos()
  }, [loadTodos])

  // 待办队列只收「动作能闭环」的项：已过期/临期/缺负责人无补录入口，留在报表里提示
  const reviewRows = useMemo<TodoRow[]>(() => {
    if (!lifecycle) return []
    const map = new Map<number, TodoRow>()
    for (const item of lifecycle.stale) {
      if (kbId && item.kbId !== kbId) continue
      map.set(item.id, {
        key: `review-${item.id}`,
        kind: 'review',
        entryId: item.id,
        name: item.name,
        kbId: item.kbId,
        detail: '长期未复核',
        time: item.lastReviewedAt ?? item.updatedAt,
      })
    }
    return [...map.values()]
  }, [lifecycle, kbId])

  const enrichRows = useMemo<TodoRow[]>(
    () =>
      enrichEntries
        .filter((e) => e.enrichError && (!kbId || e.kbId === kbId))
        .map((e) => ({
          key: `enrich-${e.id}`,
          kind: 'enrich' as const,
          entryId: e.id,
          name: e.name,
          kbId: e.kbId,
          detail: e.enrichError ?? '',
          time: e.updatedAt,
        })),
    [enrichEntries, kbId],
  )

  const feedbackRows = useMemo<TodoRow[]>(
    () =>
      (feedback?.docs ?? [])
        .filter((d) => !kbId || d.kbId === kbId)
        .map((d) => ({
          key: `feedback-${d.entryId}`,
          kind: 'feedback' as const,
          entryId: d.entryId,
          name: d.entryName ?? `文档 #${d.entryId}`,
          kbId: d.kbId,
          detail: `${d.negativeCount}/${d.totalCount} 条负面 · ${
            d.reasons.map((r) => `${r.reason}×${r.count}`).join('、') || '未填写原因'
          }`,
          time: d.lastNegativeAt,
        })),
    [feedback, kbId],
  )

  const queue = useMemo(
    () =>
      [...reviewRows, ...enrichRows, ...feedbackRows].filter(
        (r) => todoFilter === 'all' || r.kind === todoFilter,
      ),
    [reviewRows, enrichRows, feedbackRows, todoFilter],
  )

  const onReview = (row: TodoRow) => {
    Modal.confirm({
      centered: true,
      title: `登记「${row.name}」的复核？`,
      content: '将把最近复核时间更新为当前时间，并移出长期未复核分组。',
      okText: '登记复核',
      cancelText: '取消',
      onOk: async () => {
        try {
          await reviewEntry(row.entryId)
          message.success(`已登记复核：${row.name}`)
          loadTodos()
        } catch (e) {
          showError(e, '登记复核失败')
        }
      },
    })
  }

  const onEnrich = (row: TodoRow) => {
    Modal.confirm({
      centered: true,
      title: `补全「${row.name}」的元数据？`,
      content: '会重新生成分类、摘要与标签，并重建索引；人工锁定条目会被覆盖。',
      okText: '补全',
      cancelText: '取消',
      onOk: async () => {
        try {
          await runEntryEnrich(row.entryId)
          message.success(`已提交补全任务：${row.name}`)
          window.setTimeout(loadTodos, 1500)
        } catch (e) {
          showError(e, '提交补全任务失败')
        }
      },
    })
  }

  const columns: ColumnsType<TodoRow> = [
    {
      title: '类型',
      dataIndex: 'kind',
      width: 110,
      render: (kind: TodoKind) => <Tag color={KIND_META[kind].color}>{KIND_META[kind].text}</Tag>,
    },
    {
      title: '对象',
      key: 'name',
      ellipsis: true,
      render: (_, r) => <Typography.Link onClick={() => setEntryDetail({ entryId: r.entryId })}>{r.name}</Typography.Link>,
    },
    {
      title: '知识库',
      key: 'kb',
      width: 150,
      ellipsis: true,
      render: (_, r) =>
        r.kbId ? <Tag color="purple">{kbName.get(r.kbId) ?? `#${r.kbId}`}</Tag> : <Typography.Text type="secondary">-</Typography.Text>,
    },
    {
      title: '待办说明',
      dataIndex: 'detail',
      ellipsis: true,
      render: (v: string) => (
        <Tooltip title={v}>
          <Typography.Text type={v ? undefined : 'secondary'}>{v || '-'}</Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: '时间',
      dataIndex: 'time',
      width: 165,
      render: (v: string | null) => (v ? fmtTime(v) : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 110,
      render: (_, r) => (
        <Button
          size="small"
          onClick={() =>
            r.kind === 'review' ? onReview(r) : r.kind === 'enrich' ? onEnrich(r) : setEntryDetail({ entryId: r.entryId })
          }
        >
          {KIND_META[r.kind].action}
        </Button>
      ),
    },
  ]

  const tabHint =
    tab === 'tasks'
      ? '跨库待办收件箱：待复核、增强失败与负反馈集中处理；数据实时聚合，处理后刷新即可看到最新队列。'
      : '验证「这个问题能不能问出来」：跨库/单库检索调试，看命中文档与高亮片段。日常问知识请用 AI 问答。'

  // 旧入口兼容：/admin/knowledge/ops?tab=reports&view=xxx → 报表独立页
  if (tabParam === 'reports') {
    const next = new URLSearchParams()
    if (reportsView) next.set('view', reportsView)
    return (
      <Navigate
        to={{ pathname: '/admin/knowledge/reports', search: next.size ? `?${next}` : '' }}
        replace
      />
    )
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>知识运营</span>
          <Segmented
            value={tab}
            onChange={(v) => update({ tab: v as string })}
            options={[
              { value: 'tasks', label: '待办' },
              { value: 'search', label: '召回验证' },
            ]}
          />
        </Space>
      }
      extra={
        <Space wrap>
          {tab === 'tasks' && kbId && (
            <Tag closable onClose={() => update({ kbId: undefined })} color="blue">
              仅看：{kbName.get(kbId) ?? `#${kbId}`}
            </Tag>
          )}
          {tab === 'tasks' && (
            <Button icon={<ReloadOutlined />} loading={loading} onClick={loadTodos}>
              刷新
            </Button>
          )}
          <Link to="/admin/knowledge">
            <Button>知识库</Button>
          </Link>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        {tabHint}
      </Typography.Paragraph>

      {tab === 'tasks' && (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <Space style={{ marginBottom: 10, flexShrink: 0 }} wrap>
            <Segmented
              value={todoFilter}
              onChange={(v) => setTodoFilter(v as TodoFilter)}
              options={TODO_FILTER_OPTIONS.map((o) => ({
                ...o,
                label:
                  o.value === 'all'
                    ? `全部（${reviewRows.length + enrichRows.length + feedbackRows.length}）`
                    : `${o.label}（${
                        o.value === 'review'
                          ? reviewRows.length
                          : o.value === 'enrich'
                            ? enrichRows.length
                            : feedbackRows.length
                      }）`,
              }))}
            />
          </Space>

          {!loading && queue.length === 0 && todoFilter === 'all' ? (
            <Empty description="当前没有待办，知识库处于健康状态。" style={{ marginTop: 48 }} />
          ) : (
            <FitTable<TodoRow>
              rowKey="key"
              loading={loading}
              columns={columns}
              dataSource={queue}
              pagination={LIST_PAGINATION}
              locale={{ emptyText: '该分类下没有待办。' }}
            />
          )}
        </div>
      )}

      {tab === 'search' && (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <KnowledgeSearchView
            bases={bases}
            projects={projects}
            presetKbId={kbId}
            onOpenEntry={(entryId, hit, focusChunkId) => setEntryDetail({ entryId, hit, focusChunkId })}
          />
        </div>
      )}

      <KnowledgeEntryDrawer
        entryId={entryDetail?.entryId}
        hit={entryDetail?.hit}
        focusChunkId={entryDetail?.focusChunkId}
        kbNames={kbName}
        onClose={() => setEntryDetail(null)}
      />
    </Card>
  )
}
