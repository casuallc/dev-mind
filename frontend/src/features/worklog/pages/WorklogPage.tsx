import {
  Button,
  Card,
  DatePicker,
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
import { PlusOutlined, ReloadOutlined, SettingOutlined, GithubOutlined, CodeOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import { useCallback, useEffect, useState } from 'react'
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

/** 条目 RangePicker 快捷范围：本周=周一至周日 */
const RANGE_PRESETS: { label: string; value: [Dayjs, Dayjs] }[] = (() => {
  const today = dayjs()
  const monday = mondayOf(today)
  return [
    { label: '今天', value: [today, today] },
    { label: '昨天', value: [today.subtract(1, 'day'), today.subtract(1, 'day')] },
    { label: '本周', value: [monday, monday.add(6, 'day')] },
    { label: '上周', value: [monday.subtract(7, 'day'), monday.subtract(1, 'day')] },
    { label: '本月', value: [today.startOf('month'), today.endOf('month')] },
  ]
})()

/** 周报 DatePicker 快捷周 */
const WEEKLY_PRESETS = [
  { label: '本周', value: dayjs() },
  { label: '上周', value: dayjs().subtract(7, 'day') },
]

/**
 * CAP-28 个人工作日志与工时：条目（范围筛选+标题搜索+分页+git 导入）/ AI 日报（按周浏览）/ AI 周报。
 * 个人级页面，不进项目上下文（路由不进 ProjectContextGate）。
 */
export default function WorklogPage() {
  const [view, setView] = useState<View>('entries')
  // 周报锚点（picker=week 任选一天，取所在周周一）
  const [date, setDate] = useState<Dayjs>(dayjs())
  const weekStartStr = mondayOf(date).format('YYYY-MM-DD')

  // ---- 日报：按周浏览（周导航 + 周日选择条），默认当前周、选中今天 ----
  const [dailyWeekStart, setDailyWeekStart] = useState<Dayjs>(() => mondayOf(dayjs()))
  const [day, setDay] = useState<Dayjs>(dayjs())
  const dailyWeekStartStr = dailyWeekStart.format('YYYY-MM-DD')
  const dayStr = day.format('YYYY-MM-DD')
  const isCurrentWeek = dailyWeekStartStr === mondayOf(dayjs()).format('YYYY-MM-DD')

  // ---- 条目：范围筛选 + 标题搜索 + 分页 ----
  const [range, setRange] = useState<[Dayjs, Dayjs]>(RANGE_PRESETS[0].value)
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const fromStr = range[0].format('YYYY-MM-DD')
  const toStr = range[1].format('YYYY-MM-DD')

  const [entries, setEntries] = useState<WorklogEntry[]>([])
  const [entriesTotal, setEntriesTotal] = useState(0)
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

  const loadEntries = useCallback(() => {
    setLoading(true)
    listEntries(fromStr, toStr, page - 1, pageSize, keyword || undefined)
      .then((r) => {
        setEntries(r.items)
        setEntriesTotal(r.total)
      })
      .catch((e) => message.error(`加载条目失败: ${e.message}`))
      .finally(() => setLoading(false))
  }, [fromStr, toStr, page, pageSize, keyword])

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
    listWeeklyRecent(7)
      .then((list) => {
        const map: Record<string, WeeklyReport> = {}
        list.forEach((r) => {
          map[r.weekStart] = r
        })
        setRecentWeeks(map)
      })
      .catch((e) => message.error(`加载最近周报失败: ${e.message}`))
  }, [])

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

  // 周导航：±7 天整体平移（选中日保持星期几不变）；回本周 = 当前周 + 选中今天
  const shiftWeek = (n: number) => {
    setDailyWeekStart((w) => w.add(n * 7, 'day'))
    setDay((d) => d.add(n * 7, 'day'))
  }
  const backToCurrentWeek = () => {
    setDailyWeekStart(mondayOf(dayjs()))
    setDay(dayjs())
  }

  const applyRange = (r: [Dayjs | null, Dayjs | null] | null) => {
    if (!r || !r[0] || !r[1]) return
    setRange([r[0], r[1]])
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
        // 删空当前页且非首页时回退一页，避免空白页
        if (entries.length === 1 && page > 1) setPage(page - 1)
        else loadEntries()
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
        <DatePicker.RangePicker
          value={range}
          allowClear={false}
          presets={RANGE_PRESETS}
          onChange={(r) => applyRange(r)}
        />
        <Input.Search
          placeholder="搜索标题"
          allowClear
          style={{ width: 200 }}
          onSearch={applyKeyword}
          onChange={(e) => {
            if (!e.target.value) applyKeyword('')
          }}
        />
        <Button icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>
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
        <Button size="small" icon={<LeftOutlined />} onClick={() => shiftWeek(-1)} />
        <Typography.Text>
          {dailyWeekStart.format('YYYY-MM-DD')} ~ {dailyWeekStart.add(6, 'day').format('MM-DD')}
        </Typography.Text>
        {isCurrentWeek && <Tag color="blue">本周</Tag>}
        <Button size="small" icon={<RightOutlined />} onClick={() => shiftWeek(1)} />
        {!isCurrentWeek && (
          <Button size="small" onClick={backToCurrentWeek}>
            回到本周
          </Button>
        )}
        <Button icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>
      </>
    ),
    weekly: (
      <Button icon={<ReloadOutlined />} onClick={reload}>
        刷新
      </Button>
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
          {view === 'weekly' && (
            <DatePicker
              value={date}
              allowClear={false}
              picker="week"
              presets={WEEKLY_PRESETS}
              onChange={(d) => d && setDate(d)}
            />
          )}
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
            手动补录（项目支持/会议/调研）或从 git 提交导入；日报/周报的素材来源。
          </Typography.Paragraph>
          <Table
            rowKey="id"
            loading={loading}
            dataSource={entries}
            pagination={{
              current: page,
              pageSize,
              total: entriesTotal,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50, 100],
              showTotal: (t) => `共 ${t} 条`,
              onChange: (p, ps) => {
                setPage(ps === pageSize ? p : 1)
                setPageSize(ps)
              },
            }}
            locale={{
              emptyText: '范围内暂无条目：点「新建条目」手动补录，或「从 Git 导入」扫描提交',
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
            按周浏览日报（周一至周日），点某天查看/编辑；AI 汇总当日条目与 git 提交生成草稿，人工修订后「确认定稿」（已确认不可再重新生成）。
          </Typography.Paragraph>
          <WeekDayStrip weekStart={dailyWeekStart} reports={dailyWeek} selected={dayStr} onSelect={setDay} />
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
            点下方最近 7 周快速切换（更早的周走右上角周选择器）；AI 汇总该周（周一 {weekStartStr} 起）条目与日报，产出「上周总结 + 下周计划」草稿；人工修订后确认定稿。
          </Typography.Paragraph>
          <RecentWeekStrip reports={recentWeeks} selected={weekStartStr} onSelect={setDate} />
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
        defaultDate={view === 'daily' ? dayStr : dayjs().format('YYYY-MM-DD')}
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
