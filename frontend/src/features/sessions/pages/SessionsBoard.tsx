import { useEffect, useState } from 'react'
import { Button, Card, Space, Table, Tag, Typography, message } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { listSessions } from '../api'
import type { SessionSummary } from '../types'

const stateColor: Record<string, string> = {
  RUNNING: 'processing',
  WAITING_INPUT: 'gold',
  WAITING_AUTH: 'orange',
  DONE: 'success',
  FAILED: 'error',
  SUSPENDED: 'default',
  TERMINATED: 'default',
}

export default function SessionsBoard() {
  const navigate = useNavigate()
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [loading, setLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      setSessions(await listSessions())
    } catch (e) {
      message.error(`加载会话失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // M0 阶段轮询；M3 接入 WebSocket 实时推送后移除
    const timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [])

  const columns: ColumnsType<SessionSummary> = [
    {
      title: 'ID',
      dataIndex: 'id',
      width: 160,
      render: (id: string) => <Typography.Text code>{id}</Typography.Text>,
    },
    { title: '项目', dataIndex: 'projectId', width: 140 },
    {
      title: '任务说明',
      dataIndex: 'taskSpec',
      ellipsis: true,
      render: (t: string) => t?.slice(0, 80) || '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 130,
      render: (s: string) => <Tag color={stateColor[s] ?? 'default'}>{s}</Tag>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 180,
      render: (t: string) => new Date(t).toLocaleString(),
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, r) => (
        <Button size="small" onClick={() => navigate(`/sessions/${r.id}`)}>
          查看
        </Button>
      ),
    },
  ]

  return (
    <Card
      title="会话看板"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} disabled>
            新建会话（M1 开放）
          </Button>
        </Space>
      }
    >
      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={sessions}
        pagination={false}
        locale={{ emptyText: '暂无会话。M0 骨架阶段，M1 开放进程生命周期。' }}
      />
    </Card>
  )
}
