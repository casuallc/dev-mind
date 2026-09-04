// 构建记录页（/builds）：当前项目的构建配置、触发与历史。
// CAP-08 构建中心：配置（执行位置/远程服务器/并发）→ 触发构建 → 历史表格 → 日志 Drawer（WS 实时流）。
// 布局遵循 docs/core/前端内容区布局约定.md：单 Card 默认尺寸，配置/触发表单收进 extra 按钮打开的 Modal。
import { Alert, Button, Card, Drawer, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { ReloadOutlined, RocketOutlined, SettingOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { getBuild, getBuildConfig, getBuildLogs, listBuilds, saveBuildConfig, triggerBuild } from '../api'
import type { BuildConfig, BuildExecutor, BuildRecord, BuildStatus } from '../types'
import type { ProjectServer } from '../../projects/types'
import { listServers } from '../../projects/api'
import { useCurrentProjectId } from '../../../app/useCurrentProject'
import { durationMs, fmtTime } from '../../../shared/utils/format'

const STATUS_COLOR: Record<BuildStatus, string> = {
  QUEUED: 'blue',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
}


export default function BuildsPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return <BuildCenter id={projectId} />
}

function BuildCenter({ id }: { id: string }) {
  const [cfg, setCfg] = useState<BuildConfig | null>(null)
  const [servers, setServers] = useState<ProjectServer[]>([])
  const [builds, setBuilds] = useState<BuildRecord[]>([])
  const [loading, setLoading] = useState(false)
  const [commit, setCommit] = useState('')
  const [branch, setBranch] = useState('')
  const [triggerExecutor, setTriggerExecutor] = useState<'' | BuildExecutor>('')
  const [saving, setSaving] = useState(false)
  const [building, setBuilding] = useState(false)
  const [logBuild, setLogBuild] = useState<BuildRecord | null>(null)
  const [configOpen, setConfigOpen] = useState(false)
  const [triggerOpen, setTriggerOpen] = useState(false)

  const refresh = () => {
    setLoading(true)
    listBuilds(id)
      .then(setBuilds)
      .catch(() => {})
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    getBuildConfig(id).then(setCfg).catch(() => {})
    listServers(id).then(setServers).catch(() => {})
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const buildCaps = servers.filter((s) => s.enabled && s.capabilities.includes('build'))

  const onSave = async () => {
    if (!cfg) return
    setSaving(true)
    try {
      const saved = await saveBuildConfig(id, {
        executor: cfg.executor,
        remoteServerId: cfg.remoteServerId,
        concurrencyLimit: cfg.concurrencyLimit,
      })
      setCfg(saved)
      setConfigOpen(false)
      message.success('构建配置已保存')
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const onTrigger = async () => {
    setBuilding(true)
    try {
      const b = await triggerBuild(id, {
        commit: commit || undefined,
        branch: branch || undefined,
        executor: triggerExecutor || undefined,
        remoteServerId: triggerExecutor === 'REMOTE' ? cfg?.remoteServerId ?? undefined : undefined,
      })
      message.success(`构建 #${b.id} 已触发（${b.executor}）`)
      setCommit('')
      setBranch('')
      setTriggerOpen(false)
      refresh()
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBuilding(false)
    }
  }

  const columns: ColumnsType<BuildRecord> = [
    {
      title: 'ID', dataIndex: 'id', width: 70,
      render: (v: number) => `#${v}`,
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: BuildStatus) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
    },
    {
      title: '分支 / 提交', key: 'ref', width: 220,
      render: (_, r) => (
        <Space size={4}>
          {r.branch && <Tag>{r.branch}</Tag>}
          {r.commit ? <Typography.Text code>{r.commit.slice(0, 10)}</Typography.Text> : <span>-</span>}
        </Space>
      ),
    },
    {
      title: '执行位置', dataIndex: 'executor', width: 100,
      render: (v: BuildExecutor) => <Tag color={v === 'REMOTE' ? 'purple' : 'default'}>{v}</Tag>,
    },
    {
      title: '产物', dataIndex: 'artifactRef', width: 200,
      render: (v: string | null) => (v ? <Typography.Text copyable code>{v}</Typography.Text> : <span>-</span>),
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
      render: (_, r) => <Button size="small" onClick={() => setLogBuild(r)}>日志</Button>,
    },
  ]

  return (
    <Card
      title="构建记录"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>
          <Button icon={<SettingOutlined />} onClick={() => setConfigOpen(true)}>构建配置</Button>
          <Button type="primary" icon={<RocketOutlined />} onClick={() => setTriggerOpen(true)}>触发构建</Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        当前项目的构建历史：触发一次构建并查看状态与日志，执行位置/并发等在「构建配置」中调整。
      </Typography.Paragraph>
      <Table<BuildRecord>
        rowKey="id"
        loading={loading}
        dataSource={builds}
        columns={columns}
        pagination={false}
        locale={{
          emptyText: (
            <Space direction="vertical" size={8} style={{ padding: '24px 0' }}>
              <Typography.Text type="secondary">
                还没有构建记录——点「触发构建」发起第一次构建，执行位置先到「构建配置」里确认。
              </Typography.Text>
              <div>
                <Button type="primary" icon={<RocketOutlined />} onClick={() => setTriggerOpen(true)}>
                  触发构建
                </Button>
              </div>
            </Space>
          ),
        }}
      />

      <Modal
        title="构建配置"
        open={configOpen}
        onCancel={() => setConfigOpen(false)}
        onOk={onSave}
        okText="保存配置"
        confirmLoading={saving}
        width={560}
      >
        {cfg && (
          <Form layout="vertical" initialValues={{ executor: cfg.executor, concurrencyLimit: cfg.concurrencyLimit }}>
            <Form.Item label="执行位置">
              <Select<BuildExecutor>
                value={cfg.executor}
                onChange={(v) => setCfg({ ...cfg, executor: v })}
                options={[
                  { value: 'LOCAL', label: '本机' },
                  { value: 'REMOTE', label: '远程服务器' },
                ]}
              />
            </Form.Item>
            <Form.Item label="远程服务器">
              <Select<number>
                placeholder={buildCaps.length ? '选择服务器' : '无可用服务器'}
                value={cfg.remoteServerId ?? undefined}
                disabled={cfg.executor !== 'REMOTE'}
                onChange={(v) => setCfg({ ...cfg, remoteServerId: v ?? null })}
                options={buildCaps.map((s) => ({ value: s.id, label: `${s.name}（${s.accessType}）` }))}
              />
            </Form.Item>
            <Form.Item label="并发上限">
              <InputNumber min={1} max={10} value={cfg.concurrencyLimit} onChange={(v) => setCfg({ ...cfg, concurrencyLimit: v ?? 1 })} />
            </Form.Item>
          </Form>
        )}
      </Modal>

      <Modal
        title="触发构建"
        open={triggerOpen}
        onCancel={() => setTriggerOpen(false)}
        onOk={onTrigger}
        okText="触发构建"
        confirmLoading={building}
        width={520}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <Input placeholder="分支（留空=当前分支）" value={branch} onChange={(e) => setBranch(e.target.value)} />
          <Input placeholder="commit（留空=当前 HEAD）" value={commit} onChange={(e) => setCommit(e.target.value)} />
          <Select<'' | BuildExecutor>
            style={{ width: '100%' }}
            value={triggerExecutor}
            onChange={setTriggerExecutor}
            options={[
              { value: '', label: '执行位置：继承配置' },
              { value: 'LOCAL', label: '执行位置：本机' },
              { value: 'REMOTE', label: '执行位置：远程' },
            ]}
          />
        </Space>
      </Modal>

      <LogDrawer build={logBuild} onClose={() => setLogBuild(null)} />
    </Card>
  )
}

// ---------------- 日志 Drawer（WS 实时流） ----------------

function LogDrawer({ build, onClose }: { build: BuildRecord | null; onClose: () => void }) {
  const [text, setText] = useState('')
  const [connected, setConnected] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    if (!build) {
      setText('')
      setConnected(false)
      return
    }
    setText('')
    setConnected(false)
    getBuildLogs(build.id)
      .then(setText)
      .catch(() => setText(''))

    // RUNNING/QUEUED 实时流
    if (build.status === 'QUEUED' || build.status === 'RUNNING') {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${proto}://${location.host}/ws/builds/${build.id}/logs`)
      wsRef.current = ws
      ws.onopen = () => setConnected(true)
      ws.onmessage = (msg) => {
        try {
          const f = JSON.parse(msg.data)
          if (f.type === 'snapshot') {
            setText(f.logs ?? '')
          } else if (f.type === 'log') {
            setText((t) => (t ? `${t}\n${f.line}` : f.line))
          } else if (f.type === 'done') {
            setConnected(false)
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
  }, [build])

  // 构建可能中途结束，周期性刷新状态以拿到终态/产物
  useEffect(() => {
    if (!build) return
    const timer = setInterval(() => {
      getBuild(build.id)
        .then((latest) => {
          if (latest.status !== build.status && latest.status !== 'RUNNING' && latest.status !== 'QUEUED') {
            onClose()
          }
        })
        .catch(() => {})
    }, 5000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [build])

  return (
    <Drawer
      title={
        build ? (
          <Space>
            <span>构建 #{build.id} 日志</span>
            <Tag color={STATUS_COLOR[build.status]}>{build.status}</Tag>
            {connected && <Tag color="cyan">实时</Tag>}
            {build.artifactRef && <Tag color="gold">产物: {build.artifactRef}</Tag>}
          </Space>
        ) : '构建日志'
      }
      width={720}
      open={!!build}
      onClose={onClose}
    >
      {build?.errorSummary && (
        <Alert type="error" showIcon style={{ marginBottom: 12 }} message={build.errorSummary} />
      )}
      <pre
        style={{
          background: '#0f1115',
          color: '#d0d7de',
          padding: 12,
          borderRadius: 6,
          fontSize: 12,
          lineHeight: 1.6,
          maxHeight: 'calc(100vh - 200px)',
          overflow: 'auto',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
        }}
      >
        {text || '（等待日志…）'}
      </pre>
    </Drawer>
  )
}
