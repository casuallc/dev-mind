// 锁定页（/admin/projects/:id/lock）：项目级并发写配额（供 Orchestrator 并发控制）。
import { useCallback, useEffect, useState } from 'react'
import { Button, Card, Descriptions, InputNumber, Space, Typography, message } from 'antd'
import { DiffOutlined, StopOutlined } from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { claimWrite, getLock, releaseWrite, updateLock } from '../../api'
import type { ProjectLock } from '../../types'

export default function LockPage() {
  const { id = '' } = useParams<{ id: string }>()
  const [lock, setLock] = useState<ProjectLock | null>(null)
  const [max, setMax] = useState(1)

  const load = useCallback(() => {
    getLock(id)
      .then((l) => {
        setLock(l)
        setMax(l.maxConcurrent)
      })
      .catch(() => {})
  }, [id])

  useEffect(() => {
    load()
  }, [load])

  const act = async (fn: () => Promise<ProjectLock>, ok: string) => {
    try {
      const l = await fn()
      setLock(l)
      message.success(ok)
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const saveMax = async () => {
    try {
      const l = await updateLock(id, Math.max(1, max))
      setLock(l)
      message.success('已保存并发上限')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  return (
    <Card title="锁定" style={{ maxWidth: 640 }}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        项目级并发写配额，供任务编排 Orchestrator 做并发控制；达上限时 claim 返回冲突。
      </Typography.Paragraph>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Descriptions size="small" column={2}>
          <Descriptions.Item label="当前写任务">
            <Typography.Text strong>{lock?.activeWrites ?? 0}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="最大并发写">
            <Typography.Text strong>{lock?.maxConcurrent ?? '-'}</Typography.Text>
          </Descriptions.Item>
        </Descriptions>
        <Space wrap>
          <InputNumber min={1} value={max} onChange={(v) => setMax(v ?? 1)} addonBefore="并发上限" />
          <Button type="primary" onClick={saveMax}>保存上限</Button>
          <Button icon={<DiffOutlined />} onClick={() => act(() => claimWrite(id), '已占用一个写配额')}>
            占用写配额
          </Button>
          <Button icon={<StopOutlined />} onClick={() => act(() => releaseWrite(id), '已释放一个写配额')}>
            释放写配额
          </Button>
        </Space>
      </Space>
    </Card>
  )
}
