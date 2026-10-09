// 脚本测试页（/script-tests）：CAP-69 独立于项目的脚本套件——自带 git 源 + 命令 + env（脱敏）+ 超时，
// 触发后整包下发执行节点跑，JUnit XML 经 stdout marker 回收解析进用例结果。
// 运行历史复用 RunDetailDrawer（WS 实时结果/日志流）；失败运行可一键生成缺陷线索。
// 布局遵循 docs/core/前端内容区布局约定.md：单 Card + title 内 Segmented 切换视图，操作按钮收 extra，表格默认密度。
import {
  Button,
  Card,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Segmented,
  Select,
  Space,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { DeleteOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  createScriptSuite,
  deleteRun,
  deleteScriptSuite,
  getIssues,
  getRunLogs,
  getRunReport,
  listScriptRuns,
  listScriptSuites,
  runScriptSuite,
  updateScriptSuite,
} from '../api'
import type { IssueDraft, ScriptSuite, ScriptSuiteInput, ScriptSuiteRunInput, TestRun, TestRunStatus } from '../types'
import type { AgentNode } from '../../agent/types'
import { listAgentNodes } from '../../agent/api'
import { durationMs, fmtTime } from '../../../shared/utils/format'
import { STATUS_COLOR } from '../constants'
import RunDetailDrawer from '../components/RunDetailDrawer'
import IssuesTable from '../components/IssuesTable'
import EnvEditor from '../components/EnvEditor'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

export default function ScriptSuitesPage() {
  const [view, setView] = useState<string>('suites') // suites | runs
  const [suites, setSuites] = useState<ScriptSuite[]>([])
  const [runs, setRuns] = useState<TestRun[]>([])
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [loading, setLoading] = useState(false)

  // 新建/编辑抽屉（editing=null 为新建）
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<ScriptSuite | null>(null)
  const [editForm] = Form.useForm()
  const [saving, setSaving] = useState(false)

  // 运行弹窗
  const [runFor, setRunFor] = useState<ScriptSuite | null>(null)
  const [runForm] = Form.useForm()
  const [running, setRunning] = useState(false)

  // 详情 Drawer / 文本（报告·日志）/ 缺陷线索
  const [detail, setDetail] = useState<TestRun | null>(null)
  const [textModal, setTextModal] = useState<{ title: string; text: string } | null>(null)
  const [issuesModal, setIssuesModal] = useState<IssueDraft[] | null>(null)

  const nodeName = (id: string | null) => {
    if (!id) return '-'
    const n = nodes.find((x) => String(x.id) === id)
    return n ? n.name : `#${id}`
  }

  const loadAll = async () => {
    setLoading(true)
    try {
      const [s, r, nd] = await Promise.all([
        listScriptSuites(),
        listScriptRuns(),
        listAgentNodes().catch(() => []),
      ])
      setSuites(s)
      setRuns(r)
      setNodes(nd)
    } catch (e) {
      showError(e, '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const nodeOptions = nodes.map((n) => ({
    value: String(n.id),
    label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
  }))

  const openCreate = () => {
    setEditing(null)
    editForm.setFieldsValue({
      name: '', repoUrl: '', branch: 'master', workSubdir: '', command: '',
      junitPath: 'target/junit.xml', timeoutSec: 7200, workspaceKey: '', agentNodeId: undefined, env: [],
    })
    setEditOpen(true)
  }

  const openEdit = (s: ScriptSuite) => {
    setEditing(s)
    editForm.setFieldsValue({
      name: s.name,
      repoUrl: s.repoUrl,
      branch: s.branch,
      workSubdir: s.workSubdir ?? '',
      command: s.command,
      junitPath: s.junitPath,
      timeoutSec: s.timeoutSec ?? 7200,
      workspaceKey: s.workspaceKey ?? '',
      agentNodeId: s.agentNodeId ?? undefined,
      env: s.env,
    })
    setEditOpen(true)
  }

  const onSave = async () => {
    const v = await editForm.validateFields()
    const input: ScriptSuiteInput = {
      name: v.name,
      repoUrl: v.repoUrl,
      branch: v.branch,
      workSubdir: v.workSubdir?.trim() || undefined,
      command: v.command,
      junitPath: v.junitPath,
      env: (v.env ?? []).filter((e: { key?: string }) => e.key?.trim()),
      agentNodeId: v.agentNodeId || undefined,
      timeoutSec: v.timeoutSec ?? undefined,
      workspaceKey: v.workspaceKey?.trim() || undefined,
    }
    setSaving(true)
    try {
      if (editing) {
        await updateScriptSuite(editing.id, input)
        message.success('套件已保存')
      } else {
        await createScriptSuite(input)
        message.success('套件已创建')
      }
      setEditOpen(false)
      setSuites(await listScriptSuites())
    } catch (e) {
      showError(e)
    } finally {
      setSaving(false)
    }
  }

  const onDeleteSuite = (s: ScriptSuite) => {
    Modal.confirm({
      centered: true,
      title: `删除脚本套件「${s.name}」？`,
      content: '运行历史记录保留，仅删除套件定义，不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteScriptSuite(s.id)
        setSuites(await listScriptSuites())
        message.success('已删除')
      },
    })
  }

  const openRun = (s: ScriptSuite) => {
    setRunFor(s)
    runForm.setFieldsValue({ agentNodeId: undefined, env: [], command: '' })
  }

  const onRun = async () => {
    if (!runFor) return
    const v = await runForm.validateFields()
    const envRows = (v.env ?? []).filter((e: { key?: string }) => e.key?.trim())
    const input: ScriptSuiteRunInput = {
      agentNodeId: v.agentNodeId || undefined,
      env: envRows.length ? Object.fromEntries(envRows.map((e: { key: string; value?: string }) => [e.key, e.value ?? ''])) : undefined,
      command: v.command?.trim() || undefined,
    }
    setRunning(true)
    try {
      const r = await runScriptSuite(runFor.id, input)
      setRunFor(null)
      setView('runs')
      setDetail(r)
      setRuns(await listScriptRuns())
      message.success(`测试运行 #${r.id} 已创建`)
    } catch (e) {
      showError(e)
    } finally {
      setRunning(false)
    }
  }

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
        setRuns(await listScriptRuns())
        message.success('已删除')
      },
    })
  }

  const suiteColumns: ColumnsType<ScriptSuite> = [
    { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
    { title: '名称', dataIndex: 'name', width: 180, ellipsis: true },
    {
      title: '仓库', dataIndex: 'repoUrl', width: 240, ellipsis: true,
      render: (v: string, s) => (
        <span>
          <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text>
          <Tag style={{ marginLeft: 4 }}>{s.branch}</Tag>
        </span>
      ),
    },
    { title: '子目录', dataIndex: 'workSubdir', width: 100, render: (v: string | null) => v || '-' },
    {
      title: '命令', dataIndex: 'command', ellipsis: true,
      render: (v: string) => <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text>,
    },
    { title: '默认节点', dataIndex: 'agentNodeId', width: 110, render: (v: string | null) => nodeName(v) },
    { title: '超时', dataIndex: 'timeoutSec', width: 80, render: (v: number | null) => (v ? `${Math.round(v / 60)}min` : '-') },
    { title: '工作区', dataIndex: 'workspaceKey', width: 120, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => fmtTime(v) },
    {
      title: '操作',
      key: 'action',
      width: 160,
      render: (_, s) => (
        <Space size={4}>
          <Button size="small" type="primary" ghost icon={<PlayCircleOutlined />} onClick={() => openRun(s)}>运行</Button>
          <Button size="small" onClick={() => openEdit(s)}>编辑</Button>
          <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onDeleteSuite(s)} />
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
    { title: '节点', dataIndex: 'agentNodeId', width: 120, render: (v: string | null) => nodeName(v) },
    { title: '备注', dataIndex: 'errorSummary', ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => fmtTime(v) },
    { title: '耗时', key: 'dur', width: 100, render: (_, r) => durationMs(r.startedAt, r.finishedAt) },
    {
      title: '操作',
      key: 'act',
      width: 140,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => setDetail(r)}>详情</Button>
          <Button size="small" danger onClick={() => onDeleteRun(r)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>脚本测试</span>
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'suites', label: '脚本套件' },
              { value: 'runs', label: '运行历史' },
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={loadAll}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建套件</Button>
        </Space>
      }
    >
      {view === 'suites' ? (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            脚本套件独立于项目：自带 git 源（节点 clone/pull）+ 命令模板 + env（脱敏）+ 超时；
            触发后整包下发执行节点，JUnit XML 自动回收解析为用例结果。
            多个套件配同一个工作区 key 可共享工作区目录（套件间状态文件跨运行保留）。
          </Typography.Paragraph>
          <FitTable<ScriptSuite> rowKey="id" loading={loading} dataSource={suites} columns={suiteColumns}
            pagination={LIST_PAGINATION} locale={{ emptyText: '暂无脚本套件：点右上角「新建套件」登记 git 源与执行命令' }} />
        </>
      ) : (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            脚本套件的运行历史：「详情」里看实时日志与用例结果流；失败运行可在详情中一键生成缺陷线索。
          </Typography.Paragraph>
          <FitTable<TestRun> rowKey="id" loading={loading} dataSource={runs} columns={runColumns}
            pagination={LIST_PAGINATION} locale={{ emptyText: '暂无运行记录：切到「脚本套件」视图点「运行」触发一次' }} />
        </>
      )}

      {/* 新建/编辑套件 */}
      <Drawer
        title={editing ? `编辑脚本套件「${editing.name}」` : '新建脚本套件'}
        width={640}
        open={editOpen}
        onClose={() => setEditOpen(false)}
        extra={
          <Space>
            <Button onClick={() => setEditOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={onSave}>{editing ? '保存' : '创建'}</Button>
          </Space>
        }
      >
        <Form form={editForm} layout="vertical">
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入套件名' }]}>
            <Input placeholder="如 ADMQ Manager UI E2E" />
          </Form.Item>
          <Form.Item label="git 仓库地址" name="repoUrl" rules={[{ required: true, message: '请输入仓库地址' }]}
            extra="节点凭自身 git 凭据 clone/pull（内网匿名读或节点 credential helper）">
            <Input placeholder="http://git.local/group/repo.git" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item label="分支" name="branch" rules={[{ required: true, message: '请输入分支' }]} style={{ flex: 1 }}>
              <Input placeholder="master" />
            </Form.Item>
            <Form.Item label="工作子目录（可选）" name="workSubdir" style={{ flex: 1 }}
              extra="命令在仓库内该子目录执行，如 e2e">
              <Input placeholder="e2e" />
            </Form.Item>
          </Space>
          <Form.Item label="执行命令" name="command" rules={[{ required: true, message: '请输入执行命令' }]}
            extra="runner execAllowlist 逐行校验首词，npm/npx/git 等需先放行">
            <Input.TextArea rows={4} placeholder={'npm ci\nnpx playwright test'} style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Form.Item label="JUnit 产出路径" name="junitPath" rules={[{ required: true, message: '请输入 JUnit 产出路径' }]}
            extra="相对工作子目录；缺失时不判失败，仅在备注中注记">
            <Input placeholder="test-results/junit.xml" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item label="超时（秒）" name="timeoutSec" style={{ flex: 1 }}>
              <InputNumber min={60} max={86400} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="默认节点" name="agentNodeId" style={{ flex: 1 }}
              extra="留空 = 平台默认节点">
              <Select allowClear placeholder="平台默认节点" options={nodeOptions} />
            </Form.Item>
          </Space>
          <Form.Item label="工作区 key（可选）" name="workspaceKey"
            extra="默认 = 套件 id；多套件共享同一 key 即共享节点工作区目录">
            <Input placeholder="如 admq-e2e" />
          </Form.Item>
          <Form.Item label="环境变量" name="env"
            extra="注入执行进程；脱敏项保存后恒显示掩码，掩码原样回传表示该条不变">
            <EnvEditor />
          </Form.Item>
        </Form>
      </Drawer>

      {/* 运行弹窗 */}
      <Modal
        title={runFor ? `运行脚本套件「${runFor.name}」` : '运行脚本套件'}
        open={!!runFor}
        onCancel={() => setRunFor(null)}
        onOk={onRun}
        okText="执行测试"
        confirmLoading={running}
        width={560}
      >
        <Form form={runForm} layout="vertical">
          <Form.Item label="执行节点（可选）" name="agentNodeId"
            extra="留空 = 套件默认节点 → 平台默认节点">
            <Select allowClear placeholder="套件默认 → 平台默认" options={nodeOptions} />
          </Form.Item>
          <Form.Item label="env 覆盖（可选，仅本次生效）" name="env">
            <EnvEditor withSecret={false} />
          </Form.Item>
          <Form.Item label="命令覆盖（可选，仅本次生效）" name="command">
            <Input.TextArea rows={3} placeholder={runFor?.command || '留空 = 套件命令'} style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        </Form>
      </Modal>

      <RunDetailDrawer
        record={detail}
        onClose={() => setDetail(null)}
        onChanged={(r) => {
          setDetail(r)
          listScriptRuns().then(setRuns).catch(() => {})
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
