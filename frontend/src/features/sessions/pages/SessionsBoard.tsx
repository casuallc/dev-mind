// 会话工作台：默认网格（多 Agent 并排，类 PowerShell 子窗口），可切换列表视图。
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Button,
  Card,
  Col,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Row,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { createSession, deleteSession, listSessions, listTemplates } from '../api'
import type { SessionSummary, SessionTemplate } from '../types'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { listRequirements, listWorkItems } from '../../requirements/api'
import type { Requirement, WorkItem } from '../../requirements/types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import AgentPanel from '../components/AgentPanel'
import { fmtTime } from '../../../shared/utils/format'

const stateColor: Record<string, string> = {
  RUNNING: 'processing',
  WAITING_INPUT: 'gold',
  WAITING_AUTH: 'orange',
  DONE: 'success',
  FAILED: 'error',
  SUSPENDED: 'default',
  TERMINATED: 'default',
}
const STATE_OPTIONS = ['ALL', 'RUNNING', 'WAITING_INPUT', 'WAITING_AUTH', 'DONE', 'FAILED', 'SUSPENDED', 'TERMINATED']
const ACTIVE = ['RUNNING', 'WAITING_INPUT', 'WAITING_AUTH']

export default function SessionsBoard() {
  const navigate = useNavigate()
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [templates, setTemplates] = useState<SessionTemplate[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(false)
  const [view, setView] = useState<string>('workbench') // workbench | list
  const [showAll, setShowAll] = useState<boolean>(false) // 工作台：仅活跃 / 全部
  const [status, setStatus] = useState('ALL')
  const [keyword, setKeyword] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [requirements, setRequirements] = useState<Requirement[]>([])
  const [workItems, setWorkItems] = useState<WorkItem[]>([])
  const [agentNodes, setAgentNodes] = useState<AgentNode[]>([])
  const [form] = Form.useForm()
  const timerRef = useRef<number | undefined>(undefined)
  const watchProjectId = Form.useWatch('projectId', form)
  const watchRequirementId = Form.useWatch('requirementId', form)

  const load = useCallback(async (st?: string) => {
    setLoading(true)
    try {
      setSessions(await listSessions(st ?? status))
    } catch (e) {
      message.error(`加载会话失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [status])

  // 轮询刷新状态；工作台里每个面板有独立 WS 实时流，这里只刷新状态标签/摘要
  useEffect(() => {
    load()
    const timer = window.setInterval(() => load(), 3000)
    timerRef.current = timer
    return () => window.clearInterval(timer)
  }, [load])

  useEffect(() => {
    listTemplates()
      .then(setTemplates)
      .catch(() => undefined)
    listAgentNodes()
      .then(setAgentNodes)
      .catch(() => undefined)
    listProjects('ACTIVE')
      .then((ps) => {
        setProjects(ps)
        // 默认选中种子项目 default；不存在则选第一个
        if (!ps.some((p) => p.id === 'default') && ps.length > 0 && !form.getFieldValue('projectId')) {
          form.setFieldsValue({ projectId: ps[0].id })
        }
      })
      .catch(() => undefined)
  }, [form])

  const onStateChange = useCallback(() => load(), [load])

  // 项目变化时加载其需求列表（会话可挂到工作单元/需求主线上）；切换项目清空已选关联
  useEffect(() => {
    form.setFieldsValue({ requirementId: undefined, workItemId: undefined })
    setWorkItems([])
    if (!watchProjectId) {
      setRequirements([])
      return
    }
    listRequirements(watchProjectId, { size: 200 })
      .then((data) => setRequirements(data.items.filter((r) => !['DONE', 'CANCELLED'].includes(r.status))))
      .catch(() => setRequirements([]))
  }, [watchProjectId, form])

  // 需求变化时加载其工作单元；切需求清空已选工作单元
  useEffect(() => {
    form.setFieldsValue({ workItemId: undefined })
    if (!watchProjectId || !watchRequirementId) {
      setWorkItems([])
      return
    }
    listWorkItems(watchProjectId, watchRequirementId)
      .then((ws) => setWorkItems(ws.filter((w) => !['DONE', 'CANCELLED'].includes(w.status))))
      .catch(() => setWorkItems([]))
  }, [watchProjectId, watchRequirementId, form])

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    const list = status === 'ALL' ? sessions : sessions.filter((s) => s.state === status)
    if (!kw) return list
    return list.filter(
      (s) =>
        s.id.toLowerCase().includes(kw) ||
        s.taskSpec.toLowerCase().includes(kw) ||
        (s.summary ?? '').toLowerCase().includes(kw),
    )
  }, [sessions, status, keyword])

  const workbench = useMemo(() => {
    const list = showAll ? sessions : sessions.filter((s) => ACTIVE.includes(s.state))
    return [...list].sort((a, b) => {
      const aa = ACTIVE.includes(a.state) ? 0 : 1
      const bb = ACTIVE.includes(b.state) ? 0 : 1
      return aa - bb || +new Date(b.createdAt) - +new Date(a.createdAt)
    })
  }, [sessions, showAll])

  const onCreate = async (values: {
    taskSpec: string
    templateCode?: string
    model?: string
    permissionMode?: string
    projectId?: string
    requirementId?: string
    workItemId?: string
    agentNodeId?: string
  }) => {
    setCreating(true)
    try {
      const s = await createSession({
        taskSpec: values.taskSpec,
        templateCode: values.templateCode || undefined,
        model: values.model || undefined,
        permissionMode: values.permissionMode || undefined,
        projectId: values.projectId || undefined,
        requirementId: values.requirementId || undefined,
        workItemId: values.workItemId || undefined,
        agentNodeId: values.agentNodeId || undefined,
      })
      setCreateOpen(false)
      form.resetFields()
      message.success(`会话已创建：${s.id}`)
      navigate(`/sessions/${s.id}`)
    } catch (e) {
      message.error(`创建失败：${(e as Error).message}`)
    } finally {
      setCreating(false)
    }
  }

  const confirmDelete = (r: SessionSummary) => {
    Modal.confirm({
      centered: true,
      title: '删除该会话？',
      content: '将杀掉进程（如运行中）并清理 worktree，不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteSession(r.id)
          message.success('已删除')
          load()
        } catch (e) {
          message.error(`删除失败：${(e as Error).message}`)
        }
      },
    })
  }

  const columns: ColumnsType<SessionSummary> = [
    {
      title: 'ID',
      dataIndex: 'id',
      width: 140,
      render: (id: string) => <Typography.Text code>{id}</Typography.Text>,
    },
    {
      title: '任务说明',
      dataIndex: 'taskSpec',
      ellipsis: true,
      render: (t: string) => t?.slice(0, 100) || '-',
    },
    {
      title: '状态',
      dataIndex: 'state',
      width: 130,
      render: (s: string) => <Tag color={stateColor[s] ?? 'default'}>{s}</Tag>,
    },
    {
      title: '节点',
      dataIndex: 'agentNodeId',
      width: 110,
      render: (v?: string) =>
        v ? (
          <Tag color="purple">{agentNodes.find((n) => String(n.id) === v)?.name ?? `节点${v}`}</Tag>
        ) : (
          '本机'
        ),
    },
    {
      title: '摘要',
      dataIndex: 'summary',
      ellipsis: true,
      render: (s?: string) => s?.slice(0, 80) || '-',
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 170,
      render: (t: string) => fmtTime(t),
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => navigate(`/sessions/${r.id}`)}>
            查看
          </Button>
          <Button size="small" danger onClick={() => confirmDelete(r)}>
            删除
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <Card
      title={
        <Space size={12}>
          <span>会话工作台</span>
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'workbench', label: '工作台' },
              { value: 'list', label: '列表' },
            ]}
          />
        </Space>
      }
      extra={
        <Space wrap>
          {view === 'workbench' ? (
            <>
              <Segmented
                value={showAll ? 'all' : 'active'}
                onChange={(v) => setShowAll(v === 'all')}
                options={[
                  { value: 'active', label: '仅活跃' },
                  { value: 'all', label: '全部' },
                ]}
              />
              <Button icon={<ReloadOutlined />} onClick={() => load()}>
                刷新
              </Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
                新建会话
              </Button>
            </>
          ) : (
            <>
              <Select
                value={status}
                onChange={(v) => {
                  setStatus(v)
                  load(v)
                }}
                options={STATE_OPTIONS.map((s) => ({ value: s, label: s }))}
                style={{ width: 140 }}
              />
              <Input.Search
                placeholder="搜索 ID / 任务 / 摘要"
                allowClear
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                style={{ width: 220 }}
              />
              <Button icon={<ReloadOutlined />} onClick={() => load()}>
                刷新
              </Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
                新建会话
              </Button>
            </>
          )}
        </Space>
      }
    >
      {view === 'workbench' ? (
        workbench.length === 0 ? (
          <Empty description={showAll ? '暂无会话。点击「新建会话」创建第一个。' : '暂无活跃会话，点击「新建会话」发起一个新 agent。'} />
        ) : (
          <Row gutter={[16, 16]}>
            {workbench.map((s) => (
              <Col key={s.id} xs={24} sm={24} md={12} lg={12} xl={8} style={{ height: 560 }}>
                <AgentPanel session={s} onStateChange={onStateChange} />
              </Col>
            ))}
          </Row>
        )
      ) : (
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={filtered}
          pagination={false}
          locale={{ emptyText: '暂无会话。点击「新建会话」创建第一个。' }}
        />
      )}

      {/* 新建会话 */}
      <Drawer
        title="新建会话"
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        width={600}
        destroyOnHidden
        footer={
          <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button onClick={() => setCreateOpen(false)}>取消</Button>
            <Button type="primary" loading={creating} onClick={() => form.submit()}>
              创建
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" onFinish={onCreate} initialValues={{ permissionMode: 'acceptEdits', projectId: 'default' }}>
          <Form.Item label="项目" name="projectId" extra="会话将在该项目的 worktree 中工作">
            <Select
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))}
              placeholder="选择项目（无项目时可留空裸跑）"
              allowClear
              onChange={(v?: string) =>
                // 预填项目默认执行节点（CAP-21），用户可再改/清除
                form.setFieldValue('agentNodeId', projects.find((p) => p.id === v)?.agentNodeId ?? undefined)
              }
            />
          </Form.Item>
          <Form.Item
            label="执行节点"
            name="agentNodeId"
            extra="留空 = 跟随项目默认节点（无默认则本机）；选择远程节点后，会话在该节点机上运行（工作目录取节点的项目路径映射）"
          >
            <Select
              placeholder="本机（默认）"
              allowClear
              options={agentNodes
                .filter((n) => n.status === 'ONLINE')
                .map((n) => ({ value: String(n.id), label: `${n.name} (${n.os ?? '远程节点'})` }))}
              notFoundContent="暂无在线节点（后台 → Agent 节点 注册）"
            />
          </Form.Item>
          <Form.Item label="关联需求" name="requirementId" extra="不选工作单元时直挂需求（分析型会话）">
            <Select
              options={requirements.map((r) => ({ value: r.id, label: `${r.code} ${r.title}` }))}
              placeholder="（可选）选择需求"
              allowClear
              disabled={!watchProjectId}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item label="关联工作单元" name="workItemId" extra="挂到工作单元后，会话将出现在需求详情聚合中">
            <Select
              options={workItems.map((w) => ({ value: w.id, label: `${w.code} ${w.title}` }))}
              placeholder="（可选）选择工作单元"
              allowClear
              disabled={!watchRequirementId}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item label="任务说明" name="taskSpec" rules={[{ required: true, message: '请输入任务说明' }]}>
            <Input.TextArea rows={4} placeholder="例如：为项目添加用户登录功能，编写测试并通过。" />
          </Form.Item>
          <Form.Item label="会话模板" name="templateCode" extra="选择模板后任务说明可留空，按模板渲染">
            <Select
              allowClear
              placeholder="（可选）选择模板"
              options={templates.filter((t) => t.enabled).map((t) => ({ value: t.code, label: t.name }))}
            />
          </Form.Item>
          <Form.Item label="模型" name="model">
            <Input placeholder="留空使用全局默认模型" />
          </Form.Item>
          <Form.Item label="权限模式" name="permissionMode">
            <Select
              options={[
                { value: 'acceptEdits', label: 'acceptEdits（默认）' },
                { value: 'default', label: 'default（需要授权）' },
                { value: 'bypassPermissions', label: 'bypassPermissions（全放）' },
                { value: 'plan', label: 'plan（只读规划）' },
              ]}
            />
          </Form.Item>
        </Form>
      </Drawer>
    </Card>
  )
}
