// 指挥中心（CAP-16）：30 秒看全局——需求状态分布 / 活跃会话 / 待办确认 / 最近失败，10s 轮询。
import { useCallback, useEffect, useState } from 'react'
import { Badge, Button, Card, Col, Empty, List, Row, Space, Statistic, Tag, Typography, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { getDashboard } from '../api'
import type { DashboardView, FailureItem } from '../types'

const REQ_STATUS: { key: string; label: string; color: string }[] = [
  { key: 'DRAFT', label: '草稿', color: 'default' },
  { key: 'ANALYZING', label: '分析中', color: 'processing' },
  { key: 'DESIGNING', label: '设计中', color: 'processing' },
  { key: 'IN_PROGRESS', label: '进行中', color: 'processing' },
  { key: 'ACCEPTANCE', label: '待验收', color: 'warning' },
  { key: 'DONE', label: '已完成', color: 'success' },
  { key: 'CANCELLED', label: '已取消', color: 'default' },
]

const SESSION_COLOR: Record<string, string> = {
  RUNNING: 'processing',
  WAITING_INPUT: 'gold',
  WAITING_AUTH: 'orange',
}

const FAILURE_COLOR: Record<FailureItem['type'], string> = {
  BUILD: 'geekblue',
  DEPLOYMENT: 'purple',
  TEST_RUN: 'cyan',
  RELEASE: 'magenta',
}

export default function DashboardPage() {
  const navigate = useNavigate()
  const [data, setData] = useState<DashboardView | null>(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setData(await getDashboard())
    } catch (e) {
      message.error(`加载指挥中心失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
    const timer = window.setInterval(() => load(), 10000)
    return () => window.clearInterval(timer)
  }, [load])

  const waiting = data?.activeSessions.filter((s) => s.status.startsWith('WAITING')).length ?? 0

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title="需求状态分布"
        size="small"
        extra={<Button size="small" icon={<ReloadOutlined />} onClick={load} loading={loading} />}
      >
        <Row gutter={24}>
          {REQ_STATUS.map((s) => (
            <Col key={s.key}>
              <Statistic
                title={s.label}
                value={data?.requirements[s.key] ?? 0}
                valueStyle={{ color: s.key === 'ACCEPTANCE' && (data?.requirements[s.key] ?? 0) > 0 ? '#faad14' : undefined }}
              />
            </Col>
          ))}
        </Row>
      </Card>

      <Row gutter={16}>
        <Col span={8}>
          <Card size="small">
            <Statistic title="活跃会话" value={data?.activeSessions.length ?? 0}
              suffix={waiting > 0 ? <Tag color="orange">{waiting} 个在等人</Tag> : undefined} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="待验收需求" value={data?.pendingAcceptance.length ?? 0} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="待确认方案" value={data?.pendingDesigns.length ?? 0} />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={12}>
          <Card title="运行中会话" size="small">
            <List
              size="small"
              dataSource={data?.activeSessions ?? []}
              locale={{ emptyText: <Empty description="暂无活跃会话" /> }}
              renderItem={(s) => (
                <List.Item
                  style={{ cursor: 'pointer' }}
                  onClick={() => navigate(`/sessions/${s.id}`)}
                  extra={<Tag color={SESSION_COLOR[s.status] ?? 'default'}>{s.status}</Tag>}
                >
                  <List.Item.Meta
                    title={
                      <Space size={8}>
                        <Typography.Text code>{s.id}</Typography.Text>
                        {s.status.startsWith('WAITING') && <Badge status="warning" text="在等人" />}
                      </Space>
                    }
                    description={s.taskSpec || '-'}
                  />
                </List.Item>
              )}
            />
          </Card>
        </Col>
        <Col span={12}>
          <Card title="待办确认" size="small">
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>待验收需求（ACCEPTANCE）</Typography.Text>
            <List
              size="small"
              dataSource={data?.pendingAcceptance ?? []}
              locale={{ emptyText: <Empty description="无待验收需求" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
              renderItem={(r) => (
                <List.Item style={{ cursor: 'pointer' }} onClick={() => navigate(`/projects/${r.projectId}`)}>
                  <Space size={8}>
                    <Tag color="warning">{r.code}</Tag>
                    <span>{r.title}</span>
                  </Space>
                </List.Item>
              )}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>待确认方案（DRAFT）</Typography.Text>
            <List
              size="small"
              dataSource={data?.pendingDesigns ?? []}
              locale={{ emptyText: <Empty description="无待确认方案" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
              renderItem={(d) => (
                <List.Item style={{ cursor: 'pointer' }} onClick={() => navigate(`/projects/${d.projectId}`)}>
                  <Space size={8}>
                    <Tag color="gold">方案 v{d.version}</Tag>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      项目 {d.projectId}{d.docId ? ` · 文档 #${d.docId}` : ''}
                    </Typography.Text>
                  </Space>
                </List.Item>
              )}
            />
          </Card>
        </Col>
      </Row>

      <Card title="最近失败" size="small">
        <List
          size="small"
          dataSource={data?.recentFailures ?? []}
          locale={{ emptyText: <Empty description="最近没有失败记录" /> }}
          renderItem={(f) => (
            <List.Item
              style={{ cursor: 'pointer' }}
              onClick={() => navigate(`/projects/${f.projectId}`)}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {f.time ? new Date(f.time).toLocaleString() : '-'}
                </Typography.Text>
              }
            >
              <Space size={8}>
                <Tag color={FAILURE_COLOR[f.type]}>{f.type}</Tag>
                <span>{f.label}</span>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>项目 {f.projectId}</Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      </Card>
    </Space>
  )
}
