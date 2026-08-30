import { useEffect, useState } from 'react'
import { Button, Card, Empty, Space, Tag, Typography, message } from 'antd'
import { useParams } from 'react-router-dom'
import { getSession } from '../api'
import type { SessionSummary } from '../types'

export default function SessionDetail() {
  const { id } = useParams<{ id: string }>()
  const [session, setSession] = useState<SessionSummary | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!id) return
    setLoading(true)
    getSession(id)
      .then(setSession)
      .catch((e) => message.error(`加载失败：${(e as Error).message}`))
      .finally(() => setLoading(false))
  }, [id])

  if (!session) {
    return (
      <Card loading={loading}>
        <Empty description="会话不存在或尚未实现" />
      </Card>
    )
  }

  return (
    <Card
      title={
        <Space>
          <Typography.Text code>{session.id}</Typography.Text>
          <Tag color={session.status === 'RUNNING' ? 'processing' : 'default'}>
            {session.status}
          </Tag>
        </Space>
      }
      extra={
        <Space>
          <Button size="small" disabled>
            挂起
          </Button>
          <Button size="small" danger disabled>
            终止
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph>
        <Typography.Text strong>项目：</Typography.Text>
        {session.projectId}
      </Typography.Paragraph>
      <Typography.Paragraph>
        <Typography.Text strong>任务说明：</Typography.Text>
        <pre style={{ whiteSpace: 'pre-wrap' }}>{session.taskSpec}</pre>
      </Typography.Paragraph>
      <Card size="small" title="实时输出（M3 接入 WebSocket）">
        <Typography.Text type="secondary">
          实时输出流将在此展示，支持自动滚动、迟到回放与输入回复。
        </Typography.Text>
      </Card>
    </Card>
  )
}
