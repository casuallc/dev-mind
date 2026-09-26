import { Button, Empty, Space, Table, Tag, Typography } from 'antd'
import { ExportOutlined } from '@ant-design/icons'
import { fmtTime } from '../../../shared/utils/format'
import { pagePaneScrollStyle } from '../../../shared/utils/pageLayout'
import type { Bookmark, SharedWithMe } from '../types'
import StatusDot from './StatusDot'

interface Props {
  rows: SharedWithMe[]
  onOpen: (b: Bookmark) => void
  onCopy: (b: Bookmark, owner: string) => void
}

/**
 * FR-07 接收方视图：按分享者分组展示只读内容。密码字段服务端已剔除，
 * 这里连掩码都不显示（账号只列用途/用户名）；不可编辑、不可再分享，只能「复制为我的」。
 */
export default function SharedWithMePane({ rows, onOpen, onCopy }: Props) {
  if (rows.length === 0) {
    return (
      <div style={pagePaneScrollStyle}>
        <Empty description="还没有人分享收藏给你" style={{ marginTop: 48 }} />
      </div>
    )
  }
  return (
    <div style={pagePaneScrollStyle}>
      {rows.map((r) => (
        <div key={r.owner} style={{ marginBottom: 20 }}>
          <Typography.Title level={5} style={{ marginTop: 0 }}>
            {r.owner}
            <Typography.Text type="secondary" style={{ fontWeight: 400, marginLeft: 8, fontSize: 12 }}>
              分享了 {r.bookmarks.length} 条收藏
              {r.groups.length > 0 ? ` · 分组：${r.groups.map((g) => g.name).join('、')}` : ''}
            </Typography.Text>
          </Typography.Title>
          <Table<Bookmark>
            rowKey="id"
            pagination={false}
            dataSource={r.bookmarks}
            locale={{ emptyText: '这个分享暂时没有可看的收藏' }}
            columns={[
              {
                title: '',
                width: 28,
                render: (_, b) => <StatusDot bookmark={b} />,
              },
              {
                title: '名称',
                dataIndex: 'title',
                ellipsis: true,
                render: (t: string, b) => (
                  <a onClick={() => onOpen(b)} title={b.url}>
                    {t}
                  </a>
                ),
              },
              { title: '地址', dataIndex: 'url', ellipsis: true },
              {
                title: '标签',
                width: 160,
                render: (_, b) =>
                  b.tags.length === 0 ? '-' : b.tags.map((t) => <Tag key={t.id}>{t.name}</Tag>),
              },
              {
                title: '账号',
                width: 220,
                render: (_, b) =>
                  b.accounts.length === 0
                    ? '-'
                    : b.accounts.map((a) => (
                        <Tag key={a.id} color={a.hasPassword ? 'orange' : undefined}>
                          {a.label}
                          {a.username ? ` · ${a.username}` : ''}
                        </Tag>
                      )),
              },
              {
                title: '分享于',
                dataIndex: 'updatedAt',
                width: 160,
                render: (t: string) => fmtTime(t),
              },
              {
                title: '操作',
                width: 130,
                render: (_, b) => (
                  <Space>
                    <Button onClick={() => onOpen(b)}>打开</Button>
                    <Button onClick={() => onCopy(b, r.owner)}>复制为我的</Button>
                  </Space>
                ),
              },
            ]}
          />
        </div>
      ))}
      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
        <ExportOutlined /> 分享是只读引用：对方修改或撤销后这里立即变化；密码不会随分享带过来。
      </Typography.Paragraph>
    </div>
  )
}
