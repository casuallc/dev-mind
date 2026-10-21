// CAP-76 FR-30 工具目录：注册表全量导出（名称/风险/角色/归属模块/参数 schema）。
// 工具数量小（几十），过滤在前端做；点行开抽屉看参数契约。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Descriptions, Drawer, Input, Select, Space, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { listCopilotTools } from '../api'
import type { CopilotTool } from '../types'
import { RISK_OPTIONS, RiskTag } from './tags'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { LIST_PAGINATION } from '../../../shared/utils/table'

export default function ToolsPanel({ refreshTick = 0 }: { refreshTick?: number }) {
  const [rows, setRows] = useState<CopilotTool[]>([])
  const [loading, setLoading] = useState(false)
  const [risk, setRisk] = useState<string>()
  const [keyword, setKeyword] = useState('')
  const [detail, setDetail] = useState<CopilotTool | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await listCopilotTools())
    } catch (e) {
      showError(e, '加载工具目录失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load, refreshTick])

  const data = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    return rows.filter((t) => {
      if (risk && t.risk !== risk) return false
      if (!kw) return true
      return [t.name, t.title, t.description, t.ownerModule]
        .some((v) => (v ?? '').toLowerCase().includes(kw))
    })
  }, [rows, risk, keyword])

  const columns: ColumnsType<CopilotTool> = [
    {
      title: '工具名',
      dataIndex: 'name',
      width: 200,
      render: (n: string) => <Typography.Text code>{n}</Typography.Text>,
    },
    { title: '标题', dataIndex: 'title', width: 160 },
    { title: '风险', dataIndex: 'risk', width: 80, render: (r: string) => <RiskTag risk={r} /> },
    {
      title: '最低角色',
      dataIndex: 'minRole',
      width: 100,
      render: (r: string) => <Tag>{r}</Tag>,
    },
    { title: '归属模块', dataIndex: 'ownerModule', width: 150 },
    {
      title: '需项目',
      dataIndex: 'requiresProject',
      width: 80,
      render: (v: boolean) => (v ? '是' : '-'),
    },
    { title: '说明', dataIndex: 'description', ellipsis: true },
  ]

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear placeholder="风险" style={{ width: 110 }}
          value={risk} onChange={setRisk}
          options={RISK_OPTIONS}
        />
        <Input.Search
          allowClear placeholder="按名称/标题/说明/模块过滤" style={{ width: 280 }}
          onChange={(e) => setKeyword(e.target.value)}
        />
      </Space>
      <FitTable<CopilotTool>
        rowKey="name"
        loading={loading}
        columns={columns}
        dataSource={data}
        pagination={LIST_PAGINATION}
        onRow={(r) => ({ onClick: () => setDetail(r), style: { cursor: 'pointer' } })}
        locale={{ emptyText: '暂无已注册工具。能力模块提供 PlatformTool Bean 后会自动出现在这里。' }}
      />

      <Drawer title="工具契约" open={!!detail} onClose={() => setDetail(null)} width={DRAWER_DEFAULT}>
        {detail && (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="工具名" span={2}>
                <Typography.Text code>{detail.name}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="标题">{detail.title}</Descriptions.Item>
              <Descriptions.Item label="风险"><RiskTag risk={detail.risk} /></Descriptions.Item>
              <Descriptions.Item label="最低角色">{detail.minRole}</Descriptions.Item>
              <Descriptions.Item label="归属模块">{detail.ownerModule}</Descriptions.Item>
              <Descriptions.Item label="需项目">{detail.requiresProject ? '是' : '否'}</Descriptions.Item>
              <Descriptions.Item label="超时">{detail.timeoutSeconds}s</Descriptions.Item>
              <Descriptions.Item label="引入版本" span={2}>{detail.since}</Descriptions.Item>
            </Descriptions>
            <Typography.Text strong>说明</Typography.Text>
            <Typography.Paragraph style={{ marginBottom: 0 }}>{detail.description}</Typography.Paragraph>
            <Typography.Text strong>参数 schema</Typography.Text>
            <pre style={{ background: '#f6f6f6', padding: 12, borderRadius: 4, fontSize: 12, whiteSpace: 'pre-wrap' }}>
              {prettyJson(detail.parametersSchema)}
            </pre>
          </Space>
        )}
      </Drawer>
    </div>
  )
}

function prettyJson(raw?: string | null): string {
  if (!raw) return '(无)'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}
