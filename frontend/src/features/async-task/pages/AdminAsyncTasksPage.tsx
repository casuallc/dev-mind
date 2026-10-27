// CAP-80 任务管理页（/admin/tasks）：任务视图（新建/编辑/暂停/恢复/触发/取消 + 查记录）
// + 执行记录横查视图（admin 专用；行内开日志抽屉，未终态接 WS 实时流）。
// 布局遵循 docs/core/前端内容区布局约定.md：Card 标题 + Segmented 切换视图，extra 放新建/刷新，FitTable 撑满。
import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Button, Card, Drawer, Input, Modal, Segmented, Select, Space, Tag, Typography, message } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import {
  cancelTask,
  listRuns,
  listTaskRuns,
  listTasks,
  listTaskTypes,
  pauseTask,
  resumeTask,
  triggerTask,
} from '../api'
import type { AsyncTaskRunView, AsyncTaskView, TaskTypeView } from '../types'
import RunLogDrawer, { RunStatusTag } from '../components/RunLogDrawer'
import TaskFormModal from '../components/TaskFormModal'
import FitTable from '../../../shared/components/FitTable'
import { fmtTime } from '../../../shared/utils/format'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'

const TASK_STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'green',
  PAUSED: 'gold',
  DONE: 'default',
  CANCELLED: 'default',
}
const KIND_LABEL: Record<string, string> = {
  IMMEDIATE: '即时',
  DELAY: '定时',
  FIXED_DELAY: '固定间隔',
  CRON: 'Cron',
}

function scheduleDesc(t: AsyncTaskView): string {
  if (t.scheduleKind === 'FIXED_DELAY') return `每 ${Math.round((t.intervalMs ?? 0) / 1000)}s`
  if (t.scheduleKind === 'CRON') return t.cronExpr ?? ''
  if (t.scheduleKind === 'DELAY') return fmtTime(t.runAt)
  return '提交即跑'
}

export default function AdminAsyncTasksPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const view = searchParams.get('view') === 'runs' ? 'runs' : 'tasks'
  const setView = (v: string) => setSearchParams(v === 'runs' ? { view: 'runs' } : {}, { replace: true })
  const [refreshTick, setRefreshTick] = useState(0)
  /** undefined=关闭；null=新建；否则编辑 */
  const [editing, setEditing] = useState<AsyncTaskView | null | undefined>(undefined)

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <span>任务管理</span>
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'tasks', label: '任务' },
              { value: 'runs', label: '执行记录' },
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          {view === 'tasks' && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing(null)}>
              新建任务
            </Button>
          )}
          <Button icon={<ReloadOutlined />} onClick={() => setRefreshTick((t) => t + 1)}>
            刷新
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        {view === 'tasks'
          ? '通用任务框架（CAP-80）登记的全部任务：即时/定时/固定间隔/Cron。可在本页直接创建（builtin.shell 类任务选择 runner 节点执行），也可由后端模块经 Java API 登记；此处统一做观测与生命周期控制（编辑/暂停/恢复/立即触发/取消）。'
          : '全部任务的执行记录（轮次×尝试），行内开日志抽屉；未终态的运行可实时看输出。'}
      </Typography.Paragraph>
      {view === 'tasks' ? <TasksTab refreshTick={refreshTick} onEdit={setEditing} /> : <RunsTab refreshTick={refreshTick} />}
      <TaskFormModal editing={editing} onClose={(saved) => { setEditing(undefined); if (saved) setRefreshTick((t) => t + 1) }} />
    </Card>
  )
}

function TasksTab({ refreshTick, onEdit }: { refreshTick: number; onEdit: (t: AsyncTaskView) => void }) {
  const [rows, setRows] = useState<AsyncTaskView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [status, setStatus] = useState<string>()
  const [keyword, setKeyword] = useState<string>()
  const [loading, setLoading] = useState(false)
  const [runsTask, setRunsTask] = useState<AsyncTaskView | null>(null)
  const [logRun, setLogRun] = useState<AsyncTaskRunView | null>(null)
  const [typeMap, setTypeMap] = useState<Map<string, TaskTypeView>>(new Map())

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listTasks({ status, keyword, page, size })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      message.error(`加载任务列表失败：${e instanceof Error ? e.message : e}`)
    } finally {
      setLoading(false)
    }
  }, [status, keyword, page, size])

  useEffect(() => { load() }, [load, refreshTick])
  // 类型元数据（执行位置列的 LOCAL/RUNNER 标记）；失败降级为只显示节点 id
  useEffect(() => {
    listTaskTypes()
      .then((ts) => setTypeMap(new Map(ts.map((t) => [t.type, t]))))
      .catch(() => setTypeMap(new Map()))
  }, [])

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn()
      message.success(ok)
      load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e))
    }
  }

  const columns: ColumnsType<AsyncTaskView> = [
    {
      title: '名称',
      dataIndex: 'name',
      ellipsis: true,
      render: (n, r) => (
        <Space size={6}>
          <span>{n}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.taskType}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '形态',
      dataIndex: 'scheduleKind',
      width: 170,
      render: (k, r) => (
        <Space size={4} style={{ whiteSpace: 'nowrap' }}>
          <Tag>{KIND_LABEL[k] ?? k}</Tag>
          <Typography.Text type="secondary" style={{ fontSize: 12, whiteSpace: 'nowrap' }}>{scheduleDesc(r)}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s) => <Tag color={TASK_STATUS_COLOR[s] ?? 'default'}>{s}</Tag>,
    },
    {
      title: '执行位置',
      key: 'executor',
      width: 150,
      render: (_, r) => {
        const t = typeMap.get(r.taskType)
        if (t?.executor === 'RUNNER') {
          return (
            <Space size={4}>
              <Tag color="blue">runner</Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {r.agentNodeId ? `节点 ${r.agentNodeId}` : '平台默认'}
              </Typography.Text>
            </Space>
          )
        }
        return <Tag>本机</Tag>
      },
    },
    {
      title: '已跑',
      key: 'runs',
      width: 90,
      render: (_, r) => `${r.runCount}${r.maxRuns != null ? ` / ${r.maxRuns}` : ''}`,
    },
    { title: '下次执行', dataIndex: 'nextRunAt', width: 170, render: (t) => (t ? fmtTime(t) : '—') },
    { title: '属主', dataIndex: 'actor', width: 140, ellipsis: true, render: (a) => a || '—' },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (t) => fmtTime(t) },
    {
      title: '操作',
      key: 'ops',
      width: 240,
      render: (_, r) => (
        <Space>
          {r.status === 'ACTIVE' && <a onClick={() => act(() => triggerTask(r.id), '已触发（重叠策略照常生效）')}>触发</a>}
          {(r.status === 'ACTIVE' || r.status === 'PAUSED') && <a onClick={() => onEdit(r)}>编辑</a>}
          {r.status === 'ACTIVE' && <a onClick={() => act(() => pauseTask(r.id), '已暂停')}>暂停</a>}
          {r.status === 'PAUSED' && <a onClick={() => act(() => resumeTask(r.id), '已恢复')}>恢复</a>}
          <a onClick={() => setRunsTask(r)}>记录</a>
          {(r.status === 'ACTIVE' || r.status === 'PAUSED') && (
            <a
              style={{ color: '#f5222d' }}
              onClick={() =>
                Modal.confirm({
                  title: '取消任务',
                  content: `取消后任务终态不可恢复，在途执行将被协作式中断。确认取消「${r.name}」？`,
                  okText: '取消任务',
                  okButtonProps: { danger: true },
                  cancelText: '再想想',
                  onOk: () => act(() => cancelTask(r.id), '已取消'),
                })
              }
            >
              取消
            </a>
          )}
        </Space>
      ),
    },
  ]

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, flex: 1, minHeight: 0 }}>
      <Space wrap>
        <Select
          allowClear placeholder="状态" style={{ width: 130 }}
          value={status} onChange={(v) => { setStatus(v); setPage(0) }}
          options={['ACTIVE', 'PAUSED', 'DONE', 'CANCELLED'].map((s) => ({ label: s, value: s }))}
        />
        <Input.Search
          allowClear placeholder="名称/类型关键字" style={{ width: 220 }}
          onSearch={(v) => { setKeyword(v || undefined); setPage(0) }}
        />
      </Space>
      <FitTable
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(s !== size ? 0 : p - 1)
            setSize(s)
          },
        }}
        locale={{ emptyText: '暂无任务。点右上「新建任务」创建，或由后端模块经 AsyncTaskService 登记（见 CAP-80）。' }}
      />
      <TaskRunsDrawer
        task={runsTask}
        onClose={() => setRunsTask(null)}
        onOpenLog={(run) => setLogRun(run)}
      />
      <RunLogDrawer run={logRun} onClose={() => setLogRun(null)} onFinished={load} />
    </div>
  )
}

/** 单任务的执行记录抽屉（轮次×尝试） */
function TaskRunsDrawer({
  task,
  onClose,
  onOpenLog,
}: {
  task: AsyncTaskView | null
  onClose: () => void
  onOpenLog: (run: AsyncTaskRunView) => void
}) {
  const [rows, setRows] = useState<AsyncTaskRunView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    if (!task) return
    setLoading(true)
    try {
      const r = await listTaskRuns(task.id, page, 10)
      setRows(r.items)
      setTotal(r.total)
    } catch {
      // 关闭中/任务不可见时静默
    } finally {
      setLoading(false)
    }
  }, [task, page])

  useEffect(() => {
    setPage(0)
  }, [task?.id])
  useEffect(() => { load() }, [load])

  return (
    <Drawer title={task ? `执行记录：${task.name}` : '执行记录'} open={!!task} onClose={onClose} width={DRAWER_DEFAULT}>
      <RunTable rows={rows} loading={loading} total={total} page={page} onPage={setPage} onOpenLog={onOpenLog} />
    </Drawer>
  )
}

function RunsTab({ refreshTick }: { refreshTick: number }) {
  const [rows, setRows] = useState<AsyncTaskRunView[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [status, setStatus] = useState<string>()
  const [loading, setLoading] = useState(false)
  const [logRun, setLogRun] = useState<AsyncTaskRunView | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listRuns({ status, page, size })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      message.error(`加载执行记录失败：${e instanceof Error ? e.message : e}`)
    } finally {
      setLoading(false)
    }
  }, [status, page, size])

  useEffect(() => { load() }, [load, refreshTick])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, flex: 1, minHeight: 0 }}>
      <Space wrap>
        <Select
          allowClear placeholder="状态" style={{ width: 140 }}
          value={status} onChange={(v) => { setStatus(v); setPage(0) }}
          options={['QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED'].map((s) => ({ label: s, value: s }))}
        />
      </Space>
      <FitTable
        rowKey="id"
        loading={loading}
        columns={runColumns((run) => setLogRun(run))}
        dataSource={rows}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(s !== size ? 0 : p - 1)
            setSize(s)
          },
        }}
        locale={{ emptyText: '暂无执行记录。任务到点被调度后自动产生。' }}
      />
      <RunLogDrawer run={logRun} onClose={() => setLogRun(null)} onFinished={load} />
    </div>
  )
}

function runColumns(onOpenLog: (run: AsyncTaskRunView) => void): ColumnsType<AsyncTaskRunView> {
  return [
    { title: '任务', dataIndex: 'taskId', width: 170, render: (id: string) => <Typography.Text code>{id}</Typography.Text> },
    { title: '轮次', dataIndex: 'runSeq', width: 64 },
    { title: '尝试', dataIndex: 'attempt', width: 64 },
    { title: '状态', dataIndex: 'status', width: 96, render: (s) => <RunStatusTag status={s} /> },
    { title: '计划时刻', dataIndex: 'scheduledAt', width: 170, render: (t) => fmtTime(t) },
    { title: '开始', dataIndex: 'startedAt', width: 170, render: (t) => (t ? fmtTime(t) : '—') },
    { title: '耗时', dataIndex: 'durationMs', width: 90, render: (d) => (d != null ? `${d} ms` : '—') },
    { title: '错误', dataIndex: 'error', ellipsis: true, render: (e) => e || '—' },
    { title: '操作', key: 'ops', width: 80, render: (_, r) => <a onClick={() => onOpenLog(r)}>日志</a> },
  ]
}

/** 抽屉内的小表（分页条常驻即可，不需要撑满高度） */
function RunTable({
  rows,
  loading,
  total,
  page,
  onPage,
  onOpenLog,
}: {
  rows: AsyncTaskRunView[]
  loading: boolean
  total: number
  page: number
  onPage: (p: number) => void
  onOpenLog: (run: AsyncTaskRunView) => void
}) {
  return (
    <FitTable
      rowKey="id"
      loading={loading}
      columns={runColumns(onOpenLog)}
      dataSource={rows}
      pagination={{
        current: page + 1,
        pageSize: 10,
        total,
        showSizeChanger: false,
        showTotal: (t) => `共 ${t} 条`,
        onChange: (p) => onPage(p - 1),
      }}
      locale={{ emptyText: '该任务还没有执行记录。' }}
    />
  )
}
