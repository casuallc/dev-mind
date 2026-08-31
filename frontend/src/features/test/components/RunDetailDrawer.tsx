// 测试运行详情 Drawer：运行中走 WS 实时结果流，终态轮询刷新 summary。
import { Alert, Button, Drawer, Space, Table, Tag, Typography } from 'antd'
import { useEffect, useRef, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { BugOutlined } from '@ant-design/icons'
import { getRun } from '../api'
import type { CaseResult, CaseResultStatus, TestRun } from '../types'
import { RESULT_COLOR, STATUS_COLOR } from '../constants'

export default function RunDetailDrawer({ record, onClose, onChanged, onOpenText, onIssues }: {
  record: TestRun | null
  onClose: () => void
  onChanged: (r: TestRun) => void
  onOpenText: (runId: number, title: string) => void
  onIssues: (runId: number) => void
}) {
  const [d, setD] = useState<TestRun | null>(record)
  const [connected, setConnected] = useState(false)
  const latestRef = useRef<TestRun | null>(record)

  useEffect(() => {
    latestRef.current = d
  }, [d])

  useEffect(() => {
    setD(record)
    setConnected(false)
    if (!record) return
    latestRef.current = record

    const active = record.status === 'QUEUED' || record.status === 'RUNNING'
    if (active) {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${proto}://${location.host}/ws/test-runs/${record.id}/stream`)
      ws.onopen = () => setConnected(true)
      ws.onmessage = (msg) => {
        try {
          const f = JSON.parse(msg.data)
          if (f.type === 'snapshot') {
            setD((cur) => (cur ? { ...cur, status: f.status, baseUrl: f.baseUrl ?? cur.baseUrl, results: f.results ?? cur.results } : cur))
          } else if (f.type === 'result') {
            setD((cur) => (cur ? { ...cur, results: [...cur.results, f.result] } : cur))
          } else if (f.type === 'done') {
            setConnected(false)
            setD((cur) => (cur ? { ...cur, status: f.status } : cur))
            ws.close()
          }
        } catch {
          /* 忽略坏帧 */
        }
      }
      ws.onclose = () => setConnected(false)
      return () => ws.close()
    }

    // 终态：先拉一次完整结果，再定时刷新最终 summary/时间
    getRun(record.id).then((latest) => {
      if (latest.results?.length) {
        setD(latest)
        latestRef.current = latest
      }
    }).catch(() => {})
    const timer = setInterval(() => {
      getRun(record.id).then((latest) => {
        const cur = latestRef.current
        if (!cur || JSON.stringify(latest) !== JSON.stringify(cur)) {
          setD(latest)
          latestRef.current = latest
          onChanged(latest)
        }
      }).catch(() => {})
    }, 4000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record])

  const resultColumns: ColumnsType<CaseResult> = [
    { title: '#', dataIndex: 'sort', width: 44 },
    { title: '用例', dataIndex: 'name', ellipsis: true },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (v: CaseResultStatus) => <Tag color={RESULT_COLOR[v]}>{v}</Tag>,
    },
    { title: '请求', dataIndex: 'requestSummary', width: 220, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '响应', dataIndex: 'responseSummary', width: 220, ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '耗时', dataIndex: 'duration', width: 80, render: (v: number | null) => (v != null ? `${v}ms` : '-') },
  ]

  return (
    <Drawer
      title={d ? (
        <Space>
          <span>测试运行 #{d.id}</span>
          <Tag color={STATUS_COLOR[d.status]}>{d.status}</Tag>
          {d.triggeredBy === 'deploy' && <Tag color="purple">自动回归</Tag>}
          {connected && <Tag color="cyan">实时</Tag>}
        </Space>
      ) : '测试详情'}
      width={820}
      open={!!record}
      onClose={onClose}
    >
      {d && (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {d.errorSummary && <Alert type="error" showIcon message={d.errorSummary} />}
          <Space wrap>
            <Button size="small" onClick={() => onOpenText(d.id, '报告')}>报告</Button>
            <Button size="small" onClick={() => onOpenText(d.id, '日志')}>日志</Button>
            {d.status === 'FAILED' && (
              <Button size="small" danger icon={<BugOutlined />} onClick={() => onIssues(d.id)}>缺陷线索</Button>
            )}
          </Space>
          <Space wrap size={8}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              目标 {d.baseUrl || '-'} · 服务器 # {d.serverId ?? '-'} · 环境 {d.environmentId ? `#${d.environmentId}` : '-'} · 部署 #{d.deploymentId ?? '-'}
            </Typography.Text>
            {d.summary && (
              <span style={{ fontSize: 12 }}>
                {d.summary.total} 项 · <span style={{ color: '#52c41a' }}>{d.summary.passed} 过</span>{' '}
                <span style={{ color: d.summary.failed ? '#ff4d4f' : undefined }}>{d.summary.failed} 败</span>{' '}
                <span style={{ color: '#fa8c16' }}>{d.summary.skipped} 跳</span>
              </span>
            )}
            {d.reportDocId && <Tag color="geekblue">报告文档 #{d.reportDocId}</Tag>}
          </Space>
          <Typography.Text strong style={{ fontSize: 13 }}>用例结果（{d.results?.length ?? 0}）</Typography.Text>
          <Table<CaseResult> rowKey="id" size="small" columns={resultColumns} dataSource={d.results ?? []}
            pagination={false} locale={{ emptyText: '暂无结果（运行中或未配置目标）' }} />
        </Space>
      )}
    </Drawer>
  )
}
