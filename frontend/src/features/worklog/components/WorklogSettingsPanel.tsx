// 设置页「工作日志」视图（/settings/worklog）：工作日志自己的偏好——日报/周报自动生成、
// 每日工时目标、报表格式模板、远程仓库备份。
// 原为工作日志页 extra 的「设置」按钮 +「工时设置」Modal（与顶部一级导航「设置」重名），
// 现收口为设置页的第 4 个页内视图：表单进 body，操作按钮在页面 Card extra，故以 ref 暴露 reload/save。
import { Button, Form, Input, InputNumber, Spin, Switch, Typography, message } from 'antd'
import { useCallback, useEffect, useImperativeHandle, useState, type Ref } from 'react'
import { getDefaultTemplates, getSettings, updateSettings } from '../api'
import type { WorklogSettings } from '../types'
import { showError } from '../../../shared/utils/showError'

/** 页面壳（SettingsPage）工具栏在 Card extra，靠该句柄触发刷新 / 保存 */
export interface WorklogSettingsPanelHandle {
  reload: () => void
  /** 校验失败直接 resolve（antd 已高亮并滚到该字段），保存失败已在内部 toast */
  save: () => Promise<void>
}

/** 表单值形态：工时目标按小时编辑，提交时换算为分钟（与后端 dailyMinutesTarget 对齐） */
interface FormValues {
  autoDaily: boolean
  autoWeekly: boolean
  dailyHoursTarget?: number
  dailyTemplateMd?: string
  weeklyTemplateMd?: string
  remoteUrl?: string
  remoteBranch?: string
}

export default function WorklogSettingsPanel({ ref }: { ref?: Ref<WorklogSettingsPanelHandle> }) {
  const [form] = Form.useForm<FormValues>()
  const [loading, setLoading] = useState(false)

  const reload = useCallback(() => {
    setLoading(true)
    getSettings()
      .then((s: WorklogSettings) =>
        form.setFieldsValue({
          autoDaily: s.autoDaily,
          autoWeekly: s.autoWeekly,
          dailyHoursTarget: s.dailyMinutesTarget != null ? s.dailyMinutesTarget / 60 : undefined,
          dailyTemplateMd: s.dailyTemplateMd ?? '',
          weeklyTemplateMd: s.weeklyTemplateMd ?? '',
          remoteUrl: s.remoteUrl ?? '',
          remoteBranch: s.remoteBranch ?? '',
        }),
      )
      .catch((e) => showError(e, '加载设置失败'))
      .finally(() => setLoading(false))
  }, [form])

  useEffect(reload, [reload])

  // 模板留空 = 回退内置默认；「填入默认」拉取内置模板进编辑器便于在其基础上改
  const fillDefaultTemplate = async (field: 'dailyTemplateMd' | 'weeklyTemplateMd') => {
    try {
      const d = await getDefaultTemplates()
      form.setFieldsValue({ [field]: field === 'dailyTemplateMd' ? d.dailyTemplateMd : d.weeklyTemplateMd })
    } catch (e) {
      showError(e, '获取默认模板失败')
    }
  }

  const save = async () => {
    let v: FormValues
    try {
      v = await form.validateFields()
    } catch {
      return // 校验失败：字段已高亮提示
    }
    try {
      await updateSettings({
        autoDaily: v.autoDaily,
        autoWeekly: v.autoWeekly,
        dailyMinutesTarget: v.dailyHoursTarget != null ? Math.round(v.dailyHoursTarget * 60) : undefined,
        dailyTemplateMd: v.dailyTemplateMd,
        weeklyTemplateMd: v.weeklyTemplateMd,
        remoteUrl: v.remoteUrl ?? '',
        remoteBranch: v.remoteBranch ?? '',
      })
      message.success('设置已保存')
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  useImperativeHandle(ref, () => ({ reload, save }))

  return (
    <div style={{ maxWidth: 640 }}>
      <Typography.Paragraph type="secondary">
        日报/周报自动生成、每日工时目标、报表格式模板与工作日志空间的远程备份。
        改动即时生效：自动生成由服务端定时任务触发，模板用于生成会话，远程备份供工作日志页的「推送远端」使用。
      </Typography.Paragraph>
      <Spin spinning={loading}>
        <Form form={form} layout="vertical">
          <Form.Item name="autoDaily" label="每天自动生成日报草稿" valuePropName="checked" extra="默认 18:30（服务端 cron 可配）">
            <Switch />
          </Form.Item>
          <Form.Item name="autoWeekly" label="每周一自动生成上周周报草稿" valuePropName="checked" extra="默认周一 09:00">
            <Switch />
          </Form.Item>
          <Form.Item name="dailyHoursTarget" label="每日工时目标（小时）">
            <InputNumber min={0} max={24} step={0.5} style={{ width: '100%' }} placeholder="如 8" />
          </Form.Item>
          <Form.Item
            name="dailyTemplateMd"
            label="日报格式模板"
            extra={
              <>
                占位符：{'{{date}}'} / {'{{entries}}'} / {'{{commits}}'}；留空 = 内置默认。
                <Button type="link" size="small" onClick={() => fillDefaultTemplate('dailyTemplateMd')}>
                  填入内置默认
                </Button>
              </>
            }
          >
            <Input.TextArea rows={7} placeholder="留空使用内置默认模板" style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Form.Item
            name="weeklyTemplateMd"
            label="周报格式模板"
            extra={
              <>
                占位符：{'{{weekRange}}'} / {'{{entries}}'} / {'{{commits}}'}；留空 = 内置默认。
                <Button type="link" size="small" onClick={() => fillDefaultTemplate('weeklyTemplateMd')}>
                  填入内置默认
                </Button>
              </>
            }
          >
            <Input.TextArea rows={8} placeholder="留空使用内置默认模板" style={{ fontFamily: 'monospace' }} />
          </Form.Item>
          <Form.Item
            name="remoteUrl"
            label="远程仓库备份（URL）"
            extra="把日报/周报所在的工作日志空间备份到该远端（点工作日志页空间条上的「推送远端」手动同步）。仅 http/https；凭证按 URL host 匹配「设置 → 第三方账号」的个人访问令牌。留空 = 解绑。"
          >
            <Input placeholder="https://git.example.com/<你>/worklog.git" allowClear />
          </Form.Item>
          <Form.Item name="remoteBranch" label="备份分支">
            <Input placeholder="留空默认 main" allowClear />
          </Form.Item>
        </Form>
      </Spin>
    </div>
  )
}
