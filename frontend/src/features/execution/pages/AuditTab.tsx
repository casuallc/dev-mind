// CAP-36 执行审计查看（视图内容组件；外壳 Card / extra 按钮在 ExecutionPage）
// exec 帧下发 runner 节点的每次执行全量留痕（domain=agent_exec）；历史 CAP-07 SSH/HTTP 记录同表可查。
import { useCallback, useEffect, useState } from 'react'
import { Badge, Drawer, Select, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { Project } from '../../projects/types'
import { listProjects } from '../../projects/api'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import { listAudit } from '../api'
import type { AuditView } from '../types'
import { fmtTime } from '../../../shared/utils/format'

const ACTIONS = ['execute', 'connect_test', 'upload', 'download', 'health_check']
const ACTION_LABEL: Record<string, string> = {
  execute: '执行',
  connect_test: '连通测试',
  upload: '上传',
  download: '下载',
  health_check: '健康检查',
}

export default function AuditTab({ refreshTick = 0 }: { refreshTick?: number }) {
  const [projects, setProjects] = useState<Project[]>([])
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [projectId, setProjectId] = useState<string>()
  const [nodeId, setNodeId] = useState<number>()
  const [action, setAction] = useState<string>()
  const [rows, setRows] = useState<AuditView[]>([])
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<AuditView | null>(null)

  useEffect(() => {
    listProjects().then(setProjects).catch(() => undefined)
    listAgentNodes().then(setNodes).catch(() => undefined)
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await listAudit({ projectId, nodeId, action }))
    } catch {
      // 无记录时不阻塞
    } finally {
      setLoading(false)
    }
  }, [projectId, nodeId, action])

  useEffect(() => { load() }, [load, refreshTick]) // refreshTick：外壳 extra「刷新」

  const nodeName = (r: AuditView) =>
    r.serverName
      || (r.serverId != null ? nodes.find((n) => n.id === r.serverId)?.name ?? `#${r.serverId}` : '-')

  const columns: ColumnsType<AuditView> = [
    { title: '时间', dataIndex: 'createdAt', width: 170, render: (t) => fmtTime(t) },
    { title: '节点', key: 'node', width: 130, render: (_, r) => `${nodeName(r)} [${r.accessType}]` },
    {
      title: '动作',
      dataIndex: 'action',
      width: 100,
      render: (a) => <Tag color={a === 'execute' ? 'blue' : 'default'}>{ACTION_LABEL[a] ?? a}</Tag>,
    },
    { title: '模板', dataIndex: 'templateCode', width: 100, render: (c) => c ? <Typography.Text code>{c}</Typography.Text> : '-' },
    { title: '能力', dataIndex: 'capability', width: 80, render: (c) => c || '-' },
    { title: '退出码', dataIndex: 'exitCode', width: 70, render: (c) => c ?? '-' },
    {
      title: '结果',
      dataIndex: 'success',
      width: 80,
      render: (s) => (s ? <Badge status="success" text="成功" /> : <Badge status="error" text="失败" />),
    },
    { title: '耗时', dataIndex: 'durationMs', width: 80, render: (d) => `${d ?? '-'} ms` },
    {
      title: '摘要',
      dataIndex: 'detail',
      ellipsis: true,
      render: (d, r) => <a onClick={() => setDetail(r)}>{d}</a>,
    },
  ]

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      <Space>
        <Select
          allowClear placeholder="项目" style={{ width: 180 }}
          value={projectId} onChange={(v) => { setProjectId(v); setNodeId(undefined) }}
          options={projects.map((p) => ({ label: `${p.name} (${p.id})`, value: p.id }))}
        />
        <Select
          allowClear placeholder="节点" style={{ width: 220 }}
          value={nodeId} onChange={setNodeId}
          options={nodes.map((n) => ({ label: `${n.name}${n.status !== 'ONLINE' ? '（离线）' : ''}`, value: n.id }))}
        />
        <Select
          allowClear placeholder="动作" style={{ width: 140 }}
          value={action} onChange={setAction}
          options={ACTIONS.map((a) => ({ label: ACTION_LABEL[a] ?? a, value: a }))}
        />
      </Space>
      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: '暂无审计记录。构建/部署/发版/健康检查等经 exec 帧下发节点执行后自动留痕。' }}
      />

      <Drawer title="审计详情" open={!!detail} onClose={() => setDetail(null)} width={640}>
        {detail && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Typography.Paragraph type="secondary">
              {ACTION_LABEL[detail.action] ?? detail.action} · {nodeName(detail)} [{detail.accessType}]
              {detail.templateCode ? ` · 模板 ${detail.templateCode}` : ''}
              {detail.capability ? ` · 能力 ${detail.capability}` : ''} · {fmtTime(detail.createdAt)}
            </Typography.Paragraph>
            <Typography.Text strong>命令/脚本（模板渲染结果，不含凭证）</Typography.Text>
            <pre style={{ background: '#f6f6f6', padding: 12, borderRadius: 4, fontSize: 12, whiteSpace: 'pre-wrap' }}>
              {detail.command ?? '(无)'}
            </pre>
            <Typography.Text strong>输出摘要</Typography.Text>
            <pre style={{ background: '#f6f6f6', padding: 12, borderRadius: 4, fontSize: 12, whiteSpace: 'pre-wrap' }}>
              {detail.detail ?? '(无)'}
            </pre>
          </Space>
        )}
      </Drawer>
    </Space>
  )
}
