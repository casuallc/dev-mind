// CAP-56 评测/微调的执行日志抽屉：历史走 HTTP（marker 行已剔除），实时增量走 WS。
// 帧格式与构建/部署共用（snapshot 全量覆写 / log 追加一行 / item 逐题事件 / done 终态）——
// 唯一不同的是 topic：评测与微调的 id 是两个独立命名空间，各挂各的前缀，否则「打开微调 7
// 会看到评测 7 的输出」。
import { useEffect, useRef, useState } from 'react'
import { Alert, Drawer, Space, Table, Tag, Typography } from 'antd'
import { evaluationLogs, finetuneLogs } from '../api'
import LogView from '../../../shared/components/LogView'
import { CaseGroupTag, RunStatusTag, runActive } from './labCommon'
import { num } from './ReportBlocks'

/** WS 逐题事件（脚本 DEVMIND_ITEM 载荷；不落库，刷新后靠报告里的 perItem 补） */
interface ItemEvent {
  id: number
  caseGroup?: string
  questions?: number
  correct?: number
  skipped?: boolean
}

export default function LabLogDrawer({
  open,
  kind,
  id,
  status,
  title,
  onClose,
  onFinished,
}: {
  open: boolean
  kind: 'evaluations' | 'finetunes'
  id: number | null
  status: string
  title: string
  onClose: () => void
  /** 收到终态（活着打开时）后由父级重拉列表 */
  onFinished?: () => void
}) {
  const [text, setText] = useState('')
  const [items, setItems] = useState<ItemEvent[]>([])
  const [connected, setConnected] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    if (!open || id == null) {
      setText('')
      setItems([])
      setConnected(false)
      return
    }
    setText('')
    setItems([])
    setConnected(false)
    const history = kind === 'evaluations' ? evaluationLogs : finetuneLogs
    history(id)
      .then(setText)
      .catch(() => setText(''))

    // 只有未终态的才接实时流；已终态的直接看历史（服务端对终态连接会先推 snapshot+done 再保持）
    if (!runActive(status)) return
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${location.host}/ws/decision-lab/${kind}/${id}`)
    wsRef.current = ws
    ws.onopen = () => setConnected(true)
    ws.onmessage = (msg) => {
      try {
        const f = JSON.parse(msg.data)
        if (f.type === 'snapshot') {
          setText(f.logs ?? '')
        } else if (f.type === 'log') {
          setText((t) => (t ? `${t}\n${f.line}` : f.line))
        } else if (f.type === 'item' && f.item) {
          setItems((prev) => [...prev, f.item as ItemEvent])
        } else if (f.type === 'done') {
          setConnected(false)
          ws.close()
          onFinished?.()
        }
      } catch {
        /* 忽略坏帧 */
      }
    }
    ws.onclose = () => setConnected(false)
    return () => ws.close()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, id, kind, status])

  return (
    <Drawer
      title={
        <Space>
          <span>{title}</span>
          <RunStatusTag status={status} />
          {connected && <Tag color="cyan">实时</Tag>}
        </Space>
      }
      width="70%"
      open={open}
      onClose={onClose}
    >
      {items.length > 0 && (
        <>
          <Typography.Text strong>逐题事件（{items.length}）</Typography.Text>
          <Table<ItemEvent>
            rowKey={(r, i) => `${r.id}-${i ?? 0}`}
            size="small"
            style={{ margin: '8px 0 16px' }}
            dataSource={items}
            pagination={{ defaultPageSize: 10, showSizeChanger: false, showTotal: (t) => `共 ${t} 条` }}
            columns={[
              { title: '样本', dataIndex: 'id', width: 80 },
              {
                title: '组',
                dataIndex: 'caseGroup',
                width: 140,
                render: (g?: string) => (g ? <CaseGroupTag value={g} /> : '—'),
              },
              { title: '题数', dataIndex: 'questions', width: 80, render: (v?: number) => num(v, 0) },
              { title: '答对', dataIndex: 'correct', width: 80, render: (v?: number) => num(v, 0) },
              {
                title: '备注',
                render: (_, r) =>
                  r.skipped ? (
                    <Typography.Text type="warning" title="gold 落不上题面">
                      跳过（gold 落不上题面）
                    </Typography.Text>
                  ) : (
                    '—'
                  ),
              },
            ]}
          />
        </>
      )}
      {!runActive(status) && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="本次运行已结束：下面是完整历史日志（逐题事件不落库，终态后请看报告里的「逐题明细」）"
        />
      )}
      <LogView key={`${kind}-${id}`} text={text} downloadName={`${kind}-${id}`} maxHeight="calc(100vh - 240px)" />
    </Drawer>
  )
}
