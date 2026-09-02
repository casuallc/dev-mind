// CAP-04 知识库：条目管理 + 经验提案 inbox + 注入内容预览。
import { useCallback, useEffect, useState } from 'react'
import { canWrite } from '../../auth/authStore'
import {
  Button,
  Card,
  Form,
  Input,
  message,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import {
  BulbOutlined,
  DeleteOutlined,
  EditOutlined,
  FileAddOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import {
  adoptProposal,
  createEntry,
  createProposal,
  deleteEntry,
  listEntries,
  listProposals,
  previewInjection,
  rejectProposal,
  updateEntry,
} from '../api'
import type { KnowledgeEntry, KnowledgeEntryInput, KnowledgeProposal, PreviewResult } from '../types'
import { fmtTime } from '../../../shared/utils/format'

const scopeTag = (s: string) => (s === 'global' ? <Tag color="blue">global</Tag> : <Tag>project</Tag>)
const statusTag = (s: string) =>
  s === 'active' ? <Tag color="green">active</Tag> : s === 'deprecated' ? <Tag>deprecated</Tag> : <Tag color="orange">{s}</Tag>

export default function KnowledgeBase() {
  const [projects, setProjects] = useState<Project[]>([])
  const [entries, setEntries] = useState<KnowledgeEntry[]>([])
  const [entriesLoading, setEntriesLoading] = useState(false)
  const [proposals, setProposals] = useState<KnowledgeProposal[]>([])
  const [proposalsLoading, setProposalsLoading] = useState(false)
  const [searchQ, setSearchQ] = useState('')
  const [scopeFilter, setScopeFilter] = useState<string>('')

  // 条目编辑
  const [entryModalOpen, setEntryModalOpen] = useState(false)
  const [editingEntry, setEditingEntry] = useState<KnowledgeEntry | null>(null)
  const [entryForm] = Form.useForm<KnowledgeEntryInput>()
  const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null)
  const [previewForm] = Form.useForm()

  // 提案弹窗（手动沉淀）
  const [proposalModalOpen, setProposalModalOpen] = useState(false)
  const [proposalForm] = Form.useForm()

  const loadEntries = useCallback(async (q = searchQ, scope = scopeFilter) => {
    setEntriesLoading(true)
    try {
      setEntries(
        q.trim()
          ? await listEntries().then((all) =>
              all.filter(
                (e) =>
                  (e.name + e.contentMd + e.tags.join(',')).toLowerCase().includes(q.trim().toLowerCase()),
              ),
            )
          : await listEntries({ scope: scope || undefined }),
      )
    } catch (e) {
      message.error(`加载条目失败：${(e as Error).message}`)
    } finally {
      setEntriesLoading(false)
    }
  }, [searchQ, scopeFilter])

  const loadProposals = useCallback(async (status?: string) => {
    setProposalsLoading(true)
    try {
      setProposals(await listProposals(status))
    } catch (e) {
      message.error(`加载提案失败：${(e as Error).message}`)
    } finally {
      setProposalsLoading(false)
    }
  }, [])

  useEffect(() => {
    listProjects()
      .then(setProjects)
      .catch(() => undefined)
    loadEntries()
    loadProposals()
  }, [loadEntries, loadProposals])

  const openCreateEntry = () => {
    setEditingEntry(null)
    entryForm.resetFields()
    entryForm.setFieldsValue({ scope: 'global', status: 'active', tags: [] })
    setEntryModalOpen(true)
  }

  const openEditEntry = (e: KnowledgeEntry) => {
    setEditingEntry(e)
    entryForm.setFieldsValue({
      scope: e.scope,
      projectId: e.projectId ?? undefined,
      name: e.name,
      contentMd: e.contentMd,
      tags: e.tags,
      status: e.status,
    })
    setEntryModalOpen(true)
  }

  const onSaveEntry = async () => {
    const v = await entryForm.validateFields()
    try {
      if (editingEntry) {
        await updateEntry(editingEntry.id, v)
        message.success('条目已更新')
      } else {
        await createEntry(v)
        message.success('条目已创建')
      }
      setEntryModalOpen(false)
      loadEntries()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const onDeleteEntry = (e: KnowledgeEntry) => {
    Modal.confirm({
      centered: true,
      title: `删除条目「${e.name}」？`,
      content: '删除后无法恢复（不影响已生成的会话）。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteEntry(e.id)
          message.success('已删除')
          loadEntries()
        } catch (err) {
          message.error(`删除失败：${(err as Error).message}`)
        }
      },
    })
  }

  const onAdopt = (p: KnowledgeProposal, target: 'project' | 'global') => {
    Modal.confirm({
      centered: true,
      title: `采纳「${p.title}」${target === 'global' ? '到全局' : '到项目'}`,
      content:
        target === 'global'
          ? '将作为 global 经验条目，后续所有项目会话都可能注入。'
          : `将创建 project 范围条目（项目 ${p.targetProjectId ?? '待定'}）。`,
      okText: '采纳',
      cancelText: '取消',
      onOk: async () => {
        try {
          await adoptProposal(p.id, target, p.targetProjectId ?? undefined)
          message.success('已采纳为知识条目')
          loadProposals()
        } catch (err) {
          message.error(`采纳失败：${(err as Error).message}`)
        }
      },
    })
  }

  const onReject = (p: KnowledgeProposal) => {
    Modal.confirm({
      centered: true,
      title: `拒绝提案「${p.title}」？`,
      content: '提案将被标记为 rejected，不会进入知识库。',
      okText: '拒绝',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await rejectProposal(p.id)
          message.success('已拒绝')
          loadProposals()
        } catch (err) {
          message.error(`操作失败：${(err as Error).message}`)
        }
      },
    })
  }

  const onPreview = async () => {
    const v = await previewForm.validateFields()
    try {
      const r = await previewInjection(v.projectId, v.taskSpec)
      setPreviewResult(r)
    } catch (e) {
      message.error(`预览失败：${(e as Error).message}`)
    }
  }

  const onManualProposal = async () => {
    const v = await proposalForm.validateFields()
    try {
      await createProposal({
        title: v.title,
        contentMd: v.contentMd,
        targetScope: v.targetScope ?? 'project',
        targetProjectId: v.targetProjectId,
      })
      message.success('提案已提交，等待审核')
      setProposalModalOpen(false)
      proposalForm.resetFields()
      loadProposals()
    } catch (e) {
      message.error(`提交失败：${(e as Error).message}`)
    }
  }

  const entryColumns: ColumnsType<KnowledgeEntry> = [
    { title: '名称', dataIndex: 'name', ellipsis: true },
    { title: '范围', dataIndex: 'scope', width: 90, render: scopeTag },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 180,
      render: (tags: string[]) =>
        tags.length ? tags.map((t) => <Tag key={t}>{t}</Tag>) : <Typography.Text type="secondary">-</Typography.Text>,
    },
    { title: '项目', dataIndex: 'projectId', width: 110, render: (v) => v ?? '-' },
    { title: '注入次数', dataIndex: 'hitCount', width: 90 },
    { title: '状态', dataIndex: 'status', width: 100, render: statusTag },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 150,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEditEntry(r)}>
            编辑
          </Button>
          <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onDeleteEntry(r)} />
        </Space>
      ),
    },
  ]

  const proposalColumns: ColumnsType<KnowledgeProposal> = [
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '去向', dataIndex: 'targetScope', width: 90, render: scopeTag },
    { title: '来源会话', dataIndex: 'sourceSessionId', width: 130, render: (v) => v ?? '-' },
    { title: '状态', dataIndex: 'status', width: 100, render: statusTag },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 260,
      render: (_, r) =>
        r.status === 'open' ? (
          <Space size={4}>
            <Button size="small" type="primary" onClick={() => onAdopt(r, 'project')}>
              采纳到项目
            </Button>
            <Button size="small" onClick={() => onAdopt(r, 'global')}>
              晋升全局
            </Button>
            <Button size="small" danger onClick={() => onReject(r)}>
              拒绝
            </Button>
          </Space>
        ) : (
          <Typography.Text type="secondary">已处理</Typography.Text>
        ),
    },
  ]

  const projectOptions = projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Tabs
        items={[
          {
            key: 'entries',
            label: '知识条目',
            children: (
              <Card size="small">
                <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }}>
                  <Space>
                    <Input
                      allowClear
                      prefix={<SearchOutlined />}
                      placeholder="搜索名称/内容/标签"
                      style={{ width: 240 }}
                      value={searchQ}
                      onChange={(e) => setSearchQ(e.target.value)}
                      onPressEnter={() => loadEntries(searchQ, scopeFilter)}
                    />
                    <Select
                      allowClear
                      placeholder="范围"
                      style={{ width: 110 }}
                      value={scopeFilter || undefined}
                      onChange={(v) => {
                        setScopeFilter(v ?? '')
                        loadEntries(searchQ, v ?? '')
                      }}
                      options={[
                        { value: 'global', label: 'global' },
                        { value: 'project', label: 'project' },
                      ]}
                    />
                  </Space>
                  {canWrite() && (
                    <Button type="primary" icon={<FileAddOutlined />} onClick={openCreateEntry}>
                      新增条目
                    </Button>
                  )}
                </Space>
                <Table
                  size="small"
                  rowKey="id"
                  loading={entriesLoading}
                  columns={entryColumns}
                  dataSource={entries}
                  pagination={{ pageSize: 20, showSizeChanger: false }}
                />
              </Card>
            ),
          },
          {
            key: 'proposals',
            label: '经验提案',
            children: (
              <Card size="small">
                <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }}>
                  <Typography.Text type="secondary">
                    会话中「沉淀经验」或手动提交的经验，审核后进入知识库（inbox）。
                  </Typography.Text>
                  <Button icon={<BulbOutlined />} onClick={() => setProposalModalOpen(true)}>
                    手动沉淀经验
                  </Button>
                </Space>
                <Table
                  size="small"
                  rowKey="id"
                  loading={proposalsLoading}
                  columns={proposalColumns}
                  dataSource={proposals}
                  pagination={{ pageSize: 10, showSizeChanger: false }}
                />
              </Card>
            ),
          },
          {
            key: 'preview',
            label: '注入预览',
            children: (
              <Card size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Form form={previewForm} layout="inline">
                    <Form.Item name="projectId" label="项目">
                      <Select allowClear placeholder="选择项目" style={{ width: 240 }} options={projectOptions} />
                    </Form.Item>
                    <Form.Item name="taskSpec" label="任务说明" style={{ flex: 1, minWidth: 240 }}>
                      <Input placeholder="本次会话要做什么？（将写入 ## 当前任务）" />
                    </Form.Item>
                    <Button type="primary" onClick={onPreview}>
                      预览注入内容
                    </Button>
                  </Form>
                  {previewResult && (
                    <>
                      <Typography.Text>
                        命中条目（{previewResult.entriesUsed.length}）：
                        {previewResult.entriesUsed.length
                          ? previewResult.entriesUsed.map((e) => (
                              <Tag key={e.id} color={e.scope === 'global' ? 'blue' : undefined}>
                                {e.name}
                              </Tag>
                            ))
                          : '无'}
                      </Typography.Text>
                      <pre
                        style={{
                          whiteSpace: 'pre-wrap',
                          background: '#f6f6f6',
                          padding: 12,
                          borderRadius: 4,
                          fontSize: 12,
                          maxHeight: 480,
                          overflow: 'auto',
                        }}
                      >
                        {previewResult.content || '(空)'}
                      </pre>
                    </>
                  )}
                </Space>
              </Card>
            ),
          },
        ]}
      />

      {/* 条目编辑弹窗 */}
      <Modal
        title={editingEntry ? '编辑条目' : '新增条目'}
        open={entryModalOpen}
        onCancel={() => setEntryModalOpen(false)}
        onOk={onSaveEntry}
        width={640}
      >
        <Form form={entryForm} labelCol={{ span: 5 }} wrapperCol={{ span: 18 }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请填写名称' }]}>
            <Input placeholder="如：AntD 表格固定列写法" />
          </Form.Item>
          <Form.Item name="scope" label="范围" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'global', label: 'global（所有项目可注入）' },
                { value: 'project', label: 'project（本项目注入）' },
              ]}
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) =>
              getFieldValue('scope') === 'project' && (
                <Form.Item
                  name="projectId"
                  label="项目"
                  rules={[{ required: true, message: '请选择项目' }]}
                >
                  <Select showSearch optionFilterProp="label" placeholder="选择项目" options={projectOptions} />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="tags" label="标签">
            <Select mode="tags" placeholder="回车添加标签（global 条目会按项目 tags 匹配注入）" open={false} />
          </Form.Item>
          <Form.Item name="contentMd" label="内容" rules={[{ required: true, message: '请填写 Markdown 内容' }]}>
            <Input.TextArea rows={8} placeholder="Markdown 内容，注入时整段追加" />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 'active', label: 'active（启用注入）' },
                { value: 'deprecated', label: 'deprecated（停用）' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 手动沉淀经验弹窗 */}
      <Modal
        title="沉淀经验"
        open={proposalModalOpen}
        onCancel={() => setProposalModalOpen(false)}
        onOk={onManualProposal}
      >
        <Form form={proposalForm} labelCol={{ span: 5 }} wrapperCol={{ span: 18 }}>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请填写标题' }]}>
            <Input placeholder="如：Maven 多模块增量编译实践" />
          </Form.Item>
          <Form.Item name="targetScope" label="去向" initialValue="project">
            <Select
              options={[
                { value: 'project', label: '项目经验' },
                { value: 'global', label: '全局经验' },
              ]}
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {({ getFieldValue }) =>
              getFieldValue('targetScope') === 'project' && (
                <Form.Item name="targetProjectId" label="目标项目">
                  <Select allowClear showSearch optionFilterProp="label" placeholder="选择项目" options={projectOptions} />
                </Form.Item>
              )
            }
          </Form.Item>
          <Form.Item name="contentMd" label="内容" rules={[{ required: true, message: '请填写内容' }]}>
            <Input.TextArea rows={6} placeholder="经验内容（Markdown），审核采纳后成为知识条目" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
