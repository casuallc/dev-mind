// Worktree Tab：项目活跃 worktree 只读列表。
import { Button, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ReloadOutlined } from '@ant-design/icons'
import type { WorktreeInfo } from '../../types'

export default function WorktreesTab({ worktrees, onRefresh }: {
  worktrees: WorktreeInfo[]
  onRefresh: () => void
}) {
  const columns: ColumnsType<WorktreeInfo> = [
    { title: 'Session', dataIndex: 'sessionId', width: 120, render: (s: string) => <Typography.Text code>{s}</Typography.Text> },
    { title: '分支', dataIndex: 'branch', width: 160, render: (b: string) => <Tag>{b}</Tag> },
    { title: '路径', dataIndex: 'path', render: (p: string) => <span style={{ fontSize: 12 }}>{p}</span> },
  ]
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space>
        <Button size="small" icon={<ReloadOutlined />} onClick={onRefresh}>刷新</Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          该项目的活跃 worktree（CAP-05 会话隔离工作区）。
        </Typography.Text>
      </Space>
      <Table rowKey="path" size="small" columns={columns} dataSource={worktrees} pagination={false}
        locale={{ emptyText: '暂无活跃 worktree' }} />
    </Space>
  )
}
