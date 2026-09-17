// CAP-44 知识库详情：条目管理（MarkdownEditor 抽屉编辑/索引状态/重建索引）+ 检索测试 + 库设置。
import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
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
  listBaseEntries,
  reindexEntry,
  searchChunks,
  updateBase,
  updateEntry,
} from '../api'
import type {
  IndexStatus,
  KnowledgeBase,
  KnowledgeBaseInput,
  KnowledgeEntry,
  KnowledgeEntryInput,
  KnowledgeSearchResult,
} from '../types'
import MarkdownEditor from '../../../shared/components/MarkdownEditor'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'
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
      <Tooltip title="未配置 embedding，检索走关键词降级">
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

  const [base, setBase] = useState<KnowledgeBase | null>(null)
  const [entries, setEntries] = useState<KnowledgeEntry[]>([])
  const [entriesLoading, setEntriesLoading] = useState(false)
  const [view, setView] = useState<string>('entries') // entries | search | settings

  // 条目编辑
  const [entryDrawerOpen, setEntryDrawerOpen] = useState(false)
  const [editingEntry, setEditingEntry] = useState<KnowledgeEntry | null>(null)
  const [entryForm] = Form.useForm<KnowledgeEntryInput>()

  // 检索测试
  const [searchQ, setSearchQ] = useState('')
  const [searchTopK, setSearchTopK] = useState<number>(8)
  const [searching, setSearching] = useState(false)
  const [searchResult, setSearchResult] = useState<KnowledgeSearchResult | null>(null)

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

  useEffect(() => {
    if (base && view === 'settings') {
      settingsForm.setFieldsValue({
        name: base.name,
        description: base.description ?? undefined,
        injectMode: base.injectMode,
        embeddingModel: base.embeddingModel ?? undefined,
        status: base.status,
      })
    }
  }, [base, view, settingsForm])

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
      width: 200,
      render: (_, r) => (
        <Space size={4}>
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
      styles={{ body: pageCardBodyScrollStyle }}
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
              {canWrite() && (
                <Button type="primary" icon={<PlusOutlined />} onClick={openCreateEntry}>
                  新增条目
                </Button>
              )}
            </>
          )}
        </Space>
      }
    >
      {view === 'entries' && (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            {base?.injectMode === 'FULL'
              ? 'FULL 库条目在会话启动时全量注入 CLAUDE.md（global 库按项目标签过滤）；内容保存后自动重建向量索引。'
              : 'RAG 库条目不参与启动注入，仅供向量检索按提问召回；内容保存后自动重建向量索引。'}
          </Typography.Paragraph>
          <Table
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
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {!searchResult.vector && (
                <Alert
                  type="warning"
                  showIcon
                  message="embedding 未配置，本次检索为关键词 LIKE 降级（score 恒为 0）"
                />
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

      {view === 'settings' && base && (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            库的基本设置；归档后不再参与注入与检索。
          </Typography.Paragraph>
          <Form form={settingsForm} labelCol={{ span: 4 }} wrapperCol={{ span: 12 }} style={{ maxWidth: 720 }}>
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
              name="embeddingModel"
              label="向量模型"
              extra="留空用平台默认（devmind.knowledge.embedding.model）"
            >
              <Input allowClear placeholder="可选，覆盖平台默认 embedding 模型" />
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
