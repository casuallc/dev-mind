// 设置页「工作区清理」视图（/settings/workspace-cleanup，CAP-114）：会话工作区定时清理策略。
// 服务端固定每小时 tick，命中「启用 + 当前小时 == 执行时间 + 今日未跑」才执行一轮；
// 候选 = workspace_state=OPEN 且超保留天数未更新的终态会话（进行中/挂起永不自动清），
// 清理动作复用 workspace_release 丢弃式释放链路（不合并不 push）。
// 操作按钮在页面 Card extra，故以 ref 暴露 reload/save/runNow（同 WorklogSettingsPanel 模式）。
import { Checkbox, Form, InputNumber, Select, Space, Spin, Switch, Typography, message } from 'antd'
import { useCallback, useEffect, useImperativeHandle, useRef, useState, type Ref } from 'react'
import { getWorkspaceCleanupConfig, runWorkspaceCleanup, saveWorkspaceCleanupConfig } from '../api'
import type { WorkspaceCleanupConfig } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

/** 页面壳（SettingsPage）工具栏在 Card extra，靠该句柄触发刷新 / 保存 / 立即执行 */
export interface WorkspaceCleanupPanelHandle {
  reload: () => void
  /** 校验失败直接 resolve（已 toast），保存失败已在内部 toast */
  save: () => Promise<void>
  /** 立即执行一轮（无视开关与执行时间，仍按保留天数/范围筛选）；触发后排程刷新报告 */
  runNow: () => Promise<void>
}

interface FormValues {
  enabled: boolean
  retentionDays: number
  runHour: number
  scopeRequirement: boolean
  scopeSession: boolean
}

const HOUR_OPTIONS = Array.from({ length: 24 }, (_, h) => ({ value: h, label: `${h}:00` }))

/** lastReport 是服务端落的 JSON 报告，格式化展示（解析失败原样透传） */
function prettyReport(raw?: string | null): string {
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

export default function WorkspaceCleanupPanel({ ref }: { ref?: Ref<WorkspaceCleanupPanelHandle> }) {
  const [form] = Form.useForm<FormValues>()
  const [loading, setLoading] = useState(false)
  const [lastRunAt, setLastRunAt] = useState<string | null>(null)
  const [lastReport, setLastReport] = useState<string | null>(null)
  // 「立即执行」后轮询刷新的定时器，卸载时清理
  const pollTimers = useRef<number[]>([])

  const reload = useCallback(() => {
    setLoading(true)
    getWorkspaceCleanupConfig()
      .then((c: WorkspaceCleanupConfig) => {
        form.setFieldsValue({
          enabled: c.enabled,
          retentionDays: c.retentionDays,
          runHour: c.runHour,
          scopeRequirement: c.scopeRequirement,
          scopeSession: c.scopeSession,
        })
        setLastRunAt(c.lastRunAt ?? null)
        setLastReport(c.lastReport ?? null)
      })
      .catch((e) => showError(e, '加载清理策略失败'))
      .finally(() => setLoading(false))
  }, [form])

  useEffect(reload, [reload])
  useEffect(() => () => pollTimers.current.forEach((t) => window.clearTimeout(t)), [])

  const save = async () => {
    const v = form.getFieldsValue()
    if (!v.scopeRequirement && !v.scopeSession) {
      message.warning('清理范围至少选择一类工作区')
      return
    }
    try {
      const saved = await saveWorkspaceCleanupConfig({
        enabled: v.enabled,
        retentionDays: v.retentionDays,
        runHour: v.runHour,
        scopeRequirement: v.scopeRequirement,
        scopeSession: v.scopeSession,
      })
      setLastRunAt(saved.lastRunAt ?? null)
      setLastReport(saved.lastReport ?? null)
      message.success('清理策略已保存')
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  const runNow = async () => {
    try {
      const r = await runWorkspaceCleanup()
      if (!r.started) {
        message.info('上一轮清理尚未结束，本次触发已忽略')
        return
      }
      message.success('已触发一轮清理，完成后自动刷新报告')
      // 异步执行：排程几次刷新把 lastReport 拉回来
      pollTimers.current.forEach((t) => window.clearTimeout(t))
      pollTimers.current = [2000, 5000, 10000].map((ms) => window.setTimeout(reload, ms))
    } catch (e) {
      showError(e, '触发清理失败')
    }
  }

  useImperativeHandle(ref, () => ({ reload, save, runNow }))

  return (
    <div style={{ maxWidth: 640 }}>
      <Typography.Paragraph type="secondary">
        每天定时清理「超保留天数未更新的终态会话」在节点上的工作区（丢弃语义：不合并、不推送，
        直接删除工作树）；进行中与挂起的会话永不自动清理。需求级共享工作区按需求整块回收，
        同需求下仍有进行中会话时不清。清理失败只记录不阻断，下一轮自动重试；有清理或失败时发送站内通知。
      </Typography.Paragraph>
      <Spin spinning={loading}>
        <Form form={form} layout="vertical">
          <Form.Item
            name="enabled"
            valuePropName="checked"
            label="启用定时清理"
            extra="关闭后已配置的策略保留，仅不再自动执行；下方「立即执行一次」不受影响"
          >
            <Switch />
          </Form.Item>
          <Form.Item
            name="retentionDays"
            label="保留天数"
            extra="会话超过该天数未更新（且已终态）才纳入清理，1~365 天"
            rules={[{ required: true, message: '请填写保留天数' }]}
          >
            <InputNumber min={1} max={365} style={{ width: '100%' }} placeholder="如 30" />
          </Form.Item>
          <Form.Item
            name="runHour"
            label="每天执行时间"
            extra="按服务器本地时区，每小时整点判定一次，当天只执行一轮"
            rules={[{ required: true, message: '请选择执行时间' }]}
          >
            <Select options={HOUR_OPTIONS} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="清理范围" extra="至少选择一类；需求级 = 多会话共用的需求工作树">
            <Space direction="vertical">
              <Form.Item name="scopeRequirement" valuePropName="checked" noStyle>
                <Checkbox>需求级共享工作区</Checkbox>
              </Form.Item>
              <Form.Item name="scopeSession" valuePropName="checked" noStyle>
                <Checkbox>会话级工作区</Checkbox>
              </Form.Item>
            </Space>
          </Form.Item>
        </Form>
        <Typography.Title level={5}>上次执行</Typography.Title>
        <Typography.Paragraph type="secondary">
          {lastRunAt ? `执行时间：${fmtTime(lastRunAt)}` : '从未执行过'}
        </Typography.Paragraph>
        {lastReport && (
          <pre
            style={{
              margin: 0,
              padding: 12,
              maxHeight: 320,
              overflow: 'auto',
              borderRadius: 6,
              background: 'rgba(128,128,128,0.08)',
              fontSize: 12,
            }}
          >
            {prettyReport(lastReport)}
          </pre>
        )}
      </Spin>
    </div>
  )
}
