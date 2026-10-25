// CAP-87 控制台更新日志（/changelog）：只读渲染随安装包分发的 CHANGELOG.md。
// 版本/日期/关键词筛选 + 服务端分页；行内「查看」开右侧 Drawer 用共享 Markdown 渲染全文。
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Divider,
  Drawer,
  Empty,
  Input,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { ColumnsType } from 'antd/es/table'
import type { Dayjs } from 'dayjs'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { getChangelog, listChangelog } from '../api'
import type {
  ChangelogEntryBrief,
  ChangelogEntryView,
  ChangelogQuery,
  ChangelogType,
} from '../types'
import { api } from '../../../shared/api/client'
import Markdown from '../../../shared/components/Markdown'
import FitTable from '../../../shared/components/FitTable'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'
import { showError } from '../../../shared/utils/showError'

const TYPE_META: Record<ChangelogType, { color: string; label: string }> = {
  ADDED: { color: 'green', label: '新增' },
  IMPROVED: { color: 'blue', label: '优化' },
  FIXED: { color: 'orange', label: '修复' },
  BREAKING: { color: 'red', label: '破坏性变更' },
}

const DEFAULT_SIZE = 20

function TypeTags({ types }: { types: ChangelogType[] }) {
  if (!types.length) return <span>-</span>
  return (
    <Space size={4} wrap>
      {types.map((t) => {
        const meta = TYPE_META[t] ?? { color: 'default', label: t }
        return (
          <Tag key={t} color={meta.color}>
            {meta.label}
          </Tag>
        )
      })}
    </Space>
  )
}

/** 关联编号最多平铺 3 个，其余收进 +N（Tooltip 展开全文），避免固定宽列被长列表撑高。 */
function RefTags({ refs }: { refs: string[] }) {
  if (!refs.length) return <span>-</span>
  const head = refs.slice(0, 3)
  return (
    <Space size={4} wrap>
      {head.map((r) => (
        <Tag key={r}>{r}</Tag>
      ))}
      {refs.length > head.length && (
        <Tooltip title={refs.slice(head.length).join('、')}>
          <Tag>+{refs.length - head.length}</Tag>
        </Tooltip>
      )}
    </Space>
  )
}

export default function ChangelogPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [rows, setRows] = useState<ChangelogEntryBrief[]>([])
  const [total, setTotal] = useState(0)
  const [available, setAvailable] = useState(true)
  const [sourcePath, setSourcePath] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(DEFAULT_SIZE)
  const [filters, setFilters] = useState<ChangelogQuery>({})

  // 筛选框草稿值：点「查询」/回车才生效，避免每次输入都打后端
  const [keyword, setKeyword] = useState('')
  const [versionInput, setVersionInput] = useState('')
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null)

  const [currentVersion, setCurrentVersion] = useState<string | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)
  const [detail, setDetail] = useState<ChangelogEntryView | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  const load = (p = page, s = size, f = filters) => {
    setLoading(true)
    listChangelog({ ...f, page: p, size: s })
      .then((r) => {
        setRows(r.items)
        setTotal(r.total)
        setAvailable(r.available)
        setSourcePath(r.sourcePath)
      })
      .catch((e) => {
        setRows([])
        setTotal(0)
        showError(e, '加载更新日志失败')
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size, filters])

  useEffect(() => {
    api
      .get<{ version: string }>('/about')
      .then((a) => setCurrentVersion(a.version))
      .catch(() => {})
  }, [])

  const openDetail = (version: string, missingHint?: string) => {
    setDetailOpen(true)
    setDetail(null)
    setDetailLoading(true)
    getChangelog(version)
      .then(setDetail)
      .catch((e) => {
        setDetailOpen(false)
        if (missingHint) message.warning(missingHint)
        else showError(e, '加载版本详情失败')
      })
      .finally(() => setDetailLoading(false))
  }

  // 「关于」弹框带 ?version=<当前部署版本> 落地：套用版本筛选并尝试直开该版本详情，读后清参数
  useEffect(() => {
    const v = searchParams.get('version')?.trim()
    if (!v) return
    setSearchParams({}, { replace: true })
    setKeyword('')
    setVersionInput(v)
    setRange(null)
    setPage(0)
    setFilters({ version: v })
    openDetail(v, `当前部署版本 v${v.replace(/^v/i, '')} 暂无更新日志条目`)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams])

  const applyFilters = () => {
    setPage(0)
    setFilters({
      keyword: keyword.trim() || undefined,
      version: versionInput.trim() || undefined,
      from: range?.[0].format('YYYY-MM-DD'),
      to: range?.[1].format('YYYY-MM-DD'),
    })
  }

  const resetFilters = () => {
    setKeyword('')
    setVersionInput('')
    setRange(null)
    setPage(0)
    setFilters({})
  }

  const hasFilters = Boolean(filters.keyword || filters.version || filters.from || filters.to)

  const columns: ColumnsType<ChangelogEntryBrief> = [
    {
      title: '版本',
      dataIndex: 'version',
      width: 170,
      render: (v: string) => (
        <Space size={6} wrap>
          <Typography.Text strong>v{v}</Typography.Text>
          {currentVersion && v === currentVersion.replace(/^v/i, '') && <Tag color="gold">当前部署</Tag>}
        </Space>
      ),
    },
    {
      title: '发布日期',
      dataIndex: 'releasedAt',
      width: 120,
      render: (v: string | null) => v || '-',
    },
    {
      title: '变更类型',
      dataIndex: 'types',
      width: 220,
      render: (v: ChangelogType[]) => <TypeTags types={v} />,
    },
    {
      title: '变更摘要',
      dataIndex: 'summary',
      ellipsis: true,
      render: (v: string) => v || '-',
    },
    {
      title: '关联编号',
      dataIndex: 'refs',
      width: 220,
      render: (v: string[]) => <RefTags refs={v} />,
    },
    {
      title: '操作',
      key: 'ops',
      width: 80,
      render: (_: unknown, r) => (
        <Button size="small" onClick={() => openDetail(r.version)}>
          查看
        </Button>
      ),
    },
  ]

  const emptyText = !available ? (
    <Empty
      description={
        <span>
          未找到更新日志文件{sourcePath ? `（${sourcePath}）` : ''}。请确认安装包内存在{' '}
          <Typography.Text code>config/CHANGELOG.md</Typography.Text>，或配置{' '}
          <Typography.Text code>devmind.changelog.path</Typography.Text> 后刷新。
        </span>
      }
    />
  ) : hasFilters ? (
    <Empty description="没有匹配的更新日志，试试放宽筛选条件。">
      <Button onClick={resetFilters}>清空筛选</Button>
    </Empty>
  ) : (
    <Empty description="暂无已发布版本的更新日志。发布前在仓库根 CHANGELOG.md 登记新版本条目即可在此展示。" />
  )

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="更新日志"
      extra={
        <Button icon={<ReloadOutlined />} onClick={() => load()}>
          刷新
        </Button>
      }
    >
      <Typography.Paragraph type="secondary">
        更新日志（CAP-87）：只读展示随安装包分发的 CHANGELOG.md，按版本倒序查看已发布版本的变更类型、说明与关联编号；
        Unreleased（未发布）内容不会出现。
      </Typography.Paragraph>

      {currentVersion && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={
            <Space wrap size={8}>
              <span>
                当前部署版本 <Typography.Text code>v{currentVersion}</Typography.Text>
              </span>
              <Button type="link" size="small" style={{ padding: 0 }} onClick={() => openDetail(currentVersion)}>
                查看该版本变更
              </Button>
            </Space>
          }
        />
      )}

      <Space wrap style={{ marginBottom: 12 }}>
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="关键词：版本 / 说明 / 关联编号"
          style={{ width: 260 }}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onPressEnter={applyFilters}
        />
        <Input
          allowClear
          placeholder="版本号（包含匹配）"
          style={{ width: 180 }}
          value={versionInput}
          onChange={(e) => setVersionInput(e.target.value)}
          onPressEnter={applyFilters}
        />
        <DatePicker.RangePicker
          value={range}
          onChange={(r) => setRange(r && r[0] && r[1] ? [r[0], r[1]] : null)}
        />
        <Button type="primary" onClick={applyFilters}>
          查询
        </Button>
        <Button onClick={resetFilters} disabled={!hasFilters}>
          重置
        </Button>
      </Space>

      <FitTable<ChangelogEntryBrief>
        rowKey="version"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(s !== size ? 0 : p - 1)
            setSize(s)
          },
        }}
        locale={{ emptyText }}
        scroll={{ x: 960 }}
      />

      <Drawer
        title={
          detail ? (
            <Space wrap size={8}>
              <span>v{detail.version}</span>
              {detail.releasedAt && <Typography.Text type="secondary">{detail.releasedAt}</Typography.Text>}
            </Space>
          ) : (
            '版本详情'
          )
        }
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        width={DRAWER_SCREEN}
        destroyOnHidden
      >
        <Spin spinning={detailLoading}>
          {detail ? (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Space wrap size={8}>
                <TypeTags types={detail.types} />
                <RefTags refs={detail.refs} />
              </Space>
              <Divider style={{ margin: 0 }} />
              <Markdown content={detail.contentMd} />
            </Space>
          ) : (
            !detailLoading && <Empty description="未加载到版本详情" />
          )}
        </Spin>
      </Drawer>
    </Card>
  )
}
