// CAP-80 M4 新建/编辑任务弹窗：类型下拉（/types 元数据驱动）+ 四调度形态 + RUNNER 类选执行节点
// （在线节点，留空=平台默认兜底）+ builtin.shell 命令文本域（组装 payload JSON，其余类型给
// JSON 文本域并预填 payloadTemplate）+ 高级策略折叠（重试/超时/重叠）。
import { useEffect, useMemo, useState } from 'react'
import { Collapse, DatePicker, Form, Input, InputNumber, Modal, Radio, Select, message } from 'antd'
import dayjs from 'dayjs'
import { createTask, listTaskTypes, updateTask } from '../api'
import type { AsyncTaskView, TaskTypeView, TaskUpsertBody } from '../types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'

const TIME_FMT = 'YYYY-MM-DD HH:mm:ss'
const SHELL_TYPE = 'builtin.shell'

interface FormValues {
  name?: string
  type: string
  scheduleKind: 'IMMEDIATE' | 'DELAY' | 'FIXED_DELAY' | 'CRON'
  runAt?: dayjs.Dayjs
  intervalSec?: number
  cronExpr?: string
  maxRuns?: number
  agentNodeId?: string
  /** builtin.shell 专用 */
  command?: string
  /** 其余类型的原始 payload JSON */
  payload?: string
  retryMaxAttempts?: number
  retryBackoff?: 'FIXED' | 'EXPONENTIAL'
  retryIntervalSec?: number
  timeoutSec?: number
  overlapPolicy?: 'SKIP' | 'QUEUE'
}

export default function TaskFormModal({
  editing,
  onClose,
}: {
  /** null = 新建；否则编辑该任务 */
  editing: AsyncTaskView | null | undefined
  /** saved=true 表示已保存（父级刷新列表） */
  onClose: (saved: boolean) => void
}) {
  const open = editing !== undefined
  const [form] = Form.useForm<FormValues>()
  const [types, setTypes] = useState<TaskTypeView[]>([])
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [saving, setSaving] = useState(false)
  const watchType = Form.useWatch('type', form)
  const watchKind = Form.useWatch('scheduleKind', form)

  const currentType = useMemo(() => types.find((t) => t.type === watchType), [types, watchType])
  const isRunner = currentType?.executor === 'RUNNER'
  const isShell = watchType === SHELL_TYPE

  useEffect(() => {
    if (!open) return
    listTaskTypes().then(setTypes).catch((e) => message.error(`加载任务类型失败：${e instanceof Error ? e.message : e}`))
    listAgentNodes().then(setNodes).catch(() => setNodes([]))
  }, [open])

  // 每次打开按编辑对象重置表单（Modal 不销毁子树，复用时必须显式重置）
  useEffect(() => {
    if (!open) return
    if (editing) {
      let command: string | undefined
      const payload = editing.payload ?? ''
      if (editing.taskType === SHELL_TYPE) {
        try {
          const p = JSON.parse(payload || '{}') as { command?: string }
          command = p.command
        } catch { /* payload 非 JSON 时落入原文编辑 */ }
      }
      form.setFieldsValue({
        name: editing.name,
        type: editing.taskType,
        scheduleKind: editing.scheduleKind,
        runAt: editing.runAt ? dayjs(editing.runAt) : undefined,
        intervalSec: editing.intervalMs != null ? Math.round(editing.intervalMs / 1000) : undefined,
        cronExpr: editing.cronExpr ?? undefined,
        maxRuns: editing.maxRuns ?? undefined,
        agentNodeId: editing.agentNodeId ?? undefined,
        command,
        payload: editing.taskType === SHELL_TYPE ? undefined : payload,
        retryMaxAttempts: editing.retryMaxAttempts,
        retryBackoff: editing.retryBackoff,
        retryIntervalSec: editing.retryIntervalMs != null ? Math.round(editing.retryIntervalMs / 1000) : undefined,
        timeoutSec: editing.timeoutMs != null ? Math.round(editing.timeoutMs / 1000) : undefined,
        overlapPolicy: editing.overlapPolicy,
      })
    } else {
      form.resetFields()
      form.setFieldsValue({ scheduleKind: 'IMMEDIATE', retryBackoff: 'FIXED', overlapPolicy: 'SKIP' })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, editing])

  const onTypeChange = (type: string) => {
    const t = types.find((x) => x.type === type)
    if (t && type !== SHELL_TYPE) {
      form.setFieldValue('payload', t.payloadTemplate ?? '')
    }
  }

  const submit = async () => {
    const v = await form.validateFields()
    // builtin.shell：命令组装 payload；保留编辑时 payload 里的其余字段（workingDir/env 等）
    let payload = v.payload
    if (v.type === SHELL_TYPE) {
      if (!v.command || !v.command.trim()) {
        message.error('请填写要执行的 shell 命令')
        return
      }
      let rest: Record<string, unknown> = {}
      if (editing?.payload) {
        try { rest = JSON.parse(editing.payload) as Record<string, unknown> } catch { /* 忽略 */ }
      }
      payload = JSON.stringify({ ...rest, command: v.command })
    } else if (payload && payload.trim()) {
      try {
        JSON.parse(payload)
      } catch {
        message.error('payload 不是合法 JSON')
        return
      }
    } else {
      payload = undefined
    }
    const body: TaskUpsertBody = {
      name: v.name?.trim() || undefined,
      type: v.type,
      payload,
      scheduleKind: v.scheduleKind,
      runAt: v.scheduleKind === 'DELAY' && v.runAt ? v.runAt.format(TIME_FMT) : undefined,
      intervalMs: v.scheduleKind === 'FIXED_DELAY' && v.intervalSec ? v.intervalSec * 1000 : undefined,
      cronExpr: v.scheduleKind === 'CRON' ? v.cronExpr?.trim() : undefined,
      maxRuns: v.maxRuns,
      agentNodeId: isRunner ? v.agentNodeId : undefined,
      retryMaxAttempts: v.retryMaxAttempts,
      retryBackoff: v.retryBackoff,
      retryIntervalMs: v.retryIntervalSec ? v.retryIntervalSec * 1000 : undefined,
      timeoutMs: v.timeoutSec ? v.timeoutSec * 1000 : undefined,
      overlapPolicy: v.overlapPolicy,
    }
    setSaving(true)
    try {
      if (editing) {
        await updateTask(editing.id, body)
        message.success('任务已保存')
      } else {
        await createTask(body)
        message.success('任务已创建')
      }
      onClose(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  const nodeOptions = useMemo(() => {
    const opts = nodes
      .filter((n) => n.status === 'ONLINE')
      .map((n) => ({
        value: String(n.id),
        label: `${n.name} (${n.os ?? '远程节点'})${n.isDefault ? ' · 平台默认' : ''}`,
      }))
    // 编辑时节点可能已离线：保留原值选项避免显示成裸 id
    const cur = editing?.agentNodeId
    if (cur && !opts.some((o) => o.value === cur)) {
      opts.unshift({ value: cur, label: `节点 ${cur}（当前不在线）` })
    }
    return opts
  }, [nodes, editing])

  return (
    <Modal
      title={editing ? `编辑任务：${editing.name}` : '新建任务'}
      open={open}
      onCancel={() => onClose(false)}
      onOk={submit}
      confirmLoading={saving}
      okText={editing ? '保存' : '创建'}
      cancelText="取消"
      width={640}
      maskClosable={false}
    >
      <Form form={form} layout="vertical" size="small">
        <Form.Item label="任务类型" name="type" rules={[{ required: true, message: '请选择任务类型' }]}
          extra={currentType?.description || undefined}>
          <Select
            showSearch
            placeholder="选择已注册的任务类型"
            onChange={onTypeChange}
            options={types.map((t) => ({
              value: t.type,
              label: `${t.type}${t.executor === 'RUNNER' ? '（runner 执行）' : ''}`,
            }))}
          />
        </Form.Item>
        <Form.Item label="名称" name="name" extra="留空 = 用类型名">
          <Input placeholder="任务名称" maxLength={200} allowClear />
        </Form.Item>
        {isRunner && (
          <Form.Item label="执行节点" name="agentNodeId"
            extra="留空 = 平台默认节点；节点在线性在执行时判定（离线即本次执行失败并按策略重试）">
            <Select
              allowClear
              placeholder="跟随平台默认"
              options={nodeOptions}
              notFoundContent="暂无在线节点（后台 → Agent 节点 注册）"
            />
          </Form.Item>
        )}
        <Form.Item label="调度形态" name="scheduleKind" rules={[{ required: true }]}>
          <Radio.Group
            options={[
              { value: 'IMMEDIATE', label: '即时' },
              { value: 'DELAY', label: '定时' },
              { value: 'FIXED_DELAY', label: '固定间隔' },
              { value: 'CRON', label: 'Cron' },
            ]}
            optionType="button"
          />
        </Form.Item>
        {watchKind === 'DELAY' && (
          <Form.Item label="执行时刻" name="runAt" rules={[{ required: true, message: '请选择执行时刻' }]}>
            <DatePicker showTime={{ format: 'HH:mm:ss' }} format={TIME_FMT} style={{ width: '100%' }} />
          </Form.Item>
        )}
        {watchKind === 'FIXED_DELAY' && (
          <Form.Item label="间隔（秒）" name="intervalSec"
            rules={[{ required: true, message: '请填写间隔秒数' }]}
            extra="fixed-delay 语义：下次执行 = 本轮结束时刻 + 间隔">
            <InputNumber min={1} precision={0} style={{ width: 200 }} />
          </Form.Item>
        )}
        {watchKind === 'CRON' && (
          <Form.Item label="Cron 表达式" name="cronExpr"
            rules={[{ required: true, message: '请填写 cron 表达式' }]}
            extra="Spring 六段式：秒 分 时 日 月 周（如 0 0 3 * * * = 每天 03:00）">
            <Input placeholder="0 0 3 * * *" maxLength={128} />
          </Form.Item>
        )}
        {(watchKind === 'FIXED_DELAY' || watchKind === 'CRON') && (
          <Form.Item label="执行次数上限" name="maxRuns" extra="留空 = 不限">
            <InputNumber min={1} precision={0} style={{ width: 200 }} />
          </Form.Item>
        )}
        {isShell ? (
          <Form.Item label="Shell 命令" name="command"
            rules={[{ required: true, message: '请填写命令' }]}
            extra="在 runner 本地工作目录执行（过节点 execAllowlist 白名单）；标准输出/错误实时入任务日志">
            <Input.TextArea rows={4} placeholder="echo hello" style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        ) : (
          <Form.Item label="payload（JSON）" name="payload" extra="创建时固化的执行参数快照；留空 = 类型默认值">
            <Input.TextArea rows={4} placeholder="{}" style={{ fontFamily: 'monospace' }} />
          </Form.Item>
        )}
        <Collapse
          ghost
          items={[
            {
              key: 'adv',
              label: '高级策略（重试 / 超时 / 重叠）',
              children: (
                <>
                  <Form.Item label="最大尝试次数（含首次）" name="retryMaxAttempts" extra="默认 1 = 不重试">
                    <InputNumber min={1} max={20} precision={0} style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item label="重试退避" name="retryBackoff">
                    <Radio.Group
                      options={[
                        { value: 'FIXED', label: '固定间隔' },
                        { value: 'EXPONENTIAL', label: '指数退避' },
                      ]}
                      optionType="button"
                    />
                  </Form.Item>
                  <Form.Item label="重试间隔（秒）" name="retryIntervalSec" extra="默认 60s">
                    <InputNumber min={1} precision={0} style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item label="单次执行超时（秒）" name="timeoutSec"
                    extra={isRunner ? '默认 1800s；runner 侧超时整树 kill' : '默认 1800s，超时协作式中断'}>
                    <InputNumber min={1} precision={0} style={{ width: 200 }} />
                  </Form.Item>
                  <Form.Item label="重叠策略" name="overlapPolicy" extra="到点时上一轮未结束：SKIP=跳过本轮，QUEUE=排队补跑">
                    <Radio.Group
                      options={[
                        { value: 'SKIP', label: 'SKIP' },
                        { value: 'QUEUE', label: 'QUEUE' },
                      ]}
                      optionType="button"
                    />
                  </Form.Item>
                </>
              ),
            },
          ]}
        />
      </Form>
    </Modal>
  )
}
