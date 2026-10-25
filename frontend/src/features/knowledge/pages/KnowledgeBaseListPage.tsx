// CAP-86 v3 知识库（内容工作台）：库表格 + 全局统计 + 新建/上传入口；索引异常跳知识运营待办。
// 同时是聊天引用深链宿主（/admin/knowledge?entryId=&chunkId= → 打开文档详情抽屉）。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Button, Card, Segmented, Space, Tag, Tooltip, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import { listBases } from '../api'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import type { KnowledgeBase } from '../types'
import KnowledgeBaseFormModal from '../components/KnowledgeBaseFormModal'
import KnowledgeBaseSettingsDrawer from '../components/KnowledgeBaseSettingsDrawer'
import KnowledgeUploadDrawer from '../components/KnowledgeUploadDrawer'
import KnowledgeEntryDrawer from '../components/KnowledgeEntryDrawer'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { showError } from '../../../shared/utils/showError'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'

/** FR-08 索引健康一行：状态 + 数量，异常直达库详情处理（失败逐条重试 / 需重建一键重建） */
function IndexHealth({ base, onUpload }: { base: KnowledgeBase; onUpload: () => void }) {
  const s = base.indexStats
  const detailLink = `/admin/knowledge/bases/${base.id}?tab=entries`
  return (
    <Space size={4} wrap>
      <Tag color={s.ready === base.entryCount ? 'green' : 'blue'}>
        已索引 {s.ready}/{base.entryCount}
      </Tag>
      {s.pending > 0 && <Tag color="gold">待索引 {s.pending}</Tag>}
      {s.failed > 0 && (
        <Link to={`${detailLink}&indexStatus=failed`}>
          <Tooltip title="解析或向量化失败，进库详情筛选失败条目逐条重试">
            <Tag color="red">失败 {s.failed}</Tag>
          </Tooltip>
        </Link>
      )}
      {s.mismatched > 0 && (
        <Link to={detailLink}>
          <Tooltip title="这些文档的索引与当前向量端点不一致，检索命中不到；进库详情一键重建">
            <Tag color="orange">需重建 {s.mismatched}</Tag>
          </Tooltip>
        </Link>
      )}
      {s.disabled > 0 && (
        <Tooltip title="未配置向量端点：索引停用、检索走关键词降级。到「模型接入」配置端点">
          <Tag>未配置向量端点 {s.disabled}</Tag>
        </Tooltip>
      )}
      {base.entryCount === 0 && (
        <Typography.Link style={{ fontSize: 12 }} onClick={onUpload}>
          空库，去上传文档
        </Typography.Link>
      )}
    </Space>
  )
}

export default function KnowledgeBaseListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [loading, setLoading] = useState(false)
  const [projects, setProjects] = useState<Project[]>([])
  const [scopeFilter, setScopeFilter] = useState<'active' | 'archived'>('active')

  const [baseModalOpen, setBaseModalOpen] = useState(false)
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploadKbId, setUploadKbId] = useState<number | undefined>(undefined)

  const loadBases = useCallback(async () => {
    setLoading(true)
    try {
      setBases(await listBases())
    } catch (e) {
      showError(e, '加载知识库失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadBases()
    listProjects()
      .then(setProjects)
      .catch(() => undefined)
  }, [loadBases])

  // 深链 ?entryId=&chunkId=（聊天引用）：打开/关闭详情抽屉
  const entryIdRaw = Number(searchParams.get('entryId'))
  const entryId = Number.isFinite(entryIdRaw) && entryIdRaw > 0 ? entryIdRaw : undefined
  const chunkRaw = Number(searchParams.get('chunkId'))
  const focusChunkId = Number.isFinite(chunkRaw) && chunkRaw > 0 ? chunkRaw : undefined

  const closeEntry = () => {
    const next = new URLSearchParams(searchParams)
    next.delete('entryId')
    next.delete('chunkId')
    setSearchParams(next, { replace: true })
  }

  const archivedCount = useMemo(() => bases.filter((b) => b.status === 'archived').length, [bases])
  const shown = useMemo(
    () =>
      bases.filter((b) =>
        archivedCount === 0 ? true : scopeFilter === 'archived' ? b.status === 'archived' : b.status !== 'archived',
      ),
    [bases, scopeFilter, archivedCount],
  )
  const totals = useMemo(
    () => ({
      entries: bases.reduce((n, b) => n + b.entryCount, 0),
      ready: bases.reduce((n, b) => n + b.indexStats.ready, 0),
    }),
    [bases],
  )

  const kbNames = useMemo(() => {
    const map = new Map<number, string>()
    for (const b of bases) map.set(b.id, b.name)
    return map
  }, [bases])

  const openUpload = (kbId?: number) => {
    setUploadKbId(kbId)
    setUploadOpen(true)
  }

  // 设置抽屉用 URL 承载（?settings=<id>），便于从库详情旧链接直达
  const settingsRaw = Number(searchParams.get('settings'))
  const settingsBase = bases.find((b) => b.id === settingsRaw) ?? null

  const openSettings = (b: KnowledgeBase) => {
    const next = new URLSearchParams(searchParams)
    next.set('settings', String(b.id))
    setSearchParams(next, { replace: true })
  }

  const closeSettings = () => {
    const next = new URLSearchParams(searchParams)
    next.delete('settings')
    setSearchParams(next, { replace: true })
  }

  const columns: ColumnsType<KnowledgeBase> = [
    {
      title: '知识库',
      key: 'name',
      render: (_, r) => (
        <Space direction="vertical" size={2}>
          <Space size={6}>
            <Link to={`/admin/knowledge/bases/${r.id}`}>{r.name}</Link>
            {r.status === 'archived' && <Tag>已归档</Tag>}
          </Space>
          <Typography.Text type="secondary" ellipsis style={{ fontSize: 12 }} title={r.description ?? ''}>
            {r.description || '-'}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '范围 / 注入',
      key: 'scope',
      width: 180,
      render: (_, r) => (
        <Space size={4} wrap>
          <Tag color={r.scope === 'global' ? 'blue' : undefined}>
            {r.scope === 'global' ? '全局' : (r.projectName ?? r.projectId ?? '项目')}
          </Tag>
          <Tag color={r.injectMode === 'FULL' ? 'geekblue' : 'purple'}>
            {r.injectMode === 'FULL' ? '全量注入' : 'RAG 检索'}
          </Tag>
        </Space>
      ),
    },
    { title: '文档数', dataIndex: 'entryCount', width: 90 },
    {
      title: '索引健康',
      key: 'index',
      width: 320,
      render: (_, r) => <IndexHealth base={r} onUpload={() => openUpload(r.id)} />,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 165,
      render: (v: string) => fmtTime(v),
    },
    {
      title: '操作',
      key: 'actions',
      width: 240,
      render: (_, r) => (
        <Space size={2}>
          <Link to={`/admin/knowledge/bases/${r.id}`}>
            <Button size="small">详情</Button>
          </Link>
          <Button size="small" onClick={() => openSettings(r)}>
            设置
          </Button>
          <Link to={`/chats?kbId=${r.id}`}>
            <Button size="small">问答</Button>
          </Link>
          <Button size="small" onClick={() => openUpload(r.id)}>
            上传
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
          <span>知识库</span>
          {archivedCount > 0 && (
            <Segmented
              value={scopeFilter}
              onChange={(v) => setScopeFilter(v as 'active' | 'archived')}
              options={[
                { value: 'active', label: `启用中（${bases.length - archivedCount}）` },
                { value: 'archived', label: `已归档（${archivedCount}）` },
              ]}
            />
          )}
        </Space>
      }
      extra={
        <Space wrap>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={loadBases}>
            刷新
          </Button>
          <Button icon={<UploadOutlined />} onClick={() => openUpload()}>
            上传文档
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setBaseModalOpen(true)}>
            新建知识库
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12, flexShrink: 0 }}>
        共 {bases.length} 个库 · {totals.entries} 篇文档 · 已索引 {totals.ready}
        。这里管理库与内容（含索引异常处理）；待复核、负面反馈与质量报表到「知识运营」。
      </Typography.Paragraph>
      <FitTable<KnowledgeBase>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={shown}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: '还没有知识库。点右上角「新建知识库」创建第一个。' }}
      />

      <KnowledgeBaseFormModal
        open={baseModalOpen}
        projects={projects}
        onClose={() => setBaseModalOpen(false)}
        onSaved={() => loadBases()}
      />
      <KnowledgeUploadDrawer
        open={uploadOpen}
        presetKbId={uploadKbId}
        onClose={() => setUploadOpen(false)}
        onSubmitted={() => {
          setUploadOpen(false)
          loadBases()
          window.setTimeout(loadBases, 3000)
        }}
      />
      <KnowledgeBaseSettingsDrawer
        open={!!settingsBase}
        base={settingsBase}
        onClose={closeSettings}
        onSaved={(updated) => setBases((prev) => prev.map((b) => (b.id === updated.id ? updated : b)))}
        onDeleted={() => {
          closeSettings()
          loadBases()
        }}
      />
      {/* 聊天引用深链宿主：/admin/knowledge?entryId=&chunkId= */}
      <KnowledgeEntryDrawer
        entryId={entryId}
        focusChunkId={focusChunkId}
        kbNames={kbNames}
        onClose={closeEntry}
      />
    </Card>
  )
}
