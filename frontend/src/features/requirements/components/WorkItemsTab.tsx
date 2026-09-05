// 需求详情页「工作单元」Tab：WI 表格（行内状态/起会话/编辑/删除）+ 新建/编辑弹窗。
// 行内状态手动流转保留（WI 无引导流程，状态是需求 rollup 数据源）；需求完结（DONE/CANCELLED）后锁定。
import { useState } from 'react'
import { Button, Dropdown, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { DownOutlined, PlayCircleOutlined, PlusOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import {
  createWorkItem,
  deleteWorkItem,
  startWorkItemSession,
  updateWorkItem,
  updateWorkItemStatus,
} from '../api'
import type { WorkItem, WorkItemInput, WorkItemStatus, WorkItemType } from '../types'

const WI_STATUS_FLOW: WorkItemStatus[] = ['TODO', 'IN_PROGRESS', 'BLOCKED', 'DONE', 'CANCELLED']
const WI_TYPES: WorkItemType[] = ['DESIGN', 'DEVELOPMENT', 'TEST', 'DOCUMENT', 'REVIEW']

function workItemStatusColor(s: WorkItemStatus | string): string {
  switch (s) {
    case 'TODO': return 'default'
    case 'IN_PROGRESS': return 'blue'
    case 'BLOCKED': return 'orange'
    case 'DONE': return 'green'
    case 'CANCELLED': return 'red'
    default: return 'default'
  }
}

function workItemTypeColor(t: WorkItemType | string): string {
  switch (t) {
    case 'DESIGN': return 'cyan'
    case 'DEVELOPMENT': return 'blue'
    case 'TEST': return 'orange'
    case 'DOCUMENT': return 'green'
    case 'REVIEW': return 'purple'
    default: return 'default'
  }
}

export default function WorkItemsTab({ projectId, requirementId, workItems, locked, onChanged }: {
  projectId: string
  requirementId: string
  workItems: WorkItem[]
  /** 需求已完结（DONE/CANCELLED）时锁定新建与状态流转 */
  locked: boolean
  onChanged: () => Promise<void> | void
}) {
  const navigate = useNavigate()
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<WorkItem | null>(null)
  const [form] = Form.useForm<WorkItemInput>()

  const openEdit = (w: WorkItem | null) => {
    setEditing(w)
    form.setFieldsValue(w ?? { type: 'DEVELOPMENT', title: '', spec: '', ownerId: '', branchSlug: '' })
    setEditOpen(true)
  }

  const onSave = async (v: WorkItemInput) => {
    try {
      if (editing) {
        await updateWorkItem(projectId, requirementId, editing.id, v)
      } else {
        await createWorkItem(projectId, requirementId, v)
      }
      setEditOpen(false)
      message.success('已保存')
      await onChanged()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const advance = async (w: WorkItem, status: WorkItemStatus) => {
    try {
      await updateWorkItemStatus(projectId, requirementId, w.id, status)
      await onChanged()
      message.success(`${w.code} → ${status}`)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const confirmDelete = (w: WorkItem) => {
    Modal.confirm({
      centered: true,
      title: '删除工作单元？',
      content: `将删除「${w.code} ${w.title}」（关联的会话/构建等记录保留，仅解除归属）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteWorkItem(projectId, requirementId, w.id)
        await onChanged()
        message.success('已删除')
      },
    })
  }

  // CAP-14：工作单元一键起会话（spec 由后端自动带入 taskSpec），成功后跳会话详情
  const startSession = async (w: WorkItem) => {
    try {
      const s = await startWorkItemSession(projectId, w.id)
      message.success(`${w.code} 会话已启动`)
      navigate(`/sessions/${s.id}`)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns: ColumnsType<WorkItem> = [
    { title: '编号', dataIndex: 'code', width: 80, render: (v: string) => <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> },
    { title: '类型', dataIndex: 'type', width: 110, render: (t: string) => <Tag color={workItemTypeColor(t)}>{t}</Tag> },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 130,
      render: (s: WorkItemStatus, w) => (
        <Dropdown
          menu={{
            items: WI_STATUS_FLOW.map((x) => ({ key: x, label: x })),
            selectedKeys: [s],
            onClick: ({ key }) => advance(w, key as WorkItemStatus),
          }}
          trigger={['click']}
          disabled={locked}
        >
          <Tag
            color={workItemStatusColor(s)}
            style={{ marginInlineEnd: 0, cursor: locked ? 'default' : 'pointer' }}
          >
            {s} {!locked && <DownOutlined style={{ fontSize: 10 }} />}
          </Tag>
        </Dropdown>
      ),
    },
    {
      title: '操作', key: 'ops', width: 170,
      render: (_, w) => (
        <Space size={4}>
          {!locked && (w.status === 'TODO' || w.status === 'IN_PROGRESS') && (
            <Button size="small" type="link" icon={<PlayCircleOutlined />} onClick={() => startSession(w)}>
              起会话
            </Button>
          )}
          <Button size="small" type="link" onClick={() => openEdit(w)}>编辑</Button>
          <Button size="small" type="link" danger disabled={locked} onClick={() => confirmDelete(w)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {!locked && (
        <Button size="small" type="primary" ghost icon={<PlusOutlined />} onClick={() => openEdit(null)}>
          新建工作单元
        </Button>
      )}
      <Table rowKey="id" size="small" columns={columns} dataSource={workItems} pagination={false} />

      <Modal title={editing ? `编辑工作单元 ${editing.code}` : '新建工作单元'} open={editOpen}
        onCancel={() => setEditOpen(false)} onOk={() => form.submit()} okText="保存" width={560}>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="类型" name="type" rules={[{ required: true }]}>
            <Select options={WI_TYPES.map((t) => ({ value: t, label: t }))} />
          </Form.Item>
          <Form.Item label="标题" name="title" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="如 登录页扫码组件开发" />
          </Form.Item>
          <Form.Item label="执行输入 spec" name="spec" extra="起会话时作为 taskSpec 注入，可后续编辑">
            <Input.TextArea rows={4} />
          </Form.Item>
          <Form.Item label="负责人" name="ownerId">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item label="分支 slug" name="branchSlug" extra="工作分支 wi/<seq>-<slug>，缺省由标题生成">
            <Input placeholder="如 login-qrcode" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}
