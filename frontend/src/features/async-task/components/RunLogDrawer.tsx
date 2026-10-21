// CAP-80 执行日志抽屉：历史走 REST（run.logs 快照），未终态接 /ws/async-tasks/{runId} 实时流。
// 帧格式与构建/决策实验室共用（snapshot 全量覆写 / log 追加一行 / done 终态），topic=runId。
import { useEffect, useRef, useState } from 'react'
import { Drawer, Space, Tag, Typography } from 'antd'
import { getRun } from '../api'
import type { AsyncTaskRunView } from '../types'
import LogView from '../../../shared/components/LogView'
import { DRAWER_SCREEN } from '../../../shared/utils/overlay'

const TERMINAL = new Set(['SUCCESS', 'FAILED', 'CANCELLED'])

export function RunStatusTag({ status }: { status: string }) {
  const color =
    status === 'SUCCESS' ? 'green'
      : status === 'FAILED' ? 'red'
        : status === 'RUNNING' ? 'blue'
          : status === 'CANCELLED' ? 'default'
            : 'gold'
  return <Tag color={color}>{status}</Tag>
}

export default function RunLogDrawer({
  run,
  onClose,
  onFinished,
}: {
  run: AsyncTaskRunView | null
  onClose: () => void
  /** 活着打开时收到终态后由父级重拉列表 */
  onFinished?: () => void
}) {
  const [text, setText] = useState('')
  const [status, setStatus] = useState('')
  const [connected, setConnected] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    if (!run) {
      setText('')
      setStatus('')
      setConnected(false)
      return
    }
    setText(run.logs ?? '')
    setStatus(run.status)
    setConnected(false)
    if (TERMINAL.has(run.status)) return

    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${location.host}/ws/async-tasks/${run.id}`)
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
          setStatus(f.status || '')
          setConnected(false)
          ws.close()
          // 终态后补一次 REST：拿到最终 error/duration（WS 帧只带 status）
          getRun(run.id).then((r) => setText(r.logs ?? '')).catch(() => undefined)
          onFinished?.()
        }
      } catch {
        /* 忽略坏帧 */
      }
    }
    ws.onclose = () => setConnected(false)
    return () => ws.close()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run?.id])

  return (
    <Drawer
      title={
        <Space>
          <span>执行日志</span>
          {run && <Typography.Text type="secondary">第 {run.runSeq} 轮 · attempt {run.attempt}</Typography.Text>}
          {status && <RunStatusTag status={status} />}
          {connected && <Tag color="cyan">实时</Tag>}
        </Space>
      }
      width={DRAWER_SCREEN}
      open={!!run}
      onClose={onClose}
    >
      {run?.error && (
        <Typography.Paragraph type="danger" style={{ whiteSpace: 'pre-wrap' }}>
          {run.error}
        </Typography.Paragraph>
      )}
      <LogView key={run?.id} text={text} downloadName={`async-task-run-${run?.id ?? ''}`} maxHeight="calc(100vh - 220px)" />
    </Drawer>
  )
}
