// CAP-104 用例库（/tests → 用例页签）：按模块/类型/评审状态检索 CaseSpec 用例，
// 支持新建/编辑（规范编辑器）、不可变版本历史、评审通过/废弃、单用例运行。
// 面板自带筛选与操作条（与 AgentNodesPage 的子面板同构），Card extra 只随页签切换。
import {
  Button,
  Descriptions,
  Drawer,
  Dropdown,
  Flex,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { DownOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  createCase,
  deleteCase,
  listAssets,
  listCaseVersions,
  listModules,
  listSuites,
  reviewCase,
  runCase,
  searchCases,
  updateCase,
} from '../api'
import type { AgentNode } from '../../agent/types'
import type { ProjectEnvironment } from '../../projects/types'
import { listAgentNodes } from '../../agent/api'
import { listEnvironments } from '../../projects/api'
import { usePermissions } from '../../auth/usePermissions'
import { fmtTime } from '../../../shared/utils/format'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import FitTable from '../../../shared/components/FitTable'
import {
  CASE_REVIEW_OPTIONS,
  CASE_TYPE_COLOR,
  REVIEW_COLOR,
  REVIEW_LABEL,
} from '../constants'
import type {
  TestAsset,
  TestCase,
  TestCaseVersion,
  TestModule,
  TestRun,
  TestSuite,
} from '../types'
import CaseSpecDrawer, { formToSpec, type CaseSpecFormValues } from './CaseSpecDrawer'
import ModuleManageDrawer from './ModuleManageDrawer'

const TYPE_FILTER_OPTIONS = [
  { value: 'HTTP', label: 'HTTP' },
  { value: 'COMMAND', label: 'COMMAND' },
  { value: 'MANUAL', label: 'MANUAL' },
]

export default function CaseLibraryPanel({ projectId, onOpenRun }: {
  projectId: string
  onOpenRun: (run: TestRun) => void
}) {
  const { canAction } = usePermissions()
  const canManage = canAction('project.tests.action.manage-case')
  const canReview = canAction('project.tests.action.review-case')
  const canRun = canAction('project.tests.action.run')

  const [modules, setModules] = useState<TestModule[]>([])
  const [suites, setSuites] = useState<TestSuite[]>([])
  const [assets, setAssets] = useState<TestAsset[]>([])
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])
  const [rows, setRows] = useState<TestCase[]>([])
  const [loading, setLoading] = useState(false)

  const [moduleId, setModuleId] = useState<number | undefined>()
  const [type, setType] = useState<string | undefined>()
  const [reviewStatus, setReviewStatus] = useState<string | undefined>()
  const [keyword, setKeyword] = useState<string | undefined>()

  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<TestCase | null>(null)
  const [saving, setSaving] = useState(false)
  const [modulesOpen, setModulesOpen] = useState(false)
  const [versionsFor, setVersionsFor] = useState<TestCase | null>(null)
  const [versions, setVersions] = useState<TestCaseVersion[]>([])
  const [versionsLoading, setVersionsLoading] = useState(false)
  const [runFor, setRunFor] = useState<TestCase | null>(null)
  const [runEnv, setRunEnv] = useState<number | undefined>()
  const [runNode, setRunNode] = useState<string | undefined>()
  const [runBaseUrl, setRunBaseUrl] = useState('')
  const [running, setRunning] = useState(false)

  const loadRef = useCallback(async () => {
    const [m, s, a, n, ev] = await Promise.all([
      listModules(projectId).catch(() => []),
      listSuites(projectId).catch(() => []),
      listAssets(projectId, 'APPROVED').catch(() => []),
      listAgentNodes().catch(() => []),
      listEnvironments(projectId).catch(() => []),
    ])
    setModules(m)
    setSuites(s.filter((x) => x.kind !== 'script'))
    setAssets(a)
    setNodes(n)
    setEnvironments(ev)
  }, [projectId])

  const loadCases = useCallback(() => {
    setLoading(true)
    searchCases({
      projectId,
      moduleId,
      type,
      reviewStatus,
      keyword: keyword?.trim() || undefined,
    })
      .then(setRows)
      .catch((e) => showError(e, '加载用例失败'))
      .finally(() => setLoading(false))
  }, [projectId, moduleId, type, reviewStatus, keyword])

  useEffect(() => {
    void loadRef()
  }, [loadRef])

  useEffect(() => {
    loadCases()
  }, [loadCases])

  const suiteById = useMemo(() => new Map(suites.map((s) => [s.id, s])), [suites])
  const moduleById = useMemo(() => new Map(modules.map((m) => [m.id, m])), [modules])

  const moduleName = (c: TestCase): string => {
    const s = suiteById.get(c.suiteId)
    const m = s?.moduleId != null ? moduleById.get(s.moduleId) : undefined
    return m?.name ?? '未分类'
  }

  const openCreate = () => {
    if (suites.length === 0) {
      message.warning('当前项目还没有测试套件：先到「测试套件」页签新建')
      return
    }
    setEditing(null)
    setEditorOpen(true)
  }

  const openEdit = (c: TestCase) => {
    setEditing(c)
    setEditorOpen(true)
  }

  const onSave = async (v: CaseSpecFormValues) => {
    setSaving(true)
    try {
      const spec = formToSpec(v)
      const res = editing
        ? await updateCase(editing.id, spec, v.changeNote?.trim() || undefined)
        : await createCase(v.suiteId, spec, v.changeNote?.trim() || undefined)
      message.success(editing
        ? `已保存（v${res.testCase.caseVersion}，待评审）`
        : `已创建 ${res.testCase.caseKey ?? '用例'}（草稿）`)
      if (res.duplicates.length) {
        message.warning(`发现 ${res.duplicates.length} 个疑似重复用例：${res.duplicates.map((d) => d.caseKey).join('、')}`)
      }
      setEditorOpen(false)
      loadCases()
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const onReview = (c: TestCase, status: 'APPROVED' | 'DEPRECATED') => {
    Modal.confirm({
      centered: true,
      title: status === 'APPROVED' ? `评审通过「${c.name}」？` : `废弃「${c.name}」？`,
      content: status === 'APPROVED'
        ? '通过后可被方案/回归集引用；本次评审记录写入当前版本。'
        : '废弃后不可被方案新增引用，历史运行与版本保留。',
      okText: status === 'APPROVED' ? '通过' : '废弃',
      okButtonProps: status === 'DEPRECATED' ? { danger: true } : undefined,
      cancelText: '取消',
      onOk: async () => {
        await reviewCase(c.id, status)
        message.success(status === 'APPROVED' ? '已通过评审' : '已废弃')
        loadCases()
      },
    })
  }

  const onDelete = (c: TestCase) => {
    Modal.confirm({
      centered: true,
      title: `删除用例「${c.name}」？`,
      content: '将同时删除全部版本记录，不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteCase(c.id)
        message.success('已删除')
        loadCases()
      },
    })
  }

  const openVersions = async (c: TestCase) => {
    setVersionsFor(c)
    setVersions([])
    setVersionsLoading(true)
    try {
      setVersions(await listCaseVersions(c.id))
    } catch (e) {
      showError(e, '加载版本失败')
    } finally {
      setVersionsLoading(false)
    }
  }

  const openRun = (c: TestCase) => {
    setRunFor(c)
    setRunEnv(undefined)
    setRunNode(undefined)
    setRunBaseUrl('')
  }

  const doRun = async () => {
    if (!runFor) return
    setRunning(true)
    try {
      const r = await runCase(runFor.id, {
        environmentId: runEnv,
        agentNodeId: runNode,
        baseUrl: runBaseUrl.trim() || undefined,
      })
      setRunFor(null)
      message.success(`测试运行 #${r.id} 已创建`)
      onOpenRun(r)
    } catch (e) {
      showError(e, '运行失败')
    } finally {
      setRunning(false)
    }
  }

  const defaultSuiteId = useMemo(() => {
    const inModule = moduleId != null ? suites.find((s) => s.moduleId === moduleId) : undefined
    return (inModule ?? suites[0])?.id
  }, [suites, moduleId])

  const columns: ColumnsType<TestCase> = [
    {
      title: '标识', dataIndex: 'caseKey', width: 150,
      render: (v: string | null) => (v ? <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> : '-'),
    },
    {
      title: '标题', dataIndex: 'name', ellipsis: true,
      render: (v: string, c) => <Typography.Link onClick={() => void openVersions(c)}>{v}</Typography.Link>,
    },
    {
      title: '类型', dataIndex: 'type', width: 110,
      render: (v: string | null) => (v
        ? <Tag color={CASE_TYPE_COLOR[v as keyof typeof CASE_TYPE_COLOR]}>{v}</Tag>
        : '-'),
    },
    { title: '优先级', dataIndex: 'priority', width: 80, render: (v: string | null) => v || '-' },
    { title: '模块', key: 'module', width: 110, ellipsis: true, render: (_, c) => moduleName(c) },
    { title: '套件', key: 'suite', width: 160, ellipsis: true, render: (_, c) => suiteById.get(c.suiteId)?.name ?? `#${c.suiteId}` },
    {
      title: '评审', dataIndex: 'reviewStatus', width: 90,
      render: (v: keyof typeof REVIEW_COLOR | null) => (v ? <Tag color={REVIEW_COLOR[v]}>{REVIEW_LABEL[v]}</Tag> : '-'),
    },
    { title: '版本', dataIndex: 'caseVersion', width: 70, render: (v: number | null) => (v ? `v${v}` : '-') },
    {
      title: '操作',
      key: 'act',
      width: 190,
      render: (_, c) => {
        const menu = [
          { key: 'versions', label: '版本历史' },
          ...(canReview ? [
            ...(c.reviewStatus !== 'APPROVED' ? [{ key: 'approve', label: '评审通过' }] : []),
            ...(c.reviewStatus !== 'DEPRECATED' ? [{ key: 'deprecate', label: '废弃' }] : []),
          ] : []),
          ...(canManage ? [{ key: 'delete', label: '删除' }] : []),
        ]
        return (
          <Space size={4}>
            {canRun && <Button size="small" onClick={() => openRun(c)}>运行</Button>}
            {canManage && <Button size="small" onClick={() => openEdit(c)}>编辑</Button>}
            <Dropdown
              menu={{
                items: menu,
                onClick: ({ key }) => {
                  if (key === 'versions') void openVersions(c)
                  else if (key === 'approve') onReview(c, 'APPROVED')
                  else if (key === 'deprecate') onReview(c, 'DEPRECATED')
                  else if (key === 'delete') onDelete(c)
                },
              }}
            >
              <Button size="small">管理 <DownOutlined /></Button>
            </Dropdown>
          </Space>
        )
      },
    },
  ]

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        用例库以 CaseSpec 为唯一事实来源（HTTP / COMMAND / MANUAL）：保存生成不可变版本，评审通过的用例才可进入回归方案；
        点击标题查看版本历史与规范快照。
      </Typography.Paragraph>
      <Flex justify="space-between" wrap gap={8} style={{ marginBottom: 12 }}>
        <Space wrap>
          <Select<number>
            allowClear
            style={{ width: 180 }}
            placeholder="全部模块"
            value={moduleId}
            onChange={setModuleId}
            options={modules.map((m) => ({ value: m.id, label: `${m.name}（${m.suiteCount}）` }))}
          />
          <Select<string>
            allowClear
            style={{ width: 130 }}
            placeholder="全部类型"
            value={type}
            onChange={setType}
            options={TYPE_FILTER_OPTIONS}
          />
          <Select<string>
            allowClear
            style={{ width: 140 }}
            placeholder="全部评审状态"
            value={reviewStatus}
            onChange={setReviewStatus}
            options={CASE_REVIEW_OPTIONS}
          />
          <Input.Search
            allowClear
            style={{ width: 220 }}
            placeholder="标题 / 标识 / 路径"
            onSearch={(v) => setKeyword(v)}
          />
        </Space>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={loadCases}>刷新</Button>
          {canManage && <Button onClick={() => setModulesOpen(true)}>模块管理</Button>}
          {canManage && (
            <Tooltip title={suites.length ? undefined : '先到「测试套件」页签新建套件'}>
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建用例</Button>
            </Tooltip>
          )}
        </Space>
      </Flex>
      <FitTable<TestCase>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={LIST_PAGINATION}
        scroll={{ x: 1000 }}
        locale={{ emptyText: canManage ? '暂无用例：点「新建用例」编写第一条 CaseSpec；旧套件用例首次编辑会自动升级' : '暂无用例' }}
      />

      <CaseSpecDrawer
        open={editorOpen}
        suites={suites}
        nodes={nodes}
        assets={assets}
        editing={editing}
        defaultSuiteId={defaultSuiteId}
        saving={saving}
        onClose={() => setEditorOpen(false)}
        onSave={onSave}
      />

      <ModuleManageDrawer
        open={modulesOpen}
        projectId={projectId}
        onClose={() => setModulesOpen(false)}
        onChanged={() => { void loadRef(); loadCases() }}
      />

      <Drawer
        title={versionsFor ? `版本历史 · ${versionsFor.caseKey ?? versionsFor.name}` : '版本历史'}
        width={DRAWER_DEFAULT}
        open={!!versionsFor}
        onClose={() => setVersionsFor(null)}
      >
        <Table<TestCaseVersion>
          rowKey="id"
          size="small"
          loading={versionsLoading}
          dataSource={versions}
          pagination={false}
          columns={[
            { title: '版本', dataIndex: 'versionNo', width: 80, render: (v: number) => `v${v}` },
            {
              title: '评审', dataIndex: 'reviewStatus', width: 90,
              render: (v: keyof typeof REVIEW_COLOR) => <Tag color={REVIEW_COLOR[v]}>{REVIEW_LABEL[v]}</Tag>,
            },
            { title: '版本说明', dataIndex: 'changeNote', ellipsis: true, render: (v: string | null) => v || '-' },
            { title: '修改人', dataIndex: 'createdBy', width: 110, render: (v: string | null) => v || '-' },
            { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => fmtTime(v) },
            { title: '评审人', dataIndex: 'reviewedBy', width: 110, render: (v: string | null) => v || '-' },
            { title: '评审时间', dataIndex: 'reviewedAt', width: 170, render: (v: string | null) => (v ? fmtTime(v) : '-') },
          ]}
          expandable={{
            expandedRowRender: (v) => (
              <pre style={{ margin: 0, fontSize: 12, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {JSON.stringify(v.spec, null, 2)}
              </pre>
            ),
          }}
          locale={{ emptyText: '暂无版本记录' }}
        />
      </Drawer>

      <Modal
        title={runFor ? `运行用例 · ${runFor.name}` : '运行用例'}
        open={!!runFor}
        onCancel={() => setRunFor(null)}
        onOk={doRun}
        okText="执行"
        confirmLoading={running}
        width={520}
        destroyOnHidden
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {runFor?.type === 'MANUAL' && (
            <Typography.Text type="secondary">
              MANUAL 用例不会自动执行：运行创建后在运行详情里由复核人给出人工判定。
            </Typography.Text>
          )}
          <Select<number>
            allowClear
            style={{ width: '100%' }}
            placeholder="目标环境（可选）"
            value={runEnv}
            onChange={(v) => { setRunEnv(v); if (v != null) setRunNode(undefined) }}
            options={environments.map((e) => ({ value: e.id, label: e.name }))}
          />
          <Select<string>
            allowClear
            style={{ width: '100%' }}
            placeholder={nodes.length ? '执行节点（可选，COMMAND 用例必填）' : '无可用节点（先到后台「Agent 节点」登记）'}
            value={runNode}
            onChange={setRunNode}
            disabled={runEnv != null}
            options={nodes.map((n) => ({
              value: String(n.id),
              label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
            }))}
          />
          <Input placeholder="baseUrl（可选，HTTP 用例目标）" value={runBaseUrl}
            onChange={(e) => setRunBaseUrl(e.target.value)} />
          <Descriptions size="small" column={1} style={{ marginBottom: 0 }}>
            <Descriptions.Item label="断言">
              {runFor?.spec?.assertions?.length
                ? `${runFor.spec.assertions.length} 条（${runFor.spec.assertions.map((a) => `${a.source} ${a.op}`).join('；')}）`
                : '无自动断言'}
            </Descriptions.Item>
            <Descriptions.Item label="标签">{runFor?.tags || '-'}</Descriptions.Item>
          </Descriptions>
        </Space>
      </Modal>
    </div>
  )
}
