// 测试记录页（/tests）：当前项目的套件管理与测试运行历史。
// CAP-10 测试中心：套件管理（OpenAPI 生成/新建/用例编辑/沉淀文档）→ 新建测试运行（选套件+目标环境/执行节点/baseUrl）→
// 运行历史 → 详情 Drawer（WS 实时结果流）；失败运行可一键生成缺陷线索（FR-06）。
// 布局遵循 docs/core/前端内容区布局约定.md：单 Card + title 内 Segmented 切换视图，操作按钮收 extra，表格默认密度。
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import {
  DeleteOutlined,
  ExportOutlined,
  PlusOutlined,
  ReloadOutlined,
  SyncOutlined,
} from '@ant-design/icons'
import {
  createRun,
  createSuite,
  deleteRun,
  deleteSuite,
  generateSuite,
  getIssues,
  getRunLogs,
  getRunReport,
  getSuite,
  listRuns,
  listSuites,
  publishSuite,
} from '../api'
import type { IssueDraft, TestRun, TestRunStatus, TestSuite } from '../types'
import type { ProjectEnvironment } from '../../projects/types'
import type { AgentNode } from '../../agent/types'
import { listEnvironments } from '../../projects/api'
import { listAgentNodes } from '../../agent/api'
import { useCurrentProjectId } from '../../../app/useCurrentProject'
import { durationMs, fmtTime } from '../../../shared/utils/format'
import { STATUS_COLOR, SUITE_KIND_COLOR } from '../constants'
import CaseEditorDrawer from '../components/CaseEditorDrawer'
import RunDetailDrawer from '../components/RunDetailDrawer'
import IssuesTable from '../components/IssuesTable'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

export default function TestsPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return <TestCenter id={projectId} />
}

function TestCenter({ id }: { id: string }) {
  const [view, setView] = useState<string>('suites') // suites | runs
  const [suites, setSuites] = useState<TestSuite[]>([])
  const [runs, setRuns] = useState<TestRun[]>([])
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])
  const [loading, setLoading] = useState(false)

  // 新建运行弹窗表单
  const [runOpen, setRunOpen] = useState(false)
  const [suiteIds, setSuiteIds] = useState<number[]>([])
  const [environmentId, setEnvironmentId] = useState<number | undefined>()
  const [agentNodeId, setAgentNodeId] = useState<string | undefined>()
  const [baseUrl, setBaseUrl] = useState('')
  const [creating, setCreating] = useState(false)

  // 套件弹窗（新建冒烟）
  const [newOpen, setNewOpen] = useState(false)
  const [newForm] = Form.useForm()

  // 套件管理 Drawer / 用例编辑 Drawer
  const [manageId, setManageId] = useState<number | null>(null)
  const [editSuite, setEditSuite] = useState<TestSuite | null>(null)

  // 详情 Drawer / 文本（报告·日志）/ 缺陷线索
  const [detail, setDetail] = useState<TestRun | null>(null)
  const [textModal, setTextModal] = useState<{ title: string; text: string } | null>(null)
  const [issuesModal, setIssuesModal] = useState<IssueDraft[] | null>(null)

  const refresh = () => {
    listRuns(id).then(setRuns).catch(() => {})
  }

  const loadAll = async () => {
    setLoading(true)
    try {
      const [s, r, nd, ev] = await Promise.all([
        listSuites(id),
        listRuns(id),
        listAgentNodes().catch(() => []),
        listEnvironments(id).catch(() => []),
      ])
      setSuites(s)
      setRuns(r)
      setNodes(nd)
      setEnvironments(ev)
    } catch (e) {
      showError(e, '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const onGenerate = async () => {
    try {
      const s = await generateSuite(id)
      message.success(`已从 OpenAPI 生成套件「${s.name}」（${s.caseCount} 个用例）`)
      setSuites(await listSuites(id))
    } catch (e) {
      showError(e, '生成失败')
    }
  }

  const onCreateSuite = async (v: { name: string; kind: 'api' | 'smoke' }) => {
    try {
      await createSuite(id, { name: v.name, kind: v.kind })
      setNewOpen(false)
      newForm.resetFields()
      setSuites(await listSuites(id))
      message.success('套件已创建')
    } catch (e) {
      showError(e)
    }
  }

  const onDeleteSuite = (s: TestSuite) => {
    Modal.confirm({
      centered: true,
      title: `删除套件「${s.name}」？`,
      content: `将删除 ${s.caseCount} 个用例及对应结果记录，不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteSuite(s.id)
        setSuites(await listSuites(id))
        message.success('已删除')
      },
    })
  }

  const onPublish = async (s: TestSuite) => {
    try {
      const updated = await publishSuite(s.id)
      setSuites(await listSuites(id))
      message.success(`已沉淀为 api-suite 文档${updated.docId ? `（#${updated.docId}）` : ''}`)
    } catch (e) {
      showError(e, '沉淀失败')
    }
  }

  // 列表接口 cases 为空，编辑前拉全量套件（含用例明细）
  const openEditor = async (s: TestSuite) => {
    try {
      setEditSuite(await getSuite(s.id))
    } catch (e) {
      showError(e, '加载套件失败')
    }
  }

  const onCreate = async () => {
    if (!suiteIds.length) {
      message.warning('请选择测试套件')
      return
    }
    setCreating(true)
    try {
      const r = await createRun({
        projectId: id,
        suiteIds,
        agentNodeId: agentNodeId || undefined,
        environmentId: environmentId || undefined,
        baseUrl: baseUrl.trim() || undefined,
      })
      setRunOpen(false)
      setDetail(r)
      refresh()
      message.success(`测试运行 #${r.id} 已创建`)
    } catch (e) {
      showError(e)
    } finally {
      setCreating(false)
    }
  }

  // 管理 Drawer 中的套件随列表刷新保持新鲜；被删后自动关闭
  const manageSuite = manageId != null ? suites.find((s) => s.id === manageId) ?? null : null

  const suiteColumns: ColumnsType<TestSuite> = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
    { title: '名称', dataIndex: 'name', ellipsis: true, render: (n: string) => n || '-' },
    { title: '类型', dataIndex: 'kind', width: 80, render: (v: string) => <Tag color={SUITE_KIND_COLOR[v]}>{v}</Tag> },
    { title: '来源', dataIndex: 'source', width: 90, render: (v: string) => (v === 'openapi' ? <Tag color="geekblue">OpenAPI</Tag> : <Tag>手动</Tag>) },
    { title: '用例数', dataIndex: 'caseCount', width: 80 },
    { title: '沉淀文档', dataIndex: 'docId', width: 90, render: (v: number | null) => (v ? `#${v}` : <span>-</span>) },
    { title: '创建时间', dataIndex: 'createdAt', width: 150, render: (v: string) => fmtTime(v) },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, s) => (
        <Button size="small" onClick={() => setManageId(s.id)}>管理</Button>
      ),
    },
  ]

  const runColumns: ColumnsType<TestRun> = [
    { title: 'ID', dataIndex: 'id', width: 70, render: (v: number) => `#${v}` },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: TestRunStatus) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
    },
    {
      title: '结果', dataIndex: 'summary', width: 130,
      render: (s: TestRun['summary']) => (
        s ? (
          <span style={{ fontSize: 12 }}>
            {s.total} 项 · <span style={{ color: '#52c41a' }}>{s.passed} 过</span>{' '}
            <span style={{ color: s.failed ? '#ff4d4f' : undefined }}>{s.failed} 败</span>{' '}
            <span style={{ color: '#fa8c16' }}>{s.skipped} 跳</span>
          </span>
        ) : <span>-</span>
      ),
    },
    { title: '目标', dataIndex: 'baseUrl', width: 160, ellipsis: true, render: (v: string | null) => (v ? <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> : <span>-</span>) },
    {
      title: '触发', dataIndex: 'triggeredBy', width: 90,
      render: (v: string) => (v === 'deploy' ? <Tag color="purple">自动回归</Tag> : <Tag>手动</Tag>),
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 150, render: (v: string) => fmtTime(v) },
    { title: '耗时', key: 'dur', width: 100, render: (_, r) => durationMs(r.startedAt, r.finishedAt) },
    {
      title: '操作',
      key: 'act',
      width: 150,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => setDetail(r)}>详情</Button>
          <Button size="small" danger onClick={() => onDeleteRun(r)}>删除</Button>
        </Space>
      ),
    },
  ]

  const openText = async (runId: number, title: string) => {
    try {
      const text = title === '报告' ? await getRunReport(runId) : await getRunLogs(runId)
      setTextModal({ title: `测试 #${runId} ${title}`, text })
    } catch (e) {
      showError(e, '读取失败')
    }
  }

  const openIssues = async (runId: number) => {
    try {
      setIssuesModal(await getIssues(runId))
    } catch (e) {
      showError(e, '生成缺陷线索失败')
    }
  }

  const onDeleteRun = (r: TestRun) => {
    Modal.confirm({
      centered: true,
      title: `删除测试运行 #${r.id}？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteRun(r.id)
        refresh()
        message.success('已删除')
      },
    })
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title={
        <Space size={12}>
          <span>测试记录</span>
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'suites', label: '测试套件' },
              { value: 'runs', label: '运行历史' },
            ]}
          />
        </Space>
      }
      extra={
        view === 'suites' ? (
          <Space>
            <Button icon={<ReloadOutlined />} onClick={loadAll}>刷新</Button>
            <Button icon={<SyncOutlined />} onClick={onGenerate}>从 OpenAPI 生成</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setNewOpen(true)}>新建套件</Button>
          </Space>
        ) : (
          <Space>
            <Button icon={<ReloadOutlined />} onClick={loadAll}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setRunOpen(true)}>新建运行</Button>
          </Space>
        )
      }
    >
      {view === 'suites' ? (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            套件 = 一组用例；api 套件由 OpenAPI 生成（含未鉴权边界用例），smoke 冒烟套件用 health 用例做关键路径存活检查。
          </Typography.Paragraph>
          <Table<TestSuite> rowKey="id" loading={loading} dataSource={suites} columns={suiteColumns}
            pagination={LIST_PAGINATION} locale={{ emptyText: '暂无套件：先「从 OpenAPI 生成」，或新建冒烟套件（health 用例走执行节点健康检查）' }} />
        </>
      ) : (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            运行的历史记录：「详情」里看实时结果流与报告/日志；失败运行可在详情中一键生成缺陷线索。
          </Typography.Paragraph>
          <Table<TestRun> rowKey="id" loading={loading} dataSource={runs} columns={runColumns}
            pagination={LIST_PAGINATION} locale={{ emptyText: '暂无运行记录：切到「测试套件」视图准备套件后，点右上角「新建运行」执行测试' }} />
        </>
      )}

      {/* 新建套件 */}
      <Modal title="新建套件" open={newOpen} onCancel={() => setNewOpen(false)}
        onOk={() => newForm.submit()} okText="创建" width={420} destroyOnClose>
        <Form form={newForm} layout="vertical" onFinish={onCreateSuite} initialValues={{ kind: 'smoke' }}>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入套件名' }]}>
            <Input placeholder="如 冒烟套件 / 支付回归" />
          </Form.Item>
          <Form.Item label="类型" name="kind" rules={[{ required: true }]}>
            <Select options={[{ value: 'smoke', label: 'smoke（冒烟：health 用例）' }, { value: 'api', label: 'api（手工编排 http 用例）' }]} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 新建测试运行 */}
      <Modal title="新建测试运行" open={runOpen} onCancel={() => setRunOpen(false)}
        onOk={onCreate} okText="执行测试" confirmLoading={creating} width={520} destroyOnClose>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Select<number[]>
            mode="multiple"
            style={{ width: '100%' }}
            placeholder="选择测试套件"
            value={suiteIds}
            onChange={setSuiteIds}
            options={suites.map((s) => ({ value: s.id, label: `${s.name}（${s.caseCount} 用例）` }))}
          />
          <Select<number>
            style={{ width: '100%' }}
            placeholder="目标环境（可选）"
            value={environmentId}
            onChange={(v) => { setEnvironmentId(v); if (v != null) setAgentNodeId(undefined) }}
            allowClear
            options={environments.map((e) => ({ value: e.id, label: e.name }))}
          />
          <Select<string>
            style={{ width: '100%' }}
            placeholder={nodes.length ? '执行节点（可选，command 型健康检查用）' : '无可用节点（先到后台「Agent 节点」登记）'}
            value={agentNodeId}
            onChange={setAgentNodeId}
            allowClear
            disabled={environmentId != null}
            options={nodes.map((n) => ({
              value: String(n.id),
              label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
            }))}
          />
          <Input
            placeholder="baseUrl（可选，http 用例目标）"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
          />
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            目标优先级：baseUrl 显式 &gt; 环境变量（baseUrl/BASE_URL）&gt; 关联部署的环境。http 用例由服务端直接探测；command 型健康检查经 exec 帧下发执行节点。
          </Typography.Paragraph>
        </Space>
      </Modal>

      {/* 套件管理：编辑用例 / 沉淀文档 / 删除 */}
      <Drawer title={manageSuite ? `套件 · ${manageSuite.name}` : '套件'} open={!!manageSuite}
        onClose={() => setManageId(null)} width={480}>
        {manageSuite && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="ID">#{manageSuite.id}</Descriptions.Item>
              <Descriptions.Item label="类型">
                <Tag color={SUITE_KIND_COLOR[manageSuite.kind]}>{manageSuite.kind}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="来源">
                {manageSuite.source === 'openapi' ? <Tag color="geekblue">OpenAPI</Tag> : <Tag>手动</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="用例数">{manageSuite.caseCount}</Descriptions.Item>
              <Descriptions.Item label="沉淀文档">
                {manageSuite.docId ? `#${manageSuite.docId}` : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">{fmtTime(manageSuite.createdAt)}</Descriptions.Item>
            </Descriptions>
            <Space>
              <Button icon={<SyncOutlined />} onClick={() => { const s = manageSuite; setManageId(null); openEditor(s) }}>
                编辑用例
              </Button>
              <Button icon={<ExportOutlined />} disabled={!manageSuite.caseCount} onClick={() => onPublish(manageSuite)}>
                沉淀为文档
              </Button>
              <Button danger icon={<DeleteOutlined />} onClick={() => { const s = manageSuite; setManageId(null); onDeleteSuite(s) }}>
                删除
              </Button>
            </Space>
          </Space>
        )}
      </Drawer>

      <CaseEditorDrawer
        suite={editSuite}
        onClose={() => setEditSuite(null)}
        onChanged={async (s) => {
          setEditSuite(s)
          setSuites(await listSuites(id))
        }}
      />

      <RunDetailDrawer
        record={detail}
        onClose={() => setDetail(null)}
        onChanged={(r) => {
          setDetail(r)
          refresh()
        }}
        onOpenText={openText}
        onIssues={openIssues}
      />

      <Modal title={textModal?.title} open={!!textModal} footer={null} width={760}
        onCancel={() => setTextModal(null)}>
        <pre style={{ background: '#0f1115', color: '#d0d7de', padding: 12, borderRadius: 6, fontSize: 12, lineHeight: 1.6, maxHeight: '60vh', overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
          {textModal?.text || '（空）'}
        </pre>
      </Modal>

      <Modal title="缺陷线索" open={!!issuesModal} footer={null} width={760}
        onCancel={() => setIssuesModal(null)}>
        <IssuesTable issues={issuesModal ?? []} />
      </Modal>
    </Card>
  )
}
