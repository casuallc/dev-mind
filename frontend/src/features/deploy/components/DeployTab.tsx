// CAP-09 部署中心：部署计划配置（步骤/回滚步骤）→ 创建部署单（服务器+构建+环境）→ 历史表格 →
// 详情 Drawer（WS 实时步骤状态 + 日志，执行/确认/回滚）。
import {
  Alert,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useRef, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined } from '@ant-design/icons'
import {
  confirmDeployment,
  createDeployment,
  executeDeployment,
  getDeployConfig,
  getDeployment,
  getDeploymentLogs,
  listDeployments,
  rollbackDeployment,
  saveDeployConfig,
} from '../api'
import type { DeployConfig, DeployStatus, DeployStep, DeployStepInput, DeploymentRecord } from '../types'
import type { BuildRecord } from '../../build/types'
import type { ProjectEnvironment, ProjectServer } from '../../projects/types'
import { listBuilds } from '../../build/api'
import { listEnvironments, listServers } from '../../projects/api'

const STATUS_COLOR: Record<DeployStatus, string> = {
  PLANNED: 'blue',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  ROLLED_BACK: 'orange',
}
const STEP_STATUS_COLOR: Record<string, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  SKIPPED: 'default',
}
const STEP_TYPES = ['artifact', 'backup', 'deploy', 'start', 'health']

const paramsToText = (p: Record<string, string> | undefined): string =>
  Object.entries(p ?? {})
    .map(([k, v]) => `${k}=${v}`)
    .join('\n')

const textToParams = (t: string): Record<string, string> => {
  const out: Record<string, string> = {}
  t.split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .forEach((l) => {
      const i = l.indexOf('=')
      if (i > 0) out[l.slice(0, i).trim()] = l.slice(i + 1).trim()
    })
  return out
}

function fmtTime(s: string | null): string {
  if (!s) return '-'
  const d = new Date(s)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function durationMs(a: string | null, b: string | null): string {
  if (!a || !b) return '-'
  const ms = new Date(b).getTime() - new Date(a).getTime()
  if (ms < 1000) return `${ms}ms`
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m ${s % 60}s`
}

export default function DeployTab({ id }: { id: string }) {
  const [cfg, setCfg] = useState<DeployConfig | null>(null)
  const [servers, setServers] = useState<ProjectServer[]>([])
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])
  const [builds, setBuilds] = useState<BuildRecord[]>([])
  const [deploys, setDeploys] = useState<DeploymentRecord[]>([])
  const [creating, setCreating] = useState(false)
  const [detail, setDetail] = useState<DeploymentRecord | null>(null)

  // 创建表单
  const [serverId, setServerId] = useState<number | undefined>()
  const [environmentId, setEnvironmentId] = useState<number | undefined>()
  const [buildId, setBuildId] = useState<number | undefined>()
  const [env, setEnv] = useState('test')
  const [confirmRequired, setConfirmRequired] = useState(false)

  const refresh = () => listDeployments(id).then(setDeploys).catch(() => {})

  useEffect(() => {
    getDeployConfig(id).then(setCfg).catch(() => {})
    listServers(id).then(setServers).catch(() => {})
    listEnvironments(id).then(setEnvironments).catch(() => {})
    listBuilds(id).then(setBuilds).catch(() => {})
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const deployCaps = servers.filter((s) => s.enabled && s.capabilities.includes('deploy'))
  const artifactBuilds = builds.filter((b) => b.artifactRef)

  const onConfigChanged = async (cfg: DeployConfig) => {
    try {
      const saved = await saveDeployConfig(id, { steps: cfg.steps, rollbackSteps: cfg.rollbackSteps })
      setCfg(saved)
      message.success('部署计划配置已保存')
    } catch (e) {
      message.error((e as Error).message)
      setCfg(await getDeployConfig(id))
    }
  }

  const onCreate = async () => {
    if (!serverId && !environmentId) {
      message.warning('请选择目标服务器或环境')
      return
    }
    setCreating(true)
    try {
      const d = await createDeployment({
        projectId: id,
        serverId: serverId || undefined,
        environmentId: environmentId || undefined,
        buildId: buildId || undefined,
        env: environmentId ? undefined : env || 'test',
        confirmRequired,
      })
      setDetail(d)
      refresh()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setCreating(false)
    }
  }

  const columns: ColumnsType<DeploymentRecord> = [
    {
      title: 'ID', dataIndex: 'id', width: 70,
      render: (v: number) => `#${v}`,
    },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (v: DeployStatus) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
    },
    {
      title: '构建', dataIndex: 'buildId', width: 80,
      render: (v: number | null) => (v ? `#${v}` : <span>-</span>),
    },
    {
      title: '环境', dataIndex: 'env', width: 90,
      render: (v: string) => <Tag color={v === 'prod' ? 'red' : v === 'staging' ? 'orange' : 'blue'}>{v || '-'}</Tag>,
    },
    {
      title: '备份', dataIndex: 'backupRef', ellipsis: true,
      render: (v: string | null) => (v ? <Typography.Text copyable code style={{ fontSize: 12 }}>{v}</Typography.Text> : <span>-</span>),
    },
    {
      title: '回滚自', dataIndex: 'rollbackOf', width: 90,
      render: (v: number | null) => (v ? `#${v}` : <span>-</span>),
    },
    {
      title: '创建时间', dataIndex: 'createdAt', width: 160,
      render: (v: string) => fmtTime(v),
    },
    {
      title: '耗时', key: 'dur', width: 100,
      render: (_, r) => durationMs(r.startedAt, r.finishedAt),
    },
    {
      title: '', key: 'act', width: 80,
      render: (_, r) => <Button size="small" onClick={() => setDetail(r)}>详情</Button>,
    },
  ]

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      <Card size="small" title="部署计划配置">
        {cfg && (
          <ConfigEditor cfg={cfg} onChanged={onConfigChanged} />
        )}
      </Card>

      <Card size="small" title="创建部署">
        <Space wrap>
          <Select<number>
            style={{ width: 180 }}
            placeholder="选择环境（可选）"
            value={environmentId}
            onChange={setEnvironmentId}
            allowClear
            options={environments.map((e) => ({ value: e.id, label: `${e.name}${e.description ? ` · ${e.description}` : ''}` }))}
          />
          <Select<number>
            style={{ width: 200 }}
            placeholder={
              deployCaps.length
                ? environmentId ? '目标服务器（缺省取环境首台）' : '选择目标服务器'
                : '无可用服务器（需 deploy 能力）'
            }
            value={serverId}
            onChange={setServerId}
            allowClear={!!environmentId}
            options={deployCaps.map((s) => ({ value: s.id, label: `${s.name}（${s.accessType} · ${s.env || '?'}）` }))}
          />
          <Select<number>
            style={{ width: 220 }}
            placeholder={artifactBuilds.length ? '选择构建（产物）' : '无已登记产物的构建'}
            value={buildId}
            onChange={setBuildId}
            allowClear
            options={artifactBuilds.map((b) => ({ value: b.id, label: `#${b.id} · ${b.artifactRef}` }))}
          />
          {environmentId == null && (
            <Input placeholder="环境" value={env} onChange={(e) => setEnv(e.target.value)} style={{ width: 120 }} />
          )}
          <Space size={4}>
            <span style={{ fontSize: 12 }}>需确认</span>
            <Switch checked={confirmRequired} onChange={setConfirmRequired} size="small" />
          </Space>
          <Button type="primary" loading={creating} onClick={onCreate}>
            创建部署
          </Button>
        </Space>
        <div style={{ marginTop: 8, fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>
          创建后进入待执行（PLANNED），计划在执行前可见；同构建重复部署会被识别（需 force 才可重建）。
        </div>
      </Card>

      <Card size="small" title="部署历史">
        <Table<DeploymentRecord> rowKey="id" size="small" dataSource={deploys} columns={columns} pagination={{ pageSize: 10 }} />
      </Card>

      <DetailDrawer
        record={detail}
        onClose={() => setDetail(null)}
        onChanged={(d) => {
          setDetail(d)
          refresh()
        }}
      />
    </Space>
  )
}

// ---------------- 部署计划配置编辑器 ----------------

function ConfigEditor({ cfg, onChanged }: { cfg: DeployConfig; onChanged: (c: DeployConfig) => void }) {
  return (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      <StepListEditor
        title="部署步骤（拉取产物 → 备份 → 部署 → 启动 → 健康检查）"
        steps={cfg.steps}
        onChange={(steps) => onChanged({ ...cfg, steps })}
      />
      <StepListEditor
        title="回滚步骤（失败后按此恢复，${backup} 为备份引用）"
        steps={cfg.rollbackSteps}
        onChange={(rollbackSteps) => onChanged({ ...cfg, rollbackSteps })}
      />
    </Space>
  )
}

function StepListEditor({ title, steps, onChange }: {
  title: string
  steps: DeployStepInput[]
  onChange: (s: DeployStepInput[]) => void
}) {
  const [editing, setEditing] = useState<DeployStepInput | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [form] = Form.useForm()

  const openEdit = (s: DeployStepInput | null) => {
    setIsNew(!s)
    setEditing(s)
    form.setFieldsValue(
      s
        ? { ...s, paramsText: paramsToText(s.params) }
        : { name: '', type: 'deploy', templateCode: '', paramsText: '' },
    )
  }

  const save = async (v: { name: string; type: string; templateCode: string; paramsText: string }) => {
    const step: DeployStepInput = { name: v.name, type: v.type, templateCode: v.templateCode, params: textToParams(v.paramsText) }
    if (editing) {
      onChange(steps.map((s) => (s === editing ? step : s)))
    } else {
      onChange([...steps, step])
    }
    setEditing(null)
  }

  const columns: ColumnsType<DeployStepInput> = [
    { title: '名称', dataIndex: 'name', width: 140, render: (n: string) => n || '-' },
    { title: '类型', dataIndex: 'type', width: 100, render: (t: string) => <Tag color="geekblue">{t}</Tag> },
    { title: '模板 code', dataIndex: 'templateCode', width: 160, render: (c: string) => <Typography.Text code>{c}</Typography.Text> },
    {
      title: '参数',
      dataIndex: 'params',
      ellipsis: true,
      render: (p: Record<string, string>) => paramsToText(p) || '-',
    },
    {
      title: '',
      key: 'act',
      width: 130,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEdit(r)}>编辑</Button>
          <Button size="small" danger onClick={() => onChange(steps.filter((s) => s !== r))}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }}>
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>{title}</Typography.Text>
        <Button size="small" icon={<PlusOutlined />} onClick={() => openEdit(null)}>添加步骤</Button>
      </Space>
      <Table<DeployStepInput> rowKey={(r) => r.name + r.type + r.templateCode} size="small" columns={columns} dataSource={steps} pagination={false} />
      <Modal title={isNew ? '添加步骤' : '编辑步骤'} open={!!editing} onCancel={() => setEditing(null)}
        onOk={() => form.submit()} okText="保存" width={520} destroyOnClose>
        <Form form={form} layout="vertical" onFinish={save}>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入步骤名' }]}>
            <Input placeholder="如 启动服务" />
          </Form.Item>
          <Form.Item label="类型" name="type" rules={[{ required: true }]}>
            <Select options={STEP_TYPES.map((t) => ({ value: t, label: t }))} />
          </Form.Item>
          <Form.Item label="模板 code（CAP-07 白名单）" name="templateCode" rules={[{ required: true, message: '请输入模板 code' }]}>
            <Input placeholder="如 dep_start" />
          </Form.Item>
          <Form.Item label="参数（每行 key=value，可引用 ${artifact} ${backup} ${env}）" name="paramsText">
            <Input.TextArea rows={3} placeholder="port=8080" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

// ---------------- 部署详情 Drawer（WS 实时） ----------------

function DetailDrawer({ record, onClose, onChanged }: {
  record: DeploymentRecord | null
  onClose: () => void
  onChanged: (d: DeploymentRecord) => void
}) {
  const [d, setD] = useState<DeploymentRecord | null>(record)
  const [text, setText] = useState('')
  const [connected, setConnected] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setD(record)
    setText('')
    setConnected(false)
    if (!record) return
    getDeploymentLogs(record.id).then(setText).catch(() => setText(''))

    const active = record.status === 'PLANNED' || record.status === 'RUNNING'
    if (active) {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${proto}://${location.host}/ws/deployments/${record.id}/stream`)
      wsRef.current = ws
      ws.onopen = () => setConnected(true)
      ws.onmessage = (msg) => {
        try {
          const f = JSON.parse(msg.data)
          if (f.type === 'snapshot') {
            setD((cur) => (cur ? { ...cur, status: f.status, currentStep: f.currentStep, backupRef: f.backupRef, steps: f.steps } : cur))
          } else if (f.type === 'step') {
            setD((cur) => {
              if (!cur) return cur
              const idx = cur.steps.findIndex((s) => s.id === f.step.id)
              const steps = idx >= 0 ? cur.steps.map((s, i) => (i === idx ? f.step : s)) : [...cur.steps, f.step]
              return { ...cur, steps }
            })
          } else if (f.type === 'log') {
            setText((t) => (t ? `${t}\n${f.line}` : f.line))
          } else if (f.type === 'done') {
            setConnected(false)
            setD((cur) => (cur ? { ...cur, status: f.status } : cur))
            ws.close()
          }
        } catch {
          /* 忽略坏帧 */
        }
      }
      ws.onclose = () => setConnected(false)
      return () => ws.close()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record])

  // 终态轮询刷新（步骤/备份引用落库）
  useEffect(() => {
    if (!record) return
    const timer = setInterval(() => {
      getDeployment(record.id)
        .then((latest) => {
          if (JSON.stringify(latest) !== JSON.stringify(d)) {
            setD(latest)
            onChanged(latest)
          }
        })
        .catch(() => {})
    }, 4000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record])

  const act = async (fn: () => Promise<DeploymentRecord>, ok: string) => {
    if (!d) return
    setBusy(true)
    try {
      const nd = await fn()
      setD(nd)
      onChanged(nd)
      if (nd.status === 'PLANNED') {
        message.success(ok)
      }
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const stepColumns: ColumnsType<DeployStep> = [
    { title: '#', dataIndex: 'seq', width: 44 },
    { title: '步骤', dataIndex: 'name', width: 150, render: (n: string) => n || '-' },
    { title: '类型', dataIndex: 'type', width: 90, render: (t: string) => <Tag color="geekblue">{t}</Tag> },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: string) => <Tag color={STEP_STATUS_COLOR[v]}>{v}</Tag>,
    },
    { title: '详情', dataIndex: 'detail', render: (v: string | null) => v || '-' },
    {
      title: '时间', key: 'time', width: 120,
      render: (_, s) => durationMs(s.startedAt, s.finishedAt),
    },
  ]

  const canExecute = d?.status === 'PLANNED'
  const canConfirm = canExecute && d.confirmRequired && !d.confirmed
  const canRollback = !!d && d.status !== 'PLANNED' && d.status !== 'RUNNING' && d.status !== 'ROLLED_BACK'

  return (
    <Drawer
      title={
        d ? (
          <Space>
            <span>部署 #{d.id}</span>
            <Tag color={STATUS_COLOR[d.status]}>{d.status}</Tag>
            {d.confirmRequired && !d.confirmed && <Tag color="gold">待确认</Tag>}
            {connected && <Tag color="cyan">实时</Tag>}
            {d.rollbackOf && <Tag color="purple">回滚自 #{d.rollbackOf}</Tag>}
          </Space>
        ) : '部署详情'
      }
      width={820}
      open={!!record}
      onClose={onClose}
    >
      {d && (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {d.errorSummary && <Alert type="error" showIcon message={d.errorSummary} />}
          <Space wrap>
            <Button type="primary" size="small" disabled={!canExecute} loading={busy}
              onClick={() => act(() => executeDeployment(d.id), '已开始执行')}>
              执行
            </Button>
            <Button size="small" disabled={!canConfirm} loading={busy}
              onClick={() => act(() => confirmDeployment(d.id), '已确认')}>
              确认
            </Button>
            <Button size="small" danger disabled={!canRollback} loading={busy}
              onClick={() => act(() => rollbackDeployment(d.id), '已开始回滚')}>
              回滚
            </Button>
          </Space>
          <Space wrap size={8}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              构建 {d.buildId ? `#${d.buildId}` : '-'} · 服务器 # {d.serverId} · 环境 {d.env || '-'}
            </Typography.Text>
            {d.backupRef && (
              <Tag color="gold">备份: <Typography.Text copyable style={{ fontSize: 12 }}>{d.backupRef}</Typography.Text></Tag>
            )}
          </Space>
          <Typography.Text strong style={{ fontSize: 13 }}>执行计划（{d.plan.length} 步）</Typography.Text>
          <Table<DeployStep> rowKey="id" size="small" columns={stepColumns} dataSource={d.steps} pagination={false} />
          <Typography.Text strong style={{ fontSize: 13 }}>日志</Typography.Text>
          <pre
            style={{
              background: '#0f1115',
              color: '#d0d7de',
              padding: 12,
              borderRadius: 6,
              fontSize: 12,
              lineHeight: 1.6,
              maxHeight: 'calc(100vh - 420px)',
              overflow: 'auto',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
            }}
          >
            {text || '（等待日志…）'}
          </pre>
        </Space>
      )}
    </Drawer>
  )
}
