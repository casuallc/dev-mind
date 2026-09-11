// 方案设计 Tab（CAP-13/14/38）：阶段操作区（生成方案/跳过方案）+ Design 列表（确认/废弃/删除/文档预览）。
// 方案产出登记后服务端自动拆分工作单元；确认（CONFIRMED）降为纯标记——拆分时优先取已确认方案内容，不再门控。
// 阶段解锁由页面层计算 analysisDone 传入：分析完成或跳过后才可生成方案（流程不可逆引导）。
import { useCallback, useEffect, useState } from 'react'
import { Button, Popconfirm, Space, Table, Tag, Tooltip, Typography, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { flowDesign, flowSkip, listDesigns } from '../../api'
import { DESIGN_STATUS_LABEL } from '../requirementMeta'
import type { Design, DesignStatus, Requirement } from '../../types'
import { fmtTime } from '../../../../shared/utils/format'
import { showError } from '../../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../../shared/utils/table'
import DocPreviewModal from './DocPreviewModal'
import { designStatusColor, useDesignActions } from './useDesignActions'

export default function DesignsTab({ projectId, requirement, analysisDone, onChanged }: {
  projectId: string
  requirement: Requirement
  /** 需求分析已完成或已跳过（页面层计算）——未满足时生成/跳过动作禁用 */
  analysisDone: boolean
  /** 阶段动作触发后的整页刷新（overview + 页面级方案列表） */
  onChanged: () => void
}) {
  const requirementId = requirement.id
  const [designs, setDesigns] = useState<Design[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)

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

  const hasActiveDesign = designs.some((d) => d.status !== 'DISCARDED')
  const skipped = !!requirement.designSkipped

  const generate = async () => {
    setBusy(true)
    try {
      await flowDesign(projectId, requirementId)
      message.success('方案设计会话已启动，产出后将自动拆分工作单元')
      onChanged()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  const skip = async () => {
    setBusy(true)
    try {
      await flowSkip(projectId, requirementId, 'design')
      message.success('已跳过方案设计，可在「工作单元」Tab 直接 AI 拆分或手工新建')
      onChanged()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

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
        <Tooltip title={analysisDone ? '' : '先完成或跳过需求分析'}>
          <Button size="small" type="primary" loading={busy} disabled={!analysisDone} onClick={generate}>
            生成方案（AI）
          </Button>
        </Tooltip>
        {!hasActiveDesign && !skipped && (
          <Popconfirm
            title="跳过方案设计？"
            description="跳过后不可恢复（流程不可逆），可在「工作单元」Tab 直接 AI 拆分或手工新建"
            okText="跳过"
            cancelText="返回"
            onConfirm={skip}
          >
            <Button size="small" disabled={!analysisDone}>跳过方案</Button>
          </Popconfirm>
        )}
        {skipped && <Tag>已跳过方案设计</Tag>}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          方案产出自动登记为草稿并自动拆分工作单元；「确认」为纯标记，拆分时优先采用已确认方案内容。
        </Typography.Text>
        <Button size="small" icon={<ReloadOutlined />} onClick={load} loading={loading} />
      </Space>
      <Table rowKey="id" size="small" columns={columns} dataSource={designs} loading={loading} pagination={LIST_PAGINATION} />
      <DocPreviewModal preview={preview} onClose={closePreview} />
    </Space>
  )
}
