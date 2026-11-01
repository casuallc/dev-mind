// CAP-104 M3 方案运行弹窗：SEQUENTIAL 单节点编排 / SHARD 用例哈希分片 / MATRIX 每节点全量。
import { Form, Input, InputNumber, Modal, Select, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import { runPlan } from '../api'
import type { CreatePlanRunInput, PlanMode, TestPlan, TestPlanRun } from '../types'
import type { AgentNode } from '../../agent/types'
import type { ProjectEnvironment } from '../../projects/types'
import WorkItemSelect from '../../../shared/components/WorkItemSelect'
import { showError } from '../../../shared/utils/showError'

interface Props {
  plan: TestPlan | null
  nodes: AgentNode[]
  environments: ProjectEnvironment[]
  onClose: () => void
  onRan: (run: TestPlanRun) => void
}

const MODE_OPTIONS: { value: PlanMode; label: string; desc: string }[] = [
  { value: 'SEQUENTIAL', label: 'SEQUENTIAL 顺序编排', desc: '按方案项顺序执行；同并行组内并发' },
  { value: 'SHARD', label: 'SHARD 分片', desc: '每个方案项的用例按哈希分散到多个节点并行' },
  { value: 'MATRIX', label: 'MATRIX 矩阵', desc: '每个节点对每个方案项各跑全量用例（多环境/多版本对比）' },
]

export default function RunPlanModal({ plan, nodes, environments, onClose, onRan }: Props) {
  const [form] = Form.useForm()
  const [running, setRunning] = useState(false)
  const mode = Form.useWatch('mode', form) as PlanMode | undefined
  const distributed = mode === 'SHARD' || mode === 'MATRIX'

  useEffect(() => {
    if (!plan) return
    form.setFieldsValue({
      mode: 'SEQUENTIAL',
      environmentId: plan.environmentId ?? undefined,
      baseUrl: '',
      agentNodeId: undefined,
      nodeIds: [],
      labels: [],
      capabilities: [],
      failStrategy: plan.failStrategy,
      concurrency: plan.concurrency,
      timeoutSec: undefined,
      workItemId: undefined,
      requirementId: plan.requirementId ?? '',
      parametersText: JSON.stringify(plan.parameters ?? {}, null, 2),
    })
  }, [plan, form])

  const nodeOptions = nodes.map((n) => ({
    value: String(n.id),
    label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
  }))

  const onRun = async () => {
    if (!plan) return
    const v = await form.validateFields()
    let parameters: Record<string, string> | undefined
    try {
      const parsed = v.parametersText?.trim() ? JSON.parse(v.parametersText) : {}
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('方案参数必须是 JSON 对象')
      }
      parameters = Object.keys(parsed).length
        ? Object.fromEntries(Object.entries(parsed).map(([k, val]) => [k, val == null ? '' : String(val)]))
        : undefined
    } catch (e) {
      message.error(e instanceof Error ? e.message : '方案参数 JSON 解析失败')
      return
    }
    const input: CreatePlanRunInput = {
      mode: v.mode,
      environmentId: v.environmentId ?? null,
      baseUrl: v.baseUrl?.trim() || undefined,
      agentNodeId: v.mode === 'SEQUENTIAL' ? v.agentNodeId || undefined : undefined,
      nodeIds: distributed && v.nodeIds?.length ? v.nodeIds : undefined,
      labels: v.labels?.length ? v.labels : undefined,
      capabilities: v.capabilities?.length ? v.capabilities : undefined,
      failStrategy: v.failStrategy,
      concurrency: v.concurrency ?? undefined,
      timeoutSec: v.timeoutSec ?? undefined,
      workItemId: v.workItemId || undefined,
      requirementId: v.requirementId?.trim() || undefined,
      parameters,
    }
    setRunning(true)
    try {
      onRan(await runPlan(plan.id, input))
    } catch (e) {
      showError(e, '触发方案运行失败')
    } finally {
      setRunning(false)
    }
  }

  return (
    <Modal
      title={plan ? `运行方案「${plan.name}」` : '运行方案'}
      open={!!plan}
      onCancel={onClose}
      onOk={onRun}
      okText="执行方案"
      confirmLoading={running}
      width={600}
      destroyOnHidden
    >
      <Form form={form} layout="vertical">
        <Form.Item label="执行模式" name="mode" rules={[{ required: true }]}>
          <Select<PlanMode>
            options={MODE_OPTIONS.map((m) => ({ value: m.value, label: m.label }))}
          />
        </Form.Item>
        <Typography.Paragraph type="secondary" style={{ marginTop: -8, fontSize: 12 }}>
          {MODE_OPTIONS.find((m) => m.value === mode)?.desc}
        </Typography.Paragraph>
        <Form.Item label="目标环境（可选）" name="environmentId"
          extra="环境提供变量与默认节点组；未显式选节点时优先取环境节点组">
          <Select<number>
            allowClear
            placeholder="目标环境（可选）"
            options={environments.map((e) => ({ value: e.id, label: e.name }))}
          />
        </Form.Item>
        {distributed ? (
          <Form.Item label="执行节点（可选，优先级最高）" name="nodeIds"
            extra="留空 = 环境节点组 → 标签/能力匹配 → 平台默认">
            <Select<string> mode="multiple" placeholder="留空由环境/标签/平台默认解析" options={nodeOptions} />
          </Form.Item>
        ) : (
          <Form.Item label="执行节点（可选）" name="agentNodeId"
            extra="留空 = 环境节点组 → 标签/能力匹配 → 项目默认 → 平台默认">
            <Select<string> allowClear placeholder="环境节点组 → 项目默认 → 平台默认" options={nodeOptions} />
          </Form.Item>
        )}
        <Form.Item label="节点标签（可选）" name="labels"
          extra="仅在未显式选择节点时参与匹配；节点需覆盖全部标签">
          <Select<string> mode="tags" placeholder="例如 linux / gpu" options={[]} />
        </Form.Item>
        <Form.Item label="节点能力（可选）" name="capabilities"
          extra="匹配节点的 capabilities，例如 docker / jdk21">
          <Select<string> mode="tags" placeholder="例如 docker / jdk21" options={[]} />
        </Form.Item>
        <Form.Item label="失败策略" name="failStrategy">
          <Select options={[
            { value: 'CONTINUE', label: 'CONTINUE 全部执行' },
            { value: 'FAIL_FAST', label: 'FAIL_FAST 失败即停' },
          ]} />
        </Form.Item>
        <Form.Item label="并发度" name="concurrency"
          rules={[{ type: 'number', min: 1, max: 16, message: '并发度需在 1~16 之间' }]}
          extra="顺序模式控制并行组内并发；分布式模式控制节点间并发">
          <InputNumber min={1} max={16} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item label="用例缺省超时（秒，可选）" name="timeoutSec"
          rules={[{ type: 'number', min: 1, message: '超时需大于 0' }]}
          extra="方案项/用例自身 timeoutSec 优先，此处只补缺省值">
          <InputNumber min={1} style={{ width: '100%' }} placeholder="留空 = 用例默认" />
        </Form.Item>
        <Form.Item label="baseUrl（可选）" name="baseUrl">
          <Input placeholder="http://host:port" />
        </Form.Item>
        <Form.Item label="关联工作单元（可选）" name="workItemId">
          <WorkItemSelect projectId={plan?.projectId ?? ''} />
        </Form.Item>
        <Form.Item label="关联需求（可选）" name="requirementId">
          <Input placeholder="缺省取方案关联需求" />
        </Form.Item>
        <Form.Item label="运行参数覆盖（JSON 对象）" name="parametersText">
          <Input.TextArea rows={3} style={{ fontFamily: 'monospace' }} placeholder="{}" />
        </Form.Item>
      </Form>
    </Modal>
  )
}
