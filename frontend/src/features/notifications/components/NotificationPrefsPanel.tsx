// 后台「通知管理」防打扰视图（/admin/notifications?view=prefs）：CAP-06 FR-05——
// 免打扰时段、按事件类型静默、单会话静默清单。当前单用户（userId="local"），实质为全局偏好。
// 操作按钮在页面 Card extra，故以 ref 暴露 reload/save。
import { Form, Select, Spin, Tag, TimePicker, Typography, message } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useCallback, useEffect, useImperativeHandle, useState, type Ref } from 'react'
import { getPrefs, updatePrefs } from '../api'
import { EVENT_TYPES } from '../types'
import { showError } from '../../../shared/utils/showError'

/** 页面壳（AdminNotificationsPage）工具栏在 Card extra，靠该句柄触发刷新 / 保存 */
export interface NotificationPrefsPanelHandle {
  reload: () => void
  /** 校验失败直接 resolve（antd 已高亮），保存失败已在内部 toast */
  save: () => Promise<void>
}

/** 表单值形态：免打扰时段为 TimePicker 的 Dayjs（null = 不启用），提交时格式化为 "HH:mm" */
interface FormValues {
  quietStart?: Dayjs | null
  quietEnd?: Dayjs | null
  mutedEventTypes: string[]
}

/** 后端 "HH:mm" → TimePicker 值（ISO 拼接解析，免 customParseFormat 插件） */
const parseTime = (t?: string | null): Dayjs | null => (t ? dayjs(`2000-01-01T${t}`) : null)

export default function NotificationPrefsPanel({ ref }: { ref?: Ref<NotificationPrefsPanelHandle> }) {
  const [form] = Form.useForm<FormValues>()
  const [loading, setLoading] = useState(false)
  // 单会话静默清单（从通知上「静默该会话」快捷入口累积），独立保存不进主表单
  const [silencedSessions, setSilencedSessions] = useState<string[]>([])

  const reload = useCallback(() => {
    setLoading(true)
    getPrefs()
      .then((prefs) => {
        setSilencedSessions(prefs.perSessionSilence ?? [])
        form.setFieldsValue({
          quietStart: parseTime(prefs.quietStart),
          quietEnd: parseTime(prefs.quietEnd),
          mutedEventTypes: prefs.mutes?.eventTypes ?? [],
        })
      })
      .catch((e) => showError(e, '加载防打扰偏好失败'))
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
      await updatePrefs({
        mutes: { eventTypes: v.mutedEventTypes ?? [] },
        // 空 = 清除免打扰时段（后端空白串语义）
        quietStart: v.quietStart ? v.quietStart.format('HH:mm') : '',
        quietEnd: v.quietEnd ? v.quietEnd.format('HH:mm') : '',
      })
      message.success('防打扰偏好已保存')
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  useImperativeHandle(ref, () => ({ reload, save }))

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
        防打扰偏好对通知推送生效：免打扰时段内仅 P0 推外部通道，其余静默进通知中心；静默规则只挡外部通道，
        通知中心照常收录。改完点右上角「保存」统一生效。
      </Typography.Paragraph>
      <Spin spinning={loading}>
        <Form form={form} layout="vertical">
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
