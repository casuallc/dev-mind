// 需求列表卡（项目页内嵌）：按状态分组 + 状态筛选，点行进入需求详情页。
// CAP-19：Jira 同步导入的需求带来源徽标（external_links 批量反查，点击跳 Jira）。
import { useCallback, useEffect, useState } from 'react'
import { Button, Empty, List, Select, Space, Tag, Tooltip, Typography, message } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { listRequirements } from '../api'
import { listExternalLinksByType } from '../../integrations/api'
import type { ExternalLink } from '../../integrations/types'
import type { Requirement, RequirementStatus } from '../types'
import RequirementFormModal from './RequirementFormModal'
import { ALL_STATUSES, requirementStatusColor } from './requirementMeta'

export default function RequirementListCard({ projectId }: { projectId: string }) {
  const navigate = useNavigate()
  const [requirements, setRequirements] = useState<Requirement[]>([])
  const [jiraLinks, setJiraLinks] = useState<Map<string, ExternalLink>>(new Map())
  const [statusFilter, setStatusFilter] = useState<RequirementStatus[]>([])
  const [createOpen, setCreateOpen] = useState(false)

  const reload = useCallback(async () => {
    try {
      setRequirements(await listRequirements(projectId))
    } catch (e) {
      message.error(`加载需求失败：${(e as Error).message}`)
    }
    // Jira 来源徽标：旁路加载，失败不影响主列表
    listExternalLinksByType(projectId, 'REQUIREMENT')
      .then((links) => {
        const m = new Map<string, ExternalLink>()
        links
          .filter((l) => l.externalType === 'ISSUE')
          .forEach((l) => m.set(l.internalId, l))
        setJiraLinks(m)
      })
      .catch(() => setJiraLinks(new Map()))
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
                      {jiraLinks.has(r.id) && (
                        <Tooltip title={`来自 Jira，状态 ${jiraLinks.get(r.id)!.status ?? '-'}`}>
                          <Tag
                            color="blue"
                            style={{ fontSize: 11, lineHeight: '16px', marginInlineEnd: 0 }}
                            onClick={(e) => {
                              e.stopPropagation()
                              const url = jiraLinks.get(r.id)!.externalUrl
                              if (url) window.open(url, '_blank')
                            }}
                          >
                            {jiraLinks.get(r.id)!.externalKey}
                          </Tag>
                        </Tooltip>
                      )}
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
