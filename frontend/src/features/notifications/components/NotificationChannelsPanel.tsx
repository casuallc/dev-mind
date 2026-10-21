// 后台「通知管理」通道视图（/admin/notifications?view=channels）：CAP-06 FR-03/07——
// 全局通知通道（站内/Bark/企微/飞书 Webhook）的启用开关、推送最低级别与通道专属配置。
// 通道配置是平台级全局数据（notification_channels 无属主），故归后台管理；写端点仅 ADMIN。
// 操作按钮在页面 Card extra，故以 ref 暴露 reload/save。
import { Button, Form, Input, Select, Spin, Switch, Typography, message } from 'antd'
import { useCallback, useEffect, useImperativeHandle, useState, type Ref } from 'react'
import { emitTest, listChannels, updateChannel } from '../api'
import { type NotificationChannel, type NotificationLevel } from '../types'
import { showError } from '../../../shared/utils/showError'

/** 页面壳（AdminNotificationsPage）工具栏在 Card extra，靠该句柄触发刷新 / 保存 */
export interface NotificationChannelsPanelHandle {
  reload: () => void
  /** 校验失败直接 resolve（antd 已高亮），保存失败已在内部 toast */
  save: () => Promise<void>
}

interface ChannelFormValue {
  enabled: boolean
  levelThreshold: NotificationLevel
  config: Record<string, string>
}

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

export default function NotificationChannelsPanel({ ref }: { ref?: Ref<NotificationChannelsPanelHandle> }) {
  const [form] = Form.useForm<{ channels: Record<string, ChannelFormValue> }>()
  const [loading, setLoading] = useState(false)
  const [testing, setTesting] = useState(false)
  // 通道元数据（id/code/name）与表单无关，渲染数据源
  const [channels, setChannels] = useState<NotificationChannel[]>([])

  const reload = useCallback(() => {
    setLoading(true)
    listChannels()
      .then((chs) => {
        setChannels(chs)
        const channelValues: Record<string, ChannelFormValue> = {}
        for (const ch of chs) {
          const config: Record<string, string> = {}
          for (const f of CHANNEL_CONFIG_FIELDS[ch.code] ?? []) {
            const v = ch.config?.[f.key]
            config[f.key] = typeof v === 'string' ? v : ''
          }
          channelValues[ch.code] = { enabled: ch.enabled, levelThreshold: ch.levelThreshold, config }
        }
        form.setFieldsValue({ channels: channelValues })
      })
      .catch((e) => showError(e, '加载通知通道失败'))
      .finally(() => setLoading(false))
  }, [form])

  useEffect(reload, [reload])

  const save = async () => {
    let v: { channels: Record<string, ChannelFormValue> }
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
      message.success('通知通道已保存')
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

  return (
    <div style={{ maxWidth: 720 }}>
      <Typography.Paragraph type="secondary">
        通知通道决定「什么级别的事件推到哪里」，为全平台全局配置：站内中心常驻，外部通道（Bark/企微/飞书）
        建议只开 P0，避免被打扰。改完点右上角「保存」统一生效。
      </Typography.Paragraph>
      <Spin spinning={loading}>
        <Form form={form} layout="vertical">
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
        </Form>
        <Button loading={testing} onClick={onSendTest}>
          发送测试通知（P0）
        </Button>
      </Spin>
    </div>
  )
}
