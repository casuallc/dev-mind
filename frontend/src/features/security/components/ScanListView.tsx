// 扫描任务列表（FR-01/FR-02）：服务端分页 + 行内详情/发现/取消/导出。
import { Button, Dropdown, Popconfirm, Space, Tag, Typography, message } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { cancelScan, exportScan, listScans } from '../api'
import type { SecurityScan } from '../types'
import FitTable, { noWrapColumns } from '../../../shared/components/FitTable'
import { durationMs, fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'
import { attachmentRawUrl } from '../../../shared/attachments/url'
import { usePermissions } from '../../auth/usePermissions'
import { ENGINE_LABEL, SCAN_STATUS_COLOR } from './common'

interface Props {
  projectId: string
  refreshTick: number
  onOpenDetail: (scanId: number) => void
  onOpenFindings: (scan: SecurityScan) => void
}

export default function ScanListView({ projectId, refreshTick, onOpenDetail, onOpenFindings }: Props) {
  const { canAction } = usePermissions()
  const canScan = canAction('project.security.action.scan')
  const canExport = canAction('project.security.action.export')
  const [rows, setRows] = useState<SecurityScan[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const [loading, setLoading] = useState(false)

  const refresh = useCallback(() => {
    setLoading(true)
    listScans(projectId, page, size)
      .then((p) => {
        setRows(p.content)
        setTotal(p.totalElements)
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [projectId, page, size])

  useEffect(refresh, [refresh, refreshTick])

  // 有在途任务时轮询推进状态
  useEffect(() => {
    if (!rows.some((r) => r.status === 'QUEUED' || r.status === 'RUNNING')) return
    const timer = setInterval(refresh, 5000)
    return () => clearInterval(timer)
  }, [rows, refresh])

  const onCancel = async (id: number) => {
    try {
      await cancelScan(id)
      message.success(`扫描 #${id} 已取消`)
      refresh()
    } catch (e) {
      showError(e)
    }
  }

  const onExport = async (id: number, format: 'csv' | 'sarif') => {
    try {
      const r = await exportScan(id, format)
      message.success(`已导出 ${r.count} 条（${r.fileName}）`)
      window.open(attachmentRawUrl(r.attachmentId), '_blank')
    } catch (e) {
      showError(e)
    }
  }

  const columns: ColumnsType<SecurityScan> = noWrapColumns<SecurityScan>([
    { title: 'ID', dataIndex: 'id', width: 72 },
    { title: '分支', dataIndex: 'branch', width: 140 },
    {
      title: '引擎',
      dataIndex: 'engines',
      width: 160,
      render: (v: string) => (
        <Space size={4} wrap>
          {v.split(',').map((e) => (
            <Tag key={e}>{ENGINE_LABEL[e.trim()] ?? e.trim()}</Tag>
          ))}
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v: SecurityScan['status']) => <Tag color={SCAN_STATUS_COLOR[v]}>{v}</Tag>,
    },
    {
      title: '新增/存量/修复',
      width: 130,
      render: (_, r) =>
        r.status === 'SUCCESS' || r.newCount != null ? (
          <Typography.Text>
            {r.newCount ?? 0} / {r.existingCount ?? 0} / {r.fixedCount ?? 0}
          </Typography.Text>
        ) : (
          '-'
        ),
    },
    { title: '发起人', dataIndex: 'createdBy', width: 110 },
    {
      title: '发起时间',
      dataIndex: 'createdAt',
      width: 160,
      render: (v: string) => fmtTime(v),
    },
    {
      title: '耗时',
      width: 90,
      render: (_, r) => durationMs(r.startedAt, r.finishedAt),
    },
    {
      title: '操作',
      key: 'op',
      width: 220,
      render: (_, r) => (
        <Space size={4} wrap>
          <Button type="link" size="small" onClick={() => onOpenDetail(r.id)}>
            详情
          </Button>
          {r.status === 'SUCCESS' && (
            <Button type="link" size="small" onClick={() => onOpenFindings(r)}>
              发现
            </Button>
          )}
          {(r.status === 'QUEUED' || r.status === 'RUNNING') && canScan && (
            <Popconfirm title={`确认取消扫描 #${r.id}？`} onConfirm={() => onCancel(r.id)}>
              <Button type="link" size="small" danger>
                取消
              </Button>
            </Popconfirm>
          )}
          {r.status === 'SUCCESS' && canExport && (
            <Dropdown
              menu={{
                items: [
                  { key: 'csv', label: '导出 CSV' },
                  { key: 'sarif', label: '导出 SARIF' },
                ],
                onClick: ({ key }) => onExport(r.id, key as 'csv' | 'sarif'),
              }}
            >
              <Button type="link" size="small">
                导出
              </Button>
            </Dropdown>
          )}
        </Space>
      ),
    },
  ]) as ColumnsType<SecurityScan>

  return (
    <FitTable<SecurityScan>
      rowKey="id"
      columns={columns}
      dataSource={rows}
      loading={loading}
      pagination={{
        current: page + 1,
        pageSize: size,
        total,
        showSizeChanger: true,
        pageSizeOptions: [10, 20, 50],
        showTotal: (t) => `共 ${t} 条`,
        onChange: (p, s) => {
          setPage(p - 1)
          setSize(s)
        },
      }}
    />
  )
}
