// 需求列表卡：来源 Segmented（全部/Jira/自建）+ 文本搜索 + 服务端分页表格，状态/类型筛选，点行进详情。
// 来源字段（externalKey/externalUrl/remoteStatus）由列表接口直接带出，不再旁路反查 external_links。
// 布局遵循 docs/core/前端内容区布局约定.md：Card 默认尺寸、title 内 Segmented、操作收 extra、表格默认密度。
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Input, Segmented, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { listRequirements } from '../api'
import type { Requirement, RequirementSource, RequirementType } from '../types'
import RequirementFormDrawer from './RequirementFormDrawer'
import { fmtDuration, fmtTime } from '../../../shared/utils/format'
import {
  ALL_STATUSES,
  ALL_TYPES,
  STATUS_LABEL,
  TYPE_LABEL,
  priorityColor,
  requirementStatusColor,
  requirementTypeColor,
} from './requirementMeta'

type SourceView = 'ALL' | RequirementSource

export default function RequirementListCard({ projectId }: { projectId: string }) {
  const navigate = useNavigate()
  const [items, setItems] = useState<Requirement[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [sourceView, setSourceView] = useState<SourceView>('ALL')
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
    load(page, size, sourceView, keyword, statusFilter, typeFilter)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [load, page, size, sourceView, keyword, statusFilter, typeFilter])

  const reload = () => load(page, size, sourceView, keyword, statusFilter, typeFilter)

  // 来源相关列仅「全部」「Jira」视图显示（自建没有这些字段）
  const jiraColumns: ColumnsType<Requirement> = sourceView === 'LOCAL' ? [] : [
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
    {
      title: 'Jira 工时',
      dataIndex: 'spentSeconds',
      width: 110,
      render: (_: number | undefined, r) => (
        <Tooltip title="已用 / 预估（Jira time tracking，随同步刷新）">
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {fmtDuration(r.spentSeconds)} / {fmtDuration(r.estimatedSeconds)}
          </Typography.Text>
        </Tooltip>
      ),
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
      title: 'AI 耗时',
      dataIndex: 'agentSeconds',
      width: 90,
      render: (s: number | undefined) => (
        <Tooltip title="需求下所有 agent 会话时长汇总（活跃会话算到当前）">
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {fmtDuration(s)}
          </Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: Requirement['status']) => <Tag color={requirementStatusColor(s)}>{STATUS_LABEL[s]}</Tag>,
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
      <Card
        title={
          <Space size={12}>
            <span>需求</span>
            <Segmented<SourceView>
              value={sourceView}
              onChange={(v) => { setSourceView(v); setPage(0) }}
              options={[
                { value: 'ALL', label: '全部' },
                { value: 'JIRA', label: 'Jira 同步' },
                { value: 'LOCAL', label: '自建' },
              ]}
            />
          </Space>
        }
        extra={
          <Space wrap>
            <Input.Search
              allowClear
              placeholder="搜索标题 / Jira Key"
              style={{ width: 200 }}
              onSearch={(v) => { setKeyword(v.trim()); setPage(0) }}
            />
            <Select
              allowClear
              placeholder="状态（默认全部）"
              style={{ minWidth: 150 }}
              value={statusFilter || undefined}
              onChange={(v) => { setStatusFilter(v ?? ''); setPage(0) }}
              options={ALL_STATUSES.map((s) => ({ value: s, label: STATUS_LABEL[s] }))}
            />
            <Select
              allowClear
              placeholder="类型（默认全部）"
              style={{ minWidth: 130 }}
              value={typeFilter || undefined}
              onChange={(v) => { setTypeFilter(v ?? ''); setPage(0) }}
              options={ALL_TYPES.map((t) => ({ value: t, label: TYPE_LABEL[t] }))}
            />
            <Button icon={<ReloadOutlined />} onClick={reload}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              新建需求
            </Button>
          </Space>
        }
      >
        <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
          需求 = 研发主线的顶层条目：可来自 Jira 同步或本地自建，点行进详情管理设计与工作单元。
        </Typography.Paragraph>
        <Table
          rowKey="id"
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
      </Card>

      <RequirementFormDrawer
        projectId={projectId}
        editing={null}
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onSaved={() => reload()}
      />
    </>
  )
}
