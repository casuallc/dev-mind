// 构建记录页（/builds）：当前项目的构建配置、触发与历史。
// CAP-08 构建中心：配置（执行位置/执行节点/并发）→ 触发构建 → 历史表格 → 日志 Drawer（WS 实时流）。
// CAP-36：AGENT 执行由 runner 节点承接（exec 帧），配置 GitLab 仓库时凭证随帧下发。
// 布局遵循 docs/core/前端内容区布局约定.md：单 Card 默认尺寸，配置/触发表单收进 extra 按钮打开的 Modal。
import { Alert, Button, Card, Drawer, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography, message } from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { ArrowDownOutlined, ArrowUpOutlined, DownloadOutlined, ReloadOutlined, RocketOutlined, SettingOutlined, VerticalAlignBottomOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { getBuild, getBuildConfig, getBuildLogs, listBuilds, saveBuildConfig, triggerBuild } from '../api'
import type { BuildConfig, BuildExecutor, BuildRecord, BuildStatus } from '../types'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { useCurrentProjectId } from '../../../app/useCurrentProject'
import { durationMs, fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

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
  const [nodes, setNodes] = useState<AgentNode[]>([])
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
    listAgentNodes().then(setNodes).catch(() => {})
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const onlineNodes = nodes.filter((n) => n.status === 'ONLINE')
  const nodeOptions = (list: AgentNode[]) =>
    list.map((n) => ({ value: String(n.id), label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}` }))

  const onSave = async () => {
    if (!cfg) return
    setSaving(true)
    try {
      const saved = await saveBuildConfig(id, {
        executor: cfg.executor,
        agentNodeId: cfg.agentNodeId,
        concurrencyLimit: cfg.concurrencyLimit,
      })
      setCfg(saved)
      setConfigOpen(false)
      message.success('构建配置已保存')
    } catch (e) {
      showError(e)
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
        agentNodeId: triggerExecutor === 'AGENT' ? cfg?.agentNodeId ?? undefined : undefined,
      })
      message.success(`构建 #${b.id} 已触发（${b.executor}）`)
      setCommit('')
      setBranch('')
      setTriggerOpen(false)
      refresh()
    } catch (e) {
      showError(e)
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
      render: (v: BuildExecutor) => <Tag color={v === 'AGENT' ? 'purple' : 'default'}>{v}</Tag>,
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
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
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
            <Form.Item label="执行位置" extra="AGENT = 下发 runner 节点执行（exec 帧），节点在线且支持 exec 才会被选中">
              <Select<BuildExecutor>
                value={cfg.executor}
                onChange={(v) => setCfg({ ...cfg, executor: v })}
                options={[
                  { value: 'LOCAL', label: '本机' },
                  { value: 'AGENT', label: 'Agent 节点' },
                ]}
              />
            </Form.Item>
            <Form.Item label="执行节点">
              <Select<string>
                placeholder={onlineNodes.length ? '留空 = 默认路由（项目默认 → 平台默认）' : '无在线节点'}
                value={cfg.agentNodeId ?? undefined}
                disabled={cfg.executor !== 'AGENT'}
                allowClear
                onChange={(v) => setCfg({ ...cfg, agentNodeId: v ?? null })}
                options={nodeOptions(onlineNodes)}
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
              { value: 'AGENT', label: '执行位置：Agent 节点' },
            ]}
          />
        </Space>
      </Modal>

      <LogDrawer build={logBuild} onClose={() => setLogBuild(null)} />
    </Card>
  )
}

// ---------------- 日志 Drawer（WS 实时流 + 搜索定位 + 下载） ----------------

function LogDrawer({ build, onClose }: { build: BuildRecord | null; onClose: () => void }) {
  const [text, setText] = useState('')
  const [connected, setConnected] = useState(false)
  const [kw, setKw] = useState('')
  const [current, setCurrent] = useState(0)
  const [follow, setFollow] = useState(true) // 跟随最新日志：用户上翻自动暂停，回到底部恢复
  const wsRef = useRef<WebSocket | null>(null)
  const preRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (!build) {
      setText('')
      setConnected(false)
      setKw('')
      setCurrent(0)
      setFollow(true)
      return
    }
    setText('')
    setConnected(false)
    setKw('')
    setCurrent(0)
    setFollow(true)
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

  // 按行渲染（左侧行号）；有关键词时行内切片高亮，<mark data-mi=序号> 序号全局连续（大小写不敏感）
  const rendered = useMemo(() => {
    if (!text) return null
    const k = kw.toLowerCase()
    let ordinal = 0
    const rows = text.split('\n').map((line, li) => {
      let content: ReactNode = line
      if (k) {
        const lower = line.toLowerCase()
        const parts: ReactNode[] = []
        let i = 0
        for (;;) {
          const idx = lower.indexOf(k, i)
          if (idx === -1) {
            parts.push(line.slice(i))
            break
          }
          if (idx > i) parts.push(line.slice(i, idx))
          const o = ordinal++
          parts.push(
            <mark
              key={o}
              data-mi={o}
              style={{
                padding: 0,
                color: '#0f1115',
                background: o === current ? '#fa8c16' : '#d4b106',
              }}
            >
              {line.slice(idx, idx + kw.length)}
            </mark>,
          )
          i = idx + kw.length
        }
        content = parts
      }
      return { li, content }
    })
    return { rows, total: ordinal }
  }, [text, kw, current])
  const total = rendered?.total ?? 0

  // 跟随模式：新日志到达自动滚到底部
  useEffect(() => {
    if (follow && preRef.current) preRef.current.scrollTop = preRef.current.scrollHeight
  }, [text, follow])

  // 输入关键词后跳到第一处匹配
  useEffect(() => {
    if (!kw || !total) return
    setCurrent(0)
    requestAnimationFrame(() => {
      preRef.current?.querySelector('[data-mi="0"]')?.scrollIntoView({ block: 'center' })
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kw])

  const onScroll = () => {
    const el = preRef.current
    if (!el) return
    setFollow(el.scrollHeight - el.scrollTop - el.clientHeight < 40)
  }

  const jumpTo = (idx: number) => {
    if (!total) return
    const next = ((idx % total) + total) % total
    setCurrent(next)
    requestAnimationFrame(() => {
      preRef.current?.querySelector(`[data-mi="${next}"]`)?.scrollIntoView({ block: 'center' })
    })
  }

  const scrollToBottom = () => {
    if (preRef.current) preRef.current.scrollTop = preRef.current.scrollHeight
  }

  const download = () => {
    if (!build || !text) return
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `build-${build.id}.log`
    a.click()
    URL.revokeObjectURL(url)
  }

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
      width="70%"
      open={!!build}
      onClose={onClose}
      extra={
        <Space size={8}>
          <Input
            allowClear
            placeholder="搜索日志"
            style={{ width: 220 }}
            value={kw}
            onChange={(e) => setKw(e.target.value)}
            onPressEnter={() => jumpTo(current + 1)}
          />
          {kw && (
            <Typography.Text type={total ? undefined : 'danger'} style={{ minWidth: 48 }}>
              {total ? `${current + 1}/${total}` : '0/0'}
            </Typography.Text>
          )}
          <Button size="small" icon={<ArrowUpOutlined />} disabled={!total} onClick={() => jumpTo(current - 1)} />
          <Button size="small" icon={<ArrowDownOutlined />} disabled={!total} onClick={() => jumpTo(current + 1)} />
          <Button size="small" icon={<DownloadOutlined />} disabled={!text} onClick={download}>
            下载
          </Button>
        </Space>
      }
    >
      {build?.errorSummary && (
        <Alert type="error" showIcon style={{ marginBottom: 12 }} message={build.errorSummary} />
      )}
      <div style={{ position: 'relative' }}>
        <div
          ref={preRef}
          onScroll={onScroll}
          style={{
            background: '#0f1115',
            color: '#d0d7de',
            padding: '12px 12px 12px 0',
            borderRadius: 6,
            fontSize: 12,
            lineHeight: 1.6,
            fontFamily: 'Consolas, Menlo, monospace',
            maxHeight: 'calc(100vh - 200px)',
            overflow: 'auto',
          }}
        >
          {rendered
            ? rendered.rows.map((r) => (
                <div key={r.li} style={{ display: 'flex', minHeight: '1.6em' }}>
                  <span
                    style={{
                      flex: '0 0 48px',
                      paddingRight: 12,
                      textAlign: 'right',
                      color: '#6e7681',
                      userSelect: 'none',
                    }}
                  >
                    {r.li + 1}
                  </span>
                  <span style={{ flex: 1, minWidth: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                    {r.content}
                  </span>
                </div>
              ))
            : '（等待日志…）'}
        </div>
        {!follow && (
          <Button
            size="small"
            icon={<VerticalAlignBottomOutlined />}
            style={{ position: 'absolute', right: 24, bottom: 16, opacity: 0.9 }}
            onClick={scrollToBottom}
          >
            回到底部
          </Button>
        )}
      </div>
    </Drawer>
  )
}
