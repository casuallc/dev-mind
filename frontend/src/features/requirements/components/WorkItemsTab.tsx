// 需求详情页「工作单元」Tab：WI 表格（中文状态/会话列/起会话/编辑/删除）+ 新建/编辑弹窗 + 全部完成。
// 行内操作统一居中 Modal 二次确认（编辑走弹窗表单本身）；需求完结（DONE/CANCELLED）后锁定。
// CAP-52：清单由规划会话自动固化（不再是「AI 拆分」按钮），开发会话也不自动把 WI 置 DONE——
// 人核对改动后点「全部完成」一次性收口（逐个置 DONE 会连带触发 N 次构建）。
import { useState } from 'react'
import { Button, Dropdown, Empty, Form, Input, Modal, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { CheckCircleOutlined, DownOutlined, PlusOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import {
  createWorkItem,
  deleteWorkItem,
  startWorkItemSession,
  updateWorkItem,
  updateWorkItemStatus,
} from '../api'
import {
  WI_STATUS_LABEL,
  WI_TYPE_LABEL,
  workItemStatusColor,
  workItemTypeColor,
} from './requirementMeta'
import { SessionTag, latestSessionOfWorkItem } from './flow/flowSessions'
import type { RequirementOverview, WorkItem, WorkItemInput, WorkItemStatus, WorkItemType } from '../types'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const WI_STATUS_FLOW: WorkItemStatus[] = ['TODO', 'IN_PROGRESS', 'BLOCKED', 'DONE', 'CANCELLED']
const WI_TYPES: WorkItemType[] = ['DESIGN', 'DEVELOPMENT', 'TEST', 'DOCUMENT', 'REVIEW']

export default function WorkItemsTab({ projectId, requirementId, workItems, sessions, locked, onChanged }: {
  projectId: string
  requirementId: string
  workItems: WorkItem[]
  /** overview.sessions：「会话」列反查工作单元最近会话用 */
  sessions: RequirementOverview['sessions']
  /** 需求已完结（DONE/CANCELLED）时锁定新建与状态流转 */
  locked: boolean
  onChanged: () => Promise<void> | void
}) {
  const navigate = useNavigate()
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<WorkItem | null>(null)
  const [finishing, setFinishing] = useState(false)
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
      showError(e, '保存失败')
    }
  }

  const advance = (w: WorkItem, status: WorkItemStatus) => {
    Modal.confirm({
      centered: true,
      title: '变更工作单元状态？',
      content: `「${w.code} ${w.title}」将标记为「${WI_STATUS_LABEL[status]}」。`,
      okText: '确认',
      cancelText: '返回',
      onOk: async () => {
        try {
          await updateWorkItemStatus(projectId, requirementId, w.id, status)
          await onChanged()
          message.success(`${w.code} → ${WI_STATUS_LABEL[status]}`)
        } catch (e) {
          showError(e)
        }
      },
    })
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

  // CAP-14：工作单元一键起会话（spec 由后端自动带入 taskSpec），确认后启动并跳会话详情
  const startSession = (w: WorkItem) => {
    Modal.confirm({
      centered: true,
      title: '起会话？',
      content: `将为「${w.code} ${w.title}」启动 agent 会话（执行输入取工作单元 spec），启动后跳转会话详情。`,
      okText: '起会话',
      cancelText: '返回',
      onOk: async () => {
        try {
          const s = await startWorkItemSession(projectId, w.id)
          message.success(`${w.code} 会话已启动`)
          navigate(`/sessions?sid=${s.id}`)
        } catch (e) {
          showError(e)
        }
      },
    })
  }

  // CAP-52：开发会话完成后逐条核对改动，一次收口（未完成的条目留给下轮开发会话）
  const pendingItems = workItems.filter((w) => w.status !== 'DONE' && w.status !== 'CANCELLED')
  const finishAll = () => {
    Modal.confirm({
      centered: true,
      title: '全部标记完成？',
      content: `将把本需求下 ${pendingItems.length} 个未完结的工作单元置为「已完成」，`
        + '并触发既有的构建/测试联动（每一条各自触发一次）。',
      okText: '全部完成',
      cancelText: '返回',
      onOk: async () => {
        setFinishing(true)
        try {
          for (const w of pendingItems) {
            await updateWorkItemStatus(projectId, requirementId, w.id, 'DONE')
          }
          message.success(`已置完成 ${pendingItems.length} 条`)
          await onChanged()
        } catch (e) {
          showError(e)
        } finally {
          setFinishing(false)
        }
      },
    })
  }

  const columns: ColumnsType<WorkItem> = [
    { title: '编号', dataIndex: 'code', width: 80, render: (v: string) => <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> },
    {
      title: '类型', dataIndex: 'type', width: 100,
      render: (t: WorkItemType) => <Tag color={workItemTypeColor(t)}>{WI_TYPE_LABEL[t] ?? t}</Tag>,
    },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 120,
      render: (s: WorkItemStatus, w) => (
        <Dropdown
          menu={{
            items: WI_STATUS_FLOW.map((x) => ({ key: x, label: WI_STATUS_LABEL[x] })),
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
            {WI_STATUS_LABEL[s] ?? s} {!locked && <DownOutlined style={{ fontSize: 10 }} />}
          </Tag>
        </Dropdown>
      ),
    },
    {
      title: '会话', key: 'session', width: 110,
      render: (_, w) => {
        const s = latestSessionOfWorkItem(sessions, w.id)
        return s ? <SessionTag s={s} /> : <Typography.Text type="secondary">-</Typography.Text>
      },
    },
    {
      title: '操作', key: 'ops', width: 130,
      render: (_, w) => (
        <Space size={4}>
          {!locked && (w.status === 'TODO' || w.status === 'IN_PROGRESS') && (
            <Button size="small" type="link" onClick={() => startSession(w)}>
              起会话
            </Button>
          )}
          <Dropdown
            menu={{
              items: [
                { key: 'edit', label: '编辑' },
                { key: 'delete', label: '删除', danger: true, disabled: locked },
              ],
              onClick: ({ key }) => (key === 'edit' ? openEdit(w) : confirmDelete(w)),
            }}
            trigger={['click']}
          >
            <Button size="small" type="text">
              更多
            </Button>
          </Dropdown>
        </Space>
      ),
    },
  ]

  // CAP-52：没有清单时引导去起规划会话（清单由它一次性产出），手工新建保留为逃生通道
  const showGuide = workItems.length === 0 && !locked

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {!locked && (
        <Space size={8}>
          <Button size="small" type="primary" ghost icon={<PlusOutlined />} onClick={() => openEdit(null)}>
            新建工作单元
          </Button>
          <Tooltip title={pendingItems.length === 0 ? '没有未完结的工作单元' : undefined}>
            <span>
              <Button
                size="small"
                icon={<CheckCircleOutlined />}
                loading={finishing}
                disabled={pendingItems.length === 0}
                onClick={finishAll}
              >
                全部完成（{pendingItems.length}）
              </Button>
            </span>
          </Tooltip>
        </Space>
      )}
      {showGuide ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              工作单元由「开启 AI 规划」一次性产出（分析 + 方案 + 清单，并自动接开发会话）；
              也可以直接手工新建。
            </Typography.Text>
          }
        />
      ) : (
        <Table rowKey="id" size="small" columns={columns} dataSource={workItems} pagination={LIST_PAGINATION} />
      )}

      <Modal title={editing ? `编辑工作单元 ${editing.code}` : '新建工作单元'} open={editOpen}
        onCancel={() => setEditOpen(false)} onOk={() => form.submit()} okText="保存" width={560}>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="类型" name="type" rules={[{ required: true }]}>
            <Select options={WI_TYPES.map((t) => ({ value: t, label: WI_TYPE_LABEL[t] }))} />
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
