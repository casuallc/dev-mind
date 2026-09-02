// 需求详情页（/projects/:id/requirements/:rid）：单条需求的研发主线。
// 布局参考 Jira issue 页：左主（流程 + Tabs）右栏（属性卡）。
// 引导式流转——状态仅由 FlowActions 流程按钮隐式推进，Steps 只读；取消走「更多」菜单（confirm）。
// Jira 来源：托管字段本地只读（表单禁用 + 服务端强制），头卡显示 Jira key 链接与远端状态。
import { useCallback, useEffect, useState } from 'react'
import {
  Breadcrumb,
  Button,
  Card,
  Col,
  Descriptions,
  Dropdown,
  Empty,
  Modal,
  Row,
  Space,
  Spin,
  Steps,
  Tabs,
  Tag,
  Timeline,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { DownOutlined, EditOutlined, LockOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import { deleteRequirement, getRequirementOverview, updateRequirementStatus } from '../api'
import FlowActions from '../components/flow/FlowActions'
import DesignsTab from '../components/flow/DesignsTab'
import RelatedRecordsTab from '../components/RelatedRecordsTab'
import RequirementFormModal from '../components/RequirementFormModal'
import WorkItemsTab from '../components/WorkItemsTab'
import { useProject } from '../../projects/hooks/useProject'
import { getCurrentProjectId, setCurrentProject } from '../../../app/currentProjectStore'
import { fmtTime } from '../../../shared/utils/format'
import {
  requirementStatusColor,
  requirementTypeColor,
  priorityColor,
  sourceTagColor,
  SOURCE_LABEL,
  STATUS_FLOW,
  TYPE_LABEL,
} from '../components/requirementMeta'
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
  const isJira = r.source === 'JIRA'
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

  /** Jira 托管属性行的 label 带锁标（本地只读，由同步维护） */
  const managedLabel = (text: string) => (
    <Space size={4}>
      {text}
      {isJira && (
        <Tooltip title="Jira 来源字段由同步维护，本地只读">
          <LockOutlined style={{ fontSize: 11, color: '#1677ff' }} />
        </Tooltip>
      )}
    </Space>
  )

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Breadcrumb
        items={[
          { title: <a onClick={() => navigate('/overview')}>{project?.name ?? projectId}</a> },
          { title: <a onClick={() => navigate('/requirements')}>需求</a> },
          { title: r.code },
        ]}
      />

      <Row gutter={12}>
        <Col xs={24} xl={16}>
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Card size="small">
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Space wrap>
                    <Typography.Text code>{r.code}</Typography.Text>
                    <Typography.Text strong style={{ fontSize: 15 }}>{r.title}</Typography.Text>
                    <Tag color={sourceTagColor(r.source)}>{SOURCE_LABEL[r.source]}</Tag>
                    {isJira && r.externalKey && (
                      <Tag
                        color="blue"
                        style={{ cursor: r.externalUrl ? 'pointer' : 'default' }}
                        onClick={() => r.externalUrl && window.open(r.externalUrl, '_blank')}
                      >
                        {r.externalKey}
                      </Tag>
                    )}
                    {isJira && r.remoteStatus && (
                      <Tooltip title="Jira 远端状态，随同步刷新">
                        <Tag>{r.remoteStatus}</Tag>
                      </Tooltip>
                    )}
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
                {r.description && (
                  <Typography.Paragraph
                    style={{ fontSize: 13, marginBottom: 0, whiteSpace: 'pre-wrap' }}
                  >
                    {r.description}
                  </Typography.Paragraph>
                )}
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
                                {fmtTime(t.time)}
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
          </Space>
        </Col>

        <Col xs={24} xl={8}>
          <Card size="small" title="属性">
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="来源">
                <Space size={6}>
                  <Tag color={sourceTagColor(r.source)}>{SOURCE_LABEL[r.source]}</Tag>
                  {isJira && r.externalKey && (
                    <a onClick={() => r.externalUrl && window.open(r.externalUrl, '_blank')}>
                      {r.externalKey}
                    </a>
                  )}
                </Space>
              </Descriptions.Item>
              {isJira && (
                <Descriptions.Item label="Jira 状态">{r.remoteStatus ?? '-'}</Descriptions.Item>
              )}
              <Descriptions.Item label={managedLabel('优先级')}>
                {r.priority ? <Tag color={priorityColor(r.priority)}>{r.priority}</Tag> : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={managedLabel('经办人')}>{r.assignee ?? '-'}</Descriptions.Item>
              <Descriptions.Item label={managedLabel('报告人')}>{r.reporter ?? '-'}</Descriptions.Item>
              <Descriptions.Item label={managedLabel('标签')}>
                {r.labels?.length
                  ? r.labels.map((l) => <Tag key={l} style={{ fontSize: 11 }}>{l}</Tag>)
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={managedLabel('修复版本')}>
                {r.fixVersions?.length
                  ? r.fixVersions.map((v) => <Tag key={v} style={{ fontSize: 11 }}>{v}</Tag>)
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={managedLabel('截止日期')}>{r.dueDate ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="本地负责人">
                <Tooltip title="平台侧流程负责人，与 Jira 经办人相互独立">
                  <span>{r.ownerId || '-'}</span>
                </Tooltip>
              </Descriptions.Item>
              <Descriptions.Item label="创建">{fmtTime(r.createdAt)}</Descriptions.Item>
              <Descriptions.Item label="更新">{fmtTime(r.updatedAt)}</Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
      </Row>

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
