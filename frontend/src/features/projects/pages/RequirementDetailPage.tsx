// 需求详情页（/projects/:id/requirements/:rid）：单条需求的研发主线。
// 引导式流转——状态仅由 FlowActions 流程按钮隐式推进，Steps 只读；取消走「更多」菜单（confirm）。
import { useCallback, useEffect, useState } from 'react'
import {
  Breadcrumb,
  Button,
  Card,
  Descriptions,
  Dropdown,
  Empty,
  Modal,
  Space,
  Spin,
  Steps,
  Tabs,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd'
import { DownOutlined, EditOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import { deleteRequirement, getRequirementOverview, updateRequirementStatus } from '../api'
import FlowActions from '../components/flow/FlowActions'
import DesignsTab from '../components/flow/DesignsTab'
import RelatedRecordsTab from '../components/RelatedRecordsTab'
import RequirementFormModal from '../components/RequirementFormModal'
import WorkItemsTab from '../components/WorkItemsTab'
import { useProject } from '../hooks/useProject'
import { getCurrentProjectId, setCurrentProject } from '../currentProjectStore'
import { requirementStatusColor, requirementTypeColor, STATUS_FLOW, TYPE_LABEL } from '../components/requirementMeta'
import type { RequirementOverview } from '../types'

export default function RequirementDetailPage() {
  const { id: projectId, rid } = useParams<{ id: string; rid: string }>()
  const navigate = useNavigate()
  const { project } = useProject(projectId)
  const [overview, setOverview] = useState<RequirementOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [editOpen, setEditOpen] = useState(false)

  // URL 自含项目身份：从分享链接进入时把当前项目切到该需求所属项目
  useEffect(() => {
    if (projectId && projectId !== getCurrentProjectId()) {
      setCurrentProject(projectId)
    }
  }, [projectId])

  const reloadOverview = useCallback(async () => {
    if (!projectId || !rid) return
    try {
      setOverview(await getRequirementOverview(projectId, rid))
    } catch (e) {
      message.error(`加载需求主线失败：${(e as Error).message}`)
      setOverview(null)
    } finally {
      setLoading(false)
    }
  }, [projectId, rid])

  useEffect(() => {
    setLoading(true)
    reloadOverview()
  }, [reloadOverview])

  if (loading) {
    return <Card><Spin /></Card>
  }
  if (!overview) {
    return <Card><Empty description="需求不存在或已删除" /></Card>
  }

  const r = overview.requirement
  const flowIndex = STATUS_FLOW.indexOf(r.status)
  const cancellable = r.status !== 'DONE' && r.status !== 'CANCELLED'

  const confirmCancel = () => {
    Modal.confirm({
      centered: true,
      title: '取消需求？',
      content: `「${r.code} ${r.title}」将标记为 CANCELLED，工作单元与关联记录保留。`,
      okText: '取消需求',
      okButtonProps: { danger: true },
      cancelText: '返回',
      onOk: async () => {
        if (!projectId) return
        try {
          await updateRequirementStatus(projectId, r.id, 'CANCELLED')
          await reloadOverview()
          message.success(`${r.code} → CANCELLED`)
        } catch (e) {
          message.error((e as Error).message)
        }
      },
    })
  }

  const confirmDelete = () => {
    Modal.confirm({
      centered: true,
      title: '删除需求？',
      content: `将删除需求「${r.code} ${r.title}」及其工作单元/方案（关联的文档/构建/部署记录保留，仅解除主线）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        if (!projectId) return
        await deleteRequirement(projectId, r.id)
        message.success('已删除')
        navigate('/requirements', { replace: true })
      },
    })
  }

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Breadcrumb
        items={[
          { title: <a onClick={() => navigate('/overview')}>{project?.name ?? projectId}</a> },
          { title: <a onClick={() => navigate('/requirements')}>需求</a> },
          { title: r.code },
        ]}
      />

      <Card size="small">
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
            <Space wrap>
              <Typography.Text code>{r.code}</Typography.Text>
              <Typography.Text strong style={{ fontSize: 15 }}>{r.title}</Typography.Text>
              <Tag color={requirementTypeColor(r.type ?? 'FEATURE')}>{TYPE_LABEL[r.type ?? 'FEATURE']}</Tag>
              <Tag color={requirementStatusColor(r.status)}>{r.status}</Tag>
              {r.status === 'CANCELLED' && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>该需求已取消</Typography.Text>
              )}
            </Space>
            <Space wrap>
              <Button size="small" icon={<EditOutlined />} onClick={() => setEditOpen(true)}>编辑</Button>
              <Button size="small" icon={<ReloadOutlined />} onClick={reloadOverview} />
              <Dropdown
                menu={{
                  items: [
                    { key: 'cancel', label: '取消需求', danger: true, disabled: !cancellable },
                    { key: 'delete', label: '删除', danger: true },
                  ],
                  onClick: ({ key }) => (key === 'cancel' ? confirmCancel() : confirmDelete()),
                }}
              >
                <Button size="small">更多 <DownOutlined /></Button>
              </Dropdown>
            </Space>
          </Space>
          {r.status !== 'CANCELLED' && (
            <Steps
              size="small"
              current={flowIndex}
              items={STATUS_FLOW.map((s) => ({ title: s }))}
              style={{ maxWidth: 820 }}
            />
          )}
          <FlowActions requirement={r} onChanged={reloadOverview} />
          <Descriptions size="small" column={{ xs: 1, sm: 3 }}>
            <Descriptions.Item label="负责人">{r.ownerId || '-'}</Descriptions.Item>
            <Descriptions.Item label="创建">{new Date(r.createdAt).toLocaleString()}</Descriptions.Item>
            {r.description && (
              <Descriptions.Item label="描述（业务目标）" span={3}>{r.description}</Descriptions.Item>
            )}
          </Descriptions>
        </Space>
      </Card>

      <Card size="small">
        <Tabs
          size="small"
          items={[
            {
              key: 'workItems',
              label: `工作单元（${overview.workItems.length}）`,
              children: (
                <WorkItemsTab
                  projectId={r.projectId}
                  requirementId={r.id}
                  workItems={overview.workItems}
                  locked={r.status === 'DONE' || r.status === 'CANCELLED'}
                  onChanged={reloadOverview}
                />
              ),
            },
            {
              key: 'designs',
              label: '方案',
              children: <DesignsTab projectId={r.projectId} requirementId={r.id} />,
            },
            {
              key: 'timeline',
              label: `时间线（${overview.timeline.length}）`,
              children: (
                <Timeline
                  style={{ marginTop: 8 }}
                  items={overview.timeline.slice(0, 50).map((t) => ({
                    key: `${t.type}-${t.refId}-${t.time}`,
                    color: timelineColor(t.type),
                    children: (
                      <Space size={8}>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {new Date(t.time).toLocaleString()}
                        </Typography.Text>
                        <Tag style={{ fontSize: 11 }}>{t.type}</Tag>
                        <span style={{ fontSize: 13 }}>{t.label}</span>
                      </Space>
                    ),
                  }))}
                />
              ),
            },
            {
              key: 'records',
              label: '关联记录',
              children: <RelatedRecordsTab overview={overview} />,
            },
          ]}
        />
      </Card>

      {projectId && (
        <RequirementFormModal
          projectId={projectId}
          editing={r}
          open={editOpen}
          onClose={() => setEditOpen(false)}
          onSaved={() => reloadOverview()}
        />
      )}
    </Space>
  )
}

function timelineColor(type: string): string {
  switch (type) {
    case 'REQUIREMENT': return 'purple'
    case 'WORK_ITEM': return 'geekblue'
    case 'DOC': return 'green'
    case 'SESSION': return 'blue'
    case 'BUILD': return 'orange'
    case 'TEST_RUN': return 'cyan'
    case 'DEPLOYMENT': return 'red'
    case 'RELEASE': return 'magenta'
    case 'ARTIFACT': return 'gold'
    default: return 'gray'
  }
}
