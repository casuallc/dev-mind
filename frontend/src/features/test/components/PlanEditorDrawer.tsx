// CAP-104 M2 方案编辑器：基本信息 + 方案项（CASE/SUITE 引用、顺序、并行组、覆盖参数）。
// 方案项整体替换保存；参考用例/套件仅允许已评审（后端二次校验）。
import {
  Button,
  Checkbox,
  Drawer,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useRef, useState } from 'react'
import { createPlan, updatePlan } from '../api'
import type { TestCase, TestPlan, TestPlanInput, TestSuite } from '../types'
import type { ProjectEnvironment } from '../../projects/types'
import { REVIEW_COLOR, REVIEW_LABEL } from '../constants'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { showError } from '../../../shared/utils/showError'

interface ItemRow {
  key: string
  refType: 'CASE' | 'SUITE'
  refId?: number
  seq: number
  parallelGroup: string
  overridesText: string
  enabled: boolean
}

interface Props {
  open: boolean
  projectId: string
  /** null = 新建 */
  plan: TestPlan | null
  suites: TestSuite[]
  cases: TestCase[]
  environments: ProjectEnvironment[]
  onClose: () => void
  onSaved: (plan: TestPlan) => void
}

export default function PlanEditorDrawer({
  open, projectId, plan, suites, cases, environments, onClose, onSaved,
}: Props) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [environmentId, setEnvironmentId] = useState<number | undefined>()
  const [failStrategy, setFailStrategy] = useState<'FAIL_FAST' | 'CONTINUE'>('CONTINUE')
  const [concurrency, setConcurrency] = useState(1)
  const [requirementId, setRequirementId] = useState('')
  const [isTemplate, setIsTemplate] = useState(false)
  const [parametersText, setParametersText] = useState('{}')
  const [rows, setRows] = useState<ItemRow[]>([])
  const [saving, setSaving] = useState(false)
  const [overrideFor, setOverrideFor] = useState<{ key: string; text: string } | null>(null)
  const keySeq = useRef(0)
  const nextKey = () => `row-${++keySeq.current}`

  useEffect(() => {
    if (!open) return
    if (plan) {
      setName(plan.name)
      setDescription(plan.description ?? '')
      setEnvironmentId(plan.environmentId ?? undefined)
      setFailStrategy(plan.failStrategy ?? 'CONTINUE')
      setConcurrency(plan.concurrency ?? 1)
      setRequirementId(plan.requirementId ?? '')
      setIsTemplate(plan.isTemplate)
      setParametersText(JSON.stringify(plan.parameters ?? {}, null, 2))
      setRows(plan.items.map((i) => ({
        key: nextKey(),
        refType: i.refType,
        refId: i.refId,
        seq: i.seq,
        parallelGroup: i.parallelGroup ?? '',
        overridesText: JSON.stringify(i.overrides ?? {}, null, 2),
        enabled: i.enabled !== false,
      })))
    } else {
      setName('')
      setDescription('')
      setEnvironmentId(undefined)
      setFailStrategy('CONTINUE')
      setConcurrency(1)
      setRequirementId('')
      setIsTemplate(false)
      setParametersText('{}')
      setRows([{ key: nextKey(), refType: 'SUITE', seq: 1, parallelGroup: '', overridesText: '{}', enabled: true }])
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, plan])

  const refOptions = (type: 'CASE' | 'SUITE') => {
    if (type === 'CASE') {
      return cases.map((c) => ({
        value: c.id,
        label: `${c.caseKey ? `${c.caseKey} · ` : ''}${c.name}`,
      }))
    }
    return suites
      .filter((s) => s.kind !== 'script')
      .map((s) => ({ value: s.id, label: `${s.name}（${s.caseCount} 用例）` }))
  }

  const suiteReview = (id?: number) => {
    const s = suites.find((x) => x.id === id)
    return s?.reviewStatus ?? null
  }

  const addRow = () => {
    setRows((cur) => [...cur, {
      key: nextKey(),
      refType: 'SUITE',
      seq: cur.length + 1,
      parallelGroup: '',
      overridesText: '{}',
      enabled: true,
    }])
  }

  const patchRow = (key: string, patch: Partial<ItemRow>) => {
    setRows((cur) => cur.map((r) => (r.key === key ? { ...r, ...patch } : r)))
  }

  const parseObject = (text: string, label: string): Record<string, unknown> => {
    const trimmed = text.trim()
    if (!trimmed) return {}
    const value = JSON.parse(trimmed) as unknown
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      throw new Error(`${label} 必须是 JSON 对象`)
    }
    return value as Record<string, unknown>
  }

  const save = async () => {
    if (!name.trim()) {
      message.warning('请填写方案名称')
      return
    }
    if (!rows.length) {
      message.warning('请至少添加 1 个方案项')
      return
    }
    if (rows.some((r) => r.refId == null)) {
      message.warning('方案项引用的用例/套件不能为空')
      return
    }
    let parameters: Record<string, string>
    let items: TestPlanInput['items']
    try {
      const paramObj = parseObject(parametersText, '方案参数')
      parameters = Object.fromEntries(Object.entries(paramObj).map(([k, v]) => [k, v == null ? '' : String(v)]))
      items = rows.map((r, idx) => ({
        refType: r.refType,
        refId: r.refId as number,
        seq: r.seq || idx + 1,
        parallelGroup: r.parallelGroup.trim() || null,
        overrides: parseObject(r.overridesText, `方案项 #${idx + 1} 覆盖`),
        enabled: r.enabled,
      }))
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'JSON 解析失败')
      return
    }
    const input: TestPlanInput = {
      name: name.trim(),
      description: description.trim() || null,
      environmentId: environmentId ?? null,
      failStrategy,
      concurrency,
      parameters,
      requirementId: requirementId.trim() || null,
      isTemplate,
      items,
    }
    setSaving(true)
    try {
      const saved = plan ? await updatePlan(plan.id, input) : await createPlan(projectId, input)
      message.success(plan ? '方案已保存' : '方案已创建')
      onSaved(saved)
    } catch (e) {
      showError(e, '保存方案失败')
    } finally {
      setSaving(false)
    }
  }

  const columns: ColumnsType<ItemRow> = [
    {
      title: '类型', dataIndex: 'refType', width: 100,
      render: (v: 'CASE' | 'SUITE', row, idx) => (
        <Select<'CASE' | 'SUITE'>
          size="small"
          value={v}
          style={{ width: 88 }}
          options={[{ value: 'SUITE', label: '套件' }, { value: 'CASE', label: '用例' }]}
          onChange={(type) => {
            patchRow(row.key, { refType: type, refId: undefined, seq: idx + 1 })
          }}
        />
      ),
    },
    {
      title: '引用', key: 'ref', width: 330,
      render: (_, row) => (
        <Select<number>
          size="small"
          showSearch
          optionFilterProp="label"
          style={{ width: '100%' }}
          placeholder={row.refType === 'CASE' ? '选择已评审用例' : '选择套件（脚本套件不支持）'}
          value={row.refId}
          options={refOptions(row.refType)}
          onChange={(v) => patchRow(row.key, { refId: v })}
        />
      ),
    },
    {
      title: '顺序', dataIndex: 'seq', width: 90,
      render: (v: number, row) => (
        <InputNumber size="small" min={1} value={v} style={{ width: '100%' }}
          onChange={(n) => patchRow(row.key, { seq: n ?? 1 })} />
      ),
    },
    {
      title: '并行组', dataIndex: 'parallelGroup', width: 130,
      render: (v: string, row) => (
        <Input size="small" placeholder="可空" value={v}
          onChange={(e) => patchRow(row.key, { parallelGroup: e.target.value })} />
      ),
    },
    {
      title: '启用', dataIndex: 'enabled', width: 60,
      render: (v: boolean, row) => (
        <Checkbox checked={v} onChange={(e) => patchRow(row.key, { enabled: e.target.checked })} />
      ),
    },
    {
      title: '覆盖', key: 'override', width: 100,
      render: (_, row) => {
        const has = row.overridesText.trim() !== '' && row.overridesText.trim() !== '{}'
        return (
          <Button size="small" onClick={() => setOverrideFor({ key: row.key, text: row.overridesText })}>
            {has ? '已配置' : '配置'}
          </Button>
        )
      },
    },
    {
      title: '评审', key: 'review', width: 90,
      render: (_, row) => {
        if (row.refType === 'CASE') {
          const c = cases.find((x) => x.id === row.refId)
          return c?.reviewStatus
            ? <Tag color={REVIEW_COLOR[c.reviewStatus]}>{REVIEW_LABEL[c.reviewStatus]}</Tag>
            : <Tag>待评审</Tag>
        }
        const s = suiteReview(row.refId)
        return s ? <Tag color={REVIEW_COLOR[s]}>{REVIEW_LABEL[s]}</Tag> : <Tag>未评审</Tag>
      },
    },
    {
      title: '', key: 'act', width: 60,
      render: (_, row) => (
        <Button size="small" danger
          onClick={() => setRows((cur) => cur.filter((r) => r.key !== row.key))}>删除</Button>
      ),
    },
  ]

  return (
    <Drawer
      title={plan ? `编辑方案 #${plan.id}` : '新建测试方案'}
      open={open}
      width={DRAWER_DEFAULT}
      onClose={onClose}
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={save}>保存</Button>
        </Space>
      }
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Space size={16} wrap align="start">
          <div style={{ width: 280 }}>
            <Typography.Text strong>方案名称</Typography.Text>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="如：核心链路回归" />
          </div>
          <div style={{ width: 200 }}>
            <Typography.Text strong>默认环境</Typography.Text>
            <Select<number>
              style={{ width: '100%' }}
              allowClear
              placeholder="可选"
              value={environmentId}
              onChange={(v) => setEnvironmentId(v)}
              options={environments.map((e) => ({ value: e.id, label: e.name }))}
            />
          </div>
          <div style={{ width: 160 }}>
            <Typography.Text strong>失败策略</Typography.Text>
            <Select<'FAIL_FAST' | 'CONTINUE'>
              style={{ width: '100%' }}
              value={failStrategy}
              onChange={setFailStrategy}
              options={[
                { value: 'CONTINUE', label: 'CONTINUE 继续' },
                { value: 'FAIL_FAST', label: 'FAIL_FAST 快速失败' },
              ]}
            />
          </div>
          <div style={{ width: 120 }}>
            <Typography.Text strong>并发数</Typography.Text>
            <InputNumber min={1} max={16} style={{ width: '100%' }} value={concurrency}
              onChange={(v) => setConcurrency(v ?? 1)} />
          </div>
          <div style={{ width: 220 }}>
            <Typography.Text strong>关联需求</Typography.Text>
            <Input value={requirementId} onChange={(e) => setRequirementId(e.target.value)} placeholder="需求 ID（可选）" />
          </div>
          <div style={{ paddingTop: 24 }}>
            <Checkbox checked={isTemplate} onChange={(e) => setIsTemplate(e.target.checked)}>
              作为模板（不可直接运行）
            </Checkbox>
          </div>
        </Space>

        <div>
          <Typography.Text strong>描述</Typography.Text>
          <Input.TextArea rows={2} value={description} onChange={(e) => setDescription(e.target.value)}
            placeholder="方案用途/范围（可选）" />
        </div>

        <div>
          <Typography.Text strong>方案参数（JSON 对象，运行时可覆盖）</Typography.Text>
          <Input.TextArea rows={3} value={parametersText} onChange={(e) => setParametersText(e.target.value)}
            style={{ fontFamily: 'monospace' }} placeholder='{"baseUrl": "https://..."}' />
        </div>

        <Space>
          <Typography.Text strong>方案项</Typography.Text>
          <Button size="small" onClick={addRow}>添加方案项</Button>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            同序号/同并行组内并发；顺序由 seq 升序决定。覆盖支持 parameters / env / agentNodeId / labels / timeoutSec / retry。
          </Typography.Text>
        </Space>
        <Table<ItemRow>
          rowKey="key"
          size="small"
          columns={columns}
          dataSource={rows}
          pagination={false}
          locale={{ emptyText: '暂无方案项' }}
        />
      </Space>

      <Modal
        centered
        title="方案项覆盖（JSON）"
        open={!!overrideFor}
        onCancel={() => setOverrideFor(null)}
        onOk={() => {
          if (!overrideFor) return
          try {
            parseObject(overrideFor.text, '覆盖')
          } catch (e) {
            message.error(e instanceof Error ? e.message : 'JSON 解析失败')
            return
          }
          patchRow(overrideFor.key, { overridesText: overrideFor.text })
          setOverrideFor(null)
        }}
        okText="应用"
        width={640}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          示例：{`{"parameters":{"tenant":"demo"},"timeoutSec":600,"retry":{"max":2,"on":["ASSERTION","TIMEOUT"]},"env":{"BASE_URL":"http://..."}}`}
        </Typography.Paragraph>
        <Input.TextArea
          rows={10}
          value={overrideFor?.text ?? ''}
          onChange={(e) => setOverrideFor((cur) => (cur ? { ...cur, text: e.target.value } : cur))}
          style={{ fontFamily: 'monospace' }}
        />
      </Modal>
    </Drawer>
  )
}
