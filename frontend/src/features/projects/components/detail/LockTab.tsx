// 锁定 Tab：项目级并发写配额（供 Orchestrator 并发控制）。
import { useEffect, useState } from 'react'
import { Button, Descriptions, InputNumber, Space, Typography, message } from 'antd'
import { DiffOutlined, StopOutlined } from '@ant-design/icons'
import { claimWrite, releaseWrite, updateLock } from '../../api'
import type { ProjectLock } from '../../types'

export default function LockTab({ id, lock, onChanged, readOnly }: {
  id: string
  lock: ProjectLock | null
  onChanged: (l: ProjectLock) => void
  /** 只读模式（工作台 /settings 对 VIEWER）：只看配额状态，不可调整 */
  readOnly?: boolean
}) {
  const [max, setMax] = useState(lock?.maxConcurrent ?? 1)

  useEffect(() => setMax(lock?.maxConcurrent ?? 1), [lock?.maxConcurrent])

  const act = async (fn: () => Promise<ProjectLock>, ok: string) => {
    try {
      const l = await fn()
      onChanged(l)
      message.success(ok)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const saveMax = async () => {
    try {
      const l = await updateLock(id, Math.max(1, max))
      onChanged(l)
      message.success('已保存并发上限')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%', maxWidth: 480 }}>
      <Descriptions size="small" column={2}>
        <Descriptions.Item label="当前写任务">
          <Typography.Text strong>{lock?.activeWrites ?? 0}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="最大并发写">
          <Typography.Text strong>{lock?.maxConcurrent ?? '-'}</Typography.Text>
        </Descriptions.Item>
      </Descriptions>
      {!readOnly && (
        <Space wrap>
          <InputNumber min={1} value={max} onChange={(v) => setMax(v ?? 1)} addonBefore="并发上限" />
          <Button size="small" type="primary" onClick={saveMax}>保存上限</Button>
          <Button size="small" icon={<DiffOutlined />} onClick={() => act(() => claimWrite(id), '已占用一个写配额')}>
            占用写配额
          </Button>
          <Button size="small" icon={<StopOutlined />} onClick={() => act(() => releaseWrite(id), '已释放一个写配额')}>
            释放写配额
          </Button>
        </Space>
      )}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        供任务编排 Orchestrator 做项目级并发控制；达上限时 claim 返回冲突。
      </Typography.Text>
    </Space>
  )
}
