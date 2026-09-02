// 方案 Tab（CAP-13/14）：Design 列表 + 确认/废弃/删除 + 方案文档内容预览。
// AI 方案由流程引擎在方案会话完成后自动登记（DRAFT），人在此确认（CONFIRMED）后进入拆分。
import { useCallback, useEffect, useState } from 'react'
import { Button, Modal, Popconfirm, Space, Table, Tag, Typography, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { deleteDesign, listDesigns, updateDesignStatus } from '../../api'
import { getDoc } from '../../../docs/api'
import type { Design, DesignStatus } from '../../types'

function designStatusColor(s: DesignStatus): string {
  switch (s) {
    case 'DRAFT': return 'gold'
    case 'CONFIRMED': return 'green'
    case 'DISCARDED': return 'default'
    default: return 'default'
  }
}

export default function DesignsTab({ projectId, requirementId }: {
  projectId: string
  requirementId: string
}) {
  const [designs, setDesigns] = useState<Design[]>([])
  const [loading, setLoading] = useState(false)
  const [preview, setPreview] = useState<{ title: string; content: string } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setDesigns(await listDesigns(projectId, requirementId))
    } catch (e) {
      message.error(`加载方案失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [projectId, requirementId])

  useEffect(() => {
    load()
  }, [load])

  const setStatus = async (d: Design, status: DesignStatus) => {
    try {
      await updateDesignStatus(projectId, requirementId, d.id, status)
      message.success(`方案 v${d.version} → ${status}`)
      await load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const onPreview = async (d: Design) => {
    if (!d.docId) {
      message.info('该方案未关联文档')
      return
    }
    try {
      const doc = await getDoc(d.docId)
      setPreview({ title: `方案 v${d.version} · ${doc.title}`, content: doc.contentMd || '（空）' })
    } catch (e) {
      message.error(`读取方案文档失败：${(e as Error).message}`)
    }
  }

  const columns: ColumnsType<Design> = [
    { title: '版本', dataIndex: 'version', width: 70, render: (v: number) => `v${v}` },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (s: DesignStatus) => <Tag color={designStatusColor(s)}>{s}</Tag>,
    },
    { title: '文档', dataIndex: 'docId', width: 90, render: (v?: number) => v ? `#${v}` : '-' },
    {
      title: '创建', dataIndex: 'createdAt', width: 160,
      render: (v: string) => <span style={{ fontSize: 12 }}>{new Date(v).toLocaleString()}</span>,
    },
    {
      title: '操作', key: 'ops', width: 220,
      render: (_, d) => (
        <Space size={4}>
          <Button size="small" type="link" disabled={!d.docId} onClick={() => onPreview(d)}>查看</Button>
          {d.status === 'DRAFT' && (
            <>
              <Popconfirm title={`确认方案 v${d.version}？`} description="确认后可作为拆分工作单元的依据"
                onConfirm={() => setStatus(d, 'CONFIRMED')}>
                <Button size="small" type="link">确认</Button>
              </Popconfirm>
              <Button size="small" type="link" onClick={() => setStatus(d, 'DISCARDED')}>废弃</Button>
            </>
          )}
          {d.status === 'CONFIRMED' && (
            <Button size="small" type="link" onClick={() => setStatus(d, 'DISCARDED')}>废弃</Button>
          )}
          {d.status === 'DISCARDED' && (
            <Button size="small" type="link" onClick={() => setStatus(d, 'DRAFT')}>恢复</Button>
          )}
          <Popconfirm title={`删除方案 v${d.version}？`} onConfirm={async () => {
            await deleteDesign(projectId, requirementId, d.id)
            message.success('已删除')
            await load()
          }}>
            <Button size="small" type="link" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          方案由「生成方案（AI）」产出后自动登记为 DRAFT；确认（CONFIRMED）后即可 AI 拆分。简单需求可跳过方案直接拆分。
        </Typography.Text>
        <Button size="small" icon={<ReloadOutlined />} onClick={load} loading={loading} />
      </Space>
      <Table rowKey="id" size="small" columns={columns} dataSource={designs} loading={loading} pagination={false} />
      <Modal
        title={preview?.title}
        open={!!preview}
        onCancel={() => setPreview(null)}
        footer={null}
        width={860}
      >
        <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12, maxHeight: '65vh', overflow: 'auto' }}>
          {preview?.content}
        </pre>
      </Modal>
    </Space>
  )
}
