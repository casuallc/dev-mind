// 方案设计 Tab（CAP-52）：**只读展示**——规划会话产出的方案列表 + 文档预览（起会话入口在需求详情页头卡）。
// 与 AI 流程无关的记录管理保留：「确认」为纯标记（人工挑一份作准绳），废弃/恢复/删除是本地记录整理。
import { useCallback, useEffect, useState } from 'react'
import { Button, Popconfirm, Space, Table, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { listDesigns } from '../../api'
import { DESIGN_STATUS_LABEL } from '../requirementMeta'
import type { Design, DesignStatus, Requirement } from '../../types'
import { fmtTime } from '../../../../shared/utils/format'
import { showError } from '../../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../../shared/utils/table'
import DocPreviewModal from './DocPreviewModal'
import { designStatusColor, useDesignActions } from './useDesignActions'

export default function DesignsTab({ projectId, requirement, onChanged }: {
  projectId: string
  requirement: Requirement
  /** 记录变更后的整页刷新（overview + 页面级方案列表） */
  onChanged: () => void
}) {
  const requirementId = requirement.id
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

  // 记录变更后本地表与页面级方案列表都要刷（页面级列表决定头卡按钮文案是「开启」还是「重新规划」）
  const refresh = useCallback(() => {
    load()
    onChanged()
  }, [load, onChanged])

  const { preview, closePreview, setStatus, remove, previewDesign } =
    useDesignActions(projectId, requirementId, refresh)

  const columns: ColumnsType<Design> = [
    { title: '版本', dataIndex: 'version', width: 70, render: (v: number) => `v${v}` },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (s: DesignStatus) => <Tag color={designStatusColor(s)}>{DESIGN_STATUS_LABEL[s] ?? s}</Tag>,
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
              <Popconfirm title={`确认方案 v${d.version}？`} description="纯标记：AI 拆分时优先采用已确认方案的内容"
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
      <Space wrap size={8}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          规划会话产出的方案自动登记为草稿并自动拆出工作单元；「确认」为纯标记（人工挑一份作准绳）。
          生成/重跑方案请用页面右上角「开启 AI 规划」。
        </Typography.Text>
        <Button size="small" icon={<ReloadOutlined />} onClick={load} loading={loading} />
      </Space>
      <Table rowKey="id" size="small" columns={columns} dataSource={designs} loading={loading} pagination={LIST_PAGINATION} />
      <DocPreviewModal preview={preview} onClose={closePreview} />
    </Space>
  )
}
