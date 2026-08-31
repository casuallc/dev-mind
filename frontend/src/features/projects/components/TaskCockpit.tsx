// 任务驾驶舱：左侧任务列表（按状态分组），右侧选中任务的主线视图（P0-6 步骤 4）。
// 自包含组件：内部管理任务列表 + 选中 + 聚合数据（含 CAP-11 发版）+ 新建/编辑/删除/状态推进。
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  List,
  Modal,
  Row,
  Select,
  Space,
  Spin,
  Steps,
  Table,
  Tabs,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  createTask,
  deleteTask,
  getTaskOverview,
  listTasks,
  updateTask,
  updateTaskStatus,
} from '../api'
import type {
  Task,
  TaskInput,
  TaskOverview,
  TaskStatus,
} from '../types'

const STATUS_FLOW: TaskStatus[] = ['DRAFT', 'DESIGNING', 'DEVELOPING', 'TESTING', 'ACCEPTANCE', 'DONE']
const ALL_STATUSES: TaskStatus[] = [...STATUS_FLOW, 'CANCELLED']

export function taskStatusColor(s: TaskStatus | string): string {
  switch (s) {
    case 'DRAFT': return 'default'
    case 'DESIGNING': return 'cyan'
    case 'DEVELOPING': return 'blue'
    case 'TESTING': return 'orange'
    case 'ACCEPTANCE': return 'purple'
    case 'DONE': return 'green'
    case 'CANCELLED': return 'red'
    default: return 'default'
  }
}

export default function TaskCockpit({ projectId }: { projectId: string }) {
  const [tasks, setTasks] = useState<Task[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [overview, setOverview] = useState<TaskOverview | null>(null)
  const [overviewLoading, setOverviewLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<Task | null>(null)
  const [form] = Form.useForm()

  const reload = useCallback(async (keepSelection?: string | null) => {
    try {
      const rs = await listTasks(projectId)
      setTasks(rs)
      const keep = keepSelection !== undefined ? keepSelection : selectedId
      if (keep && rs.some((r) => r.id === keep)) {
        setSelectedId(keep)
      } else {
        setSelectedId(rs.length > 0 ? rs[0].id : null)
      }
    } catch (e) {
      message.error(`加载任务失败：${(e as Error).message}`)
    }
  }, [projectId, selectedId])

  useEffect(() => {
    reload(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  const loadOverview = useCallback(async (taskId: string) => {
    setOverviewLoading(true)
    try {
      setOverview(await getTaskOverview(projectId, taskId))
    } catch (e) {
      message.error(`加载任务主线失败：${(e as Error).message}`)
      setOverview(null)
    } finally {
      setOverviewLoading(false)
    }
  }, [projectId])

  useEffect(() => {
    if (selectedId) {
      loadOverview(selectedId)
    } else {
      setOverview(null)
    }
  }, [selectedId, loadOverview])

  const openEdit = (r: Task | null) => {
    setEditing(r)
    form.setFieldsValue(r ?? { title: '', description: '', ownerId: '', branchSlug: '' })
    setEditOpen(true)
  }

  const onSave = async (v: TaskInput) => {
    try {
      const saved = editing
        ? await updateTask(projectId, editing.id, v)
        : await createTask(projectId, v)
      setEditOpen(false)
      await reload(saved.id)
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const advance = async (r: Task, status: TaskStatus) => {
    try {
      await updateTaskStatus(projectId, r.id, status)
      await reload(r.id)
      message.success(`${r.code} → ${status}`)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const confirmDelete = (r: Task) => {
    Modal.confirm({
      centered: true,
      title: '删除任务？',
      content: `将删除任务「${r.code} ${r.title}」（关联的文档/构建/部署记录保留，仅解除主线）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteTask(projectId, r.id)
        await reload(null)
        message.success('已删除')
      },
    })
  }

  // 左侧列表按状态分组（流程顺序），组内按 seq 倒序
  const groups = ALL_STATUSES
    .map((s) => ({ status: s, items: tasks.filter((r) => r.status === s) }))
    .filter((g) => g.items.length > 0)

  return (
    <Row gutter={12}>
      <Col xs={24} lg={7} xl={6}>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space>
            <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
              新建任务
            </Button>
            <Button size="small" icon={<ReloadOutlined />} onClick={() => reload()} />
          </Space>
          {tasks.length === 0 ? (
            <Empty description="暂无任务，点击「新建任务」开始一条主线" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            groups.map((g) => (
              <div key={g.status}>
                <Tag color={taskStatusColor(g.status)} style={{ marginBottom: 4 }}>
                  {g.status}（{g.items.length}）
                </Tag>
                <List
                  size="small"
                  dataSource={g.items}
                  renderItem={(r) => (
                    <List.Item
                      onClick={() => setSelectedId(r.id)}
                      style={{
                        cursor: 'pointer',
                        padding: '6px 8px',
                        background: r.id === selectedId ? '#e6f4ff' : undefined,
                        borderRadius: 4,
                      }}
                    >
                      <Space size={6}>
                        <Typography.Text code style={{ fontSize: 12 }}>{r.code}</Typography.Text>
                        <Typography.Text
                          style={{ fontSize: 13 }}
                          ellipsis={{ tooltip: r.title }}
                        >
                          {r.title}
                        </Typography.Text>
                      </Space>
                    </List.Item>
                  )}
                />
              </div>
            ))
          )}
        </Space>
      </Col>
      <Col xs={24} lg={17} xl={18}>
        {overviewLoading ? (
          <Spin />
        ) : !overview ? (
          <Empty description="选择左侧任务查看主线视图" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Mainline
            overview={overview}
            onAdvance={(s) => advance(overview.task, s)}
            onEdit={() => openEdit(overview.task)}
            onDelete={() => confirmDelete(overview.task)}
            onRefresh={() => loadOverview(overview.task.id)}
          />
        )}
      </Col>

      <Modal title={editing ? `编辑任务 ${editing.code}` : '新建任务'} open={editOpen} onCancel={() => setEditOpen(false)}
        onOk={() => form.submit()} okText="保存" width={560}>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="标题" name="title" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="如 用户登录支持扫码" />
          </Form.Item>
          <Form.Item label="描述（需求内容）" name="description">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item label="负责人" name="ownerId">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item label="分支 slug" name="branchSlug" extra="任务分支 task/<seq>-<slug>，缺省由标题生成">
            <Input placeholder="如 login-qrcode" />
          </Form.Item>
        </Form>
      </Modal>
    </Row>
  )
}

// ---------------- 右侧主线视图 ----------------

function Mainline({ overview, onAdvance, onEdit, onDelete, onRefresh }: {
  overview: TaskOverview
  onAdvance: (s: TaskStatus) => void
  onEdit: () => void
  onDelete: () => void
  onRefresh: () => void
}) {
  const r = overview.task
  const flowIndex = STATUS_FLOW.indexOf(r.status)

  const docColumns: ColumnsType<TaskOverview['docs'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '类型', dataIndex: 'kind', width: 90, render: (k: string) => <Tag>{k}</Tag> },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 90 },
    { title: '版本', dataIndex: 'currentVersion', width: 60, render: (v: number) => `v${v}` },
  ]
  const sessionColumns: ColumnsType<TaskOverview['sessions'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 100, render: (v: string) => <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> },
    { title: '任务', dataIndex: 'taskSpec', ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <Tag color={s === 'DONE' ? 'green' : s === 'FAILED' ? 'red' : 'blue'}>{s}</Tag> },
    { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> },
  ]
  const buildColumns: ColumnsType<TaskOverview['builds'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
    { title: '分支', dataIndex: 'branch', width: 140, ellipsis: true, render: (v?: string) => v || '-' },
    { title: 'Commit', dataIndex: 'commit', width: 100, render: (v?: string) => v ? <Typography.Text code style={{ fontSize: 12 }}>{v.slice(0, 8)}</Typography.Text> : '-' },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <Tag color={s === 'SUCCESS' ? 'green' : s === 'FAILED' ? 'red' : 'blue'}>{s}</Tag> },
    { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> },
  ]
  const testColumns: ColumnsType<TaskOverview['testRuns'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
    { title: '触发', dataIndex: 'triggeredBy', width: 80 },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <Tag color={s === 'SUCCESS' ? 'green' : s === 'FAILED' ? 'red' : 'blue'}>{s}</Tag> },
    {
      title: '结果', dataIndex: 'summaryJson', width: 160,
      render: (j?: string) => {
        if (!j) return '-'
        try {
          const s = JSON.parse(j)
          return <span style={{ fontSize: 12 }}>共{s.total} 过{s.passed} 败{s.failed}</span>
        } catch { return '-' }
      },
    },
    { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> },
  ]
  const deployColumns: ColumnsType<TaskOverview['deployments'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
    { title: '环境', dataIndex: 'env', width: 80, render: (v?: string) => v || '-' },
    { title: '构建', dataIndex: 'buildId', width: 80, render: (v?: number) => v ? `#${v}` : '-' },
    { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <Tag color={s === 'SUCCESS' ? 'green' : s.includes('FAIL') ? 'red' : 'blue'}>{s}</Tag> },
    { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> },
  ]
  const releaseColumns: ColumnsType<TaskOverview['releases'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
    { title: '版本', dataIndex: 'version', width: 120, render: (v?: string) => v ? <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> : '-' },
    { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <Tag color={s === 'SUCCESS' ? 'green' : s.includes('FAIL') || s === 'ROLLED_BACK' ? 'red' : 'blue'}>{s}</Tag> },
    { title: '执行', dataIndex: 'executor', width: 80, render: (v?: string) => v || '-' },
    { title: '回滚', dataIndex: 'rollbackOf', width: 70, render: (v?: number) => v ? `#${v}` : '-' },
    { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> },
  ]

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Space wrap>
          <Typography.Text code>{r.code}</Typography.Text>
          <Typography.Text strong style={{ fontSize: 15 }}>{r.title}</Typography.Text>
          <Tag color={taskStatusColor(r.status)}>{r.status}</Tag>
          <Select
            size="small"
            value={r.status}
            style={{ width: 128 }}
            options={ALL_STATUSES.map((s) => ({ value: s, label: s }))}
            onChange={onAdvance}
          />
          <Button size="small" icon={<EditOutlined />} onClick={onEdit}>编辑</Button>
          <Button size="small" icon={<ReloadOutlined />} onClick={onRefresh} />
          <Button size="small" danger onClick={onDelete}>删除</Button>
        </Space>
        {r.status !== 'CANCELLED' && (
          <Steps
            size="small"
            current={flowIndex}
            items={STATUS_FLOW.map((s) => ({ title: s }))}
            style={{ maxWidth: 760 }}
          />
        )}
        <Descriptions size="small" column={{ xs: 1, sm: 3 }}>
          <Descriptions.Item label="负责人">{r.ownerId || '-'}</Descriptions.Item>
          <Descriptions.Item label="任务分支">
            <Typography.Text code style={{ fontSize: 12 }}>
              task/{r.seq}{r.branchSlug ? `-${r.branchSlug}` : ''}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="创建">{new Date(r.createdAt).toLocaleString()}</Descriptions.Item>
          {r.description && (
            <Descriptions.Item label="描述（需求内容）" span={3}>{r.description}</Descriptions.Item>
          )}
        </Descriptions>
      </Space>

      <Tabs
        size="small"
        items={[
          {
            key: 'timeline',
            label: `时间线（${overview.timeline.length}）`,
            children: (
              <Timeline
                style={{ marginTop: 8 }}
                items={overview.timeline.slice(0, 50).map((t) => ({
                  key: `${t.type}-${t.refId}-${t.time}`,
                  color: timelineColor(t.type),
                  children: (
                    <Space size={8}>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {new Date(t.time).toLocaleString()}
                      </Typography.Text>
                      <Tag style={{ fontSize: 11 }}>{t.type}</Tag>
                      <span style={{ fontSize: 13 }}>{t.label}</span>
                    </Space>
                  ),
                }))}
              />
            ),
          },
          {
            key: 'docs',
            label: `文档（${overview.docs.length}）`,
            children: <Table rowKey="id" size="small" columns={docColumns} dataSource={overview.docs} pagination={false} />,
          },
          {
            key: 'sessions',
            label: `会话（${overview.sessions.length}）`,
            children: <Table rowKey="id" size="small" columns={sessionColumns} dataSource={overview.sessions} pagination={false} />,
          },
          {
            key: 'builds',
            label: `构建（${overview.builds.length}）`,
            children: <Table rowKey="id" size="small" columns={buildColumns} dataSource={overview.builds} pagination={false} />,
          },
          {
            key: 'tests',
            label: `测试（${overview.testRuns.length}）`,
            children: <Table rowKey="id" size="small" columns={testColumns} dataSource={overview.testRuns} pagination={false} />,
          },
          {
            key: 'deploys',
            label: `部署（${overview.deployments.length}）`,
            children: <Table rowKey="id" size="small" columns={deployColumns} dataSource={overview.deployments} pagination={false} />,
          },
          {
            key: 'releases',
            label: `发版（${overview.releases.length}）`,
            children: <Table rowKey="id" size="small" columns={releaseColumns} dataSource={overview.releases} pagination={false} />,
          },
        ]}
      />
    </Space>
  )
}

function timelineColor(type: string): string {
  switch (type) {
    case 'TASK': return 'purple'
    case 'DOC': return 'green'
    case 'SESSION': return 'blue'
    case 'BUILD': return 'orange'
    case 'TEST_RUN': return 'cyan'
    case 'DEPLOYMENT': return 'red'
    case 'RELEASE': return 'magenta'
    default: return 'gray'
  }
}
