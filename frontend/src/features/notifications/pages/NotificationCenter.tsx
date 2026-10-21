// 通知中心（FR-06）：未读/历史/筛选 + 快捷动作 + 通道配置 + 防打扰偏好。
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Drawer,
  Empty,
  Input,
  Segmented,
  Select,
  Space,
  Switch,
  Tag,
  TimePicker,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { CheckOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import {
  getPrefs,
  listChannels,
  listNotifications,
  markRead as apiMarkRead,
  readAll,
  updateChannel,
  updatePrefs,
} from '../api'
import { markAllReadLocal, markReadLocal, useNotifications } from '../store'
import { executeNotificationAction } from '../actions'
import { EVENT_TYPES, LEVEL_COLOR } from '../types'
import type { AppNotification, NotificationChannel, NotificationPrefs } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

/** CAP-75 FR-03：按 code 的声明式 config 字段注册表（有字段定义的通道在抽屉里渲染配置表单）。 */
const CHANNEL_CONFIG_FIELDS: Record<
  string,
  { key: string; label: string; password?: boolean; placeholder?: string }[]
> = {
  bark: [
    { key: 'server', label: 'Server', placeholder: 'https://api.day.app' },
    { key: 'key', label: 'Key', password: true },
  ],
  wecom: [{ key: 'webhookUrl', label: 'Webhook URL', placeholder: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…' }],
  feishu: [
    { key: 'webhookUrl', label: 'Webhook URL', placeholder: 'https://open.feishu.cn/open-apis/bot/v2/hook/…' },
    { key: 'secret', label: '加签密钥（可选）', password: true },
  ],
}

export default function NotificationCenter() {
  const navigate = useNavigate()
  const store = useNotifications()
  const [items, setItems] = useState<AppNotification[]>([])
  const [loading, setLoading] = useState(false)
  const [filter, setFilter] = useState<'all' | 'unread'>('all')
  const [level, setLevel] = useState('ALL')

  // 偏好
  const [prefsOpen, setPrefsOpen] = useState(false)
  const [channels, setChannels] = useState<NotificationChannel[]>([])
  const [prefs, setPrefs] = useState<NotificationPrefs | null>(null)
  const [savingPrefs, setSavingPrefs] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const list = await listNotifications({
        level: level === 'ALL' ? undefined : level,
        unreadOnly: filter === 'unread',
        limit: 200,
      })
      setItems(list)
    } catch (e) {
      showError(e, '加载通知失败')
    } finally {
      setLoading(false)
    }
  }, [filter, level])

  useEffect(() => {
    load()
  }, [load])

  // 实时推送增量（按 id 去重，新到最前）
  const latestId = store.notifications[0]?.id
  useEffect(() => {
    const latest = store.notifications[0]
    if (!latest) return
    setItems((prev) => (prev.some((p) => p.id === latest.id) ? prev : [latest, ...prev]))
  }, [latestId]) // eslint-disable-line react-hooks/exhaustive-deps

  const onAction = async (n: AppNotification, action: string) => {
    try {
      const label = await executeNotificationAction(n, action, { navigate })
      message.success(`${label} 已执行`)
      syncLocal(n.id)
      load()
    } catch (e) {
      showError(e, '动作失败')
    }
  }

  const syncLocal = (id: number) => {
    markReadLocal(id)
    setItems((prev) =>
      prev.map((x) => (x.id === id ? { ...x, readAt: new Date().toISOString() } : x)),
    )
  }

  const onRead = async (id: number) => {
    try {
      await apiMarkRead(id)
      syncLocal(id)
    } catch (e) {
      showError(e, '操作失败')
    }
  }

  const onReadAll = async () => {
    try {
      await readAll()
      markAllReadLocal()
      setItems((prev) => prev.map((x) => (x.readAt ? x : { ...x, readAt: new Date().toISOString() })))
      message.success('已全部标记为已读')
    } catch (e) {
      showError(e, '操作失败')
    }
  }

  const openPrefs = async () => {
    setPrefsOpen(true)
    try {
      setChannels(await listChannels())
      setPrefs(await getPrefs())
    } catch (e) {
      showError(e, '加载设置失败')
    }
  }

  const savePrefs = async () => {
    if (!prefs) return
    setSavingPrefs(true)
    try {
      for (const ch of channels) {
        // config 为合并语义（后端 putAll），有字段注册表的通道把表单值一并提交
        const hasConfigForm = (CHANNEL_CONFIG_FIELDS[ch.code] ?? []).length > 0
        await updateChannel(ch.id, {
          enabled: ch.enabled,
          levelThreshold: ch.levelThreshold,
          ...(hasConfigForm ? { config: ch.config } : {}),
        })
      }
      await updatePrefs({
        mutes: prefs.mutes,
        quietStart: prefs.quietStart,
        quietEnd: prefs.quietEnd,
        perSessionSilence: prefs.perSessionSilence,
      })
      message.success('偏好已保存')
      setPrefsOpen(false)
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSavingPrefs(false)
    }
  }

  const columns: ColumnsType<AppNotification> = [
    {
      title: '级别',
      dataIndex: 'level',
      width: 70,
      render: (l: string) => <Tag color={LEVEL_COLOR[l as keyof typeof LEVEL_COLOR]}>{l}</Tag>,
    },
    {
      title: '内容',
      dataIndex: 'title',
      render: (t: string, r) => (
        <div>
          <Typography.Text strong>{t}</Typography.Text>
          {r.body ? (
            <div style={{ fontSize: 12, color: '#666', marginTop: 2 }}>{r.body}</div>
          ) : null}
        </div>
      ),
    },
    {
      title: '事件',
      dataIndex: 'eventType',
      width: 150,
      render: (e: string) => <Typography.Text code>{e}</Typography.Text>,
    },
    {
      title: '实体',
      dataIndex: 'entityId',
      width: 110,
      render: (v: string, r) =>
        r.entityType === 'SESSION' && v ? <Typography.Text code>{v}</Typography.Text> : '-',
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 170,
      render: (t: string) => fmtTime(t),
    },
    {
      title: '状态',
      dataIndex: 'readAt',
      width: 70,
      render: (r: string | null) =>
        r ? <Tag>已读</Tag> : <Tag color="blue">未读</Tag>,
    },
    {
      title: '操作',
      key: 'action',
      width: 230,
      render: (_, r) => (
        <Space size={4} wrap>
          {r.actions.map((a) => (
            <Button key={a.action} size="small" onClick={() => onAction(r, a.action)}>
              {a.label}
            </Button>
          ))}
          {!r.readAt && (
            <Button size="small" onClick={() => onRead(r.id)}>
              标记已读
            </Button>
          )}
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
          <span>通知中心</span>
          <Segmented
            value={filter}
            onChange={(v) => setFilter(v as 'all' | 'unread')}
            options={[
              { value: 'all', label: '全部' },
              { value: 'unread', label: '未读' },
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          <Select
            value={level}
            onChange={setLevel}
            style={{ width: 120 }}
            options={[
              { value: 'ALL', label: '全部级别' },
              { value: 'P0', label: 'P0' },
              { value: 'P1', label: 'P1' },
              { value: 'P2', label: 'P2' },
            ]}
          />
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button icon={<CheckOutlined />} onClick={onReadAll}>
            全部已读
          </Button>
          <Button icon={<SettingOutlined />} onClick={openPrefs}>
            通道与偏好
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        会话事件与执行结果的站内通知汇总；支持按级别筛选、标记已读，推送通道与免打扰偏好在「通道与偏好」里配置。
      </Typography.Paragraph>

      <FitTable
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={items}
        pagination={LIST_PAGINATION}
        locale={{ emptyText: <Empty description="暂无通知——会话事件与执行结果产生后会出现在这里。" /> }}
      />

      {/* 通道 + 偏好设置 */}
      <Drawer
        title="通道与偏好设置"
        width={DRAWER_DEFAULT}
        open={prefsOpen}
        onClose={() => setPrefsOpen(false)}
        extra={
          <Button type="primary" loading={savingPrefs} onClick={savePrefs}>
            保存
          </Button>
        }
      >
        {!prefs ? (
          <Empty description="加载中…" />
        ) : (
          <Space direction="vertical" size={20} style={{ width: '100%' }}>
            <div>
              <Typography.Title level={5}>通知通道（FR-03）</Typography.Title>
              <Space direction="vertical" style={{ width: '100%' }}>
                {channels.map((ch) => {
                  const fields = CHANNEL_CONFIG_FIELDS[ch.code] ?? []
                  return (
                    <div
                      key={ch.id}
                      style={{
                        padding: '8px 12px',
                        border: '1px solid #f0f0f0',
                        borderRadius: 8,
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                        <Switch
                          checked={ch.enabled}
                          onChange={(v) =>
                            setChannels((prev) => prev.map((x) => (x.id === ch.id ? { ...x, enabled: v } : x)))
                          }
                        />
                        <span style={{ flex: 1 }}>{ch.name}</span>
                        <Select
                          size="small"
                          value={ch.levelThreshold}
                          onChange={(v) =>
                            setChannels((prev) =>
                              prev.map((x) => (x.id === ch.id ? { ...x, levelThreshold: v } : x)),
                            )
                          }
                          style={{ width: 90 }}
                          options={[
                            { value: 'P0', label: '仅 P0' },
                            { value: 'P1', label: '≥ P1' },
                            { value: 'P2', label: '≥ P2' },
                          ]}
                        />
                      </div>
                      {fields.length > 0 && (
                        <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 6 }}>
                          {fields.map((f) => {
                            const Comp = f.password ? Input.Password : Input
                            return (
                              <div key={f.key}>
                                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                  {f.label}
                                </Typography.Text>
                                <Comp
                                  size="small"
                                  placeholder={f.placeholder ?? f.label}
                                  value={String(ch.config?.[f.key] ?? '')}
                                  onChange={(e) =>
                                    setChannels((prev) =>
                                      prev.map((x) =>
                                        x.id === ch.id
                                          ? { ...x, config: { ...x.config, [f.key]: e.target.value } }
                                          : x,
                                      ),
                                    )
                                  }
                                />
                              </div>
                            )
                          })}
                        </div>
                      )}
                    </div>
                  )
                })}
              </Space>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                外部通道（Bark/企微/飞书）填齐配置后开启即可；保存走合并语义，未改动的配置项不受影响。
              </Typography.Text>
            </div>

            <div>
              <Typography.Title level={5}>免打扰时段（FR-05）</Typography.Title>
              <TimePicker.RangePicker
                value={
                  prefs.quietStart && prefs.quietEnd
                    ? [dayjs(prefs.quietStart, 'HH:mm'), dayjs(prefs.quietEnd, 'HH:mm')]
                    : null
                }
                format="HH:mm"
                minuteStep={30}
                onChange={(range) =>
                  setPrefs({
                    ...prefs,
                    quietStart: range?.[0]?.format('HH:mm') ?? null,
                    quietEnd: range?.[1]?.format('HH:mm') ?? null,
                  })
                }
              />
              <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
                时段内 P1/P2 不再推送浏览器/外部通道，仅进通知中心；P0 照常推送。
              </Typography.Text>
            </div>

            <div>
              <Typography.Title level={5}>静默事件（FR-05）</Typography.Title>
              <Select
                mode="multiple"
                style={{ width: '100%' }}
                placeholder="选择要静默的事件类型"
                value={prefs.mutes.eventTypes}
                onChange={(v: string[]) => setPrefs({ ...prefs, mutes: { eventTypes: v } })}
                options={EVENT_TYPES.map((t) => ({ value: t, label: t }))}
              />
            </div>

            <div>
              <Typography.Title level={5}>静默会话（FR-05）</Typography.Title>
              <Select
                mode="tags"
                style={{ width: '100%' }}
                placeholder="输入会话 ID 回车添加"
                value={prefs.perSessionSilence}
                onChange={(v: string[]) => setPrefs({ ...prefs, perSessionSilence: v })}
              />
            </div>
          </Space>
        )}
      </Drawer>
    </Card>
  )
}
