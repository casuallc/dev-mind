import {
  Button,
  Card,
  DatePicker,
  Form,
  InputNumber,
  Modal,
  Popconfirm,
  Segmented,
  Space,
  Statistic,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined, SettingOutlined, GithubOutlined, CodeOutlined } from '@ant-design/icons'
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
  listEntries,
  listRecentDaily,
  listRecentWeekly,
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

type View = 'entries' | 'daily' | 'weekly'
/** 条目快捷筛选：本周=周一至周日 */
type Quick = 'today' | 'yesterday' | 'week' | 'lastWeek' | 'month'

const mondayOf = (d: Dayjs) => d.startOf('week').add(1, 'day') // dayjs 周日开头，+1 = 周一
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const weekdayOf = (d: Dayjs) => `周${WEEKDAYS[d.day()]}`

const quickRange = (q: Quick): [Dayjs, Dayjs] => {
  const today = dayjs()
  switch (q) {
    case 'today':
      return [today, today]
    case 'yesterday':
      return [today.subtract(1, 'day'), today.subtract(1, 'day')]
    case 'week':
      return [mondayOf(today), mondayOf(today).add(6, 'day')]
    case 'lastWeek':
      return [mondayOf(today).subtract(7, 'day'), mondayOf(today).subtract(1, 'day')]
    case 'month':
      return [today.startOf('month'), today.endOf('month')]
  }
}

const QUICK_OPTIONS = [
  { value: 'today', label: '今天' },
  { value: 'yesterday', label: '昨天' },
  { value: 'week', label: '本周' },
  { value: 'lastWeek', label: '上周' },
  { value: 'month', label: '本月' },
]

/** 报告状态点颜色：无=灰 草稿=蓝 已确认=绿 */
const STATUS_DOT: Record<string, string> = { DRAFT: '#1677ff', CONFIRMED: '#52c41a' }
const NO_REPORT_DOT = '#d9d9d9'

/** 最近报告快捷 chips（日报按天 / 周报按周）；点击切换编辑器日期。 */
function RecentChips({
  items,
  selectedKey,
  onSelect,
}: {
  items: { key: string; label: string; status?: string }[]
  selectedKey: string
  onSelect: (key: string) => void
}) {
  return (
    <div style={{ marginBottom: 16 }}>
      <Space wrap size={[6, 6]} style={{ marginRight: 12 }}>
        {items.map((it) => (
          <Tag
            key={it.key}
            style={{ cursor: 'pointer', marginInlineEnd: 0 }}
            color={it.key === selectedKey ? 'processing' : undefined}
            onClick={() => onSelect(it.key)}
          >
            <span
              style={{
                display: 'inline-block',
                width: 6,
                height: 6,
                borderRadius: '50%',
                background: it.status ? STATUS_DOT[it.status] ?? NO_REPORT_DOT : NO_REPORT_DOT,
                marginRight: 4,
                verticalAlign: 'middle',
              }}
            />
            {it.label}
          </Tag>
        ))}
      </Space>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        灰点=未生成　蓝点=草稿　绿点=已确认，点击切换
      </Typography.Text>
    </div>
  )
}

/**
 * CAP-28 个人工作日志与工时：条目（范围筛选+分页+git 导入）/ AI 日报（最近两周）/ AI 周报（最近一月）。
 * 个人级页面，不进项目上下文（路由不进 ProjectContextGate）。
 */
export default function WorklogPage() {
  const [view, setView] = useState<View>('entries')
  const [date, setDate] = useState<Dayjs>(dayjs())
  const dateStr = date.format('YYYY-MM-DD')
  const weekStartStr = mondayOf(date).format('YYYY-MM-DD')

  // ---- 条目：范围筛选 + 分页 ----
  const [quick, setQuick] = useState<Quick | undefined>('today')
  const [range, setRange] = useState<[Dayjs, Dayjs]>(quickRange('today'))
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const fromStr = range[0].format('YYYY-MM-DD')
  const toStr = range[1].format('YYYY-MM-DD')

  const [entries, setEntries] = useState<WorklogEntry[]>([])
  const [entriesTotal, setEntriesTotal] = useState(0)
  const [rangeMinutes, setRangeMinutes] = useState(0)
  const [daily, setDaily] = useState<DailyReport | undefined>()
  const [weekly, setWeekly] = useState<WeeklyReport | undefined>()
  const [recentDaily, setRecentDaily] = useState<DailyReport[]>([])
  const [recentWeekly, setRecentWeekly] = useState<WeeklyReport[]>([])
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)

  const [editTarget, setEditTarget] = useState<WorklogEntry | null>(null)
  const [editOpen, setEditOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [reposOpen, setReposOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [settingsForm] = Form.useForm()

  const loadEntries = useCallback(() => {
    setLoading(true)
    listEntries(fromStr, toStr, page - 1, pageSize)
      .then((r) => {
        setEntries(r.items)
        setEntriesTotal(r.total)
        setRangeMinutes(r.totalMinutes)
      })
      .catch((e) => message.error(`加载条目失败: ${e.message}`))
      .finally(() => setLoading(false))
  }, [fromStr, toStr, page, pageSize])

  const loadDaily = useCallback(() => {
    setLoading(true)
    getDaily(dateStr)
      .then(setDaily)
      .catch((e) => message.error(`加载日报失败: ${e.message}`))
      .finally(() => setLoading(false))
  }, [dateStr])

  const loadWeekly = useCallback(() => {
    setLoading(true)
    getWeekly(weekStartStr)
      .then(setWeekly)
      .catch((e) => message.error(`加载周报失败: ${e.message}`))
      .finally(() => setLoading(false))
  }, [weekStartStr])

  const loadRecentDaily = useCallback(() => {
    listRecentDaily(14).then(setRecentDaily).catch(() => {})
  }, [])

  const loadRecentWeekly = useCallback(() => {
    listRecentWeekly(5).then(setRecentWeekly).catch(() => {})
  }, [])

  const reload = useCallback(() => {
    if (view === 'entries') loadEntries()
    else if (view === 'daily') {
      loadDaily()
      loadRecentDaily()
    } else {
      loadWeekly()
      loadRecentWeekly()
    }
  }, [view, loadEntries, loadDaily, loadWeekly, loadRecentDaily, loadRecentWeekly])

  useEffect(reload, [reload])

  const applyQuick = (q: Quick) => {
    setQuick(q)
    setRange(quickRange(q))
    setPage(1)
  }

  const applyRange = (r: [Dayjs | null, Dayjs | null] | null) => {
    if (!r || !r[0] || !r[1]) return
    setQuick(undefined)
    setRange([r[0], r[1]])
    setPage(1)
  }

  // 生成是异步的（后端 {accepted, running}）：提交后轮询直到报告出现
  const onGenerate = async (kind: 'daily' | 'weekly', force: boolean) => {
    setGenerating(true)
    try {
      if (kind === 'daily') await generateDaily(dateStr, force)
      else await generateWeekly(weekStartStr, force)
      message.info('AI 生成中，完成后自动刷新…')
      for (let i = 0; i < 30; i++) {
        await sleep(2000)
        const r = kind === 'daily' ? await getDaily(dateStr) : await getWeekly(weekStartStr)
        if (r && (force ? r.updatedAt !== (kind === 'daily' ? daily?.updatedAt : weekly?.updatedAt) : true)) {
          if (kind === 'daily') setDaily(r as DailyReport)
          else setWeekly(r as WeeklyReport)
          message.success('生成完成')
          return
        }
      }
      message.warning('生成超时或失败，请稍后在通知中心查看结果')
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
      <Space>
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
      </Space>
    ),
    daily: (
      <Button icon={<ReloadOutlined />} onClick={reload}>
        刷新
      </Button>
    ),
    weekly: (
      <Button icon={<ReloadOutlined />} onClick={reload}>
        刷新
      </Button>
    ),
  }

  // 从 Git 导入的目标日：范围结束日，不超过今天
  const importDate = range[1].isAfter(dayjs(), 'day') ? dayjs().format('YYYY-MM-DD') : toStr

  const dailyChips = Array.from({ length: 14 }, (_, i) => {
    const d = dayjs().subtract(i, 'day')
    const key = d.format('YYYY-MM-DD')
    return {
      key,
      label: `${d.format('MM-DD')} ${weekdayOf(d)}`,
      status: recentDaily.find((r) => r.workDate === key)?.status,
    }
  })

  const weeklyChips = Array.from({ length: 5 }, (_, i) => {
    const monday = mondayOf(dayjs()).subtract(i * 7, 'day')
    const key = monday.format('YYYY-MM-DD')
    return {
      key,
      label: `${key} 周`,
      status: recentWeekly.find((r) => r.weekStart === key)?.status,
    }
  })

  return (
    <Card
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
          {view !== 'entries' && (
            <DatePicker
              value={date}
              allowClear={false}
              picker={view === 'weekly' ? 'week' : 'date'}
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
          <Space style={{ marginBottom: 16 }} wrap>
            <Segmented
              value={quick}
              onChange={(v) => applyQuick(v as Quick)}
              options={QUICK_OPTIONS}
            />
            <DatePicker.RangePicker
              value={range}
              allowClear={false}
              onChange={(r) => applyRange(r)}
            />
            <Typography.Text type="secondary">
              手动补录（项目支持/会议/调研）或从 git 提交导入；日报/周报的素材来源。
            </Typography.Text>
          </Space>
          <Space size="large" style={{ marginBottom: 16 }}>
            <Statistic title="范围工时合计" value={rangeMinutes / 60} precision={2} suffix="小时" />
            <Statistic title="条目数" value={entriesTotal} />
          </Space>
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
            AI 汇总当日条目与 git 提交生成日报草稿；人工修订后「确认定稿」（已确认不可再重新生成）。
          </Typography.Paragraph>
          <RecentChips items={dailyChips} selectedKey={dateStr} onSelect={(k) => setDate(dayjs(k))} />
          <ReportEditor
            id={daily?.id}
            status={daily?.status}
            updatedAt={daily?.updatedAt}
            generating={generating}
            fields={[{ key: 'contentMd', label: `日报内容（${dateStr}，Markdown）`, value: daily?.contentMd ?? '' }]}
            onGenerate={(force) => onGenerate('daily', force)}
            onSave={async (values) => {
              if (daily) {
                setDaily(await updateDaily(daily.id, { contentMd: values.contentMd }))
                loadRecentDaily()
              }
            }}
            onConfirm={async () => {
              if (daily) {
                setDaily(await updateDaily(daily.id, { status: 'CONFIRMED' }))
                loadRecentDaily()
              }
            }}
          />
        </>
      )}

      {view === 'weekly' && (
        <>
          <Typography.Paragraph type="secondary">
            AI 汇总本周（周一 {weekStartStr} 起）条目与日报，产出「上周总结 + 下周计划」草稿；人工修订后确认定稿。
          </Typography.Paragraph>
          <RecentChips
            items={weeklyChips}
            selectedKey={weekStartStr}
            onSelect={(k) => setDate(dayjs(k))}
          />
          <ReportEditor
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
                setWeekly(await updateWeekly(weekly.id, { summaryMd: values.summaryMd, nextPlanMd: values.nextPlanMd }))
                loadRecentWeekly()
              }
            }}
            onConfirm={async () => {
              if (weekly) {
                setWeekly(await updateWeekly(weekly.id, { status: 'CONFIRMED' }))
                loadRecentWeekly()
              }
            }}
          />
        </>
      )}

      <EntryFormDrawer
        open={editOpen}
        target={editTarget}
        defaultDate={dayjs().format('YYYY-MM-DD')}
        saving={saving}
        onCancel={() => setEditOpen(false)}
        onSave={onSaveEntry}
      />
      <GitImportModal
        open={importOpen}
        date={importDate}
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
