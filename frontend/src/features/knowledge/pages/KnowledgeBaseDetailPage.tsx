// CAP-86 FR-05 库详情页 /admin/knowledge/bases/:id：文档 / 摄入 / 同步 / 设置 四页签。
// 同步页签 = 本库同步源（SyncSourcesPanel 按 kbId 过滤）+ 飞书手动导入；设置页签含注入预览与危险区。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Dropdown,
  Form,
  Input,
  message,
  Modal,
  Popconfirm,
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
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  MessageOutlined,
  PlusOutlined,
  ReloadOutlined,
  SyncOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import type { MenuProps } from 'antd'
import {
  deleteBase,
  deleteEntry,
  getBase,
  importFeishuDocs,
  listBases,
  listFeishuIntegrations,
  pageBaseEntries,
  reindexBase,
  reindexEntry,
  resyncEntry,
  updateBase,
} from '../api'
import { listModelEndpoints } from '../../model/api'
import type { ModelEndpoint } from '../../model/types'
import type {
  DocType,
  FeishuImportResult,
  FeishuIntegration,
  KnowledgeBase,
  KnowledgeBaseInput,
  KnowledgeEntrySummary,
  KnowledgePage,
} from '../types'
import { DOC_TYPE_LABEL, DOC_TYPE_OPTIONS } from '../types'
import KnowledgeImportsView from '../components/KnowledgeImportsView'
import KnowledgeEntryEditorDrawer from './KnowledgeEntryEditorDrawer'
import KnowledgeEntryDrawer from '../components/KnowledgeEntryDrawer'
import KnowledgeUploadDrawer from '../components/KnowledgeUploadDrawer'
import SyncSourcesPanel from '../components/SyncSourcesPanel'
import InjectionPreview from '../components/InjectionPreview'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'

type BaseTab = 'entries' | 'import' | 'sync' | 'settings'

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
      <Tooltip title="未配置向量端点：索引停用、检索走关键词降级。到「模型接入」配置端点，或在「设置」为本库指定">
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
  const navigate = useNavigate()
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
  const [indexStatus, setIndexStatus] = useState<string | undefined>(undefined)

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

  // 设置
  const [settingsForm] = Form.useForm<KnowledgeBaseInput>()
  const [settingsSaving, setSettingsSaving] = useState(false)
  const [deletingBase, setDeletingBase] = useState(false)
  const [previewOpen, setPreviewOpen] = useState(false)

  const tabParam = searchParams.get('tab')
  const tab: BaseTab =
    tabParam === 'import' || tabParam === 'sync' || tabParam === 'settings' ? tabParam : 'entries'

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
    if (tab !== 'sync') return
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

  useEffect(() => {
    if (base && tab === 'settings') {
      settingsForm.setFieldsValue({
        name: base.name,
        description: base.description ?? undefined,
        injectMode: base.injectMode,
        modelEndpointId: base.modelEndpointId ?? undefined,
        status: base.status,
      })
    }
  }, [base, tab, settingsForm])

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

  const onReindexEntry = async (entry: KnowledgeEntrySummary) => {
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
  }

  const onResync = async (entry: KnowledgeEntrySummary) => {
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
  }

  const onReindexBase = async (onlyMismatched: boolean) => {
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
  }

  const onImportFeishu = async () => {
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
    setFeishuImporting(true)
    setFeishuResults(null)
    try {
      const results = await importFeishuDocs(baseId, feishuIntegrationId, urls)
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
  }

  const onSaveSettings = async () => {
    const values = await settingsForm.validateFields()
    setSettingsSaving(true)
    try {
      const updated = await updateBase(baseId, values)
      setBase(updated)
      message.success('设置已保存')
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSettingsSaving(false)
    }
  }

  const onDeleteBase = () => {
    if (!base) return
    Modal.confirm({
      centered: true,
      title: `删除知识库「${base.name}」？`,
      content:
        base.entryCount > 0
          ? `库内还有 ${base.entryCount} 个文档，确认后将连同条目与索引一并删除，无法恢复。`
          : '删除后无法恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setDeletingBase(true)
        try {
          await deleteBase(base.id, base.entryCount > 0)
          message.success('已删除')
          navigate('/admin/knowledge', { replace: true })
        } catch (err) {
          showError(err, '删除失败')
        } finally {
          setDeletingBase(false)
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
      render: (s: string) => (s === 'active' ? <Tag color="green">active</Tag> : <Tag>deprecated</Tag>),
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
      <Tag color={base.injectMode === 'FULL' ? 'geekblue' : 'purple'}>{base.injectMode}</Tag>
      <Tag color={base.scope === 'global' ? 'blue' : undefined}>
        {base.scope === 'global' ? 'global' : (base.projectName ?? base.projectId ?? 'project')}
      </Tag>
      {base.status === 'archived' && <Tag>已归档</Tag>}
    </Space>
  )

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
              { value: 'import', label: '摄入' },
              { value: 'sync', label: '同步' },
              { value: 'settings', label: '设置' },
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
          {tab === 'entries' && (
            <>
              <Popconfirm
                title="重建全库索引？"
                description={`库内 ${base?.entryCount ?? 0} 条文档会全部重新向量化（异步排队），换端点/换分块参数后需要执行。`}
                okText="重建"
                onConfirm={() => onReindexBase(false)}
                disabled={!base || base.entryCount === 0}
              >
                <Button icon={<SyncOutlined />} loading={reindexing} disabled={!base || base.entryCount === 0}>
                  重建全库索引
                </Button>
              </Popconfirm>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => openEditEntry(null)}>
                新增条目
              </Button>
            </>
          )}
          {tab === 'import' && (
            <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
              上传文档
            </Button>
          )}
        </Space>
      }
    >
      {base && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
          <Space size={8} wrap>
            <Link to="/admin/knowledge">← 库总览</Link>
            {headerTags}
            <Space size={4}>
              <span>向量端点</span>
              {base.modelEndpointName ? (
                <Tag color="blue">
                  {base.modelEndpointName}
                  {resolvedEndpoint?.dimensions ? ` · ${resolvedEndpoint.dimensions} 维` : ''}
                </Tag>
              ) : (
                <Tooltip title="未配置向量端点：索引停用、检索走关键词降级。到「模型接入」新建端点并设为平台默认，或在「设置」为本库指定。">
                  <Tag color="orange">未配置向量端点</Tag>
                </Tooltip>
              )}
            </Space>
            <span>
              索引 {base.indexStats.ready}/{base.entryCount}
              {base.indexStats.pending > 0 && ` · 待索引 ${base.indexStats.pending}`}
              {base.indexStats.failed > 0 && ` · 失败 ${base.indexStats.failed}`}
              {base.indexStats.disabled > 0 && ` · 未配置端点 ${base.indexStats.disabled}`}
              {base.indexStats.mismatched > 0 && ` · 需重建 ${base.indexStats.mismatched}`}
            </span>
            <Link to={`/admin/knowledge/search?kbId=${baseId}`}>召回验证</Link>
            {base.description && <span>{base.description}</span>}
          </Space>
        </Typography.Paragraph>
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
              description="归档库不参与检索与会话注入；需要恢复时到「设置」把状态改回 active。"
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
            locale={{ emptyText: '没有匹配的文档。可调整过滤条件，或点「新增条目 / 上传文档」补充内容。' }}
          />
        </div>
      )}

      {tab === 'import' && (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
            上传到「{base?.name ?? `#${baseId}`}」：支持 Markdown / 文本 / PDF / Word / PPT / Excel / HTML 与 ZIP；
            文件夹按目录结构入库，分类/标签自动识别。解析与索引在后台异步执行。飞书文档到「同步」页签导入。
          </Typography.Paragraph>
          <KnowledgeImportsView
            bases={base ? [base] : []}
            kbId={baseId}
            refreshKey={importsRefresh}
            onOpenEntry={(entryId) => setDetailEntryId(entryId)}
          />
        </div>
      )}

      {tab === 'sync' && (
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Card size="small" title="同步源">
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

      {tab === 'settings' && base && (
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
          <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
            库的基本设置；归档后不再参与注入与检索。范围与所属项目创建后不可改。
          </Typography.Paragraph>
          <Form form={settingsForm} labelCol={{ span: 4 }} wrapperCol={{ span: 12 }} style={{ maxWidth: 760 }}>
            <Form.Item name="name" label="名称" rules={[{ required: true, message: '请填写名称' }]}>
              <Input />
            </Form.Item>
            <Form.Item name="description" label="描述">
              <Input />
            </Form.Item>
            <Form.Item label="范围">
              <Space>
                <Tag color={base.scope === 'global' ? 'blue' : undefined}>{base.scope}</Tag>
                {base.scope === 'project' && (
                  <Typography.Text type="secondary">{base.projectName ?? base.projectId}</Typography.Text>
                )}
              </Space>
            </Form.Item>
            <Form.Item
              name="injectMode"
              label="注入模式"
              extra="FULL：会话启动全量注入（经验库）；RAG：仅检索，按提问召回内容"
            >
              <Select
                options={[
                  { value: 'RAG', label: 'RAG 检索' },
                  { value: 'FULL', label: 'FULL 全量注入' },
                ]}
              />
            </Form.Item>
            <Form.Item
              name="modelEndpointId"
              label="向量端点"
              extra="留空 = 跟随平台默认端点；指定后本库索引与检索按该端点走（不同模型/维度不能混用同一份索引）"
            >
              <Select
                allowClear
                placeholder="跟随平台默认端点"
                options={embeddingEndpoints.map((e) => ({
                  value: e.id,
                  label: `${e.name}（${e.model ?? e.provider}${e.dimensions ? ` · ${e.dimensions} 维` : ' · 未探测维度'}${e.status === 'disabled' ? ' · 已停用' : ''}）`,
                }))}
              />
            </Form.Item>
            <Form.Item label="当前生效">
              <Space>
                {base.modelEndpointName ? (
                  <Tag color="blue">{base.modelEndpointName}</Tag>
                ) : (
                  <Tooltip title="无可用向量端点：索引停用、检索走关键词降级">
                    <Tag color="orange">未配置向量端点</Tag>
                  </Tooltip>
                )}
                <Typography.Text type="secondary">
                  {resolvedEndpoint?.dimensions
                    ? `维度 ${resolvedEndpoint.dimensions}`
                    : '维度未探测（可在「模型接入」对该端点做一次连接测试）'}
                </Typography.Text>
                <Link to="/admin/models">模型接入</Link>
              </Space>
            </Form.Item>
            <Form.Item name="status" label="状态">
              <Select
                options={[
                  { value: 'active', label: 'active（启用）' },
                  { value: 'archived', label: 'archived（归档停用）' },
                ]}
              />
            </Form.Item>
            <Form.Item wrapperCol={{ offset: 4 }}>
              <Space>
                <Button type="primary" loading={settingsSaving} onClick={onSaveSettings}>
                  保存设置
                </Button>
                <Button icon={<EyeOutlined />} onClick={() => setPreviewOpen(true)}>
                  注入预览
                </Button>
              </Space>
            </Form.Item>
          </Form>

          <div style={{ borderTop: '1px solid #f0f0f0', marginTop: 8, paddingTop: 16, maxWidth: 760 }}>
            <Typography.Text type="danger" strong>
              危险区
            </Typography.Text>
            <div style={{ marginTop: 8 }}>
              <Space wrap>
                <Typography.Text type="secondary">
                  删除知识库会连同 {base.entryCount} 个文档与索引一并移除，无法恢复。
                </Typography.Text>
                <Button danger icon={<DeleteOutlined />} loading={deletingBase} onClick={onDeleteBase}>
                  删除知识库
                </Button>
              </Space>
            </div>
          </div>
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
          if (tab !== 'import') setTab('import')
        }}
      />
      <Drawer title="注入预览" open={previewOpen} onClose={() => setPreviewOpen(false)} width={DRAWER_SCREEN}>
        <InjectionPreview />
      </Drawer>
    </Card>
  )
}
