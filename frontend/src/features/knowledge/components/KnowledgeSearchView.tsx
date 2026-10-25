// CAP-86 跨库检索：工作台默认视图。BM25 + 向量 RRF，文档级聚合 + 高亮片段 + 证据分块。
import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Empty, Input, Select, Space, Tag, Typography, message } from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { searchV2 } from '../api'
import type {
  DocType,
  KnowledgeBase,
  KnowledgeSearchV2Hit,
  KnowledgeSearchV2Result,
} from '../types'
import { DOC_TYPE_LABEL, DOC_TYPE_OPTIONS } from '../types'
import type { Project } from '../../projects/types'
import FitTable from '../../../shared/components/FitTable'
import HighlightedSnippet from './HighlightedSnippet'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const DEGRADED_TEXT: Record<string, string> = {
  NO_EMBEDDING: '无可用向量端点，本次为 BM25 关键词检索（结果仍可用，语义相近的文档可能漏召）；到「模型接入」配置 Embedding 端点后重建索引即可走向量检索',
  DIMENSION_MISMATCH: '向量维度与当前端点不一致，本次已降级为 BM25 关键词检索（到库详情「文档」页签一键重建需要重建的索引即可恢复）',
  INDEX_EMPTY: '索引为空：尚未建立知识索引，请先到「文档导入」上传内容，或到「治理报告」检查索引健康度',
}

export default function KnowledgeSearchView({
  bases,
  projects,
  presetKbId,
  onOpenEntry,
}: {
  bases: KnowledgeBase[]
  projects: Project[]
  /** FR-04：?kbId= 预选单库（替代原库内「检索测试」页签） */
  presetKbId?: number
  onOpenEntry: (entryId: number, hit?: KnowledgeSearchV2Hit, focusChunkId?: number) => void
}) {
  const [query, setQuery] = useState('')
  const [kbIds, setKbIds] = useState<number[]>(presetKbId ? [presetKbId] : [])

  // 预选库可能随 URL 变化（库详情「检索测试」入口跳来）：跟随覆盖
  useEffect(() => {
    if (presetKbId) setKbIds([presetKbId])
  }, [presetKbId])
  const [docTypes, setDocTypes] = useState<DocType[]>([])
  const [projectId, setProjectId] = useState<string | undefined>(undefined)
  const [tags, setTags] = useState<string[]>([])
  const [result, setResult] = useState<KnowledgeSearchV2Result | null>(null)
  const [searching, setSearching] = useState(false)

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

  const hitColumns: ColumnsType<KnowledgeSearchV2Hit> = [
    {
      title: '文档',
      key: 'doc',
      width: 260,
      render: (_, r) => (
        <Space direction="vertical" size={2} style={{ width: '100%' }}>
          <Typography.Link onClick={() => onOpenEntry(r.entryId, r)}>{r.title}</Typography.Link>
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
                  onOpenEntry(r.entryId, r, s.chunkId)
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

  return (
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
            options={bases.filter((b) => b.status === 'active').map((b) => ({ value: b.id, label: b.name }))}
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
          <Button
            onClick={resetFilters}
            disabled={kbIds.length + docTypes.length + tags.length === 0 && !projectId}
          >
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
          description={DEGRADED_TEXT[result.degradedReason] ?? result.degradedReason}
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
            onRow={(r) => ({
              onClick: () => onOpenEntry(r.entryId, r),
              style: { cursor: 'pointer' },
            })}
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
                        onOpenEntry(r.entryId, r, c.chunkId)
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
  )
}
