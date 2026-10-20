// 设置页「通知」视图（/settings/notifications）：CAP-06 通知中心的个人设置——
// 通道开关/分级阈值/通道配置（Bark/企微/飞书 Webhook），防打扰偏好（免打扰时段、按事件类型静默、
// 单会话静默清单）。操作按钮在页面 Card extra，故以 ref 暴露 reload/save。
import { Button, Form, Input, Select, Spin, Switch, Tag, TimePicker, Typography, message } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useCallback, useEffect, useImperativeHandle, useState, type Ref } from 'react'
import { emitTest, getPrefs, listChannels, updateChannel, updatePrefs } from '../api'
import { EVENT_TYPES, type NotificationChannel, type NotificationLevel } from '../types'
import { showError } from '../../../shared/utils/showError'

/** 页面壳（SettingsPage）工具栏在 Card extra，靠该句柄触发刷新 / 保存 */
export interface NotificationSettingsPanelHandle {
  reload: () => void
  /** 校验失败直接 resolve（antd 已高亮），保存失败已在内部 toast */
  save: () => Promise<void>
}

interface ChannelFormValue {
  enabled: boolean
  levelThreshold: NotificationLevel
  config: Record<string, string>
}

/** 表单值形态：通道按 code 分组编辑；免打扰时段为 TimePicker 的 Dayjs（null = 不启用），提交时格式化为 "HH:mm" */
interface FormValues {
  channels: Record<string, ChannelFormValue>
  quietStart?: Dayjs | null
  quietEnd?: Dayjs | null
  mutedEventTypes: string[]
}

/** 后端 "HH:mm" → TimePicker 值（ISO 拼接解析，免 customParseFormat 插件） */
const parseTime = (t?: string | null): Dayjs | null => (t ? dayjs(`2000-01-01T${t}`) : null)

const LEVEL_OPTIONS = [
  { value: 'P0', label: 'P0（仅等待/失败类）' },
  { value: 'P1', label: 'P1（含完成类）' },
  { value: 'P2', label: 'P2（全部）' },
]

/** 通道专属配置项（与后端种子 config 键一致）；secret 字段用 Password 输入防瞟屏 */
const CHANNEL_CONFIG_FIELDS: Record<
  string,
  { key: string; label: string; secret?: boolean; placeholder?: string }[]
> = {
  bark: [
    { key: 'server', label: 'Bark 服务器', placeholder: 'https://api.day.app' },
    { key: 'key', label: '设备 Key', secret: true, placeholder: 'Bark App 里的推送 Key' },
  ],
  wecom: [{ key: 'webhookUrl', label: '群机器人 Webhook 地址', secret: true }],
  feishu: [
    { key: 'webhookUrl', label: '自定义机器人 Webhook 地址', secret: true },
    { key: 'secret', label: '签名校验 Secret（可选）', secret: true },
  ],
}

/** 站内中心是常驻通道：不走阈值/免打扰/静默，关掉铃铛就不更新了 → 禁编辑 */
const CENTER_CODE = 'ws'

export default function NotificationSettingsPanel({ ref }: { ref?: Ref<NotificationSettingsPanelHandle> }) {
  const [form] = Form.useForm<FormValues>()
  const [loading, setLoading] = useState(false)
  const [testing, setTesting] = useState(false)
  // 通道元数据（id/code/name）与表单无关，渲染数据源
  const [channels, setChannels] = useState<NotificationChannel[]>([])
  // 单会话静默清单（从通知上「静默该会话」快捷入口累积），独立保存不进主表单
  const [silencedSessions, setSilencedSessions] = useState<string[]>([])

  const reload = useCallback(() => {
    setLoading(true)
    Promise.all([listChannels(), getPrefs()])
      .then(([chs, prefs]) => {
        setChannels(chs)
        setSilencedSessions(prefs.perSessionSilence ?? [])
        const channelValues: Record<string, ChannelFormValue> = {}
        for (const ch of chs) {
          const config: Record<string, string> = {}
          for (const f of CHANNEL_CONFIG_FIELDS[ch.code] ?? []) {
            const v = ch.config?.[f.key]
            config[f.key] = typeof v === 'string' ? v : ''
          }
          channelValues[ch.code] = { enabled: ch.enabled, levelThreshold: ch.levelThreshold, config }
        }
        form.setFieldsValue({
          channels: channelValues,
          quietStart: parseTime(prefs.quietStart),
          quietEnd: parseTime(prefs.quietEnd),
          mutedEventTypes: prefs.mutes?.eventTypes ?? [],
        })
      })
      .catch((e) => showError(e, '加载通知设置失败'))
      .finally(() => setLoading(false))
  }, [form])

  useEffect(reload, [reload])

  const save = async () => {
    let v: FormValues
    try {
      v = await form.validateFields()
    } catch {
      return // 校验失败：字段已高亮提示
    }
    try {
      for (const ch of channels) {
        const cv = v.channels?.[ch.code]
        if (!cv) continue
        await updateChannel(ch.id, {
          enabled: cv.enabled,
          levelThreshold: cv.levelThreshold,
          ...(Object.keys(cv.config ?? {}).length > 0 ? { config: cv.config } : {}),
        })
      }
      await updatePrefs({
        mutes: { eventTypes: v.mutedEventTypes ?? [] },
        // 空 = 清除免打扰时段（后端空白串语义）
        quietStart: v.quietStart ? v.quietStart.format('HH:mm') : '',
        quietEnd: v.quietEnd ? v.quietEnd.format('HH:mm') : '',
      })
      message.success('通知设置已保存')
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  useImperativeHandle(ref, () => ({ reload, save }))

  const onSendTest = async () => {
    setTesting(true)
    try {
      await emitTest('P0')
      message.success('测试通知已发出，投递结果见通知中心该条通知')
    } catch (e) {
      showError(e, '发送测试通知失败')
    } finally {
      setTesting(false)
    }
  }

  const onRemoveSilence = (sessionId: string) => {
    const next = silencedSessions.filter((id) => id !== sessionId)
    updatePrefs({ perSessionSilence: next })
      .then(() => {
        setSilencedSessions(next)
        message.success('已恢复该会话的通知')
      })
      .catch((e) => showError(e, '操作失败'))
  }

  return (
    <div style={{ maxWidth: 720 }}>
      <Typography.Paragraph type="secondary">
        通知通道决定「什么级别的事件推到哪里」：站内中心常驻，外部通道（Bark/企微/飞书）建议只开 P0，
        避免被打扰；防打扰偏好对本人生效。改完点右上角「保存」统一生效。
      </Typography.Paragraph>
      <Spin spinning={loading}>
        <Form form={form} layout="vertical">
          <Typography.Title level={5}>通知通道</Typography.Title>
          {channels.map((ch) => {
            const fields = CHANNEL_CONFIG_FIELDS[ch.code] ?? []
            const isCenter = ch.code === CENTER_CODE
            return (
              <div
                key={ch.code}
                style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '12px 16px', marginBottom: 12 }}
              >
                <Form.Item label={ch.name} style={{ marginBottom: fields.length > 0 || !isCenter ? 12 : 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                    <Form.Item name={['channels', ch.code, 'enabled']} valuePropName="checked" noStyle>
                      <Switch disabled={isCenter} />
                    </Form.Item>
                    {isCenter ? (
                      <Typography.Text type="secondary">站内中心常驻，不可关闭</Typography.Text>
                    ) : (
                      <>
                        <Typography.Text type="secondary">推送最低级别</Typography.Text>
                        <Form.Item name={['channels', ch.code, 'levelThreshold']} noStyle>
                          <Select style={{ width: 200 }} options={LEVEL_OPTIONS} />
                        </Form.Item>
                      </>
                    )}
                  </div>
                </Form.Item>
                {fields.map((f) => (
                  <Form.Item
                    key={f.key}
                    name={['channels', ch.code, 'config', f.key]}
                    label={f.label}
                    style={{ marginBottom: 12 }}
                  >
                    {f.secret ? (
                      <Input.Password placeholder={f.placeholder} autoComplete="off" />
                    ) : (
                      <Input placeholder={f.placeholder} allowClear />
                    )}
                  </Form.Item>
                ))}
              </div>
            )
          })}
          <Button loading={testing} onClick={onSendTest} style={{ marginBottom: 24 }}>
            发送测试通知（P0）
          </Button>

          <Typography.Title level={5}>防打扰</Typography.Title>
          <Form.Item
            label="免打扰时段"
            extra="时段内仅 P0 推送外部通道，其余静默进通知中心；开始时间晚于结束时间视为跨天（如 23:00 ~ 07:30）。两端都留空 = 不启用。"
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Form.Item name="quietStart" noStyle>
                <TimePicker format="HH:mm" allowClear placeholder="开始" changeOnBlur />
              </Form.Item>
              <span>~</span>
              <Form.Item name="quietEnd" noStyle>
                <TimePicker format="HH:mm" allowClear placeholder="结束" changeOnBlur />
              </Form.Item>
            </div>
          </Form.Item>
          <Form.Item
            name="mutedEventTypes"
            label="按事件类型静默"
            extra="勾选的事件类型不再推送外部通道，仍会进通知中心。"
          >
            <Select
              mode="multiple"
              allowClear
              placeholder="选择要静默的事件类型"
              options={EVENT_TYPES.map((t) => ({ value: t, label: t }))}
            />
          </Form.Item>
          <Form.Item label="已静默的会话" extra="来自通知上的「静默该会话」快捷入口；移除后立即恢复该会话的推送。">
            {silencedSessions.length === 0 ? (
              <Typography.Text type="secondary">无</Typography.Text>
            ) : (
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {silencedSessions.map((id) => (
                  <Tag key={id} closable onClose={() => onRemoveSilence(id)}>
                    {id}
                  </Tag>
                ))}
              </div>
            )}
          </Form.Item>
        </Form>
      </Spin>
    </div>
  )
}
