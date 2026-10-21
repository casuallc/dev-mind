// CAP-71 节点审计日志面板：全量交互留痕（连接/隧道/指令/代理/上下文/文件中转/规则/包/节点运维）。
// 服务端真分页 + 分类/节点/结果/关键词过滤；分页语义下不做轮询（会跳页），刷新靠页面 extra 按钮。
import { useCallback, useEffect, useState } from 'react'
import { Badge, Descriptions, Drawer, Input, Select, Space, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { listAgentNodes, listAuditLogs } from '../api'
import type { AgentAuditLog, AgentNode, AuditCategory, AuditResult } from '../types'
import { fmtBytes, fmtTime } from '../../../shared/utils/format'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'

const CATEGORY_LABEL: Record<AuditCategory, string> = {
  CONNECTION: '连接',
  TUNNEL: '隧道',
  COMMAND: '指令',
  PROXY: '代理',
  CONTEXT: '上下文',
  FILE_TRANSFER: '文件中转',
  RULE: '规则',
  PACKAGE: '包',
  NODE: '节点',
}

const CATEGORY_COLOR: Record<AuditCategory, string> = {
  CONNECTION: 'green',
  TUNNEL: 'cyan',
  COMMAND: 'blue',
  PROXY: 'purple',
  CONTEXT: 'geekblue',
  FILE_TRANSFER: 'orange',
  RULE: 'gold',
  PACKAGE: 'magenta',
  NODE: 'default',
}

const RESULT_LABEL: Record<AuditResult, string> = { OK: '成功', FAIL: '失败', REJECTED: '拒绝' }

const CATEGORIES = Object.keys(CATEGORY_LABEL) as AuditCategory[]

/** 流量列：↑上行 ↓下行（仅代理流/文件中转有值） */
function fmtTraffic(r: AgentAuditLog): string {
  if (r.bytesUp == null && r.bytesDown == null) return '-'
  return `↑${fmtBytes(r.bytesUp)} ↓${fmtBytes(r.bytesDown)}`
}

export default function AuditLogsPanel({ refreshTick = 0 }: { refreshTick?: number }) {
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [category, setCategory] = useState<AuditCategory>()
  const [nodeId, setNodeId] = useState<number>()
  const [result, setResult] = useState<AuditResult>()
  // 关键词双 state：输入草稿不触发查询，回车/点搜索才落定（bookmarks 先例）
  const [keywordDraft, setKeywordDraft] = useState('')
  const [keyword, setKeyword] = useState('')
  const [rows, setRows] = useState<AgentAuditLog[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [loading, setLoading] = useState(false)
  const [detail, setDetail] = useState<AgentAuditLog | null>(null)

  useEffect(() => {
    listAgentNodes().then(setNodes).catch(() => undefined)
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listAuditLogs({ category, nodeId, result, keyword: keyword || undefined, page, size })
      setRows(r.items)
      setTotal(r.total)
    } catch (e) {
      showError(e, '加载审计日志失败')
    } finally {
      setLoading(false)
    }
  }, [category, nodeId, result, keyword, page, size])

  useEffect(() => { load() }, [load, refreshTick])

  const columns: ColumnsType<AgentAuditLog> = [
    { title: '时间', dataIndex: 'createdAt', width: 160, render: (t) => fmtTime(t) },
    {
      title: '分类',
      dataIndex: 'category',
      width: 90,
      render: (c: AuditCategory) => <Tag color={CATEGORY_COLOR[c] ?? 'default'}>{CATEGORY_LABEL[c] ?? c}</Tag>,
    },
    { title: '事件', dataIndex: 'event', width: 150, render: (e: string) => <Typography.Text code>{e}</Typography.Text> },
    {
      title: '节点',
      dataIndex: 'nodeName',
      width: 130,
      ellipsis: true,
      render: (s?: string, r?) =>
        s || (r?.nodeId != null ? `#${r.nodeId}` : <Typography.Text type="secondary">-</Typography.Text>),
    },
    { title: '操作人', dataIndex: 'actor', width: 90, render: (s?: string) => s || '-' },
    {
      title: '目标',
      dataIndex: 'target',
      width: 200,
      ellipsis: true,
      render: (s?: string) => (s ? <Typography.Text code>{s}</Typography.Text> : '-'),
    },
    {
      title: '结果',
      dataIndex: 'result',
      width: 80,
      render: (r: AuditResult) =>
        r === 'OK' ? <Badge status="success" text="成功" />
          : r === 'REJECTED' ? <Badge status="warning" text="拒绝" />
            : <Badge status="error" text="失败" />,
    },
    { title: '耗时', dataIndex: 'durationMs', width: 90, render: (d?: number) => (d != null ? `${d} ms` : '-') },
    { title: '流量', key: 'traffic', width: 150, render: (_, r) => fmtTraffic(r) },
    {
      title: '详情',
      dataIndex: 'detail',
      ellipsis: true,
      render: (d: string | undefined, r) =>
        d ? <a onClick={() => setDetail(r)}>{d}</a> : '-',
    },
  ]

  return (
    // 卡片 body 是 flex 列（pageCardBodyFlexStyle）：筛选行固定，表格由 FitTable 实测高度、表内滚动
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          allowClear placeholder="分类" style={{ width: 130 }}
          value={category} onChange={(v) => { setCategory(v); setPage(0) }}
          options={CATEGORIES.map((c) => ({ label: CATEGORY_LABEL[c], value: c }))}
        />
        <Select
          allowClear showSearch placeholder="节点" style={{ width: 200 }}
          value={nodeId} onChange={(v) => { setNodeId(v); setPage(0) }}
          options={nodes.map((n) => ({ label: `${n.name}${n.status !== 'ONLINE' ? '（离线）' : ''}`, value: n.id }))}
        />
        <Select
          allowClear placeholder="结果" style={{ width: 110 }}
          value={result} onChange={(v) => { setResult(v); setPage(0) }}
          options={(Object.keys(RESULT_LABEL) as AuditResult[]).map((r) => ({ label: RESULT_LABEL[r], value: r }))}
        />
        <Input.Search
          allowClear placeholder="关键词（详情/节点/来源/目标）" style={{ width: 260 }}
          value={keywordDraft}
          onChange={(e) => setKeywordDraft(e.target.value)}
          onSearch={(v) => { setKeyword(v.trim()); setPage(0) }}
        />
      </Space>
      <FitTable<AgentAuditLog>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
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
        locale={{ emptyText: '暂无审计日志。节点接入、指令下发、代理转发、文件中转等交互发生后自动留痕。' }}
      />

      <Drawer title="审计详情" open={!!detail} onClose={() => setDetail(null)} width={DRAWER_DEFAULT}>
        {detail && (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="时间">{fmtTime(detail.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="分类">
                <Tag color={CATEGORY_COLOR[detail.category] ?? 'default'}>
                  {CATEGORY_LABEL[detail.category] ?? detail.category}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="事件"><Typography.Text code>{detail.event}</Typography.Text></Descriptions.Item>
              <Descriptions.Item label="结果">{RESULT_LABEL[detail.result] ?? detail.result}</Descriptions.Item>
              <Descriptions.Item label="节点">
                {detail.nodeName || (detail.nodeId != null ? `#${detail.nodeId}` : '-')}
              </Descriptions.Item>
              <Descriptions.Item label="操作人">{detail.actor || '-'}</Descriptions.Item>
              <Descriptions.Item label="来源地址">{detail.remoteAddr || '-'}</Descriptions.Item>
              <Descriptions.Item label="目标">{detail.target || '-'}</Descriptions.Item>
              <Descriptions.Item label="耗时">
                {detail.durationMs != null ? `${detail.durationMs} ms` : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="流量">{fmtTraffic(detail)}</Descriptions.Item>
            </Descriptions>
            <Typography.Text strong>详情</Typography.Text>
            <pre style={{ background: '#f6f6f6', padding: 12, borderRadius: 4, fontSize: 12, whiteSpace: 'pre-wrap' }}>
              {detail.detail ?? '(无)'}
            </pre>
          </Space>
        )}
      </Drawer>
    </div>
  )
}
