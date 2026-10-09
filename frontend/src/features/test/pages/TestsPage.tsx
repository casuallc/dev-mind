// 测试记录页（/tests）：当前项目的套件列表与测试运行历史。
// CAP-10 测试中心 + CAP-69 脚本套件，三种类型（smoke/api/script）交互统一：
// 新建 = 统一抽屉 SuiteFormDrawer（类型选择在表单内：smoke/api 填名称、openapi 由项目 OpenAPI 文档生成、
// script 展开 git/命令/env 字段）；行操作 = 运行/编辑/删除（运行走统一 RunSuiteModal 按类型渲染字段；
// 编辑统一跳内层页 /tests/suites/:id）；顶部「新建运行」保留为多套件批量入口（不含 script，后端混入会 400）。
// 运行历史 → 详情 Drawer（WS 实时结果流）；失败运行可一键生成缺陷线索（FR-06）。
// 布局遵循 docs/core/前端内容区布局约定.md：单 Card + title 内 Segmented 切换视图，操作按钮收 extra，表格默认密度。
import {
  Button,
  Card,
  Input,
  Modal,
  Segmented,
  Select,
  Space,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { ColumnsType } from 'antd/es/table'
import {
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import {
  createRun,
  deleteRun,
  deleteScriptSuite,
  deleteSuite,
  getIssues,
  getRunLogs,
  getRunReport,
  listRuns,
  listScriptSuites,
  listSuites,
} from '../api'
import type { IssueDraft, ScriptSuite, TestRun, TestRunStatus, TestSuite } from '../types'
import type { ProjectEnvironment } from '../../projects/types'
import type { AgentNode } from '../../agent/types'
import { listEnvironments } from '../../projects/api'
import { listAgentNodes } from '../../agent/api'
import { useCurrentProjectId } from '../../../app/useCurrentProject'
import { durationMs, fmtTime } from '../../../shared/utils/format'
import { STATUS_COLOR, SUITE_KIND_COLOR } from '../constants'
import RunDetailDrawer from '../components/RunDetailDrawer'
import IssuesTable from '../components/IssuesTable'
import SuiteFormDrawer from '../components/SuiteFormDrawer'
import RunSuiteModal from '../components/RunSuiteModal'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { workItemColumn } from '../../../shared/components/WorkItemCell'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

export default function TestsPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return <TestCenter id={projectId} />
}

function TestCenter({ id }: { id: string }) {
  const navigate = useNavigate()
  const [view, setView] = useState<string>('suites') // suites | runs
  const [suites, setSuites] = useState<TestSuite[]>([])
  const [scriptSuites, setScriptSuites] = useState<ScriptSuite[]>([])
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

  // 新建套件统一抽屉（类型选择在表单内）
  const [createOpen, setCreateOpen] = useState(false)

  // 行内统一运行弹窗（runFor=null 为关闭；script 行附 scriptSuite 详情）
  const [runFor, setRunFor] = useState<TestSuite | null>(null)

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
      const [s, r, nd, ev, ss] = await Promise.all([
        listSuites(id),
        listRuns(id),
        listAgentNodes().catch(() => []),
        listEnvironments(id).catch(() => []),
        listScriptSuites(id).catch(() => []),
      ])
      setSuites(s)
      setRuns(r)
      setNodes(nd)
      setEnvironments(ev)
      setScriptSuites(ss)
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

  const onDeleteSuite = (s: TestSuite) => {
    const isScript = s.kind === 'script'
    Modal.confirm({
      centered: true,
      title: `删除套件「${s.name}」？`,
      content: isScript
        ? '运行历史记录保留，仅删除套件定义，不可恢复。'
        : `将删除 ${s.caseCount} 个用例及对应结果记录，不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        if (isScript) {
          await deleteScriptSuite(s.id)
          setScriptSuites(await listScriptSuites(id))
        } else {
          await deleteSuite(s.id)
        }
        setSuites(await listSuites(id))
        message.success('已删除')
      },
    })
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

  const scriptOf = (suiteId: number) => scriptSuites.find((x) => x.id === suiteId)

  // 列宽全部固定：名称不再吃掉剩余宽度，创建时间给足 170 不折行
  const suiteColumns: ColumnsType<TestSuite> = [
    { title: 'ID', dataIndex: 'id', width: 64, render: (v: number) => `#${v}` },
    { title: '名称', dataIndex: 'name', width: 240, ellipsis: true, render: (n: string) => n || '-' },
    { title: '类型', dataIndex: 'kind', width: 80, render: (v: string) => <Tag color={SUITE_KIND_COLOR[v]}>{v}</Tag> },
    { title: '来源', dataIndex: 'source', width: 100, render: (v: string) => (v === 'openapi' ? <Tag color="geekblue">OpenAPI</Tag> : <Tag>手动</Tag>) },
    { title: '用例数', dataIndex: 'caseCount', width: 80, render: (v: number, s) => (s.kind === 'script' ? '-' : v) },
    { title: '沉淀文档', dataIndex: 'docId', width: 90, render: (v: number | null) => (v ? `#${v}` : <span>-</span>) },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => fmtTime(v) },
    {
      title: '操作',
      key: 'action',
      width: 170,
      // 三种类型行操作统一：运行（RunSuiteModal 按类型渲染字段）/ 编辑（统一跳内层页）/ 删除
      render: (_, s) => (
        <Space size={4}>
          <Button size="small" type="primary" ghost icon={<PlayCircleOutlined />}
            onClick={() => setRunFor(s)}>运行</Button>
          <Button size="small" onClick={() => navigate(`/tests/suites/${s.id}`)}>编辑</Button>
          <Button size="small" danger onClick={() => onDeleteSuite(s)}>删除</Button>
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
    workItemColumn<TestRun>(id),
    {
      title: '触发', dataIndex: 'triggeredBy', width: 90,
      render: (v: string) => (v === 'deploy' ? <Tag color="purple">自动回归</Tag> : <Tag>手动</Tag>),
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => fmtTime(v) },
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
      styles={{ body: pageCardBodyFlexStyle }}
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
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>新建套件</Button>
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
            套件 = 一组用例；api 套件由 OpenAPI 生成（含未鉴权边界用例），smoke 冒烟套件用 health 用例做关键路径存活检查，
            script 脚本套件自带 git 源与命令、下发执行节点跑（JUnit 自动解析进用例结果）。
          </Typography.Paragraph>
          <FitTable<TestSuite> rowKey="id" loading={loading} dataSource={suites} columns={suiteColumns}
            pagination={LIST_PAGINATION} locale={{ emptyText: '暂无套件：点右上角「新建套件」——冒烟（health 用例走执行节点健康检查）/ api（手工编排或选 openapi 由项目文档生成）/ 脚本（git 源 + 命令）' }} />
        </>
      ) : (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            运行的历史记录：「详情」里看实时结果流与报告/日志；失败运行可在详情中一键生成缺陷线索。
          </Typography.Paragraph>
          <FitTable<TestRun> rowKey="id" loading={loading} dataSource={runs} columns={runColumns}
            pagination={LIST_PAGINATION} locale={{ emptyText: '暂无运行记录：切到「测试套件」视图准备套件后，点右上角「新建运行」执行测试' }} />
        </>
      )}

      {/* 新建套件（统一抽屉：类型选择在表单内，script 展开 git/命令/env 字段） */}
      {/* 新建测试运行（批量多选入口；单套件行内运行走 RunSuiteModal） */}
      <Modal title="新建测试运行" open={runOpen} onCancel={() => setRunOpen(false)}
        onOk={onCreate} okText="执行测试" confirmLoading={creating} width={520} destroyOnClose>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Select<number[]>
            mode="multiple"
            style={{ width: '100%' }}
            placeholder="选择测试套件"
            value={suiteIds}
            onChange={setSuiteIds}
            options={suites.filter((s) => s.kind !== 'script').map((s) => ({ value: s.id, label: `${s.name}（${s.caseCount} 用例）` }))}
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

      {/* 套件编辑统一走内层页 /tests/suites/:id（SuiteDetailPage 按 kind 分支：用例编排 / 脚本属性） */}

      <SuiteFormDrawer
        open={createOpen}
        projectId={id}
        nodes={nodes}
        onClose={() => setCreateOpen(false)}
        onSaved={async () => {
          setCreateOpen(false)
          setScriptSuites(await listScriptSuites(id))
          setSuites(await listSuites(id))
        }}
      />
      <RunSuiteModal
        suite={runFor}
        scriptSuite={runFor?.kind === 'script' ? scriptOf(runFor.id) : undefined}
        projectId={id}
        nodes={nodes}
        environments={environments}
        onClose={() => setRunFor(null)}
        onRan={(r) => {
          setRunFor(null)
          setDetail(r)
          refresh()
          message.success(`测试运行 #${r.id} 已创建`)
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
