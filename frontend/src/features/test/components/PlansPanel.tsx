// CAP-104 M2 测试方案列表：方案编排、模板/复制、运行与运行历史下钻。
import {
  Button,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import { copyPlan, deletePlan, listPlans, searchCases } from '../api'
import type { TestCase, TestPlan, TestSuite } from '../types'
import type { ProjectEnvironment } from '../../projects/types'
import type { AgentNode } from '../../agent/types'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import PlanEditorDrawer from './PlanEditorDrawer'
import RunPlanModal from './RunPlanModal'
import PlanRunHistoryDrawer from './PlanRunHistoryDrawer'
import PlanRunDetailDrawer from './PlanRunDetailDrawer'

interface Props {
  projectId: string
  suites: TestSuite[]
  nodes: AgentNode[]
  environments: ProjectEnvironment[]
  canManagePlan: boolean
  canRunPlan: boolean
  canManageBaseline: boolean
  canExportEvidence: boolean
  /** 打开单个子运行详情（由页面统一渲染 RunDetailDrawer） */
  onOpenRun: (runId: number) => void
}

export default function PlansPanel({
  projectId, suites, nodes, environments,
  canManagePlan, canRunPlan, canManageBaseline, canExportEvidence, onOpenRun,
}: Props) {
  const [plans, setPlans] = useState<TestPlan[]>([])
  const [cases, setCases] = useState<TestCase[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<TestPlan | null>(null)
  const [runFor, setRunFor] = useState<TestPlan | null>(null)
  const [historyFor, setHistoryFor] = useState<TestPlan | null>(null)
  const [detailRunId, setDetailRunId] = useState<number | null>(null)

  const load = () => {
    setLoading(true)
    Promise.all([
      listPlans(projectId),
      searchCases({ projectId, reviewStatus: 'APPROVED' }).catch(() => []),
    ])
      .then(([ps, cs]) => {
        setPlans(ps)
        setCases(cs)
      })
      .catch((e) => showError(e, '加载测试方案失败'))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId])

  const filtered = keyword.trim()
    ? plans.filter((p) => p.name.toLowerCase().includes(keyword.trim().toLowerCase())
      || (p.description ?? '').toLowerCase().includes(keyword.trim().toLowerCase()))
    : plans

  const onCopy = async (p: TestPlan) => {
    try {
      const copy = await copyPlan(p.id)
      message.success(`已复制为「${copy.name}」`)
      load()
    } catch (e) {
      showError(e, '复制方案失败')
    }
  }

  const onDelete = (p: TestPlan) => {
    Modal.confirm({
      centered: true,
      title: `删除方案「${p.name}」？`,
      content: '仅删除方案定义，历史运行与快照保留；不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deletePlan(p.id)
        message.success('已删除')
        load()
      },
    })
  }

  const envName = (id: number | null) => {
    if (id == null) return '-'
    return environments.find((e) => e.id === id)?.name ?? `#${id}`
  }

  const columns: ColumnsType<TestPlan> = [
    { title: 'ID', dataIndex: 'id', width: 70, render: (v: number) => `#${v}` },
    {
      title: '名称', dataIndex: 'name', width: 240, ellipsis: true,
      render: (v: string, p) => (
        <Space size={6}>
          <span>{v}</span>
          {p.isTemplate && <Tag color="purple">模板</Tag>}
          <Tag>v{p.planVersion}</Tag>
        </Space>
      ),
    },
    { title: '描述', dataIndex: 'description', ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '方案项', key: 'items', width: 80, render: (_, p) => p.items?.length ?? 0 },
    { title: '失败策略', dataIndex: 'failStrategy', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    { title: '并发', dataIndex: 'concurrency', width: 60 },
    { title: '环境', dataIndex: 'environmentId', width: 130, ellipsis: true, render: (v: number | null) => envName(v) },
    { title: '需求', dataIndex: 'requirementId', width: 110, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (v: string) => fmtTime(v) },
    {
      title: '操作', key: 'act', width: 260,
      render: (_, p) => (
        <Space size={4} wrap>
          {canRunPlan && !p.isTemplate && (
            <Button size="small" type="primary" ghost onClick={() => setRunFor(p)}>运行</Button>
          )}
          <Button size="small" onClick={() => setHistoryFor(p)}>运行历史</Button>
          {canManagePlan && (
            <>
              <Button size="small" onClick={() => { setEditing(p); setEditorOpen(true) }}>编辑</Button>
              <Button size="small" onClick={() => onCopy(p)}>复制</Button>
              <Button size="small" danger onClick={() => onDelete(p)}>删除</Button>
            </>
          )}
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Space wrap>
        <Input.Search
          allowClear
          placeholder="搜索方案名称/描述"
          style={{ width: 240 }}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onSearch={setKeyword}
        />
        <Button onClick={load} loading={loading}>刷新</Button>
        {canManagePlan && (
          <Button type="primary" onClick={() => { setEditing(null); setEditorOpen(true) }}>新建方案</Button>
        )}
      </Space>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        方案 = 有序/并行编排的用例与套件集合；运行前固化快照，失败可重试、可固化基线做回归对比。
      </Typography.Text>
      <Table<TestPlan>
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={filtered}
        pagination={{ pageSize: 20, hideOnSinglePage: true }}
        locale={{ emptyText: canManagePlan ? '暂无方案：点「新建方案」编排用例/套件' : '暂无方案' }}
      />

      <PlanEditorDrawer
        open={editorOpen}
        projectId={projectId}
        plan={editing}
        suites={suites}
        cases={cases}
        environments={environments}
        onClose={() => setEditorOpen(false)}
        onSaved={() => {
          setEditorOpen(false)
          load()
        }}
      />

      <RunPlanModal
        plan={runFor}
        nodes={nodes}
        environments={environments}
        onClose={() => setRunFor(null)}
        onRan={(run) => {
          setRunFor(null)
          setDetailRunId(run.id)
          message.success(`方案运行 #${run.id} 已创建`)
        }}
      />

      <PlanRunHistoryDrawer
        open={!!historyFor}
        plan={historyFor}
        onClose={() => setHistoryFor(null)}
        onOpenRun={(id) => setDetailRunId(id)}
      />

      <PlanRunDetailDrawer
        planRunId={detailRunId}
        onClose={() => setDetailRunId(null)}
        onOpenChild={onOpenRun}
        onOpenPlanRun={setDetailRunId}
        canRunPlan={canRunPlan}
        canManageBaseline={canManageBaseline}
        canExportEvidence={canExportEvidence}
      />
    </Space>
  )
}
