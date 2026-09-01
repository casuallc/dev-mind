// 需求列表卡（项目页内嵌）：按状态分组 + 状态筛选，点行进入需求详情页。
import { useCallback, useEffect, useState } from 'react'
import { Button, Empty, List, Select, Space, Tag, Typography, message } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { listRequirements } from '../api'
import type { Requirement, RequirementStatus } from '../types'
import RequirementFormModal from './RequirementFormModal'
import { ALL_STATUSES, requirementStatusColor } from './requirementMeta'

export default function RequirementListCard({ projectId }: { projectId: string }) {
  const navigate = useNavigate()
  const [requirements, setRequirements] = useState<Requirement[]>([])
  const [statusFilter, setStatusFilter] = useState<RequirementStatus[]>([])
  const [createOpen, setCreateOpen] = useState(false)

  const reload = useCallback(async () => {
    try {
      setRequirements(await listRequirements(projectId))
    } catch (e) {
      message.error(`加载需求失败：${(e as Error).message}`)
    }
  }, [projectId])

  useEffect(() => {
    reload()
  }, [reload])

  // 按状态分组（流程顺序），组内保持后端返回顺序；空数组筛选视为全部
  const visible = statusFilter.length > 0
    ? requirements.filter((r) => statusFilter.includes(r.status))
    : requirements
  const groups = ALL_STATUSES
    .map((s) => ({ status: s, items: visible.filter((r) => r.status === s) }))
    .filter((g) => g.items.length > 0)

  return (
    <>
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <Space wrap>
          <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建需求
          </Button>
          <Select
            mode="multiple"
            allowClear
            size="small"
            placeholder="状态筛选（默认全部）"
            style={{ minWidth: 220 }}
            value={statusFilter}
            onChange={setStatusFilter}
            options={ALL_STATUSES.map((s) => ({ value: s, label: s }))}
            maxTagCount={3}
          />
          <Button size="small" icon={<ReloadOutlined />} onClick={reload} />
        </Space>
        {visible.length === 0 ? (
          <Empty
            description={requirements.length === 0 ? '暂无需求，点击「新建需求」开始一条研发主线' : '无符合筛选的需求'}
            image={Empty.PRESENTED_IMAGE_SIMPLE}
          />
        ) : (
          groups.map((g) => (
            <div key={g.status}>
              <Tag color={requirementStatusColor(g.status)} style={{ marginBottom: 4 }}>
                {g.status}（{g.items.length}）
              </Tag>
              <List
                size="small"
                dataSource={g.items}
                renderItem={(r) => (
                  <List.Item
                    onClick={() => navigate(`/projects/${projectId}/requirements/${r.id}`)}
                    style={{ cursor: 'pointer', padding: '6px 8px', borderRadius: 4 }}
                  >
                    <Space size={6}>
                      <Typography.Text code style={{ fontSize: 12 }}>{r.code}</Typography.Text>
                      <Typography.Text style={{ fontSize: 13 }} ellipsis={{ tooltip: r.title }}>
                        {r.title}
                      </Typography.Text>
                    </Space>
                  </List.Item>
                )}
              />
            </div>
          ))
        )}
      </Space>

      <RequirementFormModal
        projectId={projectId}
        editing={null}
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onSaved={() => reload()}
      />
    </>
  )
}
