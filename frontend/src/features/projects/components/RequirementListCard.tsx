// 需求列表卡：服务端分页表格 + 状态/类型筛选，点行进入需求详情页。
// CAP-19：Jira 同步导入的需求带来源徽标（external_links 批量反查，点击跳 Jira）。
import { useCallback, useEffect, useState } from 'react'
import { Button, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { listRequirements } from '../api'
import { listExternalLinksByType } from '../../integrations/api'
import type { ExternalLink } from '../../integrations/types'
import type { Requirement, RequirementType } from '../types'
import RequirementFormModal from './RequirementFormModal'
import {
  ALL_STATUSES,
  ALL_TYPES,
  TYPE_LABEL,
  requirementStatusColor,
  requirementTypeColor,
} from './requirementMeta'

export default function RequirementListCard({ projectId }: { projectId: string }) {
  const navigate = useNavigate()
  const [items, setItems] = useState<Requirement[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [jiraLinks, setJiraLinks] = useState<Map<string, ExternalLink>>(new Map())
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [typeFilter, setTypeFilter] = useState<string>('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [createOpen, setCreateOpen] = useState(false)

  const load = useCallback(async (p: number, s: number, status: string, type: string) => {
    setLoading(true)
    try {
      const data = await listRequirements(projectId, { status, type, page: p, size: s })
      setItems(data.items)
      setTotal(data.total)
    } catch (e) {
      message.error(`加载需求失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
    // Jira 来源徽标：旁路加载，失败不影响主列表
    listExternalLinksByType(projectId, 'REQUIREMENT')
      .then((links) => {
        const m = new Map<string, ExternalLink>()
        links
          .filter((l) => l.externalType === 'ISSUE')
          .forEach((l) => m.set(l.internalId, l))
        setJiraLinks(m)
      })
      .catch(() => setJiraLinks(new Map()))
  }, [projectId])

  useEffect(() => {
    load(page, size, statusFilter, typeFilter)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load, page, size, statusFilter, typeFilter])

  const reload = () => load(page, size, statusFilter, typeFilter)

  const columns: ColumnsType<Requirement> = [
    {
      title: '编号',
      dataIndex: 'code',
      width: 100,
      render: (c: string) => <Typography.Text code style={{ fontSize: 12 }}>{c}</Typography.Text>,
    },
    {
      title: '标题',
      dataIndex: 'title',
      ellipsis: true,
      render: (t: string, r) => (
        <Space size={6}>
          <Typography.Text style={{ fontSize: 13 }} ellipsis={{ tooltip: t }}>{t}</Typography.Text>
          {jiraLinks.has(r.id) && (
            <Tooltip title={`来自 Jira，状态 ${jiraLinks.get(r.id)!.status ?? '-'}`}>
              <Tag
                color="blue"
                style={{ fontSize: 11, lineHeight: '16px', marginInlineEnd: 0 }}
                onClick={(e) => {
                  e.stopPropagation()
                  const url = jiraLinks.get(r.id)!.externalUrl
                  if (url) window.open(url, '_blank')
                }}
              >
                {jiraLinks.get(r.id)!.externalKey}
              </Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 90,
      render: (t: RequirementType | undefined) => (
        <Tag color={requirementTypeColor(t ?? 'FEATURE')}>{TYPE_LABEL[t ?? 'FEATURE']}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: Requirement['status']) => <Tag color={requirementStatusColor(s)}>{s}</Tag>,
    },
    {
      title: '更新',
      dataIndex: 'updatedAt',
      width: 150,
      render: (t: string) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {new Date(t).toLocaleString()}
        </Typography.Text>
      ),
    },
  ]

  return (
    <>
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Space wrap>
          <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建需求
          </Button>
          <Select
            allowClear
            size="small"
            placeholder="状态（默认全部）"
            style={{ minWidth: 150 }}
            value={statusFilter || undefined}
            onChange={(v) => { setStatusFilter(v ?? ''); setPage(0) }}
            options={ALL_STATUSES.map((s) => ({ value: s, label: s }))}
          />
          <Select
            allowClear
            size="small"
            placeholder="类型（默认全部）"
            style={{ minWidth: 130 }}
            value={typeFilter || undefined}
            onChange={(v) => { setTypeFilter(v ?? ''); setPage(0) }}
            options={ALL_TYPES.map((t) => ({ value: t, label: TYPE_LABEL[t] }))}
          />
          <Button size="small" icon={<ReloadOutlined />} onClick={reload} />
        </Space>
        <Table
          rowKey="id"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={items}
          onRow={(r) => ({
            onClick: () => navigate(`/projects/${projectId}/requirements/${r.id}`),
            style: { cursor: 'pointer' },
          })}
          pagination={{
            current: page + 1,
            pageSize: size,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => {
              setPage(s !== size ? 0 : p - 1)
              setSize(s)
            },
          }}
          locale={{ emptyText: '暂无需求，点击「新建需求」开始一条研发主线' }}
        />
      </Space>

      <RequirementFormModal
        projectId={projectId}
        editing={null}
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onSaved={() => reload()}
      />
    </>
  )
}
