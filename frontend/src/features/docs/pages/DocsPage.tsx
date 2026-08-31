// CAP-03 文档列表：筛选/检索 + 新建文档（模板一键预填）+ git push。
import { useCallback, useEffect, useState } from 'react'
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
  Tag,
  Typography,
} from 'antd'
import {
  CloudUploadOutlined,
  FileAddOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { createDoc, listDocs, listTemplates, pushDocs, searchDocs } from '../api'
import { KIND_LABEL, STATUS_LABEL } from '../types'
import type { DocInput, DocKind, DocMeta, DocStatus, DocTemplate } from '../types'

const kindTag = (k: DocKind) => <Tag color={k === 'requirement' ? 'blue' : k === 'design' ? 'geekblue' : k === 'api-suite' ? 'purple' : 'cyan'}>{KIND_LABEL[k] ?? k}</Tag>
const statusTag = (s: DocStatus) => (
  <Tag color={s === 'draft' ? 'default' : s === 'pending_confirm' ? 'gold' : 'green'}>{STATUS_LABEL[s] ?? s}</Tag>
)

export default function DocsPage() {
  const navigate = useNavigate()
  const [docs, setDocs] = useState<DocMeta[]>([])
  const [loading, setLoading] = useState(false)
  const [kindFilter, setKindFilter] = useState<string>('')
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [searchQ, setSearchQ] = useState('')
  const [projects, setProjects] = useState<Project[]>([])
  const [templates, setTemplates] = useState<DocTemplate[]>([])
  const [createOpen, setCreateOpen] = useState(false)
  const [createForm] = Form.useForm<DocInput & { template?: string }>()
  const [pushing, setPushing] = useState(false)

  const load = useCallback(async (kind = kindFilter, status = statusFilter, q = searchQ) => {
    setLoading(true)
    try {
      if (q.trim()) {
        setDocs(await searchDocs(q.trim()))
      } else {
        setDocs(await listDocs({ kind: kind || undefined, status: status || undefined }))
      }
    } catch (e) {
      message.error(`加载文档失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [kindFilter, statusFilter, searchQ])

  useEffect(() => {
    load()
    listProjects().then(setProjects).catch(() => undefined)
    listTemplates().then(setTemplates).catch(() => undefined)
  }, [load])

  const openCreate = () => {
    createForm.resetFields()
    createForm.setFieldsValue({ kind: 'requirement' })
    setCreateOpen(true)
  }

  const onTemplateChange = (tpl: string | undefined) => {
    const t = templates.find((x) => x.kind === tpl)
    if (t) {
      createForm.setFieldValue('contentMd', t.content)
      createForm.setFieldValue('kind', t.kind)
    }
  }

  const onCreate = async () => {
    const v = await createForm.validateFields()
    try {
      const created = await createDoc({
        kind: v.kind,
        projectId: v.projectId,
        title: v.title,
        tags: v.tags,
        template: v.template,
        contentMd: v.contentMd,
      })
      message.success('文档已创建')
      setCreateOpen(false)
      load()
      navigate(`/docs/${created.id}`)
    } catch (e) {
      message.error(`创建失败：${(e as Error).message}`)
    }
  }

  const onPush = async () => {
    setPushing(true)
    try {
      const r = await pushDocs()
      message.success(r.message)
    } catch (e) {
      message.error(`推送失败：${(e as Error).message}`)
    } finally {
      setPushing(false)
    }
  }

  const columns: ColumnsType<DocMeta> = [
    {
      title: '标题',
      dataIndex: 'title',
      render: (t: string, r) => (
        <a onClick={() => navigate(`/docs/${r.id}`)}>{t}</a>
      ),
    },
    { title: '类型', dataIndex: 'kind', width: 100, render: kindTag },
    { title: '状态', dataIndex: 'status', width: 100, render: statusTag },
    { title: '版本', dataIndex: 'currentVersion', width: 70, render: (v) => `v${v}` },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 170,
      render: (tags: string[]) =>
        tags?.length ? tags.map((t) => <Tag key={t}>{t}</Tag>) : <Typography.Text type="secondary">-</Typography.Text>,
    },
    { title: '路径', dataIndex: 'filePath', ellipsis: true, render: (p: string) => <Typography.Text code style={{ fontSize: 12 }}>{p}</Typography.Text> },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (v) => new Date(v).toLocaleString() },
  ]

  const projectOptions = projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))
  const templateOptions = templates.map((t) => ({ value: t.kind, label: KIND_LABEL[t.kind] }))

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card size="small">
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <Space>
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="检索标题/内容/标签"
              style={{ width: 240 }}
              value={searchQ}
              onChange={(e) => setSearchQ(e.target.value)}
              onPressEnter={() => load(kindFilter, statusFilter, searchQ)}
            />
            <Select
              allowClear
              placeholder="类型"
              style={{ width: 120 }}
              value={kindFilter || undefined}
              onChange={(v) => { setKindFilter(v ?? ''); load(v ?? '', statusFilter) }}
              options={Object.entries(KIND_LABEL).map(([value, label]) => ({ value, label }))}
            />
            <Select
              allowClear
              placeholder="状态"
              style={{ width: 120 }}
              value={statusFilter || undefined}
              onChange={(v) => { setStatusFilter(v ?? ''); load(kindFilter, v ?? '') }}
              options={Object.entries(STATUS_LABEL).map(([value, label]) => ({ value, label }))}
            />
          </Space>
          <Space>
            <Button icon={<CloudUploadOutlined />} loading={pushing} onClick={onPush}>
              推送到远端
            </Button>
            <Button type="primary" icon={<FileAddOutlined />} onClick={openCreate}>
              新建文档
            </Button>
          </Space>
        </Space>
      </Card>
      <Card size="small">
        <Table
          size="small"
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={docs}
          pagination={{ pageSize: 20, showSizeChanger: false }}
        />
      </Card>

      <Modal title="新建文档" open={createOpen} onCancel={() => setCreateOpen(false)} onOk={onCreate} width={640}>
        <Form form={createForm} labelCol={{ span: 5 }} wrapperCol={{ span: 18 }}>
          <Form.Item name="kind" label="类型" rules={[{ required: true }]}>
            <Select options={Object.entries(KIND_LABEL).map(([value, label]) => ({ value, label }))} />
          </Form.Item>
          <Form.Item name="template" label="套用模板" extra="选择模板将预填内容">
            <Select allowClear placeholder="从模板开始" options={templateOptions} onChange={onTemplateChange} />
          </Form.Item>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请填写标题' }]}>
            <Input placeholder="如：登录改造需求文档" />
          </Form.Item>
          <Form.Item name="projectId" label="归属项目">
            <Select allowClear showSearch optionFilterProp="label" placeholder="可留空" options={projectOptions} />
          </Form.Item>
          <Form.Item name="tags" label="标签">
            <Select mode="tags" placeholder="回车添加标签" open={false} />
          </Form.Item>
          <Form.Item name="contentMd" label="内容" rules={[{ required: true, message: '请填写内容' }]}>
            <Input.TextArea rows={10} placeholder="Markdown 内容（选择模板后自动预填）" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
