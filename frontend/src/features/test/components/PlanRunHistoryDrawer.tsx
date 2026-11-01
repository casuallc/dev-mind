// CAP-104 M2 方案运行历史：按方案过滤的父运行列表，点详情下钻子运行与用例结果。
import { Button, Drawer, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import { listPlanRuns } from '../api'
import type { TestPlan, TestPlanRun } from '../types'
import { CONCLUSION_COLOR, STATUS_COLOR } from '../constants'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

export default function PlanRunHistoryDrawer({ open, plan, onClose, onOpenRun }: {
  open: boolean
  plan: TestPlan | null
  onClose: () => void
  onOpenRun: (id: number) => void
}) {
  const [runs, setRuns] = useState<TestPlanRun[]>([])
  const [loading, setLoading] = useState(false)

  const load = () => {
    if (!plan) return
    setLoading(true)
    listPlanRuns(plan.projectId, { planId: plan.id })
      .then(setRuns)
      .catch((e) => showError(e, '加载方案运行历史失败'))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    if (open && plan) load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, plan?.id])

  const passRate = (r: TestPlanRun) => {
    const v = r.summary?.passRate
    return v == null ? '-' : `${v}%`
  }

  const columns: ColumnsType<TestPlanRun> = [
    { title: 'ID', dataIndex: 'id', width: 70, render: (v: number) => `#${v}` },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: keyof typeof STATUS_COLOR) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
    },
    {
      title: '结论', dataIndex: 'conclusion', width: 90,
      render: (v: keyof typeof CONCLUSION_COLOR | null) => (v ? <Tag color={CONCLUSION_COLOR[v]}>{v}</Tag> : '-'),
    },
    { title: '模式', dataIndex: 'mode', width: 100, render: (v: string) => <Tag>{v}</Tag> },
    {
      title: '用例', key: 'cases', width: 180,
      render: (_, r) => {
        const s = r.summary ?? {}
        return (
          <span style={{ fontSize: 12 }}>
            {String(s.total ?? 0)} 项 · <span style={{ color: '#52c41a' }}>{String(s.casePassed ?? 0)} 过</span>{' '}
            <span style={{ color: Number(s.caseFailed ?? 0) ? '#ff4d4f' : undefined }}>
              {String(s.caseFailed ?? 0)} 败
            </span>{' '}
            <span style={{ color: '#d48806' }}>{String(s.caseBlocked ?? 0)} 阻</span>
          </span>
        )
      },
    },
    { title: '通过率', key: 'rate', width: 90, render: (_, r) => passRate(r) },
    { title: '触发', dataIndex: 'triggeredBy', width: 80, render: (v: string | null) => v || '-' },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: (v: string) => fmtTime(v) },
    {
      title: '操作', key: 'act', width: 80,
      render: (_, r) => <Button size="small" onClick={() => onOpenRun(r.id)}>详情</Button>,
    },
  ]

  return (
    <Drawer
      title={plan ? `方案「${plan.name}」运行历史` : '方案运行历史'}
      open={open}
      width={DRAWER_DEFAULT}
      onClose={onClose}
      extra={<Button onClick={load} loading={loading}>刷新</Button>}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          每次运行固化方案快照；后续编辑方案不影响历史。点击详情可下钻方案项、子运行与用例结果。
        </Typography.Text>
        <Table<TestPlanRun>
          rowKey="id"
          size="small"
          loading={loading}
          dataSource={runs}
          columns={columns}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: '暂无运行记录' }}
        />
      </Space>
    </Drawer>
  )
}
