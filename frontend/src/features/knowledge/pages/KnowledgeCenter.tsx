// CAP-84 FR-15 知识中心（一级入口 /knowledge）：全局混合检索 + 文档详情 + 本地上传 + 批次进度/重试。
// 库容器管理等后台操作仍在 /admin/knowledge，本页面向「查与用」不做卡片瀑布流。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import {
  BookOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  SearchOutlined,
  SyncOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  getImport,
  listBases,
  listImports,
  retryImport,
  retryImportFile,
  searchV2,
} from '../api'
import type {
  DocType,
  ImportBatch,
  ImportBatchFile,
  KnowledgeBase,
  KnowledgeSearchV2Hit,
  KnowledgeSearchV2Result,
} from '../types'
import { DOC_TYPE_LABEL, DOC_TYPE_OPTIONS } from '../types'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import FitTable from '../../../shared/components/FitTable'
import HighlightedSnippet from '../components/HighlightedSnippet'
import KnowledgeEntryDrawer from '../components/KnowledgeEntryDrawer'
import KnowledgeUploadDrawer from '../components/KnowledgeUploadDrawer'
import GovernanceConsole from '../components/GovernanceConsole'
import { fmtTime } from '../../../shared/utils/format'
import { isAdmin } from '../../auth/authStore'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const IMPORT_STATUS: Record<string, { color: string; text: string }> = {
  RUNNING: { color: 'processing', text: '运行中' },
  DONE: { color: 'success', text: '完成' },
  PARTIAL: { color: 'warning', text: '部分失败' },
  FAILED: { color: 'error', text: '失败' },
  CANCELLED: { color: 'default', text: '已取消' },
}

const FILE_STATUS: Record<string, { color: string; text: string }> = {
  pending: { color: 'default', text: '待处理' },
  parsing: { color: 'processing', text: '解析中' },
  indexed: { color: 'success', text: '已入库' },
  skipped: { color: 'default', text: '未变更' },
  failed: { color: 'error', text: '失败' },
}

const degradedText: Record<string, string> = {
  NO_EMBEDDING: '无可用向量端点，本次为 BM25 关键词检索（结果仍可用，语义相近的文档可能漏召）',
  DIMENSION_MISMATCH: '向量维度与当前端点不一致，本次已降级为 BM25 关键词检索（可到后台重建索引修复）',
  INDEX_EMPTY: '索引为空：平台尚未建立知识索引，请到「后台 → 知识库」检查索引健康度或执行全量重建',
}

export default function KnowledgeCenter() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [view, setView] = useState<string>('search')
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [projects, setProjects] = useState<Project[]>([])

  // 检索条件与结果
  const [query, setQuery] = useState('')
  const [kbIds, setKbIds] = useState<number[]>([])
  const [docTypes, setDocTypes] = useState<DocType[]>([])
  const [projectId, setProjectId] = useState<string | undefined>(undefined)
  const [tags, setTags] = useState<string[]>([])
  const [result, setResult] = useState<KnowledgeSearchV2Result | null>(null)
  const [searching, setSearching] = useState(false)

  // 文档详情抽屉（检索命中 / ?entryId=&chunkId= 深链）
  const [detail, setDetail] = useState<{
    entryId: number
    hit?: KnowledgeSearchV2Hit
    focusChunkId?: number
  } | null>(null)

  // 上传与批次
  const [uploadOpen, setUploadOpen] = useState(false)
  const [imports, setImports] = useState<ImportBatch[]>([])
  const [importsLoading, setImportsLoading] = useState(false)
  const [retrying, setRetrying] = useState<string | null>(null)
  const [retryingFile, setRetryingFile] = useState<string | null>(null)

  useEffect(() => {
    listBases()
      .then((list) => setBases(list.filter((b) => b.status === 'active')))
      .catch(() => undefined)
    listProjects()
      .then(setProjects)
      .catch(() => undefined)
  }, [])

  // 深链 ?entryId=123&chunkId=456：聊天引用「查看原文」跳进来直接展开详情并定位分块；
  // 关闭时清参数（防粘性打开）
  useEffect(() => {
    const id = Number(searchParams.get('entryId'))
    if (!Number.isFinite(id) || id <= 0) return
    const chunk = Number(searchParams.get('chunkId'))
    setDetail({
      entryId: id,
      focusChunkId: Number.isFinite(chunk) && chunk > 0 ? chunk : undefined,
    })
  }, [searchParams])

  const closeDetail = () => {
    setDetail(null)
    if (searchParams.has('entryId') || searchParams.has('chunkId')) {
      searchParams.delete('entryId')
      searchParams.delete('chunkId')
      setSearchParams(searchParams, { replace: true })
    }
  }

  const onSearch = useCallback(async () => {
    const q = query.trim()
    if (!q) {
      message.warning('请输入要检索的问题或关键词')
      return
    }
    setSearching(true)
    try {
      setResult(
        await searchV2({
          query: q,
          kbIds: kbIds.length > 0 ? kbIds : undefined,
          docTypes: docTypes.length > 0 ? docTypes : undefined,
          projectId,
          tags: tags.length > 0 ? tags : undefined,
          topK: 30,
        }),
      )
    } catch (e) {
      showError(e, '检索失败')
    } finally {
      setSearching(false)
    }
  }, [query, kbIds, docTypes, projectId, tags])

  const resetFilters = () => {
    setKbIds([])
    setDocTypes([])
    setProjectId(undefined)
    setTags([])
  }

  const loadImports = useCallback(async () => {
    setImportsLoading(true)
    try {
      setImports(await listImports())
    } catch (e) {
      showError(e, '加载上传批次失败')
    } finally {
      setImportsLoading(false)
    }
  }, [])

  // 批次进度靠轮询（批次详情含逐文件状态）；运行中的批次由后端状态驱动，前端不做增量合并
  useEffect(() => {
    if (view !== 'imports') return
    loadImports()
    const timer = window.setInterval(loadImports, 3000)
    return () => window.clearInterval(timer)
  }, [view, loadImports])

  const onUploaded = (batchId: string) => {
    setUploadOpen(false)
    setView('imports')
    getImport(batchId)
      .then(() => loadImports())
      .catch(() => undefined)
  }

  const onRetry = async (batch: ImportBatch) => {
    setRetrying(batch.id)
    try {
      await retryImport(batch.id)
      message.success('已重新提交该批次')
      loadImports()
    } catch (e) {
      showError(e, '重试失败')
    } finally {
      setRetrying(null)
    }
  }

  const onRetryFile = async (batch: ImportBatch, file: ImportBatchFile) => {
    const key = `${batch.id}:${file.id}`
    setRetryingFile(key)
    try {
      await retryImportFile(batch.id, file.id)
      message.success(`已重新提交：${file.relativePath}`)
      loadImports()
    } catch (e) {
      showError(e, '单文件重试失败')
    } finally {
      setRetryingFile(null)
    }
  }

  const kbName = useMemo(() => {
    const map = new Map<number, string>()
    for (const b of bases) map.set(b.id, b.name)
    return map
  }, [bases])
  const admin = isAdmin()

  const hitColumns: ColumnsType<KnowledgeSearchV2Hit> = [
    {
      title: '文档',
      key: 'doc',
      width: 260,
      render: (_, r) => (
        <Space direction="vertical" size={2} style={{ width: '100%' }}>
          <Typography.Link onClick={() => setDetail({ entryId: r.entryId, hit: r })}>{r.title}</Typography.Link>
          <Typography.Text type="secondary" ellipsis style={{ fontSize: 12 }} title={r.sourceUri ?? r.path ?? ''}>
            {r.sourceUri ?? r.path ?? '-'}
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
      title: '知识库',
      dataIndex: 'kbName',
      width: 150,
      ellipsis: true,
      render: (n: string, r) => <Tag color="purple">{n || `#${r.kbId}`}</Tag>,
    },
    {
      title: '命中片段',
      dataIndex: 'snippet',
      render: (_: string, r) => {
        const snippets = (r.snippets?.length
          ? r.snippets
          : [
              {
                text: r.snippet,
                chunkId: r.chunkId,
                headingPath: r.headingPath,
                startOffset: r.startOffset,
                endOffset: r.endOffset,
              },
            ]
        ).slice(0, 3)
        return (
          <Space direction="vertical" size={6} style={{ width: '100%' }}>
            {snippets.map((s, i) => (
              <div
                key={`${s.chunkId}-${i}`}
                style={{ fontSize: 12, lineHeight: 1.7, color: '#595959', cursor: 'pointer' }}
                title="打开文档并定位该分块"
                onClick={(e) => {
                  e.stopPropagation()
                  setDetail({ entryId: r.entryId, hit: r, focusChunkId: s.chunkId })
                }}
              >
                {s.headingPath && (
                  <Tag color="default" style={{ marginInlineEnd: 6 }}>
                    {s.headingPath}
                  </Tag>
                )}
                <HighlightedSnippet text={s.text} />
              </div>
            ))}
            {(r.snippets?.length ?? 0) > 1 && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                共 {r.snippets.length} 段命中，展开行可看证据分块
              </Typography.Text>
            )}
          </Space>
        )
      },
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 160,
      render: (v: string) => fmtTime(v),
    },
  ]

  const importColumns: ColumnsType<ImportBatch> = [    {
      title: '批次',
      dataIndex: 'name',
      ellipsis: true,
      render: (n: string, r) => (
        <Space direction="vertical" size={2}>
          <span>{n}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {kbName.get(r.kbId) ?? `#${r.kbId}`} · {r.id.slice(0, 12)}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: string) => {
        const m = IMPORT_STATUS[s] ?? { color: 'default', text: s }
        return <Tag color={m.color}>{m.text}</Tag>
      },
    },
    {
      title: '结果',
      key: 'counts',
      width: 190,
      render: (_, r) => (
        <Space size={4} wrap>
          <Tag color="green">成功 {r.successCount}</Tag>
          {r.skippedCount > 0 && <Tag>跳过 {r.skippedCount}</Tag>}
          {r.failedCount > 0 && <Tag color="red">失败 {r.failedCount}</Tag>}
        </Space>
      ),
    },
    { title: '文件数', dataIndex: 'totalFiles', width: 80 },
    {
      title: '提交人',
      dataIndex: 'createdBy',
      width: 110,
      ellipsis: true,
    },
    { title: '时间', dataIndex: 'createdAt', width: 165, render: (v: string) => fmtTime(v) },
    {
      title: '操作',
      width: 100,
      render: (_, r) => (
        <Tooltip title={r.status === 'RUNNING' ? '批次运行中，结束后可重试失败项' : '失败/跳过的文件重新解析'}>
          <Button
            size="small"
            icon={<SyncOutlined />}
            loading={retrying === r.id}
            disabled={r.status === 'RUNNING' || r.failedCount === 0}
            onClick={() => onRetry(r)}
          >
            重试
          </Button>
        </Tooltip>
      ),
    },
  ]

  return (
    <Card
      title={
        <Space size={12}>
          <BookOutlined />
          <span>知识中心</span>
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'search', label: '全局检索' },
              { value: 'imports', label: '上传批次' },
              ...(admin ? [{ value: 'governance', label: '知识治理' }] : []),
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          {view === 'imports' && (
            <Button icon={<ReloadOutlined />} onClick={loadImports}>
              刷新
            </Button>
          )}
          {isAdmin() && (
            <Link to="/admin/knowledge">
              <Button icon={<DatabaseOutlined />}>库管理</Button>
            </Link>
          )}
          <Button type="primary" icon={<UploadOutlined />} onClick={() => setUploadOpen(true)}>
            上传文档
          </Button>
        </Space>
      }
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
    >
      <Typography.Paragraph type="secondary" style={{ marginTop: 0, marginBottom: 12, flexShrink: 0 }}>
        跨知识库搜技术文档、维护手册、FAQ 与开发产物：BM25 关键词 + 向量语义融合召回，按文档聚合展示命中片段。
        点标题查看全文与元数据；「上传文档」支持文件/文件夹/ZIP，异步解析后可在「上传批次」查看进度。
      </Typography.Paragraph>

      {view === 'search' && (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <Space direction="vertical" size={10} style={{ flexShrink: 0, marginBottom: 12 }}>
            <Input.Search
              allowClear
              size="large"
              prefix={<SearchOutlined />}
              placeholder="例如：生产环境 Tomcat 假死怎么排查？"
              enterButton="检索"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              loading={searching}
              onSearch={onSearch}
            />
            <Space size={8} wrap>
              <Select
                mode="multiple"
                allowClear
                placeholder="知识库（默认全部）"
                style={{ minWidth: 220, maxWidth: 360 }}
                value={kbIds}
                onChange={setKbIds}
                options={bases.map((b) => ({ value: b.id, label: b.name }))}
                maxTagCount="responsive"
              />
              <Select
                mode="multiple"
                allowClear
                placeholder="文档类型"
                style={{ minWidth: 180 }}
                value={docTypes}
                onChange={setDocTypes}
                options={DOC_TYPE_OPTIONS}
                maxTagCount="responsive"
              />
              <Select
                allowClear
                placeholder="所属项目"
                style={{ minWidth: 180 }}
                value={projectId}
                onChange={setProjectId}
                showSearch
                optionFilterProp="label"
                options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))}
              />
              <Select
                mode="tags"
                placeholder="标签过滤（回车添加）"
                style={{ minWidth: 200 }}
                value={tags}
                onChange={setTags}
                open={false}
              />
              <Button onClick={resetFilters} disabled={kbIds.length + docTypes.length + tags.length === 0 && !projectId}>
                重置过滤
              </Button>
            </Space>
          </Space>

          {result?.degradedReason && result.degradedReason !== 'NONE' && (
            <Alert
              style={{ marginBottom: 10, flexShrink: 0 }}
              type="warning"
              showIcon
              message="检索通道降级"
              description={degradedText[result.degradedReason] ?? result.degradedReason}
            />
          )}

          {result === null ? (
            <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <Empty description="输入问题或关键词开始检索；不选知识库时搜索全部启用中的库。" />
            </div>
          ) : result.hits.length === 0 ? (
            <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <Empty
                description={
                  <Space direction="vertical" size={4}>
                    <span>未命中任何文档。</span>
                    <Typography.Text type="secondary">
                      换个关键词，或放宽知识库/类型/标签过滤；也可以确认文档已上传且索引完成。
                    </Typography.Text>
                  </Space>
                }
              />
            </div>
          ) : (
            <>
              <Space size={8} style={{ marginBottom: 8, flexShrink: 0 }}>
                <Typography.Text type="secondary">
                  命中 {result.total} 篇文档{result.vector ? '（BM25 + 向量）' : '（BM25）'}
                </Typography.Text>
              </Space>
              <FitTable
                rowKey={(r) => String(r.entryId)}
                columns={hitColumns}
                dataSource={result.hits}
                pagination={LIST_PAGINATION}
                onRow={(r) => ({ onClick: () => setDetail({ entryId: r.entryId, hit: r }), style: { cursor: 'pointer' } })}
                expandable={{
                  rowExpandable: (r) => (r.chunks?.length ?? 0) > 0,
                  expandedRowRender: (r) => (
                    <Space direction="vertical" size={8} style={{ width: '100%' }}>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        证据分块（{r.chunks.length}）· 点击定位原文
                      </Typography.Text>
                      {r.chunks.map((c) => (
                        <div
                          key={c.chunkId}
                          style={{
                            border: '1px solid #f0f0f0',
                            borderRadius: 6,
                            padding: '6px 8px',
                            background: '#fafafa',
                            cursor: 'pointer',
                            fontSize: 12,
                            lineHeight: 1.6,
                          }}
                          onClick={(e) => {
                            e.stopPropagation()
                            setDetail({ entryId: r.entryId, hit: r, focusChunkId: c.chunkId })
                          }}
                        >
                          <Space size={6} wrap style={{ marginBottom: 4 }}>
                            <Tag color="blue">#{c.chunkIndex + 1}</Tag>
                            {c.headingPath && <Tag>{c.headingPath}</Tag>}
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              融合分 {c.score.toFixed(3)} · 原文 [{c.startOffset}, {c.endOffset})
                            </Typography.Text>
                          </Space>
                          <div style={{ color: '#595959' }}>
                            <HighlightedSnippet text={c.snippet} />
                          </div>
                        </div>
                      ))}
                    </Space>
                  ),
                }}
              />
            </>
          )}
        </div>
      )}

      {view === 'imports' && (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <Typography.Paragraph type="secondary" style={{ marginTop: 0, marginBottom: 12, flexShrink: 0 }}>
            每次上传生成一个批次，逐文件展示解析/入库结果；失败文件可单条重试，也可整批重试（按内容哈希幂等，不会重复入库）。
          </Typography.Paragraph>
          <FitTable<ImportBatch>
            rowKey="id"
            loading={importsLoading}
            columns={importColumns}
            dataSource={imports}
            pagination={LIST_PAGINATION}
            expandable={{
              expandedRowRender: (batch) => (
                <Table<ImportBatchFile>
                  rowKey="id"
                  size="small"
                  pagination={false}
                  dataSource={batch.files}
                  scroll={{ y: 240 }}
                  columns={[
                    {
                      title: '文件',
                      dataIndex: 'relativePath',
                      ellipsis: true,
                      render: (p: string) => <Typography.Text style={{ fontSize: 12 }}>{p}</Typography.Text>,
                    },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      width: 100,
                      render: (s: string) => {
                        const m = FILE_STATUS[s] ?? { color: 'default', text: s }
                        return <Tag color={m.color}>{m.text}</Tag>
                      },
                    },
                    {
                      title: '说明',
                      dataIndex: 'error',
                      render: (err: string | null, r) =>
                        err ? (
                          <Typography.Text type="danger" style={{ fontSize: 12 }}>
                            {err}
                          </Typography.Text>
                        ) : r.entryId ? (
                          <Typography.Link
                            style={{ fontSize: 12 }}
                            onClick={() => setDetail({ entryId: r.entryId as number })}
                          >
                            条目 #{r.entryId}
                          </Typography.Link>
                        ) : (
                          '-'
                        ),
                    },
                    {
                      title: '操作',
                      width: 86,
                      render: (_, r) =>
                        r.status === 'failed' ? (
                          <Button
                            size="small"
                            type="link"
                            loading={retryingFile === `${batch.id}:${r.id}`}
                            onClick={() => onRetryFile(batch, r)}
                          >
                            重试
                          </Button>
                        ) : null,
                    },
                  ]}
                />
              ),
              rowExpandable: (b) => b.files.length > 0,
            }}
            locale={{ emptyText: '暂无上传批次。点右上角「上传文档」导入文件/文件夹/ZIP。' }}
          />
        </div>
      )}

      {view === 'governance' && admin && (
        <GovernanceConsole bases={bases} onOpenEntry={(entryId) => setDetail({ entryId })} />
      )}

      <KnowledgeUploadDrawer open={uploadOpen} onClose={() => setUploadOpen(false)} onSubmitted={(r) => onUploaded(r.batchId)} />
      <KnowledgeEntryDrawer
        entryId={detail?.entryId}
        hit={detail?.hit}
        focusChunkId={detail?.focusChunkId}
        kbName={detail?.hit?.kbName}
        kbNames={kbName}
        onClose={closeDetail}
      />
    </Card>
  )
}
