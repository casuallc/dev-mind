// CAP-44 知识库列表：库容器管理（新建/编辑/删除）+ 经验提案 inbox + 注入内容预览。
// 条目管理与检索测试在库详情页（/admin/knowledge/bases/:id）。
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { canWrite } from '../../auth/authStore'
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  message,
  Modal,
  Segmented,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import { BulbOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import {
  adoptProposal,
  createBase,
  createProposal,
  deleteBase,
  listBases,
  listProposals,
  previewInjection,
  rejectProposal,
  updateBase,
} from '../api'
import type {
  InjectMode,
  KnowledgeBase,
  KnowledgeBaseInput,
  KnowledgeProposal,
  PreviewResult,
} from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardBodyFlexStyle, pageCardStyle, pagePaneScrollStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const scopeTag = (s: string) => (s === 'global' ? <Tag color="blue">global</Tag> : <Tag>project</Tag>)
const injectModeTag = (m: InjectMode) =>
  m === 'FULL' ? <Tag color="geekblue">FULL 全量注入</Tag> : <Tag color="purple">RAG 检索</Tag>
const baseStatusTag = (s: string) =>
  s === 'active' ? <Tag color="green">active</Tag> : <Tag>archived</Tag>
const statusTag = (s: string) =>
  s === 'active' ? <Tag color="green">active</Tag> : s === 'open' ? <Tag color="gold">open</Tag>
    : s === 'adopted' ? <Tag color="green">adopted</Tag> : s === 'rejected' ? <Tag>rejected</Tag>
    : s === 'deprecated' ? <Tag>deprecated</Tag> : <Tag color="orange">{s}</Tag>

export default function KnowledgeBaseList() {
  const [projects, setProjects] = useState<Project[]>([])
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [basesLoading, setBasesLoading] = useState(false)
  const [proposals, setProposals] = useState<KnowledgeProposal[]>([])
  const [proposalsLoading, setProposalsLoading] = useState(false)
  const [view, setView] = useState<string>('bases') // bases | proposals | preview

  // 提案管理抽屉：引用实时列表数据，状态变化自动反映
  const [manageId, setManageId] = useState<number | null>(null)
  const manageProposal = manageId != null ? proposals.find((p) => p.id === manageId) ?? null : null

  // 库编辑
  const [baseModalOpen, setBaseModalOpen] = useState(false)
  const [editingBase, setEditingBase] = useState<KnowledgeBase | null>(null)
  const [baseForm] = Form.useForm<KnowledgeBaseInput>()
  const [previewResult, setPreviewResult] = useState<PreviewResult | null>(null)
  const [previewForm] = Form.useForm()

  // 提案弹窗（手动沉淀）
  const [proposalModalOpen, setProposalModalOpen] = useState(false)
  const [proposalForm] = Form.useForm()

  const loadBases = useCallback(async () => {
    setBasesLoading(true)
    try {
      setBases(await listBases())
    } catch (e) {
      showError(e, '加载知识库失败')
    } finally {
      setBasesLoading(false)
    }
  }, [])

  const loadProposals = useCallback(async (status?: string) => {
    setProposalsLoading(true)
    try {
      setProposals(await listProposals(status))
    } catch (e) {
      showError(e, '加载提案失败')
    } finally {
      setProposalsLoading(false)
    }
  }, [])

  useEffect(() => {
    listProjects()
      .then(setProjects)
      .catch(() => undefined)
    loadBases()
    loadProposals()
  }, [loadBases, loadProposals])

  const openCreateBase = () => {
    setEditingBase(null)
    baseForm.resetFields()
    baseForm.setFieldsValue({ scope: 'global', injectMode: 'RAG', status: 'active' })
    setBaseModalOpen(true)
  }

  const openEditBase = (b: KnowledgeBase) => {
    setEditingBase(b)
    baseForm.setFieldsValue({
      name: b.name,
      description: b.description ?? undefined,
      scope: b.scope,
      projectId: b.projectId ?? undefined,
      injectMode: b.injectMode,
      status: b.status,
    })
    setBaseModalOpen(true)
  }

  const onSaveBase = async () => {
    const v = await baseForm.validateFields()
    try {
      if (editingBase) {
        await updateBase(editingBase.id, v)
        message.success('知识库已更新')
      } else {
        await createBase(v)
        message.success('知识库已创建')
      }
      setBaseModalOpen(false)
      loadBases()
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  const onDeleteBase = (b: KnowledgeBase) => {
    Modal.confirm({
      centered: true,
      title: `删除知识库「${b.name}」？`,
      content:
        b.entryCount > 0
          ? `库内还有 ${b.entryCount} 个条目，确认后将连同条目与索引一并删除，无法恢复。`
          : '删除后无法恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteBase(b.id, b.entryCount > 0)
          message.success('已删除')
          loadBases()
        } catch (err) {
          showError(err, '删除失败')
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
          ? '将作为全局经验条目进入经验库，后续所有项目会话都可能注入。'
          : `将进入项目经验库（项目 ${p.targetProjectId ?? '待定'}）。`,
      okText: '采纳',
      cancelText: '取消',
      onOk: async () => {
        try {
          await adoptProposal(p.id, target, p.targetProjectId ?? undefined)
          message.success('已采纳为知识条目')
          setManageId(null)
          loadProposals()
          loadBases()
        } catch (err) {
          showError(err, '采纳失败')
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
          setManageId(null)
          loadProposals()
        } catch (err) {
          showError(err, '操作失败')
        }
      },
    })
  }

  const onPreview = async () => {
    const v = await previewForm.validateFields()
    try {
      setPreviewResult(await previewInjection(v.projectId, v.taskSpec))
    } catch (e) {
      showError(e, '预览失败')
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
      showError(e, '提交失败')
    }
  }

  const baseColumns: ColumnsType<KnowledgeBase> = [
    {
      title: '名称',
      dataIndex: 'name',
      ellipsis: true,
      render: (v: string, r) => <Link to={`/admin/knowledge/bases/${r.id}`}>{v}</Link>,
    },
    { title: '范围', dataIndex: 'scope', width: 90, render: scopeTag },
    {
      title: '所属项目',
      dataIndex: 'projectName',
      width: 140,
      ellipsis: true,
      render: (v, r) => (r.scope === 'project' ? v ?? r.projectId ?? '-' : '-'),
    },
    { title: '注入模式', dataIndex: 'injectMode', width: 130, render: injectModeTag },
    { title: '条目数', dataIndex: 'entryCount', width: 80 },
    { title: '分块数', dataIndex: 'chunkCount', width: 80 },
    {
      // CAP-48 FR-09 索引健康度摘要：这库到底能不能用一眼可见
      title: '索引',
      key: 'indexStats',
      width: 160,
      render: (_, r) => {
        const s = r.indexStats
        if (!s || r.entryCount === 0) return <Typography.Text type="secondary">-</Typography.Text>
        const parts: string[] = []
        if (s.failed > 0) parts.push(`失败 ${s.failed}`)
        if (s.pending > 0) parts.push(`待索引 ${s.pending}`)
        if (s.disabled > 0) parts.push(`未启用 ${s.disabled}`)
        return (
          <Space size={4} wrap>
            <Tag color={s.mismatched > 0 || s.failed > 0 ? 'orange' : s.ready === r.entryCount ? 'green' : 'gold'}>
              {s.ready}/{r.entryCount} 已索引
            </Tag>
            {s.mismatched > 0 && (
              <Tooltip title="索引维度/端点与当前端点不一致，检索会漏命中；进库详情执行「重建失配条目」">
                <Tag color="red">失配 {s.mismatched}</Tag>
              </Tooltip>
            )}
            {parts.length > 0 && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {parts.join(' · ')}
              </Typography.Text>
            )}
          </Space>
        )
      },
    },
    { title: '状态', dataIndex: 'status', width: 90, render: baseStatusTag },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 130,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEditBase(r)}>
            编辑
          </Button>
          <Button size="small" danger onClick={() => onDeleteBase(r)}>
            删除
          </Button>
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
      width: 90,
      render: (_, r) =>
        r.status === 'open' ? (
          <Button size="small" onClick={() => setManageId(r.id)}>
            管理
          </Button>
        ) : (
          <Typography.Text type="secondary">已处理</Typography.Text>
        ),
    },
  ]

  const projectOptions = projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>知识库</span>
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'bases', label: '知识库' },
              { value: 'proposals', label: '经验提案' },
              { value: 'preview', label: '注入预览' },
            ]}
          />
        </Space>
      }
      extra={
        <Space wrap>
          {view === 'bases' && (
            <>
              <Button icon={<ReloadOutlined />} onClick={loadBases}>
                刷新
              </Button>
              {canWrite() && (
                <Button type="primary" icon={<PlusOutlined />} onClick={openCreateBase}>
                  新建知识库
                </Button>
              )}
            </>
          )}
          {view === 'proposals' && (
            <>
              <Button icon={<ReloadOutlined />} onClick={() => loadProposals()}>
                刷新
              </Button>
              <Button type="primary" icon={<BulbOutlined />} onClick={() => setProposalModalOpen(true)}>
                手动沉淀经验
              </Button>
            </>
          )}
        </Space>
      }
    >
      {view === 'bases' && (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            知识库是条目的容器：FULL 库（经验库）在会话启动时全量注入 CLAUDE.md，RAG 库只做向量检索按召回内容。
            点库名进入条目管理与检索测试。
          </Typography.Paragraph>
          <FitTable
            rowKey="id"
            loading={basesLoading}
            columns={baseColumns}
            dataSource={bases}
            pagination={LIST_PAGINATION}
            locale={{
              emptyText: (
                <Space direction="vertical" size={8} style={{ padding: '24px 0' }}>
                  <Typography.Text type="secondary">
                    暂无知识库——点击「新建知识库」创建一个 RAG 库沉淀文档，或在「经验提案」采纳沉淀的经验。
                  </Typography.Text>
                  {canWrite() && (
                    <div>
                      <Button type="primary" icon={<PlusOutlined />} onClick={openCreateBase}>
                        新建知识库
                      </Button>
                    </div>
                  )}
                </Space>
              ),
            }}
          />
        </>
      )}

      {view === 'proposals' && (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            会话中「沉淀经验」或手动提交的经验，审核采纳后进入对应经验库（inbox）。
          </Typography.Paragraph>
          <FitTable
            rowKey="id"
            loading={proposalsLoading}
            columns={proposalColumns}
            dataSource={proposals}
            pagination={LIST_PAGINATION}
            locale={{
              emptyText:
                '暂无待审核经验——在会话中点「沉淀经验」，或点击右上角「手动沉淀经验」提交一条。',
            }}
          />
        </>
      )}

      {view === 'preview' && (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            选择项目与任务说明，预览会话启动时实际注入的知识内容（FULL 库条目）。
          </Typography.Paragraph>
          <Space direction="vertical" style={{ width: '100%', ...pagePaneScrollStyle }}>
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
        </>
      )}

      {/* 提案管理抽屉：采纳到项目/晋升全局/拒绝 */}
      {manageProposal && (
        <Drawer
          title={`提案 · ${manageProposal.title}`}
          open
          onClose={() => setManageId(null)}
          width={560}
        >
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="去向">{scopeTag(manageProposal.targetScope)}</Descriptions.Item>
              <Descriptions.Item label="状态">{statusTag(manageProposal.status)}</Descriptions.Item>
              <Descriptions.Item label="来源会话">{manageProposal.sourceSessionId ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="创建时间">{fmtTime(manageProposal.createdAt)}</Descriptions.Item>
            </Descriptions>
            <pre
              style={{
                whiteSpace: 'pre-wrap',
                background: '#f6f6f6',
                padding: 12,
                borderRadius: 4,
                fontSize: 12,
                maxHeight: 320,
                overflow: 'auto',
                margin: 0,
              }}
            >
              {manageProposal.contentMd}
            </pre>
            {manageProposal.status === 'open' ? (
              <Space>
                <Button type="primary" onClick={() => onAdopt(manageProposal, 'project')}>
                  采纳到项目
                </Button>
                <Button onClick={() => onAdopt(manageProposal, 'global')}>晋升全局</Button>
                <Button danger onClick={() => onReject(manageProposal)}>
                  拒绝
                </Button>
              </Space>
            ) : (
              <Typography.Text type="secondary">该提案已处理。</Typography.Text>
            )}
          </Space>
        </Drawer>
      )}

      {/* 库编辑弹窗 */}
      <Modal
        title={editingBase ? '编辑知识库' : '新建知识库'}
        open={baseModalOpen}
        onCancel={() => setBaseModalOpen(false)}
        onOk={onSaveBase}
      >
        <Form form={baseForm} labelCol={{ span: 5 }} wrapperCol={{ span: 18 }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请填写名称' }]}>
            <Input placeholder="如：前端规范库" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="这个库装什么？（可选）" />
          </Form.Item>
          <Form.Item name="scope" label="范围" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'global', label: 'global（全局可用）' },
                { value: 'project', label: 'project（指定项目）' },
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
          <Form.Item
            name="injectMode"
            label="注入模式"
            rules={[{ required: true }]}
            extra="FULL：会话启动全量注入 CLAUDE.md（经验库）；RAG：仅检索，按提问召回内容"
          >
            <Select
              options={[
                { value: 'RAG', label: 'RAG 检索（推荐）' },
                { value: 'FULL', label: 'FULL 全量注入' },
              ]}
            />
          </Form.Item>
          {editingBase && (
            <Form.Item name="status" label="状态">
              <Select
                options={[
                  { value: 'active', label: 'active（启用）' },
                  { value: 'archived', label: 'archived（归档停用）' },
                ]}
              />
            </Form.Item>
          )}
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
    </Card>
  )
}
