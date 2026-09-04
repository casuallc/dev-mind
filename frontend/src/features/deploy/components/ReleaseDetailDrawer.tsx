// 发版详情 Drawer：WS 实时日志 + 状态快照，承载单条发版的成套操作（执行/回滚/删除）。
import { Alert, Button, Drawer, Popconfirm, Space, Tag, Typography, message } from 'antd'
import { useEffect, useState } from 'react'
import {
  deleteRelease,
  executeRelease,
  getRelease,
  getReleaseLogs,
  rollbackRelease,
} from '../api'
import type { ReleaseRecord } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { STATUS_COLOR } from '../constants'

export default function ReleaseDetailDrawer({ record, onClose, onChanged }: {
  record: ReleaseRecord | null
  onClose: () => void
  onChanged: () => void
}) {
  const [d, setD] = useState<ReleaseRecord | null>(record)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setD(record)
    setText('')
    if (!record) return
    getReleaseLogs(record.id).then(setText).catch(() => setText(''))

    const active = record.status === 'PLANNED' || record.status === 'RUNNING'
    if (active) {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${proto}://${location.host}/ws/releases/${record.id}/stream`)
      ws.onopen = () => undefined
      ws.onmessage = (msg) => {
        try {
          const f = JSON.parse(msg.data)
          if (f.type === 'snapshot') {
            setD((cur) => (cur ? { ...cur, status: f.status, version: f.version, tagName: f.tagName, nexusRef: f.nexusRef } : cur))
          } else if (f.type === 'log') {
            setText((t) => (t ? `${t}\n${f.line}` : f.line))
          } else if (f.type === 'done') {
            setD((cur) => (cur ? { ...cur, status: f.status } : cur))
            ws.close()
          }
        } catch {
          /* 忽略坏帧 */
        }
      }
      ws.onclose = () => undefined
      return () => ws.close()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record])

  // 终态轮询刷新（日志/状态落库后同步）
  useEffect(() => {
    if (!record) return
    const timer = setInterval(() => {
      getRelease(record.id)
        .then((latest) => {
          setD((cur) => {
            if (JSON.stringify(latest) !== JSON.stringify(cur)) {
              onChanged()
              return latest
            }
            return cur
          })
        })
        .catch(() => {})
    }, 4000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record])

  const act = async (fn: () => Promise<ReleaseRecord>, ok: string) => {
    if (!d) return
    setBusy(true)
    try {
      const nd = await fn()
      setD(nd)
      onChanged()
      message.success(ok)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer title={d ? `发版 v${d.version} #${d.id}` : '发版详情'} width={680} open={!!d} onClose={onClose}>
      {d && (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space wrap>
            <Tag color={STATUS_COLOR[d.status]}>{d.status}</Tag>
            <Tag color="geekblue">{d.executor}</Tag>
            {d.tagName && <Typography.Text code>{d.tagName}</Typography.Text>}
            {d.nexusRef && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{d.nexusRef}</Typography.Text>}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {fmtTime(d.createdAt)}
            </Typography.Text>
            {d.rollbackOf && <Tag color="orange">回滚自 #{d.rollbackOf}</Tag>}
          </Space>
          {d.errorSummary && <Alert type="error" showIcon message="失败原因" description={d.errorSummary} />}
          <Space>
            {d.status === 'PLANNED' && (
              <Button type="primary" loading={busy} onClick={() => act(() => executeRelease(d.id), '已开始执行')}>执行发版</Button>
            )}
            {d.status !== 'RUNNING' && d.status !== 'ROLLED_BACK' && (
              <Popconfirm title="回滚该发版？将删除 tag 并移除制品引用" onConfirm={() => act(() => rollbackRelease(d.id), '已回滚')}>
                <Button loading={busy}>回滚</Button>
              </Popconfirm>
            )}
            <Popconfirm
              title="删除该发版记录？"
              onConfirm={async () => {
                await deleteRelease(d.id)
                message.success('已删除')
                onChanged()
                onClose()
              }}
            >
              <Button danger>删除</Button>
            </Popconfirm>
          </Space>
          <div style={{ maxHeight: 420, overflow: 'auto', background: '#111', color: '#cfc', padding: 8, borderRadius: 4, fontFamily: 'monospace', fontSize: 12, width: '100%' }}>
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{text || '（暂无日志）'}</pre>
          </div>
        </Space>
      )}
    </Drawer>
  )
}
