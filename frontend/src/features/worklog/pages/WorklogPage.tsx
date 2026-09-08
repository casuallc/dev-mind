import {
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Segmented,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined, SettingOutlined, GithubOutlined, CodeOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createEntry,
  deleteEntry,
  generateDaily,
  generateWeekly,
  getDaily,
  getSettings,
  getWeekly,
  listDailyWeek,
  listEntries,
  listWeeklyRecent,
  updateDaily,
  updateEntry,
  updateSettings,
  updateWeekly,
} from '../api'
import type {
  DailyReport,
  EntryPayload,
  WeeklyReport,
  WorklogEntry,
  WorklogSettings,
} from '../types'
import { ENTRY_SOURCES, ENTRY_TYPES } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import EntryFormDrawer from '../components/EntryFormDrawer'
import GitImportModal from '../components/GitImportModal'
import RepoSubscriptionModal from '../components/RepoSubscriptionModal'
import ReportEditor from '../components/ReportEditor'
import WeekDayStrip from '../components/WeekDayStrip'
import RecentWeekStrip from '../components/RecentWeekStrip'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'

type View = 'entries' | 'daily' | 'weekly'

const mondayOf = (d: Dayjs) => d.startOf('week').add(1, 'day') // dayjs 周日开头，+1 = 周一
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/**
 * CAP-28 个人工作日志与工时：条目（按周浏览+标题搜索+git 导入）/ AI 日报（按周浏览）/ AI 周报。
 * 个人级页面，不进项目上下文（路由不进 ProjectContextGate）。
 */
export default function WorklogPage() {
  const [view, setView] = useState<View>('entries')
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
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [settingsForm] = Form.useForm()

  // 一次取整周条目（后端 size 上限 200，足够覆盖单人一周量），当日列表与各天条数均在前端派生
  const loadEntries = useCallback(() => {
    setLoading(true)
    const to = dayjs(entriesWeekStartStr).add(6, 'day').format('YYYY-MM-DD')
    listEntries(entriesWeekStartStr, to, 0, 200, keyword || undefined)
      .then((r) => setWeekEntries(r.items))
      .catch((e) => message.error(`加载条目失败: ${e.message}`))
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
      .catch((e) => message.error(`加载日报失败: ${e.message}`))
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
      .catch((e) => message.error(`加载最近周报失败: ${e.message}`))
  }, [weeksBack])

  const loadWeekly = useCallback(() => {
    setLoading(true)
    getWeekly(weekStartStr)
      .then(setWeekly)
      .catch((e) => message.error(`加载周报失败: ${e.message}`))
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

  // 生成是异步的（后端 {accepted, running}）：提交后轮询直到报告出现
  const onGenerate = async (kind: 'daily' | 'weekly', force: boolean) => {
    setGenerating(true)
    const prevDailyUpdatedAt = daily?.updatedAt
    try {
      if (kind === 'daily') await generateDaily(dayStr, force)
      else await generateWeekly(weekStartStr, force)
      message.info('AI 生成中，完成后自动刷新…')
      for (let i = 0; i < 60; i++) {
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
      message.error(e instanceof Error ? e.message : '生成失败')
    } finally {
      setGenerating(false)
      reload()
    }
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
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const onDeleteEntry = (e: WorklogEntry) => {
    deleteEntry(e.id)
      .then(() => {
        message.success('已删除')
        // 删空当日当前页且非首页时回退一页，避免空白页
        if (dayEntries.length === 1 && page > 1) setPage(page - 1)
        loadEntries()
      })
      .catch((err) => message.error(err instanceof Error ? err.message : '删除失败'))
  }

  const openSettings = async () => {
    try {
      const s: WorklogSettings = await getSettings()
      settingsForm.setFieldsValue({
        autoDaily: s.autoDaily,
        autoWeekly: s.autoWeekly,
        dailyHoursTarget: s.dailyMinutesTarget != null ? s.dailyMinutesTarget / 60 : undefined,
      })
      setSettingsOpen(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载设置失败')
    }
  }

  const saveSettings = async () => {
    const v = await settingsForm.validateFields()
    try {
      await updateSettings({
        autoDaily: v.autoDaily,
        autoWeekly: v.autoWeekly,
        dailyMinutesTarget: v.dailyHoursTarget != null ? Math.round(v.dailyHoursTarget * 60) : undefined,
      })
      message.success('设置已保存')
      setSettingsOpen(false)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    }
  }

  const extraByView: Record<View, React.ReactNode> = {
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

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title={
        <Space size={12}>
          <span>工作日志</span>
          <Segmented
            value={view}
            onChange={(v) => setView(v as View)}
            options={[
              { value: 'entries', label: '工作条目' },
              { value: 'daily', label: '日报' },
              { value: 'weekly', label: '周报' },
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          {extraByView[view]}
          <Button icon={<CodeOutlined />} onClick={() => setReposOpen(true)}>
            仓库订阅
          </Button>
          <Button icon={<SettingOutlined />} onClick={openSettings}>
            设置
          </Button>
        </Space>
      }
    >
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
                    <Space size={4}>
                      <span>{e.repoName ?? `#${e.repoId}`}</span>
                      {e.commitSha && (
                        <Typography.Text code>{e.commitSha.slice(0, 7)}</Typography.Text>
                      )}
                    </Space>
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
                    <Popconfirm title="删除该条目？" onConfirm={() => onDeleteEntry(e)}>
                      <Button size="small" danger>
                        删除
                      </Button>
                    </Popconfirm>
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
            按周浏览日报（周一至周日），在周日条上左右滑动或点两侧箭头切换上一周/下一周，点某天查看/编辑；AI 汇总当日条目与 git 提交生成草稿，人工修订后「确认定稿」（已确认不可再重新生成）。
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
            点下方周条切换周，左右滑动或点两侧箭头整页翻看更早的 7 周；AI 汇总该周（周一 {weekStartStr} 起）条目与日报，产出「上周总结 + 下周计划」草稿；人工修订后确认定稿。
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

      <Modal
        title="工时设置"
        open={settingsOpen}
        onOk={saveSettings}
        onCancel={() => setSettingsOpen(false)}
        destroyOnHidden
      >
        <Form form={settingsForm} layout="vertical">
          <Form.Item name="autoDaily" label="每天自动生成日报草稿" valuePropName="checked" extra="默认 18:30（服务端 cron 可配）">
            <Switch />
          </Form.Item>
          <Form.Item name="autoWeekly" label="每周一自动生成上周周报草稿" valuePropName="checked" extra="默认周一 09:00">
            <Switch />
          </Form.Item>
          <Form.Item name="dailyHoursTarget" label="每日工时目标（小时）">
            <InputNumber min={0} max={24} step={0.5} style={{ width: '100%' }} placeholder="如 8" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
