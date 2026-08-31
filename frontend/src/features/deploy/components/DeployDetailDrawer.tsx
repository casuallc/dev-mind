// 部署详情 Drawer：WS 实时步骤状态 + 日志流，支持执行/确认/回滚操作。
import {
  Alert,
  Button,
  Drawer,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useRef, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import {
  confirmDeployment,
  executeDeployment,
  getDeployment,
  getDeploymentLogs,
  rollbackDeployment,
} from '../api'
import type { DeploymentRecord, DeployStep } from '../types'
import { durationMs } from '../../../shared/utils/format'
import { STATUS_COLOR } from '../constants'

const STEP_STATUS_COLOR: Record<string, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  SKIPPED: 'default',
}

export default function DeployDetailDrawer({ record, onClose, onChanged }: {
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
