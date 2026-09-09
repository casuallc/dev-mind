import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Table, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { listNodeActiveSessions } from '../api'
import type { NodeActiveSession } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

/** 活跃会话表列：节点抽屉清单与强制升级确认弹窗共用。 */
export const activeSessionColumns: ColumnsType<NodeActiveSession> = [
  {
    title: '类型',
    dataIndex: 'kind',
    width: 70,
    render: (k: string) =>
      k === 'CHAT' ? <Tag color="purple">问答</Tag> : <Tag color="blue">会话</Tag>,
  },
  {
    title: '标题',
    dataIndex: 'title',
    ellipsis: true,
    render: (t?: string) => t || '-',
  },
  { title: '状态', dataIndex: 'status', width: 130 },
  { title: '创建人', dataIndex: 'createdBy', width: 100, render: (s?: string) => s || '-' },
  { title: '创建时间', dataIndex: 'createdAt', width: 150, render: (t?: string) => fmtTime(t) },
]

/** 节点抽屉「活跃会话」卡片：打开即加载，手动刷新；与强制升级弹窗共用列定义。 */
export default function ActiveSessionsCard({ nodeId }: { nodeId: number }) {
  // null = 加载中
  const [sessions, setSessions] = useState<NodeActiveSession[] | null>(null)

  const load = useCallback(() => {
    setSessions(null)
    listNodeActiveSessions(nodeId)
      .then(setSessions)
      .catch((e) => {
        showError(e, '加载会话清单失败')
        setSessions([])
      })
  }, [nodeId])

  useEffect(load, [load])

  return (
    <Card
      size="small"
      title={`活跃会话（${sessions?.length ?? '-'}）`}
      extra={
        <Button size="small" icon={<ReloadOutlined />} onClick={load}>
          刷新
        </Button>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
        活动三态 RUNNING / WAITING_INPUT / WAITING_AUTH 的开发会话与通用问答；已结束的不在此列。
      </Typography.Paragraph>
      <Table<NodeActiveSession>
        rowKey="sessionId"
        size="small"
        loading={sessions === null}
        pagination={false}
        dataSource={sessions ?? []}
        columns={activeSessionColumns}
      />
    </Card>
  )
}
