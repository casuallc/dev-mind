// CAP-44 知识库详情：条目管理（MarkdownEditor 抽屉编辑/索引状态/重建索引）+ 检索测试 + 飞书导入（CAP-45）+ 库设置。
import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { canWrite } from '../../auth/authStore'
import {
  Alert,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  InputNumber,
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
  ArrowLeftOutlined,
  CloudDownloadOutlined,
  CommentOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SyncOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  createEntry,
  deleteEntry,
  getBase,
  importFeishuDocs,
  listBaseEntries,
  listFeishuIntegrations,
  reindexBase,
  reindexEntry,
  resyncEntry,
  searchChunks,
  updateBase,
  updateEntry,
} from '../api'
import { listModelEndpoints } from '../../model/api'
import type { ModelEndpoint } from '../../model/types'
import type {
  FeishuImportResult,
  FeishuIntegration,
  IndexStatus,
  KnowledgeBase,
  KnowledgeBaseInput,
  KnowledgeEntry,
  KnowledgeEntryInput,
  KnowledgeSearchResult,
} from '../types'
import MarkdownEditor from '../../../shared/components/MarkdownEditor'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardBodyFlexStyle, pageCardStyle, pagePaneScrollStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const sourceTag = (s: string) =>
  s === 'feishu' ? <Tag color="cyan">飞书</Tag> : <Tag>手动</Tag>

const indexStatusTag = (e: KnowledgeEntry, onRetry: (e: KnowledgeEntry) => void) => {
  const s: IndexStatus = e.indexStatus
  if (s === 'ready') return <Tag color="green">已索引</Tag>
  if (s === 'pending') return <Tag color="gold">待索引</Tag>
  if (s === 'disabled')
    return (
      <Tooltip title="无可用向量端点，索引停用、检索走关键词降级">
        <Tag>未启用</Tag>
      </Tooltip>
    )
  return (
    <Space size={4}>
      <Tooltip title={e.indexError ?? '索引失败'}>
        <Tag color="red">索引失败</Tag>
      </Tooltip>
      <Button size="small" type="link" icon={<SyncOutlined />} onClick={() => onRetry(e)} />
    </Space>
  )
}

export default function KnowledgeBaseDetail() {
  const { id } = useParams<{ id: string }>()
  const baseId = Number(id)
  const navigate = useNavigate()

  const [base, setBase] = useState<KnowledgeBase | null>(null)
  const [entries, setEntries] = useState<KnowledgeEntry[]>([])
  const [entriesLoading, setEntriesLoading] = useState(false)
  const [view, setView] = useState<string>('entries') // entries | search | settings
  // CAP-48 向量端点清单（库级覆盖选择器）
  const [endpoints, setEndpoints] = useState<ModelEndpoint[]>([])
  const [reindexing, setReindexing] = useState(false)
  // 条目编辑
  const [entryDrawerOpen, setEntryDrawerOpen] = useState(false)
  const [editingEntry, setEditingEntry] = useState<KnowledgeEntry | null>(null)
  const [entryForm] = Form.useForm<KnowledgeEntryInput>()

  // 检索测试
  const [searchQ, setSearchQ] = useState('')
  const [searchTopK, setSearchTopK] = useState<number>(8)
  const [searching, setSearching] = useState(false)
  const [searchResult, setSearchResult] = useState<KnowledgeSearchResult | null>(null)

  // 飞书导入（CAP-45）
  const [feishuIntegrations, setFeishuIntegrations] = useState<FeishuIntegration[] | null>(null)
  const [feishuIntegrationId, setFeishuIntegrationId] = useState<number | null>(null)
  const [feishuUrls, setFeishuUrls] = useState('')
  const [feishuImporting, setFeishuImporting] = useState(false)
  const [feishuResults, setFeishuResults] = useState<FeishuImportResult[] | null>(null)
  const [resyncingId, setResyncingId] = useState<number | null>(null)

  // 设置
  const [settingsForm] = Form.useForm<KnowledgeBaseInput>()
  const [settingsSaving, setSettingsSaving] = useState(false)

  const load = useCallback(async () => {
    setEntriesLoading(true)
    try {
      const [b, es] = await Promise.all([getBase(baseId), listBaseEntries(baseId)])
      setBase(b)
      setEntries(es)
    } catch (e) {
      showError(e, '加载知识库失败')
    } finally {
      setEntriesLoading(false)
    }
  }, [baseId])

  useEffect(() => {
    load()
  }, [load])

  // 向量端点清单（CAP-48 解析链：库级覆盖 → 平台默认）——只用于展示与选择，失败不影响页面
  useEffect(() => {
    listModelEndpoints()
      .then(setEndpoints)
      .catch(() => undefined)
  }, [])

  /** 本库当前实际生效的端点（库级覆盖命中，否则平台默认） */
  const resolvedEndpoint =
    endpoints.find(e => e.id === base?.modelEndpointId) ??
    endpoints.find(e => e.isDefault && e.status === 'active') ??
    null

  useEffect(() => {
    if (base && view === 'settings') {
      settingsForm.setFieldsValue({
        name: base.name,
        description: base.description ?? undefined,
        injectMode: base.injectMode,
        modelEndpointId: base.modelEndpointId ?? undefined,
        status: base.status,
      })
    }
  }, [base, view, settingsForm])

  // 飞书导入视图打开时拉集成清单（空 = 未配置，页面提示去集成页）
  useEffect(() => {
    if (view !== 'feishu' || feishuIntegrations !== null) return
    listFeishuIntegrations()
      .then(list => {
        setFeishuIntegrations(list)
        if (list.length === 1) setFeishuIntegrationId(list[0].id)
      })
      .catch(e => showError(e, '加载飞书集成失败'))
  }, [view, feishuIntegrations])

  const openCreateEntry = () => {
    setEditingEntry(null)
    entryForm.resetFields()
    entryForm.setFieldsValue({ status: 'active', tags: [] })
    setEntryDrawerOpen(true)
  }

  const openEditEntry = (e: KnowledgeEntry) => {
    setEditingEntry(e)
    entryForm.setFieldsValue({
      name: e.name,
      contentMd: e.contentMd,
      tags: e.tags,
      status: e.status,
    })
    setEntryDrawerOpen(true)
  }

  const onSaveEntry = async () => {
    const v = await entryForm.validateFields()
    try {
      if (editingEntry) {
        await updateEntry(editingEntry.id, v)
        message.success('条目已更新（自动重建索引）')
      } else {
        await createEntry({ ...v, kbId: baseId })
        message.success('条目已创建（自动建立索引）')
      }
      setEntryDrawerOpen(false)
      load()
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  const onDeleteEntry = (e: KnowledgeEntry) => {
    Modal.confirm({
      centered: true,
      title: `删除条目「${e.name}」？`,
      content: '删除后连同索引一并移除，无法恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteEntry(e.id)
          message.success('已删除')
          load()
        } catch (err) {
          showError(err, '删除失败')
        }
      },
    })
  }

  const onReindex = async (e: KnowledgeEntry) => {
    try {
      await reindexEntry(e.id)
      message.success('已触发重建索引')
      setTimeout(load, 1500)
    } catch (err) {
      showError(err, '重建索引失败')
    }
  }

  /** CAP-48 FR-08 整库重建（换端点/换模型/调分块参数后的修复入口，异步排队） */
  const onReindexBase = async (onlyMismatched: boolean) => {
    setReindexing(true)
    try {
      const r = await reindexBase(baseId, onlyMismatched)
      if (r.queued === 0) {
        message.info(
          onlyMismatched
            ? '没有失配条目需要重建（端点未探测过维度时无法判定失配，可先到「管理 → 测试连接」探测）'
            : '库里没有可重建的条目',
        )
      } else {
        message.success(`已入队重建 ${r.queued} 条，稍后自动刷新`)
      }
      setTimeout(load, 2000)
    } catch (e) {
      showError(e, '重建索引失败')
    } finally {
      setReindexing(false)
    }
  }

  const onImportFeishu = async () => {
    const urls = feishuUrls
      .split('\n')
      .map(u => u.trim())
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
      const created = results.filter(r => r.status === 'created').length
      const updated = results.filter(r => r.status === 'updated').length
      const failed = results.filter(r => r.status === 'failed').length
      if (failed === 0) {
        message.success(`导入完成：新建 ${created}、更新 ${updated}、未变更 ${results.length - created - updated}`)
      } else {
        message.warning(`导入完成：新建 ${created}、更新 ${updated}、失败 ${failed}（详见下方列表）`)
      }
      load()
    } catch (e) {
      showError(e, '飞书导入失败')
    } finally {
      setFeishuImporting(false)
    }
  }

  const onResync = async (e: KnowledgeEntry) => {
    setResyncingId(e.id)
    try {
      const r = await resyncEntry(e.id)
      if (r.status === 'updated') {
        message.success(`「${e.name}」已同步最新内容（自动重建索引）`)
        setTimeout(load, 1500)
      } else if (r.status === 'unchanged') {
        message.info(`「${e.name}」内容无变更`)
      } else {
        message.warning(`重同步失败：${r.error ?? '未知原因'}（保留旧内容）`)
      }
    } catch (err) {
      showError(err, '重同步失败')
    } finally {
      setResyncingId(null)
    }
  }

  const onSearch = async () => {
    if (!searchQ.trim()) return
    setSearching(true)
    try {
      setSearchResult(await searchChunks([baseId], searchQ.trim(), searchTopK))
    } catch (e) {
      showError(e, '检索失败')
    } finally {
      setSearching(false)
    }
  }

  const onSaveSettings = async () => {
    const v = await settingsForm.validateFields()
    setSettingsSaving(true)
    try {
      const updated = await updateBase(baseId, v)
      setBase(updated)
      message.success('设置已保存')
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSettingsSaving(false)
    }
  }

  const entryColumns: ColumnsType<KnowledgeEntry> = [
    { title: '名称', dataIndex: 'name', ellipsis: true },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 180,
      render: (tags: string[]) =>
        tags.length ? tags.map((t) => <Tag key={t}>{t}</Tag>) : <Typography.Text type="secondary">-</Typography.Text>,
    },
    { title: '来源', dataIndex: 'source', width: 80, render: sourceTag },
    {
      title: '索引',
      dataIndex: 'indexStatus',
      width: 130,
      render: (_, r) => indexStatusTag(r, onReindex),
    },
    { title: '注入次数', dataIndex: 'hitCount', width: 90 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s) => (s === 'active' ? <Tag color="green">active</Tag> : <Tag>deprecated</Tag>),
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 260,
      render: (_, r) => (
        <Space size={4} wrap>
          {r.source === 'feishu' && (
            <Tooltip title="按来源 URL 重拉飞书文档，内容变更才更新">
              <Button size="small" loading={resyncingId === r.id} onClick={() => onResync(r)}>
                重同步
              </Button>
            </Tooltip>
          )}
          <Button size="small" onClick={() => openEditEntry(r)}>
            编辑
          </Button>
          <Button size="small" onClick={() => onReindex(r)}>
            重建索引
          </Button>
          <Button size="small" danger onClick={() => onDeleteEntry(r)}>
            删除
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <Link to="/admin/knowledge">
            <Button size="small" type="text" icon={<ArrowLeftOutlined />} />
          </Link>
          <span>{base?.name ?? '知识库'}</span>
          {base && (
            <Space size={4}>
              <Tag color={base.injectMode === 'FULL' ? 'geekblue' : 'purple'}>{base.injectMode}</Tag>
              <Tag color={base.scope === 'global' ? 'blue' : undefined}>{base.scope}</Tag>
            </Space>
          )}
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'entries', label: `条目${base ? ` (${base.entryCount})` : ''}` },
              { value: 'search', label: '检索测试' },
              { value: 'feishu', label: '飞书导入' },
              { value: 'settings', label: '设置' },
            ]}
          />
        </Space>
      }
      extra={
        <Space wrap>
          {view === 'entries' && (
            <>
              <Button icon={<ReloadOutlined />} onClick={load}>
                刷新
              </Button>
              {canWrite() && base && base.entryCount > 0 && (
                <Popconfirm
                  title="重建全库索引？"
                  description={`库内 ${base.entryCount} 条条目会全部重新向量化（异步排队），换端点/换分块参数后需要执行。`}
                  okText="重建"
                  onConfirm={() => onReindexBase(false)}
                >
                  <Button icon={<SyncOutlined />} loading={reindexing}>
                    重建全库索引
                  </Button>
                </Popconfirm>
              )}
              {canWrite() && (
                <Button type="primary" icon={<PlusOutlined />} onClick={openCreateEntry}>
                  新增条目
                </Button>
              )}
            </>
          )}
          {/* CAP-46 FR-04：发起绑定本库的 AI 问答 */}
          <Button
            icon={<CommentOutlined />}
            disabled={!base || base.status !== 'active'}
            onClick={() => navigate(`/chats?kbId=${baseId}`)}
          >
            发起会话
          </Button>
        </Space>
      }
    >
      {view === 'entries' && (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            {base?.injectMode === 'FULL'
              ? 'FULL 库条目在会话启动时全量注入 CLAUDE.md（global 库按项目标签过滤）；内容保存后自动重建向量索引。'
              : 'RAG 库条目不参与启动注入，仅供向量检索按提问召回；内容保存后自动重建向量索引。'}
            {base && (
              <>
                {' '}当前向量端点：
                {base.modelEndpointName ? (
                  <Tag color="blue">
                    {base.modelEndpointName}
                    {resolvedEndpoint?.dimensions ? ` · ${resolvedEndpoint.dimensions} 维` : ' · 维度未探测'}
                  </Tag>
                ) : (
                  <Tooltip title="无可用端点：索引停用、检索走关键词降级。到「模型接入」新建端点或设为平台默认。">
                    <Tag color="orange">未配置</Tag>
                  </Tooltip>
                )}
                索引 {base.indexStats.ready}/{base.entryCount} 已索引
                {base.indexStats.pending > 0 && ` · 待索引 ${base.indexStats.pending}`}
                {base.indexStats.failed > 0 && ` · 失败 ${base.indexStats.failed}`}
                {base.indexStats.disabled > 0 && ` · 未启用 ${base.indexStats.disabled}`}
              </>
            )}
          </Typography.Paragraph>
          {/* CAP-48 FR-06 失配告警：换了端点/模型后维度对不上，检索会静默搜不到东西 */}
          {base && base.indexStats.mismatched > 0 && (
            <Alert
              style={{ marginBottom: 12 }}
              type="warning"
              showIcon
              message={`有 ${base.indexStats.mismatched} 条条目的索引与当前端点不匹配`}
              description={
                resolvedEndpoint
                  ? `这些条目是用别的端点（或别的维度）建的索引，当前端点「${resolvedEndpoint.name}」${
                      resolvedEndpoint.dimensions ? `为 ${resolvedEndpoint.dimensions} 维` : '维度未知'
                    }，检索时会因维度不同而命中不到。重建后即刻恢复。`
                  : '当前库没有可用向量端点，条目的历史索引无法用于检索。'
              }
              action={
                <Button size="small" loading={reindexing} onClick={() => onReindexBase(true)}>
                  重建失配条目
                </Button>
              }
            />
          )}
          <FitTable
            rowKey="id"
            loading={entriesLoading}
            columns={entryColumns}
            dataSource={entries}
            pagination={LIST_PAGINATION}
            locale={{
              emptyText: (
                <Space direction="vertical" size={8} style={{ padding: '24px 0' }}>
                  <Typography.Text type="secondary">库内暂无条目——点击「新增条目」创建第一条。</Typography.Text>
                  {canWrite() && (
                    <div>
                      <Button type="primary" icon={<PlusOutlined />} onClick={openCreateEntry}>
                        新增条目
                      </Button>
                    </div>
                  )}
                </Space>
              ),
            }}
          />
        </>
      )}

      {view === 'search' && (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            输入提问测试本库的检索召回效果（向量检索按相关度排序；未配置 embedding 时降级关键词匹配）。
          </Typography.Paragraph>
          <Space.Compact style={{ width: '100%', maxWidth: 720, marginBottom: 16 }}>
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="输入测试提问，回车检索"
              value={searchQ}
              onChange={(e) => setSearchQ(e.target.value)}
              onPressEnter={onSearch}
            />
            <InputNumber min={1} max={50} value={searchTopK} onChange={(v) => setSearchTopK(v ?? 8)} style={{ width: 90 }} />
            <Button type="primary" loading={searching} onClick={onSearch}>
              检索
            </Button>
          </Space.Compact>
          {searchResult && (
            <Space direction="vertical" size={12} style={{ width: '100%', ...pagePaneScrollStyle }}>
              {searchResult.degradedReason === 'DIMENSION_MISMATCH' && (
                <Alert
                  type="warning"
                  showIcon
                  message="维度失配：库内索引与当前端点维度不一致，命中被全部过滤"
                  description={
                    resolvedEndpoint
                      ? `当前端点「${resolvedEndpoint.name}」为 ${resolvedEndpoint.dimensions ?? '未知'} 维。同维度的余弦才有意义，维度不同的向量算出来恒为 0 分，所以本次过滤掉了全部候选。执行「重建全库索引」（条目页右上角）后即可恢复。`
                      : '当前库没有可用向量端点，历史索引无法参与检索。'
                  }
                  action={
                    <Button size="small" loading={reindexing} onClick={() => onReindexBase(true)}>
                      重建失配条目
                    </Button>
                  }
                />
              )}
              {searchResult.degradedReason === 'NO_EMBEDDING' && (
                <Alert
                  type="warning"
                  showIcon
                  message="无可用向量端点，本次检索为关键词 LIKE 降级（score 恒为 0）"
                  description="到「模型接入」新建一个 Embedding 端点并设为平台默认（或在本库设置里指定端点）后重新索引，即可走向量检索。"
                />
              )}
              {searchResult.degradedReason === 'NONE' && (
                <Typography.Text type="secondary">
                  向量检索命中：端点「{resolvedEndpoint?.name ?? '平台默认'}
                  {resolvedEndpoint?.dimensions ? ` · ${resolvedEndpoint.dimensions} 维` : ''}」
                </Typography.Text>
              )}
              {searchResult.chunks.length === 0 && (
                <Typography.Text type="secondary">无命中——换个问法，或先确认条目索引状态为「已索引」。</Typography.Text>
              )}
              {searchResult.chunks.map((c, i) => (
                <Card key={`${c.entryId}-${i}`} size="small">
                  <Space direction="vertical" size={8} style={{ width: '100%' }}>
                    <Space size={8} wrap>
                      <Typography.Text strong>{c.entryName}</Typography.Text>
                      {searchResult.vector && (
                        <Tag color={c.score >= 0.5 ? 'green' : 'orange'}>score {c.score.toFixed(3)}</Tag>
                      )}
                    </Space>
                    <pre
                      style={{
                        whiteSpace: 'pre-wrap',
                        background: '#f6f6f6',
                        padding: 12,
                        borderRadius: 4,
                        fontSize: 12,
                        maxHeight: 240,
                        overflow: 'auto',
                        margin: 0,
                      }}
                    >
                      {c.content}
                    </pre>
                  </Space>
                </Card>
              ))}
            </Space>
          )}
        </>
      )}

      {view === 'feishu' && (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            从飞书文档手动导入为知识条目：选集成 → 粘贴文档 URL（每行一条，支持 /wiki/、/docx/、/docs/
            三种链接）→ 导入。同一文档重复导入按内容哈希判重；导入后条目来源标记「飞书」，
            可在条目列表点「重同步」拉取最新内容。
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
            <Space direction="vertical" size={12} style={{ width: '100%', maxWidth: 860, ...pagePaneScrollStyle }}>
              <Space wrap>
                <span>飞书集成：</span>
                <Select
                  style={{ minWidth: 260 }}
                  placeholder="选择飞书集成（自建应用）"
                  loading={feishuIntegrations === null}
                  value={feishuIntegrationId ?? undefined}
                  onChange={v => setFeishuIntegrationId(v)}
                  options={(feishuIntegrations ?? []).map(i => ({ value: i.id, label: i.name }))}
                />
              </Space>
              <Input.TextArea
                rows={6}
                placeholder={'每行一条飞书文档 URL，如：\nhttps://xxx.feishu.cn/wiki/AbCdEf123\nhttps://xxx.feishu.cn/docx/XyZ456'}
                value={feishuUrls}
                onChange={e => setFeishuUrls(e.target.value)}
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
                  rowKey={r => r.url}
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
                          <Typography.Text type="danger" style={{ fontSize: 12 }}>{err}</Typography.Text>
                        ) : r.entryId ? (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            条目 #{r.entryId}
                          </Typography.Text>
                        ) : null,
                    },
                  ]}
                />
              )}
            </Space>
          )}
        </>
      )}

      {view === 'settings' && base && (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            库的基本设置；归档后不再参与注入与检索。
          </Typography.Paragraph>
          <Form form={settingsForm} labelCol={{ span: 4 }} wrapperCol={{ span: 12 }} style={{ maxWidth: 720, ...pagePaneScrollStyle }}>
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
                  <Typography.Text type="secondary">
                    {base.projectName ?? base.projectId}（范围与所属项目创建后不可改）
                  </Typography.Text>
                )}
              </Space>
            </Form.Item>
            <Form.Item
              name="injectMode"
              label="注入模式"
              extra="FULL：会话启动全量注入 CLAUDE.md（经验库）；RAG：仅检索，按提问召回内容"
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
                options={endpoints.map(e => ({
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
              <Button type="primary" loading={settingsSaving} onClick={onSaveSettings}>
                保存设置
              </Button>
            </Form.Item>
          </Form>
        </>
      )}

      {/* 条目编辑抽屉（MarkdownEditor） */}
      <Drawer
        title={editingEntry ? `编辑条目 · ${editingEntry.name}` : '新增条目'}
        open={entryDrawerOpen}
        onClose={() => setEntryDrawerOpen(false)}
        width={760}
        footer={
          <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button onClick={() => setEntryDrawerOpen(false)}>取消</Button>
            <Button type="primary" onClick={onSaveEntry}>保存</Button>
          </Space>
        }
      >
        <Form form={entryForm} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请填写名称' }]}>
            <Input placeholder="如：AntD 表格固定列写法" />
          </Form.Item>
          <Form.Item name="tags" label="标签">
            <Select
              mode="tags"
              placeholder="回车添加标签（global 经验库条目按项目 tags 匹配注入）"
              open={false}
            />
          </Form.Item>
          <Form.Item name="contentMd" label="内容（Markdown）" rules={[{ required: true, message: '请填写内容' }]}>
            <MarkdownEditor height={420} placeholder="Markdown 内容；保存后自动分块并建立向量索引" />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 'active', label: 'active（启用）' },
                { value: 'deprecated', label: 'deprecated（停用）' },
              ]}
            />
          </Form.Item>
        </Form>
      </Drawer>
    </Card>
  )
}
