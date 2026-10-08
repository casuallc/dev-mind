// 个人工作台首页（/home）：约定骨架单 Card——title=工作台+概览/用量 Segmented，extra=快捷操作。
// 概览视图：问候行 + 统计带（进行中会话/未读通知/今日成本/今日 tokens）+ 双栏列表（进行中会话/未读通知）。
// 用量视图：嵌入 CAP-67 UsagePage（?view=usage 深链，/usage 重定向至此）。各数据源独立加载、失败静默置空。
import { useEffect, useState, useSyncExternalStore } from 'react'
import { Button, Card, Col, Empty, List, Row, Segmented, Space, Statistic, Tag, Typography } from 'antd'
import {
  ArrowRightOutlined,
  CommentOutlined,
  FieldTimeOutlined,
  PlusOutlined,
  RobotOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { getUserSnapshot, subscribeAuth } from '../../auth/authStore'
import { listSessions } from '../../sessions/api'
import type { SessionSummary } from '../../sessions/types'
import { ACTIVE_STATES, stateColor } from '../../../shared/chat/stateMeta'
import { listNotifications, unreadCount } from '../../notifications/api'
import type { AppNotification } from '../../notifications/types'
import { fmtCost, fmtTime, fmtTokens } from '../../../shared/utils/format'
import { pageCardBodyScrollStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import UsagePage from '../../usage/UsagePage'
import { getUsageSummary } from '../../usage/api'
import type { UsageSummary } from '../../usage/types'

function greeting(): string {
  const h = dayjs().hour()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
}

export default function HomePage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const user = useSyncExternalStore(subscribeAuth, getUserSnapshot)
  const view = searchParams.get('view') === 'usage' ? 'usage' : 'overview'

  const [activeSessions, setActiveSessions] = useState<SessionSummary[]>([])
  const [unread, setUnread] = useState(0)
  const [notices, setNotices] = useState<AppNotification[]>([])
  const [todayUsage, setTodayUsage] = useState<UsageSummary | null>(null)

  useEffect(() => {
    if (view !== 'overview') return
    // 各卡独立取数，任一失败不影响其他卡
    listSessions()
      .then((list) =>
        setActiveSessions(list.filter((s) => ACTIVE_STATES.includes(s.state)).slice(0, 5)),
      )
      .catch(() => setActiveSessions([]))

    unreadCount()
      .then((r) => setUnread(r.count))
      .catch(() => setUnread(0))
    listNotifications({ unreadOnly: true, limit: 5 })
      .then(setNotices)
      .catch(() => setNotices([]))

    getUsageSummary({ from: dayjs().startOf('day').toISOString() })
      .then(setTodayUsage)
      .catch(() => setTodayUsage(null))
  }, [view])

  const stats = [
    { title: '进行中会话', value: activeSessions.length, suffix: '个' },
    { title: '未读通知', value: unread, suffix: '条' },
    { title: '今日成本', value: fmtCost(todayUsage?.costUsd ?? 0) },
    {
      title: '今日 tokens',
      value: fmtTokens((todayUsage?.inputTokens ?? 0) + (todayUsage?.outputTokens ?? 0)),
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title={
        <Space size={12}>
          <span>工作台</span>
          <Segmented
            options={[
              { label: '概览', value: 'overview' },
              { label: '用量', value: 'usage' },
            ]}
            value={view}
            onChange={(v) => setSearchParams(v === 'usage' ? { view: 'usage' } : {}, { replace: true })}
          />
        </Space>
      }
      extra={
        <Space>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/sessions')}>
            新建会话
          </Button>
          <Button icon={<CommentOutlined />} onClick={() => navigate('/chats')}>
            发起问答
          </Button>
          <Button icon={<FieldTimeOutlined />} onClick={() => navigate('/worklog')}>
            记工时
          </Button>
        </Space>
      }
    >
      {view === 'usage' ? (
        <UsagePage />
      ) : (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 16 }}>
            {greeting()}，{user?.displayName || user?.username} · {dayjs().format('YYYY年MM月DD日 dddd')}
          </Typography.Paragraph>

          {/* 统计带 */}
          <div
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              gap: '12px 48px',
              padding: '12px 24px',
              marginBottom: 16,
              background: '#fafafa',
              borderRadius: 8,
            }}
          >
            {stats.map((s) => (
              <Statistic key={s.title} title={s.title} value={s.value} suffix={s.suffix} />
            ))}
          </div>

          {/* 双栏列表 */}
          <Row gutter={[16, 16]}>
            <Col xs={24} md={12}>
              <Card
                style={{ height: '100%' }}
                title={
                  <Space>
                    <RobotOutlined />
                    进行中的会话
                  </Space>
                }
                extra={
                  <Typography.Link onClick={() => navigate('/sessions')}>
                    全部 <ArrowRightOutlined />
                  </Typography.Link>
                }
              >
                <List
                  size="small"
                  dataSource={activeSessions}
                  locale={{
                    emptyText: (
                      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无进行中的会话">
                        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/sessions')}>
                          新建会话
                        </Button>
                      </Empty>
                    ),
                  }}
                  renderItem={(s) => (
                    <List.Item style={{ paddingInline: 0 }}>
                      <Typography.Text
                        ellipsis
                        style={{ flex: 1, minWidth: 0, fontSize: 13, cursor: 'pointer' }}
                        onClick={() => navigate('/sessions')}
                      >
                        {s.taskSpec || s.id}
                      </Typography.Text>
                      <Tag color={stateColor[s.state]} style={{ marginInlineEnd: 0 }}>
                        {s.state}
                      </Tag>
                    </List.Item>
                  )}
                />
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card
                style={{ height: '100%' }}
                title={
                  <Space>
                    <CommentOutlined />
                    未读通知
                  </Space>
                }
                extra={
                  <Typography.Link onClick={() => navigate('/notifications')}>
                    全部 <ArrowRightOutlined />
                  </Typography.Link>
                }
              >
                <List
                  size="small"
                  dataSource={notices}
                  locale={{
                    emptyText: (
                      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有未读通知" />
                    ),
                  }}
                  renderItem={(n) => (
                    <List.Item style={{ paddingInline: 0 }}>
                      <Typography.Text
                        ellipsis
                        style={{ flex: 1, minWidth: 0, fontSize: 13, cursor: 'pointer' }}
                        onClick={() => navigate('/notifications')}
                      >
                        {n.title}
                      </Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {fmtTime(n.createdAt)}
                      </Typography.Text>
                    </List.Item>
                  )}
                />
              </Card>
            </Col>
          </Row>
        </>
      )}
    </Card>
  )
}
