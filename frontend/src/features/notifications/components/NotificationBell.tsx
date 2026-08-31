// 顶部铃铛：未读角标 + 下拉最近未读 + 一键执行动作 / 全部已读。
import { Badge, Button, Dropdown, Empty, Space, Tag, Typography, message } from 'antd'
import { BellOutlined, CheckOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { useNotifications, markAllReadLocal } from '../store'
import { readAll } from '../api'
import { executeNotificationAction } from '../actions'
import { LEVEL_COLOR } from '../types'
import type { AppNotification } from '../types'

export default function NotificationBell() {
  const { notifications, connected } = useNotifications()
  const navigate = useNavigate()
  const unread = notifications.filter((n) => !n.readAt)

  const onAction = async (n: AppNotification, action: string) => {
    try {
      const label = await executeNotificationAction(n, action, { navigate })
      message.success(`${label} 已执行`)
    } catch (e) {
      message.error(`动作失败：${(e as Error).message}`)
    }
  }

  const onReadAll = async () => {
    try {
      await readAll()
      markAllReadLocal()
      message.success('已全部标记为已读')
    } catch (e) {
      message.error(`操作失败：${(e as Error).message}`)
    }
  }

  const content = (
    <div style={{ width: 340, maxHeight: 440, overflow: 'auto', padding: 8 }}>
      {unread.length === 0 ? (
        <Empty
          description="暂无未读通知"
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          style={{ padding: '16px 0' }}
        />
      ) : (
        <Space direction="vertical" size={6} style={{ width: '100%' }}>
          {unread.slice(0, 8).map((n) => (
            <div
              key={n.id}
              style={{
                padding: '8px 10px',
                borderRadius: 6,
                background: n.level === 'P0' ? '#fff1f0' : '#fafafa',
              }}
            >
              <Space size={6} wrap>
                <Tag color={LEVEL_COLOR[n.level]} style={{ marginInlineEnd: 0 }}>
                  {n.level}
                </Tag>
                <Typography.Text strong ellipsis style={{ maxWidth: 210 }}>
                  {n.title}
                </Typography.Text>
              </Space>
              {n.body ? (
                <div style={{ fontSize: 12, color: '#666', marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                  {n.body}
                </div>
              ) : null}
              <Space size={4} style={{ marginTop: 4, flexWrap: 'wrap' }}>
                {n.actions.map((a) => (
                  <Button
                    key={a.action}
                    size="small"
                    type="link"
                    style={{ padding: 0 }}
                    onClick={() => onAction(n, a.action)}
                  >
                    {a.label}
                  </Button>
                ))}
              </Space>
            </div>
          ))}
        </Space>
      )}
      <div style={{ textAlign: 'center', paddingTop: 6 }}>
        <Button size="small" type="link" icon={<CheckOutlined />} onClick={onReadAll}>
          全部已读
        </Button>
        <Button size="small" type="link" onClick={() => navigate('/notifications')}>
          查看全部通知
        </Button>
      </div>
    </div>
  )

  return (
    <Badge count={unread.length} overflowCount={99} size="small">
      <Dropdown dropdownRender={() => content} trigger={['click']} placement="bottomRight">
        <Button
          type="text"
          icon={<BellOutlined style={{ fontSize: 16 }} />}
          title={connected ? '通知实时流已连接' : '通知实时流已断开'}
        />
      </Dropdown>
    </Badge>
  )
}
