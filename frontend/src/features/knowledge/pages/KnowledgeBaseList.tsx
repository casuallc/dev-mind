// CAP-44 知识库列表：库容器管理（新建/编辑/删除）。
// 经验提案 inbox 与注入预览已迁入「项目管理」聚合页（features/knowledge/pages/ProposalsView、InjectionPreviewView）。
// 条目管理与检索测试在库详情页（/admin/knowledge/bases/:id）。
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
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
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { createBase, deleteBase, listBases, updateBase } from '../api'
import type { InjectMode, KnowledgeBase, KnowledgeBaseInput } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const scopeTag = (s: string) => (s === 'global' ? <Tag color="blue">global</Tag> : <Tag>project</Tag>)
const injectModeTag = (m: InjectMode) =>
  m === 'FULL' ? <Tag color="geekblue">FULL 全量注入</Tag> : <Tag color="purple">RAG 检索</Tag>
const baseStatusTag = (s: string) =>
  s === 'active' ? <Tag color="green">active</Tag> : <Tag>archived</Tag>

export default function KnowledgeBaseList() {
  const [projects, setProjects] = useState<Project[]>([])
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [basesLoading, setBasesLoading] = useState(false)

  // 库编辑
  const [baseModalOpen, setBaseModalOpen] = useState(false)
  const [editingBase, setEditingBase] = useState<KnowledgeBase | null>(null)
  const [baseForm] = Form.useForm<KnowledgeBaseInput>()

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

  useEffect(() => {
    listProjects()
      .then(setProjects)
      .catch(() => undefined)
    loadBases()
  }, [loadBases])

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

  const projectOptions = projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="知识库"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={loadBases}>
            刷新
          </Button>
          {canWrite() && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreateBase}>
              新建知识库
            </Button>
          )}
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginTop: 0, marginBottom: 12 }}>
        知识库是条目的容器：FULL 库（经验库）在会话启动时全量注入 CLAUDE.local.md，RAG 库只做向量检索按召回内容。
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
                暂无知识库——点击「新建知识库」创建一个 RAG 库沉淀文档，或到「项目管理 · 经验提案」采纳沉淀的经验。
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
            extra="FULL：会话启动全量注入 CLAUDE.local.md（经验库）；RAG：仅检索，按提问召回内容"
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
    </Card>
  )
}
