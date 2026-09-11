// 方案 Tab（CAP-13/14）：Design 列表 + 确认/废弃/删除 + 方案文档内容预览（Markdown 渲染）。
// AI 方案由流程引擎在方案会话完成后自动登记（DRAFT），人在此确认（CONFIRMED）后进入拆分。
// 状态操作与预览逻辑走共享 useDesignActions/DocPreviewModal（流程 Tab 同用，CAP-37 FR-04）。
import { useCallback, useEffect, useState } from 'react'
import { Button, Popconfirm, Space, Table, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { listDesigns } from '../../api'
import type { Design, DesignStatus } from '../../types'
import { fmtTime } from '../../../../shared/utils/format'
import { showError } from '../../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../../shared/utils/table'
import DocPreviewModal from './DocPreviewModal'
import { designStatusColor, useDesignActions } from './useDesignActions'

export default function DesignsTab({ projectId, requirementId }: {
  projectId: string
  requirementId: string
}) {
  const [designs, setDesigns] = useState<Design[]>([])
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setDesigns(await listDesigns(projectId, requirementId))
    } catch (e) {
      showError(e, '加载方案失败')
    } finally {
      setLoading(false)
    }
  }, [projectId, requirementId])

  useEffect(() => {
    load()
  }, [load])

  const { preview, closePreview, setStatus, remove, previewDesign } =
    useDesignActions(projectId, requirementId, load)

  const columns: ColumnsType<Design> = [
    { title: '版本', dataIndex: 'version', width: 70, render: (v: number) => `v${v}` },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (s: DesignStatus) => <Tag color={designStatusColor(s)}>{s}</Tag>,
    },
    { title: '文档', dataIndex: 'docId', width: 90, render: (v?: number) => v ? `#${v}` : '-' },
    {
      title: '创建', dataIndex: 'createdAt', width: 160,
      render: (v: string) => <span style={{ fontSize: 12 }}>{fmtTime(v)}</span>,
    },
    {
      title: '操作', key: 'ops', width: 220,
      render: (_, d) => (
        <Space size={4}>
          <Button size="small" type="link" disabled={!d.docId} onClick={() => previewDesign(d)}>查看</Button>
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
          <Button size="small" type="link" danger onClick={() => remove(d)}>删除</Button>
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
      <Table rowKey="id" size="small" columns={columns} dataSource={designs} loading={loading} pagination={LIST_PAGINATION} />
      <DocPreviewModal preview={preview} onClose={closePreview} />
    </Space>
  )
}
