// CAP-86 v3 库详情页 /admin/knowledge/bases/:id：文档 / 手动上传 / 飞书同步 三页签（库设置在列表页「设置」抽屉）。
// 手动上传 = 本库上传批次；飞书同步 = 同步源（Git / 飞书增量）与飞书文档手动导入。
// 顶部状态条异常时给出下一步动作，正常态只留「添加内容」。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, Navigate, useParams, useSearchParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Dropdown,
  Input,
  message,
  Modal,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import {
  CloudDownloadOutlined,
  EditOutlined,
  MessageOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import type { MenuProps } from 'antd'
import {
  deleteEntry,
  getBase,
  importFeishuDocs,
  listBases,
  listFeishuIntegrations,
  pageBaseEntries,
  reindexBase,
  reindexEntry,
  resyncEntry,
} from '../api'
import { listModelEndpoints } from '../../model/api'
import type { ModelEndpoint } from '../../model/types'
import type {
  DocType,
  FeishuImportResult,
  FeishuIntegration,
  KnowledgeBase,
  KnowledgeEntrySummary,
  KnowledgePage,
} from '../types'
import { DOC_TYPE_LABEL, DOC_TYPE_OPTIONS } from '../types'
import KnowledgeImportsView from '../components/KnowledgeImportsView'
import KnowledgeEntryEditorDrawer from './KnowledgeEntryEditorDrawer'
import KnowledgeEntryDrawer from '../components/KnowledgeEntryDrawer'
import KnowledgeUploadDrawer from '../components/KnowledgeUploadDrawer'
import SyncSourcesPanel from '../components/SyncSourcesPanel'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'

type BaseTab = 'entries' | 'upload' | 'feishu'

const ENTRY_PAGE_SIZE = 20

const SOURCE_OPTIONS = [
  { value: 'manual', label: '手动' },
  { value: 'file', label: '文件' },
  { value: 'feishu', label: '飞书' },
  { value: 'git', label: 'Git' },
  { value: 'api', label: 'API' },
]

const INDEX_OPTIONS = [
  { value: 'ready', label: '已索引' },
  { value: 'pending', label: '待索引' },
  { value: 'failed', label: '索引失败' },
  { value: 'disabled', label: '未配置向量端点' },
]

function sourceTag(source: string) {
  if (source === 'feishu') return <Tag color="cyan">飞书</Tag>
  if (source === 'file') return <Tag color="blue">文件</Tag>
  if (source === 'git') return <Tag color="geekblue">Git</Tag>
  if (source === 'api') return <Tag color="purple">API</Tag>
  return <Tag>手动</Tag>
}

function indexStatusTag(entry: KnowledgeEntrySummary, onRetry: (entry: KnowledgeEntrySummary) => void) {
  const status = entry.indexStatus
  if (status === 'ready') return <Tag color="green">已索引</Tag>
  if (status === 'pending') return <Tag color="gold">待索引</Tag>
  if (status === 'disabled') {
    return (
      <Tooltip title="未配置向量端点：索引停用、检索走关键词降级。到「模型接入」配置端点，或回到库列表点「设置」为本库指定">
        <Tag>未配置向量端点</Tag>
      </Tooltip>
    )
  }
  return (
    <Space size={4}>
      <Tooltip title={entry.indexError ?? '索引失败'}>
        <Tag color="red">索引失败</Tag>
      </Tooltip>
      <Button
        size="small"
        onClick={(e) => {
          e.stopPropagation()
          onRetry(entry)
        }}
      >
        重试
      </Button>
    </Space>
  )
}

export default function KnowledgeBaseDetailPage() {
  const { id } = useParams<{ id: string }>()
  const baseId = Number(id)
  const [searchParams, setSearchParams] = useSearchParams()

  const [base, setBase] = useState<KnowledgeBase | null>(null)
  const [baseLoading, setBaseLoading] = useState(false)
  const [bases, setBases] = useState<KnowledgeBase[]>([])

  // 文档页：服务端分页 + 过滤
  const [entriesPage, setEntriesPage] = useState<KnowledgePage<KnowledgeEntrySummary> | null>(null)
  const [entriesLoading, setEntriesLoading] = useState(false)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(ENTRY_PAGE_SIZE)
  const [keyword, setKeyword] = useState('')
  const [docType, setDocType] = useState<string | undefined>(undefined)
  const [source, setSource] = useState<string | undefined>(undefined)
  // 外部深链（库列表「失败 N」）可直接带索引状态过滤
  const [indexStatus, setIndexStatus] = useState<string | undefined>(() => {
    const raw = searchParams.get('indexStatus')
    return INDEX_OPTIONS.some((o) => o.value === raw) ? (raw as string) : undefined
  })

  // 条目编辑 / 详情 / 重同步
  const [editorOpen, setEditorOpen] = useState(false)
  const [editingEntryId, setEditingEntryId] = useState<number | null>(null)
  const [detailEntryId, setDetailEntryId] = useState<number | undefined>(undefined)
  const [reindexing, setReindexing] = useState(false)
  const [resyncingId, setResyncingId] = useState<number | null>(null)

  // 向量端点
  const [endpoints, setEndpoints] = useState<ModelEndpoint[]>([])

  // 摄入页
  const [importsRefresh, setImportsRefresh] = useState(0)
  const [uploadOpen, setUploadOpen] = useState(false)

  // 同步页 · 飞书手动导入
  const [feishuIntegrations, setFeishuIntegrations] = useState<FeishuIntegration[] | null>(null)
  const [feishuIntegrationId, setFeishuIntegrationId] = useState<number | null>(null)
  const [feishuUrls, setFeishuUrls] = useState('')
  const [feishuImporting, setFeishuImporting] = useState(false)
  const [feishuResults, setFeishuResults] = useState<FeishuImportResult[] | null>(null)

  const tabParam = searchParams.get('tab')
  // 旧深链兼容：?tab=sync → 飞书同步；?tab=sources|import → 手动上传
  const tab: BaseTab =
    tabParam === 'feishu' || tabParam === 'sync'
      ? 'feishu'
      : tabParam === 'upload' || tabParam === 'sources' || tabParam === 'import'
        ? 'upload'
        : 'entries'

  const setTab = (next: BaseTab) => {
    const params = new URLSearchParams(searchParams)
    if (next === 'entries') params.delete('tab')
    else params.set('tab', next)
    setSearchParams(params, { replace: true })
  }

  const loadBase = useCallback(async () => {
    if (!Number.isFinite(baseId) || baseId <= 0) return
    setBaseLoading(true)
    try {
      setBase(await getBase(baseId))
    } catch (e) {
      showError(e, '加载知识库失败')
    } finally {
      setBaseLoading(false)
    }
  }, [baseId])

  const loadEntries = useCallback(async () => {
    if (!Number.isFinite(baseId) || baseId <= 0) return
    setEntriesLoading(true)
    try {
      setEntriesPage(await pageBaseEntries(baseId, { page, size, keyword, docType, source, indexStatus }))
    } catch (e) {
      showError(e, '加载文档失败')
    } finally {
      setEntriesLoading(false)
    }
  }, [baseId, page, size, keyword, docType, source, indexStatus])

  useEffect(() => {
    loadBase()
  }, [loadBase])

  useEffect(() => {
    loadEntries()
  }, [loadEntries])

  useEffect(() => {
    listModelEndpoints()
      .then(setEndpoints)
      .catch(() => undefined)
  }, [])

  // 同步源表单需要全库列表（SyncSourcesPanel 的库名映射）
  useEffect(() => {
    if (tab !== 'feishu') return
    listBases()
      .then(setBases)
      .catch(() => undefined)
    if (feishuIntegrations === null) {
      listFeishuIntegrations()
        .then((list) => {
          setFeishuIntegrations(list)
          if (list.length === 1) setFeishuIntegrationId(list[0].id)
        })
        .catch((e) => showError(e, '加载飞书集成失败'))
    }
  }, [tab, feishuIntegrations])

  const embeddingEndpoints = useMemo(
    () => endpoints.filter((e) => e.kind === 'EMBEDDING'),
    [endpoints],
  )
  const resolvedEndpoint =
    embeddingEndpoints.find((e) => e.id === base?.modelEndpointId) ??
    embeddingEndpoints.find((e) => e.isDefault && e.status === 'active') ??
    null

  const refreshAll = () => {
    loadBase()
    loadEntries()
  }

  const openEditEntry = (entryId: number | null) => {
    setEditingEntryId(entryId)
    setEditorOpen(true)
  }

  const onEntrySaved = () => {
    loadEntries()
    loadBase()
  }

  const onDeleteEntry = (entry: KnowledgeEntrySummary) => {
    Modal.confirm({
      centered: true,
      title: `删除文档「${entry.name}」？`,
      content: '删除后连同索引一并移除，无法恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteEntry(entry.id)
          message.success('已删除')
          setPage(0)
          loadEntries()
          loadBase()
        } catch (err) {
          showError(err, '删除失败')
        }
      },
    })
  }

  const onReindexEntry = (entry: KnowledgeEntrySummary) => {
    Modal.confirm({
      centered: true,
      title: `重建「${entry.name}」的索引？`,
      content: '将重新分块并向量化（异步排队）；重建期间该文档可能暂时检索不到。',
      okText: '重建',
      cancelText: '取消',
      onOk: async () => {
        try {
          await reindexEntry(entry.id)
          message.success('已触发重建索引')
          window.setTimeout(() => {
            loadEntries()
            loadBase()
          }, 1500)
        } catch (err) {
          showError(err, '重建索引失败')
        }
      },
    })
  }

  const onResync = (entry: KnowledgeEntrySummary) => {
    Modal.confirm({
      centered: true,
      title: `重同步「${entry.name}」的飞书内容？`,
      content: '将从飞书拉取最新内容；有变更时更新正文并自动重建索引，无变更不处理。',
      okText: '重同步',
      cancelText: '取消',
      onOk: async () => {
        setResyncingId(entry.id)
        try {
          const result = await resyncEntry(entry.id)
          if (result.status === 'updated') {
            message.success(`「${entry.name}」已同步最新内容（自动重建索引）`)
            window.setTimeout(() => {
              loadEntries()
              loadBase()
            }, 1500)
          } else if (result.status === 'unchanged') {
            message.info(`「${entry.name}」内容无变更`)
          } else {
            message.warning(`重同步失败：${result.error ?? '未知原因'}（保留旧内容）`)
          }
        } catch (err) {
          showError(err, '重同步失败')
        } finally {
          setResyncingId(null)
        }
      },
    })
  }

  const onReindexBase = (onlyMismatched: boolean) => {
    Modal.confirm({
      centered: true,
      title: onlyMismatched
        ? `重建 ${base?.indexStats.mismatched ?? 0} 条端点不一致的索引？`
        : '重建全库索引？',
      content: onlyMismatched
        ? '只重建与当前向量端点不一致的条目（异步排队），其余文档不受影响。'
        : `库内 ${base?.entryCount ?? 0} 条文档会全部重新分块并向量化（异步排队），换端点/换分块参数后需要执行。`,
      okText: '重建',
      cancelText: '取消',
      onOk: async () => {
        setReindexing(true)
        try {
          const result = await reindexBase(baseId, onlyMismatched)
          if (result.queued === 0) {
            message.info(
              onlyMismatched
                ? '没有需要重建的条目（端点未探测过维度时无法判定）'
                : '库里没有可重建的条目',
            )
          } else {
            message.success(`已入队重建 ${result.queued} 条，稍后自动刷新`)
          }
          window.setTimeout(() => {
            loadBase()
            loadEntries()
          }, 2000)
        } catch (e) {
          showError(e, '重建索引失败')
        } finally {
          setReindexing(false)
        }
      },
    })
  }

  const onImportFeishu = () => {
    const urls = feishuUrls
      .split('\n')
      .map((u) => u.trim())
      .filter(Boolean)
    if (!feishuIntegrationId) {
      message.warning('请先选择飞书集成')
      return
    }
    if (urls.length === 0) {
      message.warning('请粘贴至少一条飞书文档 URL（每行一条）')
      return
    }
    const integrationId = feishuIntegrationId
    Modal.confirm({
      centered: true,
      title: `导入 ${urls.length} 条飞书文档？`,
      content: '同一文档重复导入按内容哈希判重：内容有变更时会更新已有条目并自动重建索引。',
      okText: '导入',
      cancelText: '取消',
      onOk: async () => {
        setFeishuImporting(true)
        setFeishuResults(null)
        try {
          const results = await importFeishuDocs(baseId, integrationId, urls)
          setFeishuResults(results)
          const created = results.filter((r) => r.status === 'created').length
          const updated = results.filter((r) => r.status === 'updated').length
          const failed = results.filter((r) => r.status === 'failed').length
          if (failed === 0) {
            message.success(`导入完成：新建 ${created}、更新 ${updated}、未变更 ${results.length - created - updated}`)
          } else {
            message.warning(`导入完成：新建 ${created}、更新 ${updated}、失败 ${failed}（详见列表）`)
          }
          loadEntries()
          loadBase()
        } catch (e) {
          showError(e, '飞书导入失败')
        } finally {
          setFeishuImporting(false)
        }
      },
    })
  }

  const entryColumns: ColumnsType<KnowledgeEntrySummary> = [
    {
      title: '文档',
      key: 'name',
      width: 300,
      render: (_, r) => (
        <Space direction="vertical" size={2} style={{ width: '100%' }}>
          <Typography.Link onClick={() => setDetailEntryId(r.id)}>{r.name}</Typography.Link>
          <Typography.Text type="secondary" ellipsis style={{ fontSize: 12 }} title={r.sourceUri ?? ''}>
            {r.sourceUri ?? '-'}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'docType',
      width: 104,
      render: (t: DocType | null) => (t ? <Tag color="blue">{DOC_TYPE_LABEL[t] ?? t}</Tag> : '-'),
    },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 170,
      render: (tags: string[]) =>
        tags.length ? (
          <Space size={2} wrap>
            {tags.slice(0, 2).map((t) => (
              <Tag key={t}>{t}</Tag>
            ))}
            {tags.length > 2 && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                +{tags.length - 2}
              </Typography.Text>
            )}
          </Space>
        ) : (
          <Typography.Text type="secondary">-</Typography.Text>
        ),
    },
    { title: '来源', dataIndex: 'source', width: 82, render: sourceTag },
    {
      title: '索引',
      dataIndex: 'indexStatus',
      width: 132,
      render: (_, r) => indexStatusTag(r, onReindexEntry),
    },
    {
      title: '版本/命中',
      key: 'version',
      width: 104,
      render: (_, r) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          v{r.contentVersion} · 命中 {r.hitCount}
        </Typography.Text>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 96,
      render: (s: string) => (s === 'active' ? <Tag color="green">启用</Tag> : <Tag>已下线</Tag>),
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 165, render: (v: string) => fmtTime(v) },
    {
      title: '操作',
      width: 150,
      render: (_, r) => {
        const items: MenuProps['items'] = [
          { key: 'reindex', label: '重建索引' },
          ...(r.source === 'feishu' ? [{ key: 'resync', label: '重同步飞书内容' }] : []),
          { type: 'divider' as const },
          { key: 'delete', label: '删除', danger: true },
        ]
        return (
          <Space size={4}>
            <Button
              size="small"
              icon={<EditOutlined />}
              onClick={(e) => {
                e.stopPropagation()
                openEditEntry(r.id)
              }}
            >
              编辑
            </Button>
            <Dropdown
              menu={{
                items,
                onClick: ({ key, domEvent }) => {
                  domEvent.stopPropagation()
                  if (key === 'reindex') onReindexEntry(r)
                  else if (key === 'resync') onResync(r)
                  else if (key === 'delete') onDeleteEntry(r)
                },
              }}
            >
              <Button size="small" loading={resyncingId === r.id} onClick={(e) => e.stopPropagation()}>
                更多
              </Button>
            </Dropdown>
          </Space>
        )
      },
    },
  ]

  const headerTags = base && (
    <Space size={4} wrap>
      <Tag color={base.scope === 'global' ? 'blue' : undefined}>
        {base.scope === 'global'
          ? '范围：全局'
          : `范围：${base.projectName ?? base.projectId ?? '项目'}`}
      </Tag>
      {base.status === 'archived' && <Tag>已归档</Tag>}
    </Space>
  )

  const addContentItems: MenuProps['items'] = [
    { key: 'upload', label: '上传文件 / 文件夹 / ZIP' },
    { key: 'markdown', label: '新建 Markdown 文档' },
    { key: 'feishu', label: '从飞书导入' },
  ]

  const onAddContent: MenuProps['onClick'] = ({ key }) => {
    if (key === 'upload') setUploadOpen(true)
    else if (key === 'markdown') openEditEntry(null)
    else if (key === 'feishu') setTab('feishu')
  }

  const showFailedEntries = () => {
    const params = new URLSearchParams(searchParams)
    params.delete('tab')
    params.set('indexStatus', 'failed')
    setSearchParams(params, { replace: true })
    setIndexStatus('failed')
    setPage(0)
  }

  // 旧 ?tab=settings：设置已移到库列表抽屉，直接带库 ID 打开
  if (tabParam === 'settings') {
    return <Navigate to={`/admin/knowledge?settings=${baseId}`} replace />
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>{base?.name ?? `知识库 #${id}`}</span>
          {baseLoading && !base && <Typography.Text type="secondary">加载中…</Typography.Text>}
          <Segmented
            value={tab}
            onChange={(key) => setTab(key as BaseTab)}
            options={[
              { value: 'entries', label: `文档${base ? ` (${base.entryCount})` : ''}` },
              { value: 'upload', label: '手动上传' },
              { value: 'feishu', label: '飞书同步' },
            ]}
          />
        </Space>
      }
      extra={
        <Space wrap>
          <Link to={`/chats?kbId=${baseId}`}>
            <Button icon={<MessageOutlined />}>用此库问答</Button>
          </Link>
          <Button icon={<ReloadOutlined />} onClick={refreshAll}>
            刷新
          </Button>
          <Dropdown menu={{ items: addContentItems, onClick: onAddContent }} placement="bottomRight">
            <Button type="primary" icon={<PlusOutlined />}>
              添加内容
            </Button>
          </Dropdown>
          <Link to="/admin/knowledge">
            <Button>返回列表</Button>
          </Link>
        </Space>
      }
    >
      {base && (
        <Space direction="vertical" size={4} style={{ marginBottom: 12, flexShrink: 0, width: '100%' }}>
          <Space size={8} wrap>
            {headerTags}
            <Space size={4}>
              <span>向量端点</span>
              {base.modelEndpointName ? (
                <Tag color="blue">
                  {base.modelEndpointName}
                  {resolvedEndpoint?.dimensions ? ` · ${resolvedEndpoint.dimensions} 维` : ''}
                </Tag>
              ) : (
                <Tooltip title="未配置向量端点：索引停用、检索走关键词降级。到「模型接入」新建端点并设为平台默认，或回到库列表点「设置」为本库指定。">
                  <Tag color="orange">未配置向量端点</Tag>
                </Tooltip>
              )}
            </Space>
            <Typography.Text type="secondary">
              已索引 {base.indexStats.ready}/{base.entryCount}
            </Typography.Text>
            {base.indexStats.pending > 0 && (
              <Typography.Text type="secondary">待索引 {base.indexStats.pending}</Typography.Text>
            )}
            {base.indexStats.failed > 0 && (
              <Typography.Link onClick={showFailedEntries}>
                失败 {base.indexStats.failed} · 去处理
              </Typography.Link>
            )}
            {base.indexStats.mismatched > 0 && (
              <Typography.Link onClick={() => onReindexBase(true)}>
                需重建 {base.indexStats.mismatched} · 一键重建
              </Typography.Link>
            )}
            {base.indexStats.disabled > 0 && (
              <Tag>未配置端点 {base.indexStats.disabled}</Tag>
            )}
            <Link to={`/admin/knowledge/ops?tab=search&kbId=${baseId}`}>召回验证</Link>
            <Link to={`/admin/knowledge/ops?tab=tasks&kbId=${baseId}`}>库待办</Link>
          </Space>
          {base.description && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {base.description}
            </Typography.Text>
          )}
        </Space>
      )}

      {tab === 'entries' && (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <Space size={8} wrap style={{ marginBottom: 10, flexShrink: 0 }}>
            <Input.Search
              allowClear
              placeholder="按名称搜索"
              style={{ width: 240 }}
              onSearch={(v) => {
                setPage(0)
                setKeyword(v.trim())
              }}
            />
            <Select
              allowClear
              placeholder="文档类型"
              style={{ width: 150 }}
              value={docType}
              onChange={(v) => {
                setPage(0)
                setDocType(v)
              }}
              options={DOC_TYPE_OPTIONS}
            />
            <Select
              allowClear
              placeholder="来源"
              style={{ width: 110 }}
              value={source}
              onChange={(v) => {
                setPage(0)
                setSource(v)
              }}
              options={SOURCE_OPTIONS}
            />
            <Select
              allowClear
              placeholder="索引状态"
              style={{ width: 150 }}
              value={indexStatus}
              onChange={(v) => {
                setPage(0)
                setIndexStatus(v)
              }}
              options={INDEX_OPTIONS}
            />
            <Button
              onClick={() => {
                setPage(0)
                setKeyword('')
                setDocType(undefined)
                setSource(undefined)
                setIndexStatus(undefined)
              }}
              disabled={!keyword && !docType && !source && !indexStatus}
            >
              重置
            </Button>
          </Space>

          {base?.status === 'archived' && (
            <Alert
              style={{ marginBottom: 10, flexShrink: 0 }}
              type="info"
              showIcon
              message="该库已归档"
              description="归档库不参与检索（含场景绑定）；需要恢复时到知识库列表点「设置」把状态改回「启用」。"
            />
          )}
          {base && base.indexStats.mismatched > 0 && (
            <Alert
              style={{ marginBottom: 10, flexShrink: 0 }}
              type="warning"
              showIcon
              message={`有 ${base.indexStats.mismatched} 条文档需要重建索引`}
              description={
                resolvedEndpoint
                  ? `这些文档是用别的端点（或维度）建的索引，当前端点「${resolvedEndpoint.name}」${
                      resolvedEndpoint.dimensions ? `为 ${resolvedEndpoint.dimensions} 维` : '维度未知'
                    }，检索时会因维度不同命中不到。重建后即刻恢复。`
                  : '当前库没有可用向量端点，历史索引无法用于检索。'
              }
              action={
                <Button size="small" loading={reindexing} onClick={() => onReindexBase(true)}>
                  一键重建
                </Button>
              }
            />
          )}

          <FitTable<KnowledgeEntrySummary>
            rowKey="id"
            loading={entriesLoading}
            columns={entryColumns}
            dataSource={entriesPage?.items ?? []}
            onRow={(r) => ({ onClick: () => setDetailEntryId(r.id), style: { cursor: 'pointer' } })}
            pagination={{
              current: page + 1,
              pageSize: size,
              total: entriesPage?.total ?? 0,
              showSizeChanger: true,
              pageSizeOptions: [20, 50, 100],
              showTotal: (total) => `共 ${total} 条`,
              onChange: (nextPage, nextSize) => {
                setPage(nextPage - 1)
                setSize(nextSize)
              },
            }}
            locale={{ emptyText: '没有匹配的文档。可调整过滤条件，或点右上角「添加内容」补充。' }}
          />
        </div>
      )}

      {tab === 'upload' && (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
            支持 Markdown / 文本 / PDF / Word / PPT / Excel / HTML 与 ZIP；文件夹按目录结构入库，解析与索引在后台异步执行。
            点右上角「添加内容」上传文件 / 文件夹 / ZIP，或新建 Markdown 文档。
          </Typography.Paragraph>
          <KnowledgeImportsView
            bases={base ? [base] : []}
            kbId={baseId}
            refreshKey={importsRefresh}
            onOpenEntry={(entryId) => setDetailEntryId(entryId)}
          />
        </div>
      )}

      {tab === 'feishu' && (
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Card size="small" title="同步源">
              <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
                飞书空间 / 目录按水位增量同步，Git 本地副本也在本页维护；同一文档按内容哈希判重，删除只停同步、已入库文档保留。
              </Typography.Paragraph>
              <SyncSourcesPanel bases={bases.length > 0 ? bases : base ? [base] : []} kbId={baseId} />
            </Card>
            <Card size="small" title="飞书文档手动导入">
              <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
                选集成 → 粘贴文档 URL（每行一条，支持 /wiki/、/docx/、/docs/ 三种链接）→ 导入。同一文档重复导入按内容哈希判重；
                导入后条目来源标记「飞书」，可在「文档」页签「更多 → 重同步飞书内容」拉取最新内容。
              </Typography.Paragraph>
              {feishuIntegrations !== null && feishuIntegrations.length === 0 ? (
                <Alert
                  type="info"
                  showIcon
                  message="尚未配置飞书集成"
                  description={
                    <span>
                      请先到 <Link to="/admin/integrations">平台集成</Link> 新建「飞书」类型集成
                      （填自建应用的 App ID / App Secret），再回到本页导入。
                    </span>
                  }
                />
              ) : (
                <Space direction="vertical" size={12} style={{ width: '100%', maxWidth: 860 }}>
                  <Space wrap>
                    <span>飞书集成：</span>
                    <Select
                      style={{ minWidth: 260 }}
                      placeholder="选择飞书集成（自建应用）"
                      loading={feishuIntegrations === null}
                      value={feishuIntegrationId ?? undefined}
                      onChange={(v) => setFeishuIntegrationId(v)}
                      options={(feishuIntegrations ?? []).map((i) => ({ value: i.id, label: i.name }))}
                    />
                  </Space>
                  <Input.TextArea
                    rows={5}
                    placeholder={'每行一条飞书文档 URL，如：\nhttps://xxx.feishu.cn/wiki/AbCdEf123\nhttps://xxx.feishu.cn/docx/XyZ456'}
                    value={feishuUrls}
                    onChange={(e) => setFeishuUrls(e.target.value)}
                  />
                  <div>
                    <Button
                      type="primary"
                      icon={<CloudDownloadOutlined />}
                      loading={feishuImporting}
                      onClick={onImportFeishu}
                    >
                      导入
                    </Button>
                  </div>
                  {feishuResults && (
                    <Table<FeishuImportResult>
                      rowKey={(r) => r.url}
                      size="small"
                      pagination={false}
                      dataSource={feishuResults}
                      columns={[
                        {
                          title: '文档 URL',
                          dataIndex: 'url',
                          ellipsis: true,
                          render: (u: string) => (
                            <Typography.Text style={{ fontSize: 12 }}>{u}</Typography.Text>
                          ),
                        },
                        {
                          title: '结果',
                          dataIndex: 'status',
                          width: 100,
                          render: (s: FeishuImportResult['status']) =>
                            s === 'created' ? (
                              <Tag color="green">新建</Tag>
                            ) : s === 'updated' ? (
                              <Tag color="blue">已更新</Tag>
                            ) : s === 'unchanged' ? (
                              <Tag>未变更</Tag>
                            ) : (
                              <Tag color="red">失败</Tag>
                            ),
                        },
                        {
                          title: '说明',
                          dataIndex: 'error',
                          width: 260,
                          render: (err: string | null, r) =>
                            err ? (
                              <Typography.Text type="danger" style={{ fontSize: 12 }}>
                                {err}
                              </Typography.Text>
                            ) : r.entryId ? (
                              <Typography.Link
                                style={{ fontSize: 12 }}
                                onClick={() => setDetailEntryId(r.entryId as number)}
                              >
                                查看条目 #{r.entryId}
                              </Typography.Link>
                            ) : null,
                        },
                      ]}
                    />
                  )}
                </Space>
              )}
            </Card>
          </Space>
        </div>
      )}

      <KnowledgeEntryEditorDrawer
        open={editorOpen}
        baseId={baseId}
        entryId={editingEntryId}
        onClose={() => setEditorOpen(false)}
        onSaved={onEntrySaved}
      />
      <KnowledgeEntryDrawer entryId={detailEntryId} onClose={() => setDetailEntryId(undefined)} />
      <KnowledgeUploadDrawer
        open={uploadOpen}
        presetKbId={baseId}
        onClose={() => setUploadOpen(false)}
        onSubmitted={() => {
          setUploadOpen(false)
          setImportsRefresh((n) => n + 1)
          refreshAll()
          window.setTimeout(refreshAll, 3000)
          setTab('upload')
        }}
      />
    </Card>
  )
}
