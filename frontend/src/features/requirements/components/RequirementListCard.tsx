// 需求列表卡：来源 Tab（全部/Jira/自建）+ 文本搜索 + 服务端分页表格，状态/类型筛选，点行进详情。
// 来源字段（externalKey/externalUrl/remoteStatus）由列表接口直接带出，不再旁路反查 external_links。
import { useCallback, useEffect, useState } from 'react'
import { Button, Input, Select, Space, Table, Tabs, Tag, Tooltip, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { listRequirements } from '../api'
import type { Requirement, RequirementSource, RequirementType } from '../types'
import RequirementFormModal from './RequirementFormModal'
import { fmtTime } from '../../../shared/utils/format'
import {
  ALL_STATUSES,
  ALL_TYPES,
  TYPE_LABEL,
  priorityColor,
  requirementStatusColor,
  requirementTypeColor,
} from './requirementMeta'

type SourceTab = '' | RequirementSource

export default function RequirementListCard({ projectId }: { projectId: string }) {
  const navigate = useNavigate()
  const [items, setItems] = useState<Requirement[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [sourceTab, setSourceTab] = useState<SourceTab>('')
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [typeFilter, setTypeFilter] = useState<string>('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [createOpen, setCreateOpen] = useState(false)

  const load = useCallback(async (
    p: number, s: number, source: string, kw: string, status: string, type: string,
  ) => {
    setLoading(true)
    try {
      const data = await listRequirements(projectId, { status, type, source, keyword: kw, page: p, size: s })
      setItems(data.items)
      setTotal(data.total)
    } catch (e) {
      message.error(`加载需求失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [projectId])

  useEffect(() => {
    load(page, size, sourceTab, keyword, statusFilter, typeFilter)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load, page, size, sourceTab, keyword, statusFilter, typeFilter])

  const reload = () => load(page, size, sourceTab, keyword, statusFilter, typeFilter)

  // 来源相关列仅「全部」「Jira」Tab 显示（自建没有这些字段）
  const jiraColumns: ColumnsType<Requirement> = sourceTab === 'LOCAL' ? [] : [
    {
      title: 'Jira Key',
      dataIndex: 'externalKey',
      width: 110,
      render: (k: string | undefined, r) => k ? (
        <Tag
          color="blue"
          style={{ fontSize: 11, lineHeight: '16px', marginInlineEnd: 0, cursor: r.externalUrl ? 'pointer' : 'default' }}
          onClick={(e) => {
            e.stopPropagation()
            if (r.externalUrl) window.open(r.externalUrl, '_blank')
          }}
        >
          {k}
        </Tag>
      ) : '-',
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      width: 90,
      render: (p: string | undefined) => p ? <Tag color={priorityColor(p)}>{p}</Tag> : '-',
    },
    {
      title: '经办人',
      dataIndex: 'assignee',
      width: 100,
      ellipsis: true,
      render: (a: string | undefined) => a ?? '-',
    },
    {
      title: 'Jira 状态',
      dataIndex: 'remoteStatus',
      width: 110,
      render: (s: string | undefined) => s ? (
        <Tooltip title="Jira 远端状态，随同步刷新">
          <Tag style={{ fontSize: 11, lineHeight: '16px', marginInlineEnd: 0 }}>{s}</Tag>
        </Tooltip>
      ) : '-',
    },
  ]

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
      render: (t: string) => (
        <Typography.Text style={{ fontSize: 13 }} ellipsis={{ tooltip: t }}>{t}</Typography.Text>
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
    ...jiraColumns,
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
          {fmtTime(t)}
        </Typography.Text>
      ),
    },
  ]

  return (
    <>
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Tabs
          size="small"
          activeKey={sourceTab}
          onChange={(k) => { setSourceTab(k as SourceTab); setPage(0) }}
          items={[
            { key: '', label: '全部' },
            { key: 'JIRA', label: 'Jira 同步' },
            { key: 'LOCAL', label: '自建' },
          ]}
          style={{ marginBottom: -8 }}
        />
        <Space wrap>
          <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建需求
          </Button>
          <Input.Search
            allowClear
            size="small"
            placeholder="搜索标题 / Jira Key"
            style={{ width: 200 }}
            onSearch={(v) => { setKeyword(v.trim()); setPage(0) }}
          />
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
