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
import { PlusOutlined, ReloadOutlined, SettingOutlined, GithubOutlined } from '@ant-design/icons'
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
import ReportEditor from '../components/ReportEditor'

type View = 'entries' | 'daily' | 'weekly'

const mondayOf = (d: Dayjs) => d.startOf('week').add(1, 'day') // dayjs 周日开头，+1 = 周一
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/**
 * CAP-28 个人工作日志与工时：条目（含 git 导入）/ AI 日报 / AI 周报 三视图。
 * 个人级页面，不进项目上下文（路由不进 ProjectContextGate）。
 */
export default function WorklogPage() {
  const [view, setView] = useState<View>('entries')
  const [date, setDate] = useState<Dayjs>(dayjs())
  const dateStr = date.format('YYYY-MM-DD')
  const weekStartStr = mondayOf(date).format('YYYY-MM-DD')

  const [entries, setEntries] = useState<WorklogEntry[]>([])
  const [daily, setDaily] = useState<DailyReport | undefined>()
  const [weekly, setWeekly] = useState<WeeklyReport | undefined>()
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)

  const [editTarget, setEditTarget] = useState<WorklogEntry | null>(null)
  const [editOpen, setEditOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [settingsForm] = Form.useForm()

  const loadEntries = useCallback(() => {
    setLoading(true)
    listEntries(dateStr, dateStr)
      .then(setEntries)
      .catch((e) => message.error(`加载条目失败: ${e.message}`))
      .finally(() => setLoading(false))
  }, [dateStr])

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

  const reload = useCallback(() => {
    if (view === 'entries') loadEntries()
    else if (view === 'daily') loadDaily()
    else loadWeekly()
  }, [view, loadEntries, loadDaily, loadWeekly])

  useEffect(reload, [reload])

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

  const totalHours = entries.reduce((sum, e) => sum + (e.hours || 0), 0)

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
          <DatePicker
            value={date}
            allowClear={false}
            picker={view === 'weekly' ? 'week' : 'date'}
            onChange={(d) => d && setDate(d)}
          />
          {extraByView[view]}
          <Button icon={<SettingOutlined />} onClick={openSettings}>
            设置
          </Button>
        </Space>
      }
    >
      {view === 'entries' && (
        <>
          <Typography.Paragraph type="secondary">
            每天多条工作条目：手动补录（项目支持/会议/调研）或从 git 提交导入；日报/周报的素材来源。
          </Typography.Paragraph>
          <Space size="large" style={{ marginBottom: 16 }}>
            <Statistic title="当日工时合计" value={totalHours} precision={2} suffix="小时" />
            <Statistic title="条目数" value={entries.length} />
          </Space>
          <Table
            rowKey="id"
            loading={loading}
            dataSource={entries}
            pagination={false}
            locale={{
              emptyText: '当日暂无条目：点「新建条目」手动补录，或「从 Git 导入」扫描当日提交',
            }}
            columns={[
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
                    <Popconfirm
                      title="删除该条目？"
                      onConfirm={() =>
                        deleteEntry(e.id)
                          .then(() => {
                            message.success('已删除')
                            loadEntries()
                          })
                          .catch((err) => message.error(err instanceof Error ? err.message : '删除失败'))
                      }
                    >
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
          <ReportEditor
            id={daily?.id}
            status={daily?.status}
            updatedAt={daily?.updatedAt}
            generating={generating}
            fields={[{ key: 'contentMd', label: '日报内容（Markdown）', value: daily?.contentMd ?? '' }]}
            onGenerate={(force) => onGenerate('daily', force)}
            onSave={async (values) => {
              if (daily) setDaily(await updateDaily(daily.id, { contentMd: values.contentMd }))
            }}
            onConfirm={async () => {
              if (daily) setDaily(await updateDaily(daily.id, { status: 'CONFIRMED' }))
            }}
          />
        </>
      )}

      {view === 'weekly' && (
        <>
          <Typography.Paragraph type="secondary">
            AI 汇总本周（周一 {weekStartStr} 起）条目与日报，产出「上周总结 + 下周计划」草稿；人工修订后确认定稿。
          </Typography.Paragraph>
          <ReportEditor
            id={weekly?.id}
            status={weekly?.status}
            updatedAt={weekly?.updatedAt}
            generating={generating}
            fields={[
              { key: 'summaryMd', label: '周总结（Markdown）', value: weekly?.summaryMd ?? '' },
              { key: 'nextPlanMd', label: '下周计划（Markdown）', value: weekly?.nextPlanMd ?? '' },
            ]}
            onGenerate={(force) => onGenerate('weekly', force)}
            onSave={async (values) => {
              if (weekly)
                setWeekly(await updateWeekly(weekly.id, { summaryMd: values.summaryMd, nextPlanMd: values.nextPlanMd }))
            }}
            onConfirm={async () => {
              if (weekly) setWeekly(await updateWeekly(weekly.id, { status: 'CONFIRMED' }))
            }}
          />
        </>
      )}

      <EntryFormDrawer
        open={editOpen}
        target={editTarget}
        defaultDate={dateStr}
        saving={saving}
        onCancel={() => setEditOpen(false)}
        onSave={onSaveEntry}
      />
      <GitImportModal
        open={importOpen}
        date={dateStr}
        onCancel={() => setImportOpen(false)}
        onImported={loadEntries}
      />

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
