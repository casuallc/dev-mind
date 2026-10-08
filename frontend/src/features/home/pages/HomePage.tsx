// 个人工作台首页（/home）：问候 + 快捷操作 + 聚合卡（进行中会话 / 未读通知）。
// 页内 Segmented 切「概览 / 用量」（CAP-67 用量统计作为工作台页内视图，?view=usage 可深链）。
// 数据全部走各能力既有 API，各卡独立加载、失败静默置空。
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
import { fmtTime } from '../../../shared/utils/format'
import { pageRootScrollStyle } from '../../../shared/utils/pageLayout'
import UsagePage from '../../usage/UsagePage'

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

  useEffect(() => {
    if (view !== 'overview') return
    // 各卡独立取数，任一失败不影响其他卡
    listSessions()
      .then((list) =>
        setActiveSessions(list.filter((s) => ACTIVE_STATES.includes(s.state)).slice(0, 4)),
      )
      .catch(() => setActiveSessions([]))

    unreadCount()
      .then((r) => setUnread(r.count))
      .catch(() => setUnread(0))
    listNotifications({ unreadOnly: true, limit: 5 })
      .then(setNotices)
      .catch(() => setNotices([]))
  }, [view])

  return (
    <div style={pageRootScrollStyle}>
      {/* 问候区 + 快捷操作 */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-end',
          flexWrap: 'wrap',
          gap: 12,
          marginBottom: 16,
        }}
      >
        <div>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {greeting()}，{user?.displayName || user?.username}
          </Typography.Title>
          <Typography.Text type="secondary">
            {dayjs().format('YYYY年MM月DD日 dddd')}
          </Typography.Text>
        </div>
        <Space>
          <Segmented
            options={[
              { label: '概览', value: 'overview' },
              { label: '用量', value: 'usage' },
            ]}
            value={view}
            onChange={(v) => setSearchParams(v === 'usage' ? { view: 'usage' } : {}, { replace: true })}
          />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/sessions')}
          >
            新建会话
          </Button>
          <Button icon={<CommentOutlined />} onClick={() => navigate('/chats')}>
            发起问答
          </Button>
          <Button icon={<FieldTimeOutlined />} onClick={() => navigate('/worklog')}>
            记工时
          </Button>
        </Space>
      </div>

      {view === 'usage' ? (
        <UsagePage />
      ) : (
        /* 统计卡行 */
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
              <Statistic value={activeSessions.length} suffix="个" />
              <List
                size="small"
                dataSource={activeSessions.slice(0, 3)}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无进行中的会话" /> }}
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
              <Statistic value={unread} suffix="条" />
              <List
                size="small"
                dataSource={notices}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有未读通知" /> }}
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
      )}
    </div>
  )
}
