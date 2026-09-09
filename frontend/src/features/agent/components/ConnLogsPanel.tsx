import { useEffect, useState } from 'react'
import { Button, Space, Table, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { listConnLogs } from '../api'
import type { AgentConnLog, ConnLogEvent } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

const eventColor: Record<ConnLogEvent, string> = {
  CONNECT: 'green',
  REJECT: 'red',
  DISCONNECT: 'default',
}

const eventLabel: Record<ConnLogEvent, string> = {
  CONNECT: '接入',
  REJECT: '拒绝',
  DISCONNECT: '断线',
}

/**
 * 节点连接流水面板：接入/拒绝/断线落库后的页面视图。
 * 拒绝记录没有节点归属，来源地址（IP:端口）是定位陌生 runner 的唯一线索。
 */
export default function ConnLogsPanel() {
  const [logs, setLogs] = useState<AgentConnLog[]>([])
  const [loading, setLoading] = useState(false)

  const reload = () => {
    setLoading(true)
    listConnLogs()
      .then(setLogs)
      .catch((e) => showError(e, '加载连接日志失败'))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    reload()
    const timer = window.setInterval(reload, 5000) // 与节点列表一致轻轮询
    return () => window.clearInterval(timer)
  }, [])

  const columns = [
    { title: '时间', dataIndex: 'createdAt', width: 170, render: (t?: string) => fmtTime(t) },
    {
      title: '事件',
      dataIndex: 'event',
      width: 90,
      render: (e: ConnLogEvent) => <Tag color={eventColor[e] ?? 'default'}>{eventLabel[e] ?? e}</Tag>,
    },
    {
      title: '节点',
      dataIndex: 'nodeName',
      width: 200,
      render: (s?: string) => s || <Typography.Text type="secondary">（未识别）</Typography.Text>,
    },
    {
      title: '来源地址',
      dataIndex: 'remoteAddr',
      width: 160,
      render: (s?: string) => (s ? <Typography.Text code>{s}</Typography.Text> : '-'),
    },
    { title: '详情', dataIndex: 'detail', render: (s?: string) => s || '-' },
  ]

  return (
    <>
      <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }}>
        <Typography.Text type="secondary">
          接入 / 拒绝 / 断线流水（最新 200 条，保留 7 天）。「拒绝」= token 无效或节点已禁用，按来源地址定位陌生 runner。
        </Typography.Text>
        <Button icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>
      </Space>
      <Table<AgentConnLog>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={logs}
        pagination={false}
        locale={{ emptyText: '暂无连接日志' }}
      />
    </>
  )
}
