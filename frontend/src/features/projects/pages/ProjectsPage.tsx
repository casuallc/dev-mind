// CAP-02 项目列表（业务视图，全角色只读）：表格 + 状态筛选 + 「进入」= 切换为当前项目并回概览。
// 入口已移出侧边栏，仅从项目切换器底部「查看全部项目」进入；增删改在后台 /admin/projects。
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Select, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { listProjects } from '../api'
import { setCurrentProject } from '../../../app/currentProjectStore'
import type { Project } from '../types'
import { fmtTime } from '../../../shared/utils/format'

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: 'ACTIVE', color: 'green' },
  { value: 'ARCHIVED', label: 'ARCHIVED', color: 'default' },
]

export default function ProjectsPage() {
  const navigate = useNavigate()
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState('ALL')

  const load = useCallback(async (st?: string) => {
    setLoading(true)
    try {
      setProjects(await listProjects(st ?? status))
    } catch (e) {
      message.error(`加载项目失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [status])

  useEffect(() => {
    load()
  }, [load])

  const enter = (id: string) => {
    setCurrentProject(id)
    navigate('/overview')
  }

  const columns: ColumnsType<Project> = [
    {
      title: '名称',
      dataIndex: 'name',
      width: 180,
      render: (n: string, r) => (
        <Button type="link" style={{ padding: 0 }} onClick={() => enter(r.id)}>
          {n}
        </Button>
      ),
    },
    { title: 'ID', dataIndex: 'id', width: 110, render: (id: string) => <Typography.Text code>{id}</Typography.Text> },
    {
      title: '仓库路径',
      dataIndex: 'path',
      ellipsis: true,
      render: (p: string) => <Typography.Text style={{ fontSize: 12 }}>{p}</Typography.Text>,
    },
    { title: '分支', dataIndex: 'defaultBranch', width: 110, render: (b?: string) => b || '-' },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 220,
      render: (tags: string[]) =>
        tags?.length ? tags.map((t) => <Tag key={t} style={{ marginBottom: 2 }}>{t}</Tag>) : '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: string) => {
        const o = STATUS_OPTIONS.find((x) => x.value === s)
        return <Tag color={o?.color ?? 'default'}>{s}</Tag>
      },
    },
    {
      title: '摘要',
      dataIndex: 'summaryGeneratedAt',
      width: 140,
      render: (t?: string) => fmtTime(t),
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_, r) => (
        <Button size="small" onClick={() => enter(r.id)}>
          进入
        </Button>
      ),
    },
  ]

  return (
    <Card
      title="项目"
      extra={
        <Space>
          <Select
            value={status}
            onChange={(v) => {
              setStatus(v)
              load(v)
            }}
            options={[{ value: 'ALL', label: '全部' }, ...STATUS_OPTIONS.map((o) => ({ value: o.value, label: o.label }))]}
            style={{ width: 130 }}
          />
          <Button icon={<ReloadOutlined />} onClick={() => load()}>
            刷新
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        全部已注册项目的只读列表，点「进入」切换为当前项目并回到概览；新建/编辑/删除在后台「项目管理」进行。
      </Typography.Paragraph>
      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={projects}
        pagination={false}
        locale={{ emptyText: '暂无项目，请联系管理员在后台注册。' }}
      />
    </Card>
  )
}
