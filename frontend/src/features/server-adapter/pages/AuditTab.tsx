// CAP-07 FR-06 执行审计查看
import { useCallback, useEffect, useState } from 'react'
import { Badge, Drawer, Select, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { Project } from '../../projects/types'
import { listProjects } from '../../projects/api'
import { listServers, listAudit } from '../api'
import type { AuditView, ServerListItem } from '../types'

const ACTIONS = ['connect_test', 'execute', 'upload', 'download', 'health_check']
const ACTION_LABEL: Record<string, string> = {
  connect_test: '连通测试',
  execute: '执行',
  upload: '上传',
  download: '下载',
  health_check: '健康检查',
}

export default function AuditTab() {
  const [projects, setProjects] = useState<Project[]>([])
  const [servers, setServers] = useState<ServerListItem[]>([])
  const [projectId, setProjectId] = useState<string>()
  const [serverId, setServerId] = useState<number>()
  const [action, setAction] = useState<string>()
  const [rows, setRows] = useState<AuditView[]>([])
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<AuditView | null>(null)

  useEffect(() => {
    listProjects().then(setProjects).catch(() => undefined)
    listServers().then(setServers).catch(() => undefined)
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await listAudit({ projectId, serverId, action }))
    } catch (e) {
      // 无服务器时不阻塞
    } finally {
      setLoading(false)
    }
  }, [projectId, serverId, action])

  useEffect(() => { load() }, [load])

  const columns: ColumnsType<AuditView> = [
    { title: '时间', dataIndex: 'createdAt', width: 170, render: (t) => new Date(t).toLocaleString() },
    { title: '服务器', dataIndex: 'serverName', width: 120, render: (n, r) => `${n} [${r.accessType}]` },
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
          value={projectId} onChange={(v) => { setProjectId(v); setServerId(undefined) }}
          options={projects.map((p) => ({ label: `${p.name} (${p.id})`, value: p.id }))}
        />
        <Select
          allowClear placeholder="服务器" style={{ width: 220 }}
          value={serverId} onChange={setServerId}
          options={servers.filter((s) => !projectId || s.projectId === projectId)
            .map((s) => ({ label: `${s.name} (${s.accessType})`, value: s.id }))}
        />
        <Select
          allowClear placeholder="动作" style={{ width: 140 }}
          value={action} onChange={setAction}
          options={ACTIONS.map((a) => ({ label: ACTION_LABEL[a] ?? a, value: a }))}
        />
      </Space>
      <Table size="small" rowKey="id" loading={loading} columns={columns} dataSource={rows} pagination={false} />

      <Drawer title="审计详情" open={!!detail} onClose={() => setDetail(null)} width={640}>
        {detail && (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Typography.Paragraph type="secondary">
              {ACTION_LABEL[detail.action] ?? detail.action} · {detail.serverName} [{detail.accessType}]
              {detail.templateCode ? ` · 模板 ${detail.templateCode}` : ''}
              {detail.capability ? ` · 能力 ${detail.capability}` : ''} · {new Date(detail.createdAt).toLocaleString()}
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
