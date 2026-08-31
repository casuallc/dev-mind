// CAP-10 测试中心：套件管理（OpenAPI 生成/新建/用例编辑/沉淀文档）→ 新建测试运行（选套件+目标环境/服务器/baseUrl）→
// 运行历史 → 详情 Drawer（WS 实时结果流）；失败运行可一键生成缺陷线索（FR-06）。
import {
  Alert,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import type { FormInstance } from 'antd'
import { useEffect, useRef, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import {
  BugOutlined,
  DeleteOutlined,
  ExportOutlined,
  FileAddOutlined,
  PlusOutlined,
  SyncOutlined,
} from '@ant-design/icons'
import {
  createRun,
  createSuite,
  deleteRun,
  deleteSuite,
  generateSuite,
  getIssues,
  getRun,
  getRunLogs,
  getRunReport,
  getSuite,
  listRuns,
  listSuites,
  publishSuite,
  saveCases,
} from '../api'
import type {
  CaseResult,
  CaseResultStatus,
  IssueDraft,
  TestCase,
  TestCaseInput,
  TestRun,
  TestRunStatus,
  TestSuite,
} from '../types'
import type { ProjectEnvironment, ProjectServer } from '../../projects/types'
import { listEnvironments, listServers } from '../../projects/api'

const STATUS_COLOR: Record<TestRunStatus, string> = {
  QUEUED: 'default',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
}
const RESULT_COLOR: Record<CaseResultStatus, string> = {
  pass: 'green',
  fail: 'red',
  skip: 'orange',
}
const SUITE_KIND_COLOR: Record<string, string> = { api: 'blue', smoke: 'purple' }
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

function fmtTime(s: string | null): string {
  if (!s) return '-'
  const d = new Date(s)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function durationMs(a: string | null, b: string | null): string {
  if (!a || !b) return '-'
  const ms = new Date(b).getTime() - new Date(a).getTime()
  if (ms < 1000) return `${ms}ms`
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m ${s % 60}s`
}

const paramsToText = (p: Record<string, string> | undefined): string =>
  Object.entries(p ?? {}).map(([k, v]) => `${k}=${v}`).join('\n')

const textToParams = (t: string): Record<string, string> => {
  const out: Record<string, string> = {}
  t.split('\n').map((l) => l.trim()).filter(Boolean).forEach((l) => {
    const i = l.indexOf('=')
    if (i > 0) out[l.slice(0, i).trim()] = l.slice(i + 1).trim()
  })
  return out
}

export default function TestTab({ id }: { id: string }) {
  const [suites, setSuites] = useState<TestSuite[]>([])
  const [runs, setRuns] = useState<TestRun[]>([])
  const [servers, setServers] = useState<ProjectServer[]>([])
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])
  const [loading, setLoading] = useState(false)

  // 新建运行表单
  const [suiteIds, setSuiteIds] = useState<number[]>([])
  const [environmentId, setEnvironmentId] = useState<number | undefined>()
  const [serverId, setServerId] = useState<number | undefined>()
  const [baseUrl, setBaseUrl] = useState('')
  const [creating, setCreating] = useState(false)

  // 套件弹窗（新建冒烟）
  const [newOpen, setNewOpen] = useState(false)
  const [newForm] = Form.useForm()

  // 用例编辑 Drawer
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
      const [s, r, sv, ev] = await Promise.all([
        listSuites(id),
        listRuns(id),
        listServers(id).catch(() => []),
        listEnvironments(id).catch(() => []),
      ])
      setSuites(s)
      setRuns(r)
      setServers(sv)
      setEnvironments(ev)
    } catch (e) {
      message.error(`加载失败：${(e as Error).message}`)
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
      message.error(`生成失败：${(e as Error).message}`)
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
      message.error((e as Error).message)
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
      message.error(`沉淀失败：${(e as Error).message}`)
    }
  }

  // 列表接口 cases 为空，编辑前拉全量套件（含用例明细）
  const openEditor = async (s: TestSuite) => {
    try {
      setEditSuite(await getSuite(s.id))
    } catch (e) {
      message.error(`加载套件失败：${(e as Error).message}`)
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
        serverId: serverId || undefined,
        environmentId: environmentId || undefined,
        baseUrl: baseUrl.trim() || undefined,
      })
      setDetail(r)
      refresh()
      message.success(`测试运行 #${r.id} 已创建`)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setCreating(false)
    }
  }

  const testServers = servers.filter((s) => s.enabled && s.capabilities.includes('test'))

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
      width: 220,
      render: (_, s) => (
        <Space size={4}>
          <Button size="small" icon={<SyncOutlined />} onClick={() => openEditor(s)}>编辑用例</Button>
          <Button size="small" icon={<ExportOutlined />} disabled={!s.caseCount} onClick={() => onPublish(s)}>沉淀</Button>
          <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onDeleteSuite(s)}>删除</Button>
        </Space>
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
      title: '',
      key: 'act',
      width: 240,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => setDetail(r)}>详情</Button>
          <Button size="small" icon={<FileAddOutlined />} onClick={() => openText(r.id, '报告')}>报告</Button>
          {r.status === 'FAILED' && (
            <Button size="small" danger icon={<BugOutlined />} onClick={() => openIssues(r.id)}>缺陷线索</Button>
          )}
          <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onDeleteRun(r)} />
        </Space>
      ),
    },
  ]

  const openText = async (runId: number, title: string) => {
    try {
      const text = title === '报告' ? await getRunReport(runId) : await getRunLogs(runId)
      setTextModal({ title: `测试 #${runId} ${title}`, text })
    } catch (e) {
      message.error(`读取失败：${(e as Error).message}`)
    }
  }

  const openIssues = async (runId: number) => {
    try {
      setIssuesModal(await getIssues(runId))
    } catch (e) {
      message.error(`生成缺陷线索失败：${(e as Error).message}`)
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
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title="测试套件"
        extra={
          <Space>
            <Button size="small" type="primary" icon={<SyncOutlined />} onClick={onGenerate}>
              从 OpenAPI 生成
            </Button>
            <Button size="small" icon={<PlusOutlined />} onClick={() => setNewOpen(true)}>
              新建套件
            </Button>
            <Button size="small" icon={<FileAddOutlined />} onClick={loadAll}>刷新</Button>
          </Space>
        }
      >
        <Table<TestSuite> rowKey="id" size="small" loading={loading} dataSource={suites} columns={suiteColumns}
          pagination={false} locale={{ emptyText: '暂无套件：先「从 OpenAPI 生成」，或新建冒烟套件（health 用例走服务器健康检查）' }} />
        <div style={{ marginTop: 8, fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>
          套件 = 一组用例；api 套件由 OpenAPI 生成（含未鉴权边界用例），smoke 冒烟套件用 health 用例做关键路径存活检查。
        </div>
      </Card>

      <Card size="small" title="新建测试运行">
        <Space wrap>
          <Select<number[]>
            mode="multiple"
            style={{ minWidth: 320 }}
            placeholder="选择测试套件"
            value={suiteIds}
            onChange={setSuiteIds}
            options={suites.map((s) => ({ value: s.id, label: `${s.name}（${s.caseCount} 用例）` }))}
          />
          <Select<number>
            style={{ width: 180 }}
            placeholder="目标环境（可选）"
            value={environmentId}
            onChange={(v) => { setEnvironmentId(v); if (v != null) setServerId(undefined) }}
            allowClear
            options={environments.map((e) => ({ value: e.id, label: e.name }))}
          />
          <Select<number>
            style={{ width: 200 }}
            placeholder={testServers.length ? '目标服务器（可选）' : '无可用服务器（需 test 能力）'}
            value={serverId}
            onChange={setServerId}
            allowClear
            disabled={environmentId != null}
            options={testServers.map((s) => ({ value: s.id, label: `${s.name}（${s.accessType}）` }))}
          />
          <Input
            style={{ width: 220 }}
            placeholder="baseUrl（可选，http 用例目标）"
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
          />
          <Button type="primary" loading={creating} onClick={onCreate}>
            执行测试
          </Button>
        </Space>
        <div style={{ marginTop: 8, fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>
          目标优先级：baseUrl 显式 &gt; 服务器（http 取配置 baseUrl）&gt; 环境（变量 baseUrl/BASE_URL）。未配置时 http 用例跳过、health 用例照跑。
        </div>
      </Card>

      <Card size="small" title="运行历史">
        <Table<TestRun> rowKey="id" size="small" dataSource={runs} columns={runColumns} pagination={{ pageSize: 10 }} />
      </Card>

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

      <CaseEditorDrawer
        suite={editSuite}
        onClose={() => setEditSuite(null)}
        onChanged={async (s) => {
          setEditSuite(s)
          setSuites(await listSuites(id))
        }}
      />

      <DetailDrawer
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
    </Space>
  )
}

// ---------------- 用例编辑器（整体替换） ----------------

interface CaseFormValues {
  name: string
  kind: 'http' | 'health'
  method: string
  path: string
  paramsText: string
  headersText: string
  body: string
  expectedStatus: string
  expectedContains: string
  healthMode: 'http' | 'command'
  healthUrl: string
  healthCommand: string
  enabled: boolean
}

function caseToForm(c: TestCaseInput): CaseFormValues {
  const e = (c.expected ?? {}) as Record<string, unknown>
  const healthMode = e.type === 'command' ? 'command' : 'http'
  return {
    name: c.name ?? '',
    kind: (c.kind === 'health' ? 'health' : 'http'),
    method: c.method || 'GET',
    path: c.path ?? '',
    paramsText: paramsToText(c.params),
    headersText: paramsToText(c.headers),
    body: c.body ?? '',
    expectedStatus: String(e.status ?? ''),
    expectedContains: String(e.contains ?? ''),
    healthMode,
    healthUrl: String(e.url ?? ''),
    healthCommand: String(e.command ?? ''),
    enabled: c.enabled !== false,
  }
}

function formToCase(id: number | undefined, v: CaseFormValues): TestCaseInput {
  const expected: Record<string, unknown> = {}
  if (v.kind === 'health') {
    if (v.healthMode === 'command') {
      expected.type = 'command'
      expected.command = v.healthCommand.trim()
    } else {
      expected.type = 'http'
      if (v.healthUrl.trim()) expected.url = v.healthUrl.trim()
      const st = statusValue(v.expectedStatus)
      if (st !== undefined) expected.status = st
    }
  } else {
    const st = statusValue(v.expectedStatus)
    if (st !== undefined) expected.status = st
    if (v.expectedContains.trim()) expected.contains = v.expectedContains.trim()
  }
  return {
    id,
    name: v.name.trim(),
    kind: v.kind,
    method: v.method || 'GET',
    path: v.path.trim(),
    params: textToParams(v.paramsText),
    headers: textToParams(v.headersText),
    body: v.body || null,
    expected,
    enabled: v.enabled,
  }
}

/** status 支持整数或 "2XX" 前缀通配 */
function statusValue(s: string): number | string | undefined {
  const t = s.trim()
  if (!t) return undefined
  if (/^[1-5][0-9][0-9]$/.test(t)) return Number(t)
  return t.toUpperCase()
}

function CaseEditorDrawer({ suite, onClose, onChanged }: {
  suite: TestSuite | null
  onClose: () => void
  onChanged: (s: TestSuite) => void
}) {
  const [cases, setCases] = useState<TestCaseInput[]>([])
  const [saving, setSaving] = useState(false)
  const [editing, setEditing] = useState<TestCaseInput | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [form] = Form.useForm<CaseFormValues>()

  useEffect(() => {
    if (!suite) return
    setCases(suite.cases.map((c) => fromView(c)))
  }, [suite])

  const openEdit = (c: TestCaseInput | null) => {
    setIsNew(!c)
    setEditing(c)
    form.setFieldsValue(c ? caseToForm(c) : { kind: 'http', method: 'GET', enabled: true, healthMode: 'command' })
  }

  const saveCase = async (v: CaseFormValues) => {
    const next = formToCase(editing?.id, v)
    if (editing) {
      setCases(cases.map((c) => (c === editing ? next : c)))
    } else {
      setCases([...cases, next])
    }
    setEditing(null)
  }

  const onSaveAll = async () => {
    if (!suite) return
    setSaving(true)
    try {
      const updated = await saveCases(suite.id, cases)
      onChanged(updated)
      message.success(`已保存 ${cases.length} 个用例`)
      onClose()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  const columns: ColumnsType<TestCaseInput> = [
    { title: '#', dataIndex: 'sort', width: 44, render: (_, __, i) => i + 1 },
    { title: '名称', dataIndex: 'name', ellipsis: true, render: (n: string) => n || '-' },
    { title: '类型', dataIndex: 'kind', width: 70, render: (k: string) => <Tag color={k === 'health' ? 'purple' : 'blue'}>{k}</Tag> },
    { title: '方法', dataIndex: 'method', width: 70, render: (m: string) => <Tag>{m}</Tag> },
    { title: '路径', dataIndex: 'path', ellipsis: true, render: (p: string) => <code style={{ fontSize: 12 }}>{p}</code> },
    { title: '期望', dataIndex: 'expected', width: 160, render: (e: Record<string, unknown>) => <span style={{ fontSize: 12 }}>{JSON.stringify(e ?? {})}</span> },
    { title: '启用', dataIndex: 'enabled', width: 60, render: (v: boolean) => (v ? <Tag color="green">是</Tag> : <Tag>否</Tag>) },
    {
      title: '',
      key: 'act',
      width: 110,
      render: (_, c) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEdit(c)}>编辑</Button>
          <Button size="small" danger onClick={() => setCases(cases.filter((x) => x !== c))}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Drawer
      title={suite ? `编辑用例 · ${suite.name}（${suite.caseCount}）` : '编辑用例'}
      width={900}
      open={!!suite}
      onClose={onClose}
      extra={
        <Space>
          <Button size="small" onClick={onClose}>取消</Button>
          <Button size="small" type="primary" loading={saving} onClick={onSaveAll}>保存全部</Button>
        </Space>
      }
    >
      {suite && (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Space wrap>
            <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => openEdit(null)}>添加用例</Button>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              整体替换保存：不在列表中的现有用例将被删除；http 用例直请求 baseUrl，health 用例走目标服务器健康检查。
            </Typography.Text>
          </Space>
          <Table<TestCaseInput> rowKey={(c) => c.id ?? c.name + c.path} size="small" columns={columns}
            dataSource={cases} pagination={false} locale={{ emptyText: '暂无用例' }} />

          <Modal title={isNew ? '添加用例' : '编辑用例'} open={!!editing} onCancel={() => setEditing(null)}
            onOk={() => form.submit()} okText="保存" width={640} destroyOnClose>
            <CaseForm form={form} onFinish={saveCase} />
          </Modal>
        </Space>
      )}
    </Drawer>
  )
}

function fromView(c: TestCase): TestCaseInput {
  return {
    id: c.id,
    name: c.name,
    kind: c.kind,
    method: c.method,
    path: c.path,
    params: c.params,
    headers: c.headers,
    body: c.body,
    expected: c.expected,
    enabled: c.enabled,
  }
}

function CaseForm({ form, onFinish }: { form: FormInstance<CaseFormValues>; onFinish: (v: CaseFormValues) => void }) {
  const kind = Form.useWatch('kind', form)
  const healthMode = Form.useWatch('healthMode', form)
  return (
    <Form form={form} layout="vertical" onFinish={onFinish}>
      <Space size={8} style={{ display: 'flex' }} align="start">
        <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入用例名' }]} style={{ flex: 1 }}>
          <Input placeholder="如 健康检查 / 登录接口" />
        </Form.Item>
        <Form.Item label="类型" name="kind" style={{ width: 110 }}>
          <Select options={[{ value: 'http', label: 'http' }, { value: 'health', label: 'health' }]} />
        </Form.Item>
        <Form.Item label="启用" name="enabled" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Space>
      {kind === 'health' ? (
        <Space size={8} style={{ display: 'flex' }} align="start">
          <Form.Item label="检查方式" name="healthMode" style={{ width: 130 }}>
            <Select options={[{ value: 'command', label: '命令' }, { value: 'http', label: 'HTTP' }]} />
          </Form.Item>
          {healthMode === 'command' ? (
            <Form.Item label="命令（CAP-07 模板）" name="healthCommand" rules={[{ required: true, message: '请输入命令' }]} style={{ flex: 1 }}>
              <Input placeholder="如 echo ok 或模板 code（走服务器命令模板白名单）" />
            </Form.Item>
          ) : (
            <Space size={8} style={{ display: 'flex' }}>
              <Form.Item label="URL" name="healthUrl" style={{ width: 260 }}>
                <Input placeholder="留空用运行 baseUrl+path" />
              </Form.Item>
              <Form.Item label="期望状态" name="expectedStatus" style={{ width: 130 }}>
                <Input placeholder="如 200 或 2XX" />
              </Form.Item>
            </Space>
          )}
        </Space>
      ) : (
        <Space size={8} style={{ display: 'flex' }} align="start">
          <Form.Item label="方法" name="method" style={{ width: 110 }}>
            <Select options={METHODS.map((m) => ({ value: m, label: m }))} />
          </Form.Item>
          <Form.Item label="路径" name="path" rules={[{ required: true, message: '请输入路径' }]} style={{ flex: 1 }}>
            <Input placeholder="如 /api/users/{id}" />
          </Form.Item>
        </Space>
      )}
      {kind !== 'health' && (
        <>
          <Space size={8} style={{ display: 'flex' }}>
            <Form.Item label="Query 参数（每行 k=v）" name="paramsText" style={{ flex: 1 }}>
              <Input.TextArea rows={2} placeholder="name=test" />
            </Form.Item>
            <Form.Item label="Header（每行 k=v）" name="headersText" style={{ flex: 1 }}>
              <Input.TextArea rows={2} placeholder="X-Api-Key=xxx" />
            </Form.Item>
          </Space>
          <Form.Item label="请求体（JSON）" name="body">
            <Input.TextArea rows={2} placeholder='{"name":"carol"}' />
          </Form.Item>
          <Space size={8} style={{ display: 'flex' }}>
            <Form.Item label="期望状态" name="expectedStatus" style={{ width: 130 }}>
              <Input placeholder="如 200 或 2XX" />
            </Form.Item>
            <Form.Item label="期望包含（可选）" name="expectedContains" style={{ flex: 1 }}>
              <Input placeholder="响应体包含的子串" />
            </Form.Item>
          </Space>
        </>
      )}
    </Form>
  )
}

// ---------------- 详情 Drawer（WS 实时） ----------------

function DetailDrawer({ record, onClose, onChanged, onOpenText, onIssues }: {
  record: TestRun | null
  onClose: () => void
  onChanged: (r: TestRun) => void
  onOpenText: (runId: number, title: string) => void
  onIssues: (runId: number) => void
}) {
  const [d, setD] = useState<TestRun | null>(record)
  const [connected, setConnected] = useState(false)
  const latestRef = useRef<TestRun | null>(record)

  useEffect(() => {
    latestRef.current = d
  }, [d])

  useEffect(() => {
    setD(record)
    setConnected(false)
    if (!record) return
    latestRef.current = record

    const active = record.status === 'QUEUED' || record.status === 'RUNNING'
    if (active) {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${proto}://${location.host}/ws/test-runs/${record.id}/stream`)
      ws.onopen = () => setConnected(true)
      ws.onmessage = (msg) => {
        try {
          const f = JSON.parse(msg.data)
          if (f.type === 'snapshot') {
            setD((cur) => (cur ? { ...cur, status: f.status, baseUrl: f.baseUrl ?? cur.baseUrl, results: f.results ?? cur.results } : cur))
          } else if (f.type === 'result') {
            setD((cur) => (cur ? { ...cur, results: [...cur.results, f.result] } : cur))
          } else if (f.type === 'done') {
            setConnected(false)
            setD((cur) => (cur ? { ...cur, status: f.status } : cur))
            ws.close()
          }
        } catch {
          /* 忽略坏帧 */
        }
      }
      ws.onclose = () => setConnected(false)
      return () => ws.close()
    }

    // 终态：先拉一次完整结果，再定时刷新最终 summary/时间
    getRun(record.id).then((latest) => {
      if (latest.results?.length) {
        setD(latest)
        latestRef.current = latest
      }
    }).catch(() => {})
    const timer = setInterval(() => {
      getRun(record.id).then((latest) => {
        const cur = latestRef.current
        if (!cur || JSON.stringify(latest) !== JSON.stringify(cur)) {
          setD(latest)
          latestRef.current = latest
          onChanged(latest)
        }
      }).catch(() => {})
    }, 4000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record])

  const resultColumns: ColumnsType<CaseResult> = [
    { title: '#', dataIndex: 'sort', width: 44 },
    { title: '用例', dataIndex: 'name', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (v: CaseResultStatus) => <Tag color={RESULT_COLOR[v]}>{v}</Tag>,
    },
    { title: '请求', dataIndex: 'requestSummary', width: 220, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '响应', dataIndex: 'responseSummary', width: 220, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '耗时', dataIndex: 'duration', width: 80, render: (v: number | null) => (v != null ? `${v}ms` : '-') },
  ]

  return (
    <Drawer
      title={d ? (
        <Space>
          <span>测试运行 #{d.id}</span>
          <Tag color={STATUS_COLOR[d.status]}>{d.status}</Tag>
          {d.triggeredBy === 'deploy' && <Tag color="purple">自动回归</Tag>}
          {connected && <Tag color="cyan">实时</Tag>}
        </Space>
      ) : '测试详情'}
      width={820}
      open={!!record}
      onClose={onClose}
    >
      {d && (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {d.errorSummary && <Alert type="error" showIcon message={d.errorSummary} />}
          <Space wrap>
            <Button size="small" onClick={() => onOpenText(d.id, '报告')}>报告</Button>
            <Button size="small" onClick={() => onOpenText(d.id, '日志')}>日志</Button>
            {d.status === 'FAILED' && (
              <Button size="small" danger icon={<BugOutlined />} onClick={() => onIssues(d.id)}>缺陷线索</Button>
            )}
          </Space>
          <Space wrap size={8}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              目标 {d.baseUrl || '-'} · 服务器 # {d.serverId ?? '-'} · 环境 {d.environmentId ? `#${d.environmentId}` : '-'} · 部署 #{d.deploymentId ?? '-'}
            </Typography.Text>
            {d.summary && (
              <span style={{ fontSize: 12 }}>
                {d.summary.total} 项 · <span style={{ color: '#52c41a' }}>{d.summary.passed} 过</span>{' '}
                <span style={{ color: d.summary.failed ? '#ff4d4f' : undefined }}>{d.summary.failed} 败</span>{' '}
                <span style={{ color: '#fa8c16' }}>{d.summary.skipped} 跳</span>
              </span>
            )}
            {d.reportDocId && <Tag color="geekblue">报告文档 #{d.reportDocId}</Tag>}
          </Space>
          <Typography.Text strong style={{ fontSize: 13 }}>用例结果（{d.results?.length ?? 0}）</Typography.Text>
          <Table<CaseResult> rowKey="id" size="small" columns={resultColumns} dataSource={d.results ?? []}
            pagination={false} locale={{ emptyText: '暂无结果（运行中或未配置目标）' }} />
        </Space>
      )}
    </Drawer>
  )
}

// ---------------- 缺陷线索表格 ----------------

function IssuesTable({ issues }: { issues: IssueDraft[] }) {
  const columns: ColumnsType<IssueDraft> = [
    { title: '缺陷标题', dataIndex: 'title', ellipsis: true },
    { title: '期望', dataIndex: 'expected', width: 240, ellipsis: true, render: (v: string) => <span style={{ fontSize: 12 }}>{v || '-'}</span> },
    { title: '实际', dataIndex: 'actual', width: 240, ellipsis: true, render: (v: string) => <span style={{ fontSize: 12, color: '#ff4d4f' }}>{v || '-'}</span> },
  ]
  if (!issues.length) {
    return <Typography.Text type="secondary">无失败用例可转。</Typography.Text>
  }
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        失败用例已汇总为缺陷线索，可直接作为缺陷单标题与复现信息派发修复 Agent。
      </Typography.Text>
      <Table<IssueDraft> rowKey="caseId" size="small" columns={columns} dataSource={issues} pagination={false} />
    </Space>
  )
}
