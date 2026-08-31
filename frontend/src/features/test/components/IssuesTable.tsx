// 缺陷线索表格：失败用例汇总为缺陷单标题与复现信息（FR-06）。
import { Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { IssueDraft } from '../types'

export default function IssuesTable({ issues }: { issues: IssueDraft[] }) {
  const columns: ColumnsType<IssueDraft> = [
    { title: '缺陷标题', dataIndex: 'title', ellipsis: true },
    { title: '期望', dataIndex: 'expected', width: 240, ellipsis: true, render: (v: string) => <span style={{ fontSize: 12 }}>{v || '-'}</span> },
    { title: '实际', dataIndex: 'actual', width: 240, ellipsis: true, render: (v: string) => <span style={{ fontSize: 12, color: '#ff4d4f' }}>{v || '-'}</span> },
  ]
  if (!issues.length) {
    return <Typography.Text type="secondary">无失败用例可转。</Typography.Text>
  }
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        失败用例已汇总为缺陷线索，可直接作为缺陷单标题与复现信息派发修复 Agent。
      </Typography.Text>
      <Table<IssueDraft> rowKey="caseId" size="small" columns={columns} dataSource={issues} pagination={false} />
    </Space>
  )
}
