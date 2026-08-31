// 需求驾驶舱（CAP-13）：左侧需求列表（按状态分组），右侧选中需求的主线视图。
// 自包含组件：内部管理需求列表 + 选中 + 聚合数据（工作单元/文档/会话/构建/测试/部署/发版/产物）
// + 需求与工作单元的新建/编辑/删除/状态推进。
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
  createRequirement,
  createWorkItem,
  deleteRequirement,
  deleteWorkItem,
  getRequirementOverview,
  listRequirements,
  updateRequirement,
  updateRequirementStatus,
  updateWorkItem,
  updateWorkItemStatus,
} from '../api'
import type {
  Requirement,
  RequirementInput,
  RequirementOverview,
  RequirementStatus,
  WorkItem,
  WorkItemInput,
  WorkItemStatus,
  WorkItemType,
} from '../types'

const STATUS_FLOW: RequirementStatus[] =
  ['DRAFT', 'ANALYZING', 'DESIGNING', 'IN_PROGRESS', 'ACCEPTANCE', 'DONE']
const ALL_STATUSES: RequirementStatus[] = [...STATUS_FLOW, 'CANCELLED']

const WI_STATUS_FLOW: WorkItemStatus[] = ['TODO', 'IN_PROGRESS', 'BLOCKED', 'DONE', 'CANCELLED']
const WI_TYPES: WorkItemType[] = ['DESIGN', 'DEVELOPMENT', 'TEST', 'DOCUMENT', 'REVIEW']

export function requirementStatusColor(s: RequirementStatus | string): string {
  switch (s) {
    case 'DRAFT': return 'default'
    case 'ANALYZING': return 'geekblue'
    case 'DESIGNING': return 'cyan'
    case 'IN_PROGRESS': return 'blue'
    case 'ACCEPTANCE': return 'purple'
    case 'DONE': return 'green'
    case 'CANCELLED': return 'red'
    default: return 'default'
  }
}

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

export default function RequirementCockpit({ projectId }: { projectId: string }) {
  const [requirements, setRequirements] = useState<Requirement[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [overview, setOverview] = useState<RequirementOverview | null>(null)
  const [overviewLoading, setOverviewLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<Requirement | null>(null)
  const [form] = Form.useForm()

  const reload = useCallback(async (keepSelection?: string | null) => {
    try {
      const rs = await listRequirements(projectId)
      setRequirements(rs)
      const keep = keepSelection !== undefined ? keepSelection : selectedId
      if (keep && rs.some((r) => r.id === keep)) {
        setSelectedId(keep)
      } else {
        setSelectedId(rs.length > 0 ? rs[0].id : null)
      }
    } catch (e) {
      message.error(`加载需求失败：${(e as Error).message}`)
    }
  }, [projectId, selectedId])

  useEffect(() => {
    reload(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  const loadOverview = useCallback(async (requirementId: string) => {
    setOverviewLoading(true)
    try {
      setOverview(await getRequirementOverview(projectId, requirementId))
    } catch (e) {
      message.error(`加载需求主线失败：${(e as Error).message}`)
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

  const openEdit = (r: Requirement | null) => {
    setEditing(r)
    form.setFieldsValue(r ?? { title: '', description: '', ownerId: '' })
    setEditOpen(true)
  }

  const onSave = async (v: RequirementInput) => {
    try {
      const saved = editing
        ? await updateRequirement(projectId, editing.id, v)
        : await createRequirement(projectId, v)
      setEditOpen(false)
      await reload(saved.id)
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const advance = async (r: Requirement, status: RequirementStatus) => {
    try {
      await updateRequirementStatus(projectId, r.id, status)
      await reload(r.id)
      message.success(`${r.code} → ${status}`)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const confirmDelete = (r: Requirement) => {
    Modal.confirm({
      centered: true,
      title: '删除需求？',
      content: `将删除需求「${r.code} ${r.title}」及其工作单元/方案（关联的文档/构建/部署记录保留，仅解除主线）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteRequirement(projectId, r.id)
        await reload(null)
        message.success('已删除')
      },
    })
  }

  // 工作单元操作（刷新聚合视图即可，需求状态由后端 rollup）
  const saveWorkItem = async (workItemId: string | null, v: WorkItemInput) => {
    if (!overview) return
    const rid = overview.requirement.id
    if (workItemId) {
      await updateWorkItem(projectId, rid, workItemId, v)
    } else {
      await createWorkItem(projectId, rid, v)
    }
    await loadOverview(rid)
    await reload(rid)
  }

  const advanceWorkItem = async (w: WorkItem, status: WorkItemStatus) => {
    if (!overview) return
    try {
      await updateWorkItemStatus(projectId, overview.requirement.id, w.id, status)
      await loadOverview(overview.requirement.id)
      await reload(overview.requirement.id)
      message.success(`${w.code} → ${status}`)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const removeWorkItem = async (w: WorkItem) => {
    if (!overview) return
    await deleteWorkItem(projectId, overview.requirement.id, w.id)
    await loadOverview(overview.requirement.id)
    await reload(overview.requirement.id)
    message.success('已删除')
  }

  // 左侧列表按状态分组（流程顺序），组内按 seq 倒序
  const groups = ALL_STATUSES
    .map((s) => ({ status: s, items: requirements.filter((r) => r.status === s) }))
    .filter((g) => g.items.length > 0)

  return (
    <Row gutter={12}>
      <Col xs={24} lg={7} xl={6}>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space>
            <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
              新建需求
            </Button>
            <Button size="small" icon={<ReloadOutlined />} onClick={() => reload()} />
          </Space>
          {requirements.length === 0 ? (
            <Empty description="暂无需求，点击「新建需求」开始一条研发主线" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            groups.map((g) => (
              <div key={g.status}>
                <Tag color={requirementStatusColor(g.status)} style={{ marginBottom: 4 }}>
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
          <Empty description="选择左侧需求查看主线视图" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <Mainline
            overview={overview}
            onAdvance={(s) => advance(overview.requirement, s)}
            onEdit={() => openEdit(overview.requirement)}
            onDelete={() => confirmDelete(overview.requirement)}
            onRefresh={() => loadOverview(overview.requirement.id)}
            onSaveWorkItem={saveWorkItem}
            onAdvanceWorkItem={advanceWorkItem}
            onRemoveWorkItem={removeWorkItem}
          />
        )}
      </Col>

      <Modal title={editing ? `编辑需求 ${editing.code}` : '新建需求'} open={editOpen} onCancel={() => setEditOpen(false)}
        onOk={() => form.submit()} okText="保存" width={560}>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="标题" name="title" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="如 用户登录支持扫码" />
          </Form.Item>
          <Form.Item label="描述（业务目标）" name="description">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item label="负责人" name="ownerId">
            <Input placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>
    </Row>
  )
}

// ---------------- 右侧主线视图 ----------------

function Mainline({ overview, onAdvance, onEdit, onDelete, onRefresh,
  onSaveWorkItem, onAdvanceWorkItem, onRemoveWorkItem }: {
  overview: RequirementOverview
  onAdvance: (s: RequirementStatus) => void
  onEdit: () => void
  onDelete: () => void
  onRefresh: () => void
  onSaveWorkItem: (workItemId: string | null, v: WorkItemInput) => Promise<void>
  onAdvanceWorkItem: (w: WorkItem, s: WorkItemStatus) => void
  onRemoveWorkItem: (w: WorkItem) => void
}) {
  const r = overview.requirement
  const flowIndex = STATUS_FLOW.indexOf(r.status)
  const [wiEditOpen, setWiEditOpen] = useState(false)
  const [wiEditing, setWiEditing] = useState<WorkItem | null>(null)
  const [wiForm] = Form.useForm()

  const openWiEdit = (w: WorkItem | null) => {
    setWiEditing(w)
    wiForm.setFieldsValue(w ?? { type: 'DEVELOPMENT', title: '', spec: '', ownerId: '', branchSlug: '' })
    setWiEditOpen(true)
  }

  const onWiSave = async (v: WorkItemInput) => {
    try {
      await onSaveWorkItem(wiEditing?.id ?? null, v)
      setWiEditOpen(false)
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const confirmWiDelete = (w: WorkItem) => {
    Modal.confirm({
      centered: true,
      title: '删除工作单元？',
      content: `将删除「${w.code} ${w.title}」（关联的会话/构建等记录保留，仅解除归属）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => onRemoveWorkItem(w),
    })
  }

  const wiColumns: ColumnsType<WorkItem> = [
    { title: '编号', dataIndex: 'code', width: 80, render: (v: string) => <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> },
    { title: '类型', dataIndex: 'type', width: 110, render: (t: string) => <Tag color={workItemTypeColor(t)}>{t}</Tag> },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 170,
      render: (s: WorkItemStatus, w) => (
        <Space size={4}>
          <Tag color={workItemStatusColor(s)} style={{ marginInlineEnd: 0 }}>{s}</Tag>
          <Select
            size="small"
            value={s}
            style={{ width: 108 }}
            variant="borderless"
            options={WI_STATUS_FLOW.map((x) => ({ value: x, label: x }))}
            onChange={(next) => onAdvanceWorkItem(w, next)}
          />
        </Space>
      ),
    },
    {
      title: '操作', key: 'ops', width: 120,
      render: (_, w) => (
        <Space size={4}>
          <Button size="small" type="link" onClick={() => openWiEdit(w)}>编辑</Button>
          <Button size="small" type="link" danger onClick={() => confirmWiDelete(w)}>删除</Button>
        </Space>
      ),
    },
  ]

  const docColumns: ColumnsType<RequirementOverview['docs'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '类型', dataIndex: 'kind', width: 90, render: (k: string) => <Tag>{k}</Tag> },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 90 },
    { title: '版本', dataIndex: 'currentVersion', width: 60, render: (v: number) => `v${v}` },
  ]
  const sessionColumns: ColumnsType<RequirementOverview['sessions'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 100, render: (v: string) => <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> },
    { title: '任务', dataIndex: 'taskSpec', ellipsis: true },
    { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <Tag color={s === 'DONE' ? 'green' : s === 'FAILED' ? 'red' : 'blue'}>{s}</Tag> },
    { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> },
  ]
  const buildColumns: ColumnsType<RequirementOverview['builds'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
    { title: '分支', dataIndex: 'branch', width: 140, ellipsis: true, render: (v?: string) => v || '-' },
    { title: 'Commit', dataIndex: 'commit', width: 100, render: (v?: string) => v ? <Typography.Text code style={{ fontSize: 12 }}>{v.slice(0, 8)}</Typography.Text> : '-' },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <Tag color={s === 'SUCCESS' ? 'green' : s === 'FAILED' ? 'red' : 'blue'}>{s}</Tag> },
    { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> },
  ]
  const testColumns: ColumnsType<RequirementOverview['testRuns'][number]> = [
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
  const deployColumns: ColumnsType<RequirementOverview['deployments'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
    { title: '环境', dataIndex: 'env', width: 80, render: (v?: string) => v || '-' },
    { title: '构建', dataIndex: 'buildId', width: 80, render: (v?: number) => v ? `#${v}` : '-' },
    { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <Tag color={s === 'SUCCESS' ? 'green' : s.includes('FAIL') ? 'red' : 'blue'}>{s}</Tag> },
    { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> },
  ]
  const releaseColumns: ColumnsType<RequirementOverview['releases'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
    { title: '版本', dataIndex: 'version', width: 120, render: (v?: string) => v ? <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> : '-' },
    { title: '状态', dataIndex: 'status', width: 110, render: (s: string) => <Tag color={s === 'SUCCESS' ? 'green' : s.includes('FAIL') || s === 'ROLLED_BACK' ? 'red' : 'blue'}>{s}</Tag> },
    { title: '执行', dataIndex: 'executor', width: 80, render: (v?: string) => v || '-' },
    { title: '回滚', dataIndex: 'rollbackOf', width: 70, render: (v?: number) => v ? `#${v}` : '-' },
    { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> },
  ]
  const artifactColumns: ColumnsType<RequirementOverview['artifacts'][number]> = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
    { title: '类型', dataIndex: 'type', width: 110, render: (t: string) => <Tag>{t}</Tag> },
    { title: '名称', dataIndex: 'name', ellipsis: true, render: (v?: string) => v || '-' },
    { title: '来源', dataIndex: 'producerType', width: 100, render: (v?: string) => v || '-' },
    { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> },
  ]

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Space wrap>
          <Typography.Text code>{r.code}</Typography.Text>
          <Typography.Text strong style={{ fontSize: 15 }}>{r.title}</Typography.Text>
          <Tag color={requirementStatusColor(r.status)}>{r.status}</Tag>
          <Select
            size="small"
            value={r.status}
            style={{ width: 140 }}
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
            style={{ maxWidth: 820 }}
          />
        )}
        <Descriptions size="small" column={{ xs: 1, sm: 3 }}>
          <Descriptions.Item label="负责人">{r.ownerId || '-'}</Descriptions.Item>
          <Descriptions.Item label="工作单元">{overview.workItems.length} 个</Descriptions.Item>
          <Descriptions.Item label="创建">{new Date(r.createdAt).toLocaleString()}</Descriptions.Item>
          {r.description && (
            <Descriptions.Item label="描述（业务目标）" span={3}>{r.description}</Descriptions.Item>
          )}
        </Descriptions>
      </Space>

      <Tabs
        size="small"
        items={[
          {
            key: 'workItems',
            label: `工作单元（${overview.workItems.length}）`,
            children: (
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <Button size="small" type="primary" ghost icon={<PlusOutlined />} onClick={() => openWiEdit(null)}>
                  新建工作单元
                </Button>
                <Table rowKey="id" size="small" columns={wiColumns} dataSource={overview.workItems} pagination={false} />
              </Space>
            ),
          },
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
          {
            key: 'artifacts',
            label: `产物（${overview.artifacts.length}）`,
            children: <Table rowKey="id" size="small" columns={artifactColumns} dataSource={overview.artifacts} pagination={false} />,
          },
        ]}
      />

      <Modal title={wiEditing ? `编辑工作单元 ${wiEditing.code}` : '新建工作单元'} open={wiEditOpen}
        onCancel={() => setWiEditOpen(false)} onOk={() => wiForm.submit()} okText="保存" width={560}>
        <Form form={wiForm} layout="vertical" onFinish={onWiSave}>
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

function timelineColor(type: string): string {
  switch (type) {
    case 'REQUIREMENT': return 'purple'
    case 'WORK_ITEM': return 'geekblue'
    case 'DOC': return 'green'
    case 'SESSION': return 'blue'
    case 'BUILD': return 'orange'
    case 'TEST_RUN': return 'cyan'
    case 'DEPLOYMENT': return 'red'
    case 'RELEASE': return 'magenta'
    case 'ARTIFACT': return 'gold'
    default: return 'gray'
  }
}
