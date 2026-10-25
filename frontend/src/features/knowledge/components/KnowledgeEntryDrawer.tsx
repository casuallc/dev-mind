// CAP-84 FR-15 文档详情抽屉：概览（元数据 + 命中片段）/ 全文 / 分块 / 版本，共用检索与深链入口。
// 深链 /admin/knowledge?entryId=123&chunkId=456（聊天引用）打开后直接定位到分块页签并滚动到对应块。
import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Modal,
  Space,
  Spin,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { Link } from 'react-router-dom'
import {
  CheckCircleOutlined,
  FileTextOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import {
  getEntry,
  getEntryVersion,
  listEntryChunks,
  listEntryVersions,
  reindexEntry,
  reviewEntry,
  runEntryEnrich,
} from '../api'
import type {
  KnowledgeChunk,
  KnowledgeEntry,
  KnowledgeSearchV2Hit,
  KnowledgeVersion,
  KnowledgeVersionDetail,
} from '../types'
import { DOC_TYPE_LABEL } from '../types'
import Markdown from '../../../shared/components/Markdown'
import HighlightedSnippet from './HighlightedSnippet'
import { fmtTime } from '../../../shared/utils/format'
import { isAdmin } from '../../auth/authStore'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'

type TabKey = 'overview' | 'content' | 'chunks' | 'versions'

export default function KnowledgeEntryDrawer({
  entryId,
  hit,
  focusChunkId,
  kbName,
  kbNames,
  onClose,
}: {
  entryId?: number
  hit?: KnowledgeSearchV2Hit
  /** FR-19 深链：聊天引用携带的 chunkId，打开后定位分块 */
  focusChunkId?: number
  kbName?: string
  /** 深链无命中快照时，用库名映射补全「所属知识库」显示 */
  kbNames?: Map<number, string>
  onClose: () => void
}) {
  const [entry, setEntry] = useState<KnowledgeEntry | null>(null)
  const [chunks, setChunks] = useState<KnowledgeChunk[]>([])
  const [versions, setVersions] = useState<KnowledgeVersion[]>([])
  const [loading, setLoading] = useState(false)
  const [tabLoading, setTabLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<TabKey>('overview')
  const [reindexing, setReindexing] = useState(false)
  const [reviewing, setReviewing] = useState(false)
  const [enriching, setEnriching] = useState(false)
  const [versionDetail, setVersionDetail] = useState<KnowledgeVersionDetail | null>(null)
  const [versionLoading, setVersionLoading] = useState(false)

  useEffect(() => {
    if (entryId == null) {
      setEntry(null)
      setChunks([])
      setVersions([])
      setError(null)
      setVersionDetail(null)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    setTab(focusChunkId ? 'chunks' : 'overview')
    setTabLoading(true)
    getEntry(entryId)
      .then((e) => {
        if (!cancelled) setEntry(e)
      })
      .catch((e) => {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : '加载文档失败')
          showError(e, '加载文档失败')
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    Promise.allSettled([listEntryChunks(entryId), listEntryVersions(entryId)]).then(([c, v]) => {
      if (cancelled) return
      if (c.status === 'fulfilled') setChunks(c.value)
      else showError(c.reason, '加载分块失败')
      if (v.status === 'fulfilled') setVersions(v.value)
      else showError(v.reason, '加载版本失败')
      setTabLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [entryId, focusChunkId])

  // 深链定位：分块已加载且页签为分块时滚动到目标块；块不存在（索引已更新）给出提示
  useEffect(() => {
    if (tab !== 'chunks' || !focusChunkId || chunks.length === 0) return
    const timer = window.setTimeout(() => {
      document.getElementById(`knowledge-chunk-${focusChunkId}`)?.scrollIntoView({ block: 'center' })
    }, 120)
    return () => window.clearTimeout(timer)
  }, [tab, focusChunkId, chunks])

  const docType = entry?.docType ?? hit?.docType
  const baseName = kbName ?? hit?.kbName ?? (entry?.kbId ? kbNames?.get(entry.kbId) : undefined)
  const kbLabel = baseName ?? (entry?.kbId ? `#${entry.kbId}` : null)
  const focusMissing = !!focusChunkId && chunks.length > 0 && !chunks.some((c) => c.id === focusChunkId)

  const onReindex = async () => {
    if (!entryId) return
    setReindexing(true)
    try {
      const updated = await reindexEntry(entryId)
      setEntry(updated)
      message.success('已提交重建索引，稍后刷新查看结果')
    } catch (e) {
      showError(e, '重建索引失败')
    } finally {
      setReindexing(false)
    }
  }

  const onReview = async () => {
    if (!entryId) return
    setReviewing(true)
    try {
      setEntry(await reviewEntry(entryId))
      message.success('已登记复核')
    } catch (e) {
      showError(e, '登记复核失败')
    } finally {
      setReviewing(false)
    }
  }

  const onEnrich = async () => {
    if (!entryId) return
    setEnriching(true)
    try {
      await runEntryEnrich(entryId)
      message.success('已提交自动补全任务，稍后重新打开可查看结果')
    } catch (e) {
      showError(e, '提交补全任务失败')
    } finally {
      setEnriching(false)
    }
  }

  const openVersion = async (v: number) => {
    if (!entryId) return
    setVersionLoading(true)
    try {
      setVersionDetail(await getEntryVersion(entryId, v))
    } catch (e) {
      showError(e, '加载版本正文失败')
    } finally {
      setVersionLoading(false)
    }
  }

  const overview = entry && (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {hit && (
        <Alert
          type="success"
          showIcon
          message={
            <Space size={8} wrap>
              <span>检索命中片段（{hit.snippets.length || 1}）</span>
              {hit.kbName && <Tag color="purple">{hit.kbName}</Tag>}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                相关度 {hit.score.toFixed(3)} · 命中 {hit.chunkCount} 块
              </Typography.Text>
              {hit.chunks.length > 0 && (
                <Button size="small" type="link" onClick={() => setTab('chunks')}>
                  查看分块证据
                </Button>
              )}
            </Space>
          }
          description={
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              {(hit.snippets.length > 0
                ? hit.snippets
                : [
                    {
                      text: hit.snippet,
                      chunkId: hit.chunkId,
                      headingPath: hit.headingPath,
                      startOffset: hit.startOffset,
                      endOffset: hit.endOffset,
                    },
                  ]
              ).map((s, i) => (
                <div key={`${s.chunkId}-${i}`} style={{ lineHeight: 1.8 }}>
                  {s.headingPath && <Tag style={{ marginBottom: 4 }}>{s.headingPath}</Tag>}
                  <HighlightedSnippet text={s.text} />
                </div>
              ))}
            </Space>
          }
        />
      )}
      <Descriptions
        size="small"
        column={2}
        bordered
        items={[
          ...(kbLabel
            ? [
                {
                  key: 'kb',
                  label: '所属知识库',
                  children:
                    entry?.kbId && isAdmin() ? (
                      <Link to={`/admin/knowledge/bases/${entry.kbId}`}>{kbLabel}</Link>
                    ) : (
                      kbLabel
                    ),
                },
              ]
            : []),
          {
            key: 'path',
            label: '来源路径',
            children: entry.sourceUri ?? entry.path ?? '-',
            span: 2,
          },
          {
            key: 'version',
            label: '内容版本',
            children: `v${entry.contentVersion ?? 1}`,
          },
          {
            key: 'updated',
            label: '更新时间',
            children: fmtTime(entry.updatedAt),
          },
          {
            key: 'index',
            label: '索引状态',
            children:
              entry.indexStatus === 'ready' ? (
                <Tag color="green">已索引</Tag>
              ) : entry.indexStatus === 'pending' ? (
                <Tag color="gold">待索引</Tag>
              ) : entry.indexStatus === 'failed' ? (
                <Tag color="red">索引失败</Tag>
              ) : (
                <Tag>未启用</Tag>
              ),
          },
          {
            key: 'mime',
            label: '文件类型',
            children: entry.mimeType ?? '-',
          },
          {
            key: 'owner',
            label: '负责人',
            children: entry.owner ?? '-',
          },
          {
            key: 'valid',
            label: '有效期',
            children:
              entry.validFrom || entry.validTo
                ? `${entry.validFrom ? fmtTime(entry.validFrom) : '不限'} ~ ${entry.validTo ? fmtTime(entry.validTo) : '不限'}`
                : '长期有效',
          },
          {
            key: 'reviewed',
            label: '最近复核',
            children: entry.lastReviewedAt ? fmtTime(entry.lastReviewedAt) : '未复核',
          },
          {
            key: 'enrich',
            label: '自动增强',
            children: !entry.autoEnrich ? (
              <Tooltip title="已人工修正分类/摘要/标签，自动增强不会覆盖">
                <Tag>人工锁定</Tag>
              </Tooltip>
            ) : entry.enrichError ? (
              <Tooltip title={entry.enrichError}>
                <Tag color="red">上次失败</Tag>
              </Tooltip>
            ) : entry.enrichedAt ? (
              <Tag color="green">已增强 {fmtTime(entry.enrichedAt)}</Tag>
            ) : (
              <Tag color="gold">待增强</Tag>
            ),
          },
          {
            key: 'tags',
            label: '标签',
            children:
              entry.tags.length > 0 ? (
                <Space size={4} wrap>
                  {entry.tags.map((t) => (
                    <Tag key={t}>{t}</Tag>
                  ))}
                </Space>
              ) : (
                '-'
              ),
            span: 2,
          },
        ]}
      />
      {entry.summary && (
        <div>
          <Typography.Text strong>摘要</Typography.Text>
          <Typography.Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
            {entry.summary}
          </Typography.Paragraph>
        </div>
      )}
      {entry.indexError && <Alert type="error" showIcon message="索引错误" description={entry.indexError} />}
      {entry.enrichError && (
        <Alert type="warning" showIcon message="自动增强失败" description={entry.enrichError} />
      )}
    </Space>
  )

  const chunkPanel = (
    <Spin spinning={tabLoading}>
      {focusMissing && (
        <Alert
          style={{ marginBottom: 10 }}
          type="warning"
          showIcon
          message="引用的分块已不存在"
          description="文档可能已更新或重建索引；下方为当前最新分块，可按标题路径在全文里核对。"
        />
      )}
      {chunks.length === 0 ? (
        <Empty description="暂无分块。文档可能尚未完成索引，可在右上角重建索引。" />
      ) : (
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Typography.Text type="secondary">
            共 {chunks.length} 块 · 分块是检索与引用定位的最小单位
          </Typography.Text>
          {chunks.map((c) => {
            const focused = c.id === focusChunkId
            return (
              <div
                key={c.id}
                id={`knowledge-chunk-${c.id}`}
                style={{
                  border: `1px solid ${focused ? '#1677ff' : '#f0f0f0'}`,
                  background: focused ? '#f0f7ff' : '#fafafa',
                  borderRadius: 8,
                  padding: '10px 12px',
                }}
              >
                <Space size={8} wrap style={{ marginBottom: 6 }}>
                  <Tag color="blue">#{c.chunkIndex + 1}</Tag>
                  {c.headingPath && <Tag>{c.headingPath}</Tag>}
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    token {c.tokenCount} · 原文 [{c.startOffset}, {c.endOffset})
                  </Typography.Text>
                  {focused && <Tag color="processing">当前引用</Tag>}
                </Space>
                <Typography.Paragraph
                  style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12, lineHeight: 1.7 }}
                >
                  {c.content}
                </Typography.Paragraph>
              </div>
            )
          })}
        </Space>
      )}
    </Spin>
  )

  const versionPanel = (
    <Spin spinning={tabLoading}>
      {versions.length === 0 ? (
        <Empty description="暂无历史版本" />
      ) : (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          {versions.map((v) => (
            <div
              key={v.id}
              style={{ display: 'flex', alignItems: 'center', gap: 12, borderBottom: '1px solid #f5f5f5', padding: '8px 0' }}
            >
              <Tag color={v.contentVersion === entry?.contentVersion ? 'green' : 'default'}>
                v{v.contentVersion}
              </Tag>
              <div style={{ flex: 1, minWidth: 0 }}>
                <Typography.Text ellipsis style={{ display: 'block' }}>
                  {v.changeNote || '（无变更说明）'}
                </Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {v.createdBy || '-'} · {fmtTime(v.createdAt)}
                </Typography.Text>
              </div>
              <Button size="small" onClick={() => openVersion(v.contentVersion)}>
                查看正文
              </Button>
            </div>
          ))}
        </Space>
      )}
    </Spin>
  )

  return (
    <Drawer
      open={entryId != null}
      onClose={onClose}
      width={DRAWER_SCREEN}
      title={
        <Space size={8}>
          <FileTextOutlined />
          <span>{entry?.name ?? hit?.title ?? '文档详情'}</span>
          {docType && <Tag color="blue">{DOC_TYPE_LABEL[docType] ?? docType}</Tag>}
        </Space>
      }
      extra={
        entry ? (
          <Space>
            <Button icon={<CheckCircleOutlined />} loading={reviewing} onClick={onReview}>
              登记复核
            </Button>
            {isAdmin() && (
              <Button icon={<ThunderboltOutlined />} loading={enriching} onClick={onEnrich}>
                补全元数据
              </Button>
            )}
            {isAdmin() && (
              <Button icon={<ReloadOutlined />} loading={reindexing} onClick={onReindex}>
                重建索引
              </Button>
            )}
          </Space>
        ) : undefined
      }
    >
      {loading && (
        <div style={{ padding: 48, textAlign: 'center' }}>
          <Spin />
        </div>
      )}
      {error && !loading && <Alert type="error" showIcon message="加载失败" description={error} />}
      {entry && !loading && (
        <Tabs
          activeKey={tab}
          onChange={(k) => setTab(k as TabKey)}
          items={[
            { key: 'overview', label: '概览', children: overview },
            {
              key: 'content',
              label: '全文',
              children: <Markdown content={entry.contentMd} emptyText="文档暂无正文" />,
            },
            { key: 'chunks', label: `分块（${chunks.length}）`, children: chunkPanel },
            { key: 'versions', label: `版本（${versions.length}）`, children: versionPanel },
          ]}
        />
      )}
      <Modal
        open={!!versionDetail || versionLoading}
        title={versionDetail ? `v${versionDetail.contentVersion} · ${versionDetail.changeNote ?? '历史版本'}` : '加载版本…'}
        footer={null}
        width={900}
        onCancel={() => setVersionDetail(null)}
      >
        <Spin spinning={versionLoading}>
          {versionDetail && (
            <>
              <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                {versionDetail.createdBy || '-'} · {fmtTime(versionDetail.createdAt)}
              </Typography.Paragraph>
              <div style={{ maxHeight: '65vh', overflow: 'auto' }}>
                <Markdown content={versionDetail.contentMd} emptyText="该版本无正文" />
              </div>
            </>
          )}
        </Spin>
      </Modal>
    </Drawer>
  )
}
