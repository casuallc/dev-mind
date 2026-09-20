import {
  Alert,
  Button,
  Card,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined, GithubOutlined, CodeOutlined, RobotOutlined, CloudUploadOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createDaily,
  createEntry,
  createWeekly,
  deleteEntry,
  ensureWorkspace,
  generateDaily,
  generateWeekly,
  getDaily,
  getSettings,
  getWeekly,
  getWorkspace,
  listDailyWeek,
  listEntries,
  listWeeklyRecent,
  pushWorkspace,
  updateDaily,
  updateEntry,
  updateWeekly,
} from '../api'
import type {
  DailyReport,
  EntryPayload,
  WeeklyReport,
  WorklogEntry,
  WorkspaceView,
} from '../types'
import { ENTRY_SOURCES, ENTRY_TYPES } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import EntryFormDrawer from '../components/EntryFormDrawer'
import GitImportModal from '../components/GitImportModal'
import RepoSubscriptionModal from '../components/RepoSubscriptionModal'
import ReportEditor from '../components/ReportEditor'
import WeekDayStrip from '../components/WeekDayStrip'
import RecentWeekStrip from '../components/RecentWeekStrip'
import WorklogViewSwitch, { isContextView, isSessionsView, type WorklogView } from '../components/WorklogViewSwitch'
import SessionsBoard from '../../sessions/pages/SessionsBoard'
import ProjectContextPage from '../../scenarios/pages/ProjectContextPage'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

type View = WorklogView

const mondayOf = (d: Dayjs) => d.startOf('week').add(1, 'day') // dayjs 周日开头，+1 = 周一
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/**
 * CAP-28 个人工作日志与工时：条目（按周浏览+标题搜索+git 导入）/ AI 日报（按周浏览）/ AI 周报。
 * CAP-41：日志空间的「对话/列表」「知识」收为页内视图（锁定 WORKLOG 项目），不再跳项目上下文。
 * 个人级页面，不进项目上下文（路由不进 ProjectContextGate）。
 */
export default function WorklogPage() {
  const [view, setView] = useState<View>('entries')

  // ---- CAP-41 工作日志空间：WORKLOG 项目 + runner 持久工作区状态条 ----
  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null)
  const [ensuring, setEnsuring] = useState(false)
  // CAP-41 M3：远端备份绑定状态（决定「推送远端」按钮可用性；详细编辑在设置 Modal）
  const [remoteUrl, setRemoteUrl] = useState<string | null>(null)
  const [pushing, setPushing] = useState(false)
  const loadWorkspace = useCallback(() => {
    getWorkspace()
      .then(setWorkspace)
      .catch(() => setWorkspace(null))
    getSettings()
      .then((s) => setRemoteUrl(s.remoteUrl ?? null))
      .catch(() => setRemoteUrl(null))
  }, [])
  useEffect(loadWorkspace, [loadWorkspace])

  /** CAP-41 M3：手动推送远端（runner 侧 git push；结果回执 ok/detail/error） */
  const onPushRemote = async () => {
    setPushing(true)
    try {
      const ack = await pushWorkspace()
      if (ack.ok) message.success(ack.detail ? `已推送远端：${ack.detail}` : '已推送远端')
      else Modal.error({ centered: true, title: '推送远端失败', content: ack.error || '未知原因' })
    } catch (e) {
      showError(e, '推送远端失败')
    } finally {
      setPushing(false)
    }
  }

  const onEnsure = async () => {
    setEnsuring(true)
    try {
      const w = await ensureWorkspace()
      setWorkspace(w)
      message.success('工作日志空间已就绪')
    } catch (e) {
      showError(e, '初始化失败')
    } finally {
      setEnsuring(false)
    }
  }

  /** 打开日志空间会话（与 claude 对话记日志）：切到页内「对话」视图 */
  const openSession = () => {
    if (!workspace?.projectId) return
    setView('chat')
  }
  // 周报选中周（周条点击切换）+ 周条窗口回退周数（0=最右为本周，滑动/箭头翻页）
  const [date, setDate] = useState<Dayjs>(dayjs())
  const [weeksBack, setWeeksBack] = useState(0)
  const weekStartStr = mondayOf(date).format('YYYY-MM-DD')
  const isCurrentWeekSelected = weekStartStr === mondayOf(dayjs()).format('YYYY-MM-DD')

  // ---- 日报：按周浏览（周导航 + 周日选择条），默认当前周、选中今天 ----
  const [dailyWeekStart, setDailyWeekStart] = useState<Dayjs>(() => mondayOf(dayjs()))
  const [day, setDay] = useState<Dayjs>(dayjs())
  const dailyWeekStartStr = dailyWeekStart.format('YYYY-MM-DD')
  const dayStr = day.format('YYYY-MM-DD')
  const isCurrentWeek = dailyWeekStartStr === mondayOf(dayjs()).format('YYYY-MM-DD')

  // ---- 条目：按周浏览（同日报的周日条），点某天看当日条目；标题搜索 + 前端分页 ----
  const [entriesWeekStart, setEntriesWeekStart] = useState<Dayjs>(() => mondayOf(dayjs()))
  const [entryDay, setEntryDay] = useState<Dayjs>(dayjs())
  const entriesWeekStartStr = entriesWeekStart.format('YYYY-MM-DD')
  const entryDayStr = entryDay.format('YYYY-MM-DD')
  const isEntriesCurrentWeek = entriesWeekStartStr === mondayOf(dayjs()).format('YYYY-MM-DD')
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)

  const [weekEntries, setWeekEntries] = useState<WorklogEntry[]>([])
  const [dailyWeek, setDailyWeek] = useState<Record<string, DailyReport>>({})
  const [weekly, setWeekly] = useState<WeeklyReport | undefined>()
  const [recentWeeks, setRecentWeeks] = useState<Record<string, WeeklyReport>>({})
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)

  const daily = dailyWeek[dayStr]

  const [editTarget, setEditTarget] = useState<WorklogEntry | null>(null)
  const [editOpen, setEditOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [reposOpen, setReposOpen] = useState(false)

  // 一次取整周条目（后端 size 上限 200，足够覆盖单人一周量），当日列表与各天条数均在前端派生
  const loadEntries = useCallback(() => {
    setLoading(true)
    const to = dayjs(entriesWeekStartStr).add(6, 'day').format('YYYY-MM-DD')
    listEntries(entriesWeekStartStr, to, 0, 200, keyword || undefined)
      .then((r) => setWeekEntries(r.items))
      .catch((e) => showError(e, '加载条目失败'))
      .finally(() => setLoading(false))
  }, [entriesWeekStartStr, keyword])

  /** 选中日当天的条目（表格数据源） */
  const dayEntries = useMemo(
    () => weekEntries.filter((e) => e.workDate === entryDayStr),
    [weekEntries, entryDayStr],
  )
  /** workDate → 当日条数（周日条状态点用） */
  const entryCountByDate = useMemo(() => {
    const m: Record<string, number> = {}
    weekEntries.forEach((e) => {
      m[e.workDate] = (m[e.workDate] ?? 0) + 1
    })
    return m
  }, [weekEntries])

  const loadDailyWeek = useCallback(() => {
    setLoading(true)
    listDailyWeek(dailyWeekStartStr)
      .then((list) => {
        const map: Record<string, DailyReport> = {}
        list.forEach((r) => {
          map[r.workDate] = r
        })
        setDailyWeek(map)
      })
      .catch((e) => showError(e, '加载日报失败'))
      .finally(() => setLoading(false))
  }, [dailyWeekStartStr])

  const loadRecentWeeks = useCallback(() => {
    // 覆盖可见窗口：回退 weeksBack 周时多取相应周数
    listWeeklyRecent(7 + weeksBack)
      .then((list) => {
        const map: Record<string, WeeklyReport> = {}
        list.forEach((r) => {
          map[r.weekStart] = r
        })
        setRecentWeeks(map)
      })
      .catch((e) => showError(e, '加载最近周报失败'))
  }, [weeksBack])

  const loadWeekly = useCallback(() => {
    setLoading(true)
    getWeekly(weekStartStr)
      .then(setWeekly)
      .catch((e) => showError(e, '加载周报失败'))
      .finally(() => setLoading(false))
  }, [weekStartStr])

  const reload = useCallback(() => {
    if (view === 'entries') loadEntries()
    else if (view === 'daily') loadDailyWeek()
    else {
      loadWeekly()
      loadRecentWeeks()
    }
  }, [view, loadEntries, loadDailyWeek, loadWeekly, loadRecentWeeks])

  useEffect(reload, [reload])

  // 周导航：±7 天整体平移（选中日保持星期几不变），已处于当前周时禁止再向后切未来周
  const shiftWeek = (n: number) => {
    if (n > 0 && isCurrentWeek) return
    setDailyWeekStart((w) => w.add(n * 7, 'day'))
    setDay((d) => d.add(n * 7, 'day'))
  }
  const backToCurrentWeek = () => {
    setDailyWeekStart(mondayOf(dayjs()))
    setDay(dayjs())
  }

  // 周报窗口翻页：+1 更早 7 周，-1 更近 7 周；最右不越过本周（weeksBack 最小 0）
  const shiftWeekWindow = (pages: number) => setWeeksBack((b) => Math.max(0, b + pages))
  const backToThisWeek = () => {
    setWeeksBack(0)
    setDate(dayjs())
  }

  // 条目周导航：同日报规则，已处于当前周时禁止再向后切未来周；切周/选天/搜索均回到第 1 页
  const shiftEntriesWeek = (n: number) => {
    if (n > 0 && isEntriesCurrentWeek) return
    setEntriesWeekStart((w) => w.add(n * 7, 'day'))
    setEntryDay((d) => d.add(n * 7, 'day'))
    setPage(1)
  }
  const backToEntriesToday = () => {
    setEntriesWeekStart(mondayOf(dayjs()))
    setEntryDay(dayjs())
    setPage(1)
  }
  const selectEntryDay = (d: Dayjs) => {
    setEntryDay(d)
    setPage(1)
  }

  const applyKeyword = (kw: string) => {
    setKeyword(kw.trim())
    setPage(1)
  }

  // 生成 = 创建 worklog 会话（CAP-41）：受理返回 {sessionId, reused}，成稿随会话结束回传落镜像，
  // 前端轮询 GET 报告直到镜像出现（reused 表示已有报告未重新生成）
  const onGenerate = async (kind: 'daily' | 'weekly', force: boolean) => {
    setGenerating(true)
    const prevDailyUpdatedAt = daily?.updatedAt
    try {
      const ack = kind === 'daily' ? await generateDaily(dayStr, force) : await generateWeekly(weekStartStr, force)
      if (ack.reused) {
        message.info('已存在该报告，未重复生成（如需重生成请使用强制重新生成）')
        return
      }
      message.info(`生成会话已创建${ack.sessionId ? `（${ack.sessionId}）` : ''}，完成后自动刷新…`)
      for (let i = 0; i < 150; i++) {
        await sleep(2000)
        const r = kind === 'daily' ? await getDaily(dayStr) : await getWeekly(weekStartStr)
        if (r && (force ? r.updatedAt !== (kind === 'daily' ? prevDailyUpdatedAt : weekly?.updatedAt) : true)) {
          if (kind === 'daily') setDailyWeek((m) => ({ ...m, [dayStr]: r as DailyReport }))
          else {
            setWeekly(r as WeeklyReport)
            setRecentWeeks((m) => ({ ...m, [weekStartStr]: r as WeeklyReport }))
          }
          message.success('生成完成')
          return
        }
      }
      // 超时/失败的真实原因由后端落通知中心（P0），此处引导查看
      message.warning('生成超时或失败，失败原因与结果请查看通知中心')
    } catch (e) {
      showError(e, '生成失败')
    } finally {
      setGenerating(false)
      reload()
    }
  }

  // 手动创建空白草稿（不经 AI）：创建后进入编辑态，保存/确认与 AI 草稿同一路径
  const onCreateDaily = async () => {
    const r = await createDaily(dayStr)
    setDailyWeek((m) => ({ ...m, [dayStr]: r }))
  }
  const onCreateWeekly = async () => {
    const r = await createWeekly(weekStartStr)
    setWeekly(r)
    setRecentWeeks((m) => ({ ...m, [weekStartStr]: r }))
  }

  const onSaveEntry = async (payload: EntryPayload) => {
    setSaving(true)
    try {
      if (editTarget) await updateEntry(editTarget.id, payload)
      else await createEntry(payload)
      message.success('已保存')
      setEditOpen(false)
      loadEntries()
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const onDeleteEntry = (e: WorklogEntry) => {
    // 确认弹窗统一走平台通用的居中 Modal.confirm，不用贴按钮的 Popconfirm
    Modal.confirm({
      centered: true,
      title: '删除该条目？',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: () =>
        deleteEntry(e.id)
          .then(() => {
            message.success('已删除')
            // 删空当日当前页且非首页时回退一页，避免空白页
            if (dayEntries.length === 1 && page > 1) setPage(page - 1)
            loadEntries()
          })
          .catch((err) => showError(err, '删除失败')),
    })
  }

  const extraByView: Partial<Record<View, React.ReactNode>> = {
    entries: (
      <>
        <Button
          disabled={isEntriesCurrentWeek && entryDayStr === dayjs().format('YYYY-MM-DD')}
          onClick={backToEntriesToday}
        >
          今天
        </Button>
        <Button icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>
        <Input.Search
          placeholder="搜索标题"
          allowClear
          style={{ width: 200 }}
          onSearch={applyKeyword}
          onChange={(e) => {
            if (!e.target.value) applyKeyword('')
          }}
        />
        <Button icon={<GithubOutlined />} onClick={() => setImportOpen(true)}>
          从 Git 导入
        </Button>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            setEditTarget(null)
            setEditOpen(true)
          }}
        >
          新建条目
        </Button>
      </>
    ),
    daily: (
      <>
        <Button
          disabled={isCurrentWeek && dayStr === dayjs().format('YYYY-MM-DD')}
          onClick={backToCurrentWeek}
        >
          今天
        </Button>
        <Button icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>
      </>
    ),
    weekly: (
      <>
        <Button disabled={weeksBack === 0 && isCurrentWeekSelected} onClick={backToThisWeek}>
          本周
        </Button>
        <Button icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>
      </>
    ),
  }

  // ---- 对话/对话列表/知识视图：整页渲染内嵌工作台（锁定 WORKLOG 项目），title 放共享视图切换器保证可切回 ----
  const viewSwitch = <WorklogViewSwitch value={view} onChange={setView} />
  const readyProjectId = workspace?.exists && workspace.projectId ? workspace.projectId : null
  if (isSessionsView(view) || isContextView(view)) {
    if (!readyProjectId) {
      return (
        <Card style={pageCardStyle} styles={{ body: pageCardBodyScrollStyle }} title={viewSwitch}>
          <Alert
            type="info"
            showIcon
            message="工作日志空间尚未初始化"
            description="对话与知识视图依赖日志空间（runner 节点上按用户隔离的持久 git 工作区），初始化后可用。"
            action={
              <Button type="primary" loading={ensuring} onClick={onEnsure}>
                初始化空间
              </Button>
            }
          />
        </Card>
      )
    }
    return isSessionsView(view) ? (
      <SessionsBoard
        projectId={readyProjectId}
        title={viewSwitch}
        view={view}
        onViewChange={(v) => setView(v as WorklogView)}
        worklog
      />
    ) : (
      <ProjectContextPage
        projectId={readyProjectId}
        title={viewSwitch}
        view={view}
        onViewChange={(v) => setView(v as WorklogView)}
      />
    )
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title={viewSwitch}
      extra={
        <Space>
          {extraByView[view]}
          <Button icon={<CodeOutlined />} onClick={() => setReposOpen(true)}>
            仓库订阅
          </Button>
          {/* 工时/报表/备份偏好已收口到一级导航「设置」页的工作日志视图（原「工时设置」Modal），
              此处不再留重复入口；仅「推送远端」的 tooltip 指路 */}
        </Space>
      }
    >
      {workspace && !workspace.exists && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="工作日志空间尚未初始化"
          description="在 runner 节点上按用户隔离的持久 git 工作区（工作日志/日报/周报由 AI 会话直接写入）。初始化后可在「对话」页签与 claude 对话记日志。"
          action={
            <Button type="primary" loading={ensuring} onClick={onEnsure}>
              初始化空间
            </Button>
          }
        />
      )}
      {workspace?.exists && (
        <Alert
          type={workspace.nodeOnline ? 'success' : 'warning'}
          showIcon
          style={{ marginBottom: 12 }}
          message={
            <Space size={8} wrap>
              <span>工作日志空间已就绪</span>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                亲和节点 {workspace.agentNodeId}
                {workspace.nodeOnline ? '（在线）' : '（离线，会话暂不可用）'}
              </Typography.Text>
            </Space>
          }
          action={
            <Space>
              <Button size="small" icon={<ReloadOutlined />} onClick={loadWorkspace} />
              <Tooltip
                title={
                  !remoteUrl
                    ? '未绑定远程仓库：在「设置 → 工作日志」的「远程仓库备份」里配置后可用'
                    : !workspace.nodeOnline
                      ? '亲和节点离线，暂不可推送'
                      : `推送到 ${remoteUrl}`
                }
              >
                <Button
                  size="small"
                  icon={<CloudUploadOutlined />}
                  loading={pushing}
                  disabled={!remoteUrl || !workspace.nodeOnline}
                  onClick={onPushRemote}
                >
                  推送远端
                </Button>
              </Tooltip>
              <Button size="small" type="primary" icon={<RobotOutlined />} onClick={openSession}>
                打开会话
              </Button>
            </Space>
          }
        />
      )}
      {view === 'entries' && (
        <>
          <Typography.Paragraph type="secondary">
            按周浏览条目（周一至周日），在周日条上左右滑动或点两侧箭头切周，点某天只看当日记录；手动补录（项目支持/会议/调研）或从 git 提交导入；日报/周报的素材来源。
          </Typography.Paragraph>
          <WeekDayStrip
            weekStart={entriesWeekStart}
            selected={entryDayStr}
            onSelect={selectEntryDay}
            onShiftWeek={shiftEntriesWeek}
            renderStatus={(key, future) => {
              if (future) return <Typography.Text type="secondary">—</Typography.Text>
              const n = entryCountByDate[key] ?? 0
              return n > 0 ? (
                <Typography.Text style={{ color: '#1677ff' }}>{n} 条</Typography.Text>
              ) : (
                <Typography.Text type="secondary">○ 无</Typography.Text>
              )
            }}
          />
          <div style={{ textAlign: 'center', marginTop: -8, marginBottom: 12 }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {entriesWeekStart.format('YYYY-MM-DD')} ~ {entriesWeekStart.add(6, 'day').format('MM-DD')}
              {isEntriesCurrentWeek && '（本周）'}
            </Typography.Text>
          </div>
          <Table
            rowKey="id"
            loading={loading}
            dataSource={dayEntries.slice((page - 1) * pageSize, page * pageSize)}
            pagination={{
              current: page,
              pageSize,
              total: dayEntries.length,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50, 100],
              showTotal: (t) => `共 ${t} 条`,
              onChange: (p, ps) => {
                setPage(ps === pageSize ? p : 1)
                setPageSize(ps)
              },
            }}
            locale={{
              emptyText: '当日暂无条目：点「新建条目」手动补录，或「从 Git 导入」扫描提交',
            }}
            columns={[
              { title: '日期', dataIndex: 'workDate', width: 110 },
              { title: '标题', dataIndex: 'title', ellipsis: true },
              {
                title: '类型',
                dataIndex: 'entryType',
                width: 90,
                render: (t: string) => <Tag>{ENTRY_TYPES[t] ?? t}</Tag>,
              },
              {
                title: '工时',
                dataIndex: 'hours',
                width: 90,
                render: (h: number) => `${h}h`,
              },
              {
                title: '来源',
                dataIndex: 'source',
                width: 100,
                render: (s: string) => ENTRY_SOURCES[s] ?? s,
              },
              {
                title: '仓库',
                width: 180,
                render: (_, e) =>
                  e.repoId ? (
                    // 仓库名与 commit sha 分两行，避免长仓库名把 sha 挤换行
                    <div>
                      <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {e.repoName ?? `#${e.repoId}`}
                      </div>
                      {e.commitSha && <Typography.Text code>{e.commitSha.slice(0, 7)}</Typography.Text>}
                    </div>
                  ) : (
                    '-'
                  ),
              },
              {
                title: '关联',
                width: 140,
                render: (_, e) =>
                  e.jiraIssueKey ? <Tag color="blue">{e.jiraIssueKey}</Tag> : e.requirementId ? <Tag>{e.requirementId}</Tag> : '-',
              },
              {
                title: '记录时间',
                dataIndex: 'createdAt',
                width: 170,
                render: (t: string) => fmtTime(t),
              },
              {
                title: '操作',
                width: 150,
                render: (_, e) => (
                  <Space>
                    <Button
                      size="small"
                      onClick={() => {
                        setEditTarget(e)
                        setEditOpen(true)
                      }}
                    >
                      编辑
                    </Button>
                    <Button size="small" danger onClick={() => onDeleteEntry(e)}>
                      删除
                    </Button>
                  </Space>
                ),
              },
            ]}
          />
        </>
      )}

      {view === 'daily' && (
        <>
          <Typography.Paragraph type="secondary">
            按周浏览日报（周一至周日），在周日条上左右滑动或点两侧箭头切换上一周/下一周，点某天查看/编辑；可「手动填写」直接写，或由 AI 汇总当日条目与 git 提交生成草稿，人工修订后「确认定稿」（已确认不可再重新生成）。
          </Typography.Paragraph>
          <WeekDayStrip
            weekStart={dailyWeekStart}
            selected={dayStr}
            onSelect={setDay}
            onShiftWeek={shiftWeek}
            renderStatus={(key, future) => {
              if (future) return <Typography.Text type="secondary">—</Typography.Text>
              const r = dailyWeek[key]
              if (!r) return <Typography.Text type="secondary">○ 无</Typography.Text>
              return r.status === 'CONFIRMED' ? (
                <Typography.Text style={{ color: '#52c41a' }}>● 已确认</Typography.Text>
              ) : (
                <Typography.Text style={{ color: '#faad14' }}>◐ 草稿</Typography.Text>
              )
            }}
          />
          <div style={{ textAlign: 'center', marginTop: -8, marginBottom: 12 }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {dailyWeekStart.format('YYYY-MM-DD')} ~ {dailyWeekStart.add(6, 'day').format('MM-DD')}
              {isCurrentWeek && '（本周）'}
            </Typography.Text>
          </div>
          <ReportEditor
            key={dayStr}
            id={daily?.id}
            status={daily?.status}
            updatedAt={daily?.updatedAt}
            generating={generating}
            fields={[{ key: 'contentMd', label: `日报内容（${dayStr}，Markdown）`, value: daily?.contentMd ?? '' }]}
            onCreateManual={onCreateDaily}
            onGenerate={(force) => onGenerate('daily', force)}
            onSave={async (values) => {
              if (daily) {
                const updated = await updateDaily(daily.id, { contentMd: values.contentMd })
                setDailyWeek((m) => ({ ...m, [dayStr]: updated }))
              }
            }}
            onConfirm={async () => {
              if (daily) {
                const updated = await updateDaily(daily.id, { status: 'CONFIRMED' })
                setDailyWeek((m) => ({ ...m, [dayStr]: updated }))
              }
            }}
          />
        </>
      )}

      {view === 'weekly' && (
        <>
          <Typography.Paragraph type="secondary">
            点下方周条切换周，左右滑动或点两侧箭头整页翻看更早的 7 周；可「手动填写」直接写，或由 AI 汇总该周（周一 {weekStartStr} 起）条目与日报产出「上周总结 + 下周计划」草稿；人工修订后确认定稿。
          </Typography.Paragraph>
          <RecentWeekStrip
            reports={recentWeeks}
            selected={weekStartStr}
            onSelect={setDate}
            weeksBack={weeksBack}
            onShiftWindow={shiftWeekWindow}
          />
          <ReportEditor
            key={weekStartStr}
            id={weekly?.id}
            status={weekly?.status}
            updatedAt={weekly?.updatedAt}
            generating={generating}
            fields={[
              { key: 'summaryMd', label: `周总结（${weekStartStr} 周，Markdown）`, value: weekly?.summaryMd ?? '' },
              { key: 'nextPlanMd', label: '下周计划（Markdown）', value: weekly?.nextPlanMd ?? '' },
            ]}
            onCreateManual={onCreateWeekly}
            onGenerate={(force) => onGenerate('weekly', force)}
            onSave={async (values) => {
              if (weekly) {
                const updated = await updateWeekly(weekly.id, { summaryMd: values.summaryMd, nextPlanMd: values.nextPlanMd })
                setWeekly(updated)
                setRecentWeeks((m) => ({ ...m, [weekStartStr]: updated }))
              }
            }}
            onConfirm={async () => {
              if (weekly) {
                const updated = await updateWeekly(weekly.id, { status: 'CONFIRMED' })
                setWeekly(updated)
                setRecentWeeks((m) => ({ ...m, [weekStartStr]: updated }))
              }
            }}
          />
        </>
      )}

      <EntryFormDrawer
        open={editOpen}
        target={editTarget}
        defaultDate={
          view === 'daily' ? dayStr : view === 'entries' ? entryDayStr : dayjs().format('YYYY-MM-DD')
        }
        saving={saving}
        onCancel={() => setEditOpen(false)}
        onSave={onSaveEntry}
      />
      <GitImportModal
        open={importOpen}
        onCancel={() => setImportOpen(false)}
        onImported={loadEntries}
      />
      <RepoSubscriptionModal open={reposOpen} onCancel={() => setReposOpen(false)} />
    </Card>
  )
}
