// 需求详情页「关联记录」Tab：7 类只读记录（文档/会话/构建/测试/部署/发版/产物）合并为一个类型筛选 + 表格。
// 会话行可点击跳 /sessions/:id；其余类型无详情路由，保持只读。
import { useState, type ReactNode } from 'react'
import { Select, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import type { RequirementOverview } from '../types'

type RecordType = 'docs' | 'sessions' | 'builds' | 'tests' | 'deploys' | 'releases' | 'artifacts'

const RECORD_LABEL: Record<RecordType, string> = {
  docs: '文档',
  sessions: '会话',
  builds: '构建',
  tests: '测试',
  deploys: '部署',
  releases: '发版',
  artifacts: '产物',
}

const statusTag = (s: string) => (
  <Tag color={s === 'SUCCESS' || s === 'DONE' ? 'green' : s === 'FAILED' || s.includes('FAIL') || s === 'ROLLED_BACK' ? 'red' : 'blue'}>{s}</Tag>
)
const timeCol = { title: '创建', dataIndex: 'createdAt', width: 150, render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span> } as const

// RecordType → RequirementOverview 字段名（tests/deploys 与字段名不一致，需映射）
const OVERVIEW_KEY = {
  docs: 'docs', sessions: 'sessions', builds: 'builds', tests: 'testRuns',
  deploys: 'deployments', releases: 'releases', artifacts: 'artifacts',
} as const
type Row<T extends RecordType> = RequirementOverview[(typeof OVERVIEW_KEY)[T]][number]

const docColumns: ColumnsType<Row<'docs'>> = [
  { title: 'ID', dataIndex: 'id', width: 60 },
  { title: '类型', dataIndex: 'kind', width: 90, render: (k: string) => <Tag>{k}</Tag> },
  { title: '标题', dataIndex: 'title', ellipsis: true },
  { title: '状态', dataIndex: 'status', width: 90 },
  { title: '版本', dataIndex: 'currentVersion', width: 60, render: (v: number) => `v${v}` },
]
const sessionColumns: ColumnsType<Row<'sessions'>> = [
  { title: 'ID', dataIndex: 'id', width: 100, render: (v: string) => <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> },
  { title: '任务', dataIndex: 'taskSpec', ellipsis: true },
  { title: '状态', dataIndex: 'status', width: 110, render: statusTag },
  timeCol,
]
const buildColumns: ColumnsType<Row<'builds'>> = [
  { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
  { title: '分支', dataIndex: 'branch', width: 140, ellipsis: true, render: (v?: string) => v || '-' },
  { title: 'Commit', dataIndex: 'commit', width: 100, render: (v?: string) => v ? <Typography.Text code style={{ fontSize: 12 }}>{v.slice(0, 8)}</Typography.Text> : '-' },
  { title: '状态', dataIndex: 'status', width: 100, render: statusTag },
  timeCol,
]
const testColumns: ColumnsType<Row<'tests'>> = [
  { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
  { title: '触发', dataIndex: 'triggeredBy', width: 80 },
  { title: '状态', dataIndex: 'status', width: 100, render: statusTag },
  {
    title: '结果', dataIndex: 'summaryJson', width: 160,
    render: (j?: string) => {
      if (!j) return '-'
      try {
        const s = JSON.parse(j)
        return <span style={{ fontSize: 12 }}>共{s.total} 过{s.passed} 败{s.failed}</span>
      } catch { return '-' }
    },
  },
  timeCol,
]
const deployColumns: ColumnsType<Row<'deploys'>> = [
  { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
  { title: '环境', dataIndex: 'env', width: 80, render: (v?: string) => v || '-' },
  { title: '构建', dataIndex: 'buildId', width: 80, render: (v?: number) => v ? `#${v}` : '-' },
  { title: '状态', dataIndex: 'status', width: 110, render: statusTag },
  timeCol,
]
const releaseColumns: ColumnsType<Row<'releases'>> = [
  { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
  { title: '版本', dataIndex: 'version', width: 120, render: (v?: string) => v ? <Typography.Text code style={{ fontSize: 12 }}>{v}</Typography.Text> : '-' },
  { title: '状态', dataIndex: 'status', width: 110, render: statusTag },
  { title: '执行', dataIndex: 'executor', width: 80, render: (v?: string) => v || '-' },
  { title: '回滚', dataIndex: 'rollbackOf', width: 70, render: (v?: number) => v ? `#${v}` : '-' },
  timeCol,
]
const artifactColumns: ColumnsType<Row<'artifacts'>> = [
  { title: 'ID', dataIndex: 'id', width: 60, render: (v: number) => `#${v}` },
  { title: '类型', dataIndex: 'type', width: 110, render: (t: string) => <Tag>{t}</Tag> },
  { title: '名称', dataIndex: 'name', ellipsis: true, render: (v?: string) => v || '-' },
  { title: '来源', dataIndex: 'producerType', width: 100, render: (v?: string) => v || '-' },
  timeCol,
]

const RECORD_COUNT: Record<RecordType, (o: RequirementOverview) => number> = {
  docs: (o) => o.docs.length,
  sessions: (o) => o.sessions.length,
  builds: (o) => o.builds.length,
  tests: (o) => o.testRuns.length,
  deploys: (o) => o.deployments.length,
  releases: (o) => o.releases.length,
  artifacts: (o) => o.artifacts.length,
}

export default function RelatedRecordsTab({ overview }: { overview: RequirementOverview }) {
  const navigate = useNavigate()
  const [type, setType] = useState<RecordType>('docs')

  const renderers: Record<RecordType, (o: RequirementOverview) => ReactNode> = {
    docs: (o) => <Table rowKey="id" size="small" columns={docColumns} dataSource={o.docs} pagination={false} />,
    sessions: (o) => (
      <Table
        rowKey="id"
        size="small"
        columns={sessionColumns}
        dataSource={o.sessions}
        pagination={false}
        onRow={(s) => ({ onClick: () => navigate(`/sessions/${s.id}`), style: { cursor: 'pointer' } })}
      />
    ),
    builds: (o) => <Table rowKey="id" size="small" columns={buildColumns} dataSource={o.builds} pagination={false} />,
    tests: (o) => <Table rowKey="id" size="small" columns={testColumns} dataSource={o.testRuns} pagination={false} />,
    deploys: (o) => <Table rowKey="id" size="small" columns={deployColumns} dataSource={o.deployments} pagination={false} />,
    releases: (o) => <Table rowKey="id" size="small" columns={releaseColumns} dataSource={o.releases} pagination={false} />,
    artifacts: (o) => <Table rowKey="id" size="small" columns={artifactColumns} dataSource={o.artifacts} pagination={false} />,
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Select
        size="small"
        style={{ width: 160 }}
        value={type}
        onChange={setType}
        options={(Object.keys(RECORD_LABEL) as RecordType[]).map((k) => ({
          value: k,
          label: `${RECORD_LABEL[k]}（${RECORD_COUNT[k](overview)}）`,
        }))}
      />
      {renderers[type](overview)}
    </Space>
  )
}
