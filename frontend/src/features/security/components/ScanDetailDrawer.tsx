// 扫描详情抽屉：引擎子状态条 + 报告附件清单 + 执行日志（/ws/security/{id} 实时流）。
// 帧协议对齐构建日志：snapshot / log / done。
import { Alert, Drawer, Space, Tag, Typography } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { getScan } from '../api'
import type { ScanDetail } from '../types'
import LogView from '../../../shared/components/LogView'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'
import { attachmentRawUrl } from '../../../shared/attachments/url'
import { ENGINE_LABEL, SCAN_STATUS_COLOR } from './common'

const ENGINE_STATUS_COLOR: Record<string, string> = {
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
}

interface Props {
  scanId: number | null
  onClose: () => void
}

export default function ScanDetailDrawer({ scanId, onClose }: Props) {
  const [detail, setDetail] = useState<ScanDetail | null>(null)
  const [text, setText] = useState('')
  const [connected, setConnected] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    if (!scanId) {
      setDetail(null)
      setText('')
      setConnected(false)
      return
    }
    setText('')
    setConnected(false)
    getScan(scanId).then(setDetail).catch(() => {})

    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${location.host}/ws/security/${scanId}`)
    wsRef.current = ws
    ws.onopen = () => setConnected(true)
    ws.onmessage = (msg) => {
      try {
        const f = JSON.parse(msg.data)
        if (f.type === 'snapshot') {
          setText(f.logs ?? '')
        } else if (f.type === 'log') {
          setText((t) => (t ? `${t}\n${f.line}` : f.line))
        } else if (f.type === 'done') {
          setConnected(false)
          getScan(scanId).then(setDetail).catch(() => {})
          ws.close()
        }
      } catch {
        /* 忽略坏帧 */
      }
    }
    ws.onclose = () => setConnected(false)
    return () => ws.close()
  }, [scanId])

  // 运行中周期刷新引擎子状态/计数（终态由 done 帧触发一次终刷）
  useEffect(() => {
    if (!scanId || !detail || (detail.scan.status !== 'QUEUED' && detail.scan.status !== 'RUNNING')) {
      return
    }
    const timer = setInterval(() => {
      getScan(scanId).then(setDetail).catch(() => {})
    }, 5000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scanId, detail?.scan.status])

  const scan = detail?.scan

  return (
    <Drawer
      title={
        scan ? (
          <Space>
            <span>扫描 #{scan.id}</span>
            <Tag color={SCAN_STATUS_COLOR[scan.status]}>{scan.status}</Tag>
            {connected && <Tag color="cyan">实时</Tag>}
            <Typography.Text type="secondary" style={{ fontWeight: 'normal' }}>
              {scan.branch}
              {scan.baselineCommitSha ? ` · ${scan.baselineCommitSha.slice(0, 10)}` : ''}
            </Typography.Text>
          </Space>
        ) : '扫描详情'
      }
      width={DRAWER_SCREEN}
      open={!!scanId}
      onClose={onClose}
    >
      {scan?.errorMessage && (
        <Alert type="error" showIcon style={{ marginBottom: 12 }} message={scan.errorMessage} />
      )}
      {detail && Object.keys(detail.engineStatus).length > 0 && (
        <Space size={8} wrap style={{ marginBottom: 12 }}>
          {Object.entries(detail.engineStatus).map(([engine, st]) => (
            <Tag key={engine} color={ENGINE_STATUS_COLOR[st.status ?? ''] ?? 'default'}>
              {st.displayName ?? ENGINE_LABEL[engine] ?? engine}: {st.status}
              {st.error ? `（${st.error}）` : ''}
            </Tag>
          ))}
        </Space>
      )}
      {scan && (scan.newCount != null || scan.filteredCount != null) && (
        <Typography.Paragraph type="secondary">
          新增 {scan.newCount ?? 0} · 存量 {scan.existingCount ?? 0} · 修复 {scan.fixedCount ?? 0}
          {scan.filteredCount ? ` · 白名单过滤 ${scan.filteredCount}` : ''}
        </Typography.Paragraph>
      )}
      {detail && detail.reports.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          <Typography.Text type="secondary">引擎原始报告（权限受附件 scope 约束）：</Typography.Text>
          <Space size={8} wrap>
            {detail.reports.map((r) => (
              <a key={r.id} href={attachmentRawUrl(r.attachmentId)} download>
                {r.engine}.{r.format}
              </a>
            ))}
          </Space>
        </div>
      )}
      {scanId && <LogView key={scanId} text={text} downloadName={`security-scan-${scanId}`} />}
    </Drawer>
  )
}
