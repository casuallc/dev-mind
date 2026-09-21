// 需求详情页（/projects/:id/requirements/:rid）：单条需求的研发主线。
// 布局：头卡（默认尺寸，标题栏更高；extra 集中全部操作：开启 AI 规划/重新开发/验收/编辑/属性/Jira 操作/刷新/更多/返回列表）+ 白底 Tabs 卡。
// 不设阶段引导卡：需求状态由工作单元 rollup 自动派生（全部完结 → ACCEPTANCE），验收按钮直接放头卡 extra。
// CAP-52 入口收敛：流程只有一个起点「开启 AI 规划」（一个会话产出 分析+方案+工作单元，自动接开发会话），
// 与「重新开发」（按已固化清单重起需求级开发会话）；分析/方案 Tab 降为只读展示（要改就整段重跑），
// 阶段跳过/单阶段起会话的入口已随 CAP-52 删除。工作单元仍可人工增删改与行内起会话。
// 属性面板非常驻：点「属性」按钮开右侧 Drawer；需求描述超长时默认收起（渐变遮罩 + 展开/收起，ResizeObserver 跟随图片加载重测）。
// Jira 来源：托管字段本地只读（表单禁用 + 服务端强制），属性面板显示 Jira key 链接与远端状态。
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Dropdown,
  Empty,
  Modal,
  Space,
  Spin,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import {
  ArrowLeftOutlined,
  CheckOutlined,
  CloudUploadOutlined,
  DownOutlined,
  EditOutlined,
  LockOutlined,
  PlayCircleOutlined,
  ProfileOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  UpOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  deleteRequirement,
  flowDev,
  flowPlan,
  getRequirementOverview,
  listDesigns,
  refreshRequirementFromJira,
  updateRequirementStatus,
} from '../api'
import JiraActions from '../components/JiraActions'
import JiraDescription from '../components/JiraDescription'
import JiraPushModal from '../components/JiraPushModal'
import AnalysisTab from '../components/flow/AnalysisTab'
import DesignsTab from '../components/flow/DesignsTab'
import RelatedRecordsTab from '../components/RelatedRecordsTab'
import RequirementFormDrawer from '../components/RequirementFormDrawer'
import TimelineTab from '../components/TimelineTab'
import WorkItemsTab from '../components/WorkItemsTab'
import { ACTIVE_SESSION_STATES, latestFlowSession } from '../components/flow/flowSessions'
import { getCurrentProjectId, setCurrentProject } from '../../../app/currentProjectStore'
import { fmtDuration, fmtTime } from '../../../shared/utils/format'
import { pageRootScrollStyle } from '../../../shared/utils/pageLayout'
import {
  requirementStatusColor,
  requirementTypeColor,
  priorityColor,
  sourceTagColor,
  SOURCE_LABEL,
  STATUS_LABEL,
  TYPE_LABEL,
} from '../components/requirementMeta'
import type { Design, RequirementOverview } from '../types'
import { showError } from '../../../shared/utils/showError'

/** 描述收起高度（超出才显示展开/收起；留 24px 余量避免刚好贴线也出按钮） */
const DESC_COLLAPSED_HEIGHT = 168

/** 超长描述收起容器：默认限高 + 底部渐变遮罩 + 展开/收起；内容不超高则原样渲染无按钮。
 *  用 ResizeObserver 重测（Jira 截图异步加载完成后高度才稳定）。 */
function CollapsibleDescription({ children }: { children: ReactNode }) {
  const [expanded, setExpanded] = useState(false)
  const [overflows, setOverflows] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    const check = () => setOverflows(el.scrollHeight > DESC_COLLAPSED_HEIGHT + 24)
    check()
    const ro = new ResizeObserver(check)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const clipped = overflows && !expanded
  return (
    <div>
      <div
        ref={ref}
        style={{
          position: 'relative',
          maxHeight: clipped ? DESC_COLLAPSED_HEIGHT : undefined,
          overflow: clipped ? 'hidden' : 'visible',
        }}
      >
        {children}
        {clipped && (
          <div
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              height: 48,
              background: 'linear-gradient(rgba(255,255,255,0), #fff)',
              pointerEvents: 'none',
            }}
          />
        )}
      </div>
      {overflows && (
        <Button
          type="link"
          size="small"
          style={{ padding: 0, marginTop: 4 }}
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? <>收起 <UpOutlined /></> : <>展开全部 <DownOutlined /></>}
        </Button>
      )}
    </div>
  )
}

export default function RequirementDetailPage() {
  const { id: projectId, rid } = useParams<{ id: string; rid: string }>()
  const navigate = useNavigate()
  const [overview, setOverview] = useState<RequirementOverview | null>(null)
  const [loading, setLoading] = useState(true)
  const [editOpen, setEditOpen] = useState(false)
  const [propsOpen, setPropsOpen] = useState(false)
  const [pushOpen, setPushOpen] = useState(false)
  const [refreshingJira, setRefreshingJira] = useState(false)
  const [designs, setDesigns] = useState<Design[]>([])
  // CAP-52 流程动作互斥：同一时刻只允许一个在飞（规划 / 开发）
  const [flowBusy, setFlowBusy] = useState<null | 'plan' | 'dev'>(null)
  // Tab 与 URL 同步（?tab=analysis/design/workItems）：流程通知深链直达对应 Tab，刷新/分享不丢位置
  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab = searchParams.get('tab') || 'analysis'
  const setActiveTab = (key: string) => {
    setSearchParams(key === 'analysis' ? {} : { tab: key }, { replace: true })
  }

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
      showError(e, '加载需求主线失败')
      setOverview(null)
    } finally {
      setLoading(false)
    }
  }, [projectId, rid])

  useEffect(() => {
    setLoading(true)
    reloadOverview()
  }, [reloadOverview])

  // 方案列表在页面层取一次：方案设计阶段完成判定（有未废弃方案）用
  const loadDesigns = useCallback(() => {
    if (!projectId || !rid) return
    listDesigns(projectId, rid).then(setDesigns).catch(() => {})
  }, [projectId, rid])

  useEffect(loadDesigns, [loadDesigns])

  // 流程动作/验收触发的刷新：overview 与 designs 一起重取
  const reloadAll = useCallback(() => {
    reloadOverview()
    loadDesigns()
  }, [reloadOverview, loadDesigns])

  if (loading) {
    return <Card><Spin /></Card>
  }
  if (!overview) {
    return <Card><Empty description="需求不存在或已删除" /></Card>
  }

  const r = overview.requirement
  const isJira = r.source === 'JIRA'
  const terminal = r.status === 'DONE' || r.status === 'CANCELLED'
  const cancellable = !terminal
  // CAP-52：流程只有一个起点与一个续跑口——「开启/重新规划」与「重新开发」。已有进行中的流程会话时
  // 两个入口都禁用（服务端另有互斥预检，这里只是别让人白点）。
  const planSession = latestFlowSession(overview.sessions, '[flow:plan]')
  const devSession = latestFlowSession(overview.sessions, '[flow:dev]')
  const flowActive = [planSession, devSession].some(
    (s) => !!s && ACTIVE_SESSION_STATES.includes(s.status),
  )
  // 已有规划产出（分析文档或方案）= 再点是「整段重跑」而非首次规划
  const planned = overview.docs.some((d) => d.kind === 'analysis')
    || designs.some((d) => d.status !== 'DISCARDED')
  const hasWorkItems = overview.workItems.length > 0

  /** 起流程会话：规划/开发共用（成功后刷新 overview + 方案列表，会话状态进「会话」列与 Tab 标签） */
  const startFlow = async (kind: 'plan' | 'dev') => {
    if (!projectId) return
    setFlowBusy(kind)
    try {
      const s = kind === 'plan'
        ? await flowPlan(projectId, r.id)
        : await flowDev(projectId, r.id)
      message.success(kind === 'plan'
        ? '规划会话已启动：一个会话产出分析+方案+工作单元，完成后自动接开发会话'
        : '开发会话已启动：按工作单元清单一次做完')
      navigate(`/sessions?sid=${s.id}`)
    } catch (e) {
      showError(e)
    } finally {
      setFlowBusy(null)
    }
  }

  const confirmPlan = () => {
    if (!planned) {
      startFlow('plan')
      return
    }
    Modal.confirm({
      centered: true,
      title: '重新规划？',
      content: '将重跑整个规划链路（分析 + 方案 + 工作单元），新产出会落成分析/方案文档的新版本；'
        + '已有工作单元不会被删除。',
      okText: '重新规划',
      cancelText: '返回',
      onOk: () => startFlow('plan'),
    })
  }

  const confirmDev = () => {
    Modal.confirm({
      centered: true,
      title: '起开发会话？',
      content: `将按当前 ${overview.workItems.length} 个工作单元的清单起一个开发会话，一次做完整个需求`
        + '（清单里未完成的会置为「进行中」）。',
      okText: '起会话',
      cancelText: '返回',
      onOk: () => startFlow('dev'),
    })
  }

  // 需求翻 DONE（终态）：验收通过（ACCEPTANCE 主按钮）与直接完成（伪需求/无需工作单元，不经 rollup）共用，仅文案不同
  const confirmMarkDone = (mode: 'accept' | 'direct') => {
    const direct = mode === 'direct'
    Modal.confirm({
      centered: true,
      title: direct ? '直接完成需求？' : '验收通过？',
      content: direct
        ? `「${r.code} ${r.title}」将不经过工作单元直接标记为 DONE，适用于伪需求/无需开发处理的条目。`
        : `「${r.code} ${r.title}」将标记为 DONE，工作单元与关联记录保留。`,
      okText: direct ? '直接完成' : '验收通过',
      cancelText: '返回',
      onOk: async () => {
        if (!projectId) return
        try {
          await updateRequirementStatus(projectId, r.id, 'DONE')
          message.success(`${r.code} → DONE`)
          reloadAll()
        } catch (e) {
          showError(e)
        }
      },
    })
  }

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
          showError(e)
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

  /** CAP-47 FR-05：按已关联 issue 手动拉回托管字段（同步配置未覆盖该 issue 时的兜底通道） */
  const refreshFromJira = async () => {
    if (!projectId) return
    setRefreshingJira(true)
    try {
      const res = await refreshRequirementFromJira(projectId, r.id)
      message.success(`已从 Jira 刷新 ${res.externalKey}${res.remoteStatus ? ` → ${res.remoteStatus}` : ''}`)
      await reloadOverview()
    } catch (e) {
      showError(e, '从 Jira 刷新失败')
    } finally {
      setRefreshingJira(false)
    }
  }

  return (
    <Space direction="vertical" size={12} style={{ width: '100%', ...pageRootScrollStyle }}>
      <Card
        title={
          <Space size={8} wrap style={{ whiteSpace: 'normal' }}>
            <Typography.Text code>{r.code}</Typography.Text>
            <Typography.Text strong>{r.title}</Typography.Text>
            <Tag color={requirementTypeColor(r.type ?? 'FEATURE')}>{TYPE_LABEL[r.type ?? 'FEATURE']}</Tag>
            <Tag color={requirementStatusColor(r.status)}>{STATUS_LABEL[r.status]}</Tag>
            {r.status === 'CANCELLED' && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>该需求已取消</Typography.Text>
            )}
          </Space>
        }
        extra={
          <Space size={8} wrap>
            {!terminal && (
              <Tooltip title={flowActive ? '该需求已有进行中的流程会话，等它结束后再起' : undefined}>
                <span>
                  <Button
                    type={r.status === 'ACCEPTANCE' ? 'default' : 'primary'}
                    icon={<ThunderboltOutlined />}
                    loading={flowBusy === 'plan'}
                    disabled={flowActive}
                    onClick={confirmPlan}
                  >
                    {planned ? '重新规划' : '开启 AI 规划'}
                  </Button>
                </span>
              </Tooltip>
            )}
            {!terminal && hasWorkItems && (
              <Tooltip title={flowActive ? '该需求已有进行中的流程会话，等它结束后再起' : undefined}>
                <span>
                  <Button
                    icon={<PlayCircleOutlined />}
                    loading={flowBusy === 'dev'}
                    disabled={flowActive}
                    onClick={confirmDev}
                  >
                    重新开发
                  </Button>
                </span>
              </Tooltip>
            )}
            {r.status === 'ACCEPTANCE' && (
              <Button type="primary" icon={<CheckOutlined />} onClick={() => confirmMarkDone('accept')}>
                验收通过
              </Button>
            )}
            <Button icon={<EditOutlined />} onClick={() => setEditOpen(true)}>编辑</Button>
            <Button icon={<ProfileOutlined />} onClick={() => setPropsOpen(true)}>属性</Button>
            {!isJira && (
              <Tooltip title={terminal ? '需求已完结（DONE/CANCELLED），不可推送' : undefined}>
                <span>
                  <Button
                    icon={<CloudUploadOutlined />}
                    disabled={terminal}
                    onClick={() => setPushOpen(true)}
                  >
                    推送到 Jira
                  </Button>
                </span>
              </Tooltip>
            )}
            {isJira && <JiraActions requirement={r} onChanged={reloadOverview} />}
            <Button icon={<ReloadOutlined />} onClick={reloadOverview}>刷新</Button>
            <Dropdown
              menu={{
                items: [
                  ...(r.status !== 'ACCEPTANCE' ? [
                    { key: 'done', label: '直接完成', icon: <CheckOutlined />, disabled: terminal },
                  ] : []),
                  { key: 'cancel', label: '取消需求', danger: true, disabled: !cancellable },
                  { key: 'delete', label: '删除', danger: true },
                ],
                onClick: ({ key }) => {
                  if (!projectId) return
                  if (key === 'cancel') confirmCancel()
                  else if (key === 'delete') confirmDelete()
                  else if (key === 'done') confirmMarkDone('direct')
                },
              }}
            >
              <Button>更多 <DownOutlined /></Button>
            </Dropdown>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/requirements')}>返回列表</Button>
          </Space>
        }
      >
        {r.description && (
          <CollapsibleDescription>
            {r.source === 'JIRA' ? (
              <JiraDescription description={r.description} pid={r.projectId} rid={r.id} />
            ) : (
              <Typography.Paragraph style={{ fontSize: 13, marginBottom: 0, whiteSpace: 'pre-wrap' }}>
                {r.description}
              </Typography.Paragraph>
            )}
          </CollapsibleDescription>
        )}
      </Card>

      <Card size="small">
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            {
              key: 'analysis',
              label: '需求分析',
              children: (
                <AnalysisTab
                  projectId={r.projectId}
                  requirement={r}
                  overview={overview}
                  onChanged={reloadAll}
                />
              ),
            },
            {
              key: 'design',
              label: '方案设计',
              children: (
                <DesignsTab
                  projectId={r.projectId}
                  requirement={r}
                  onChanged={reloadAll}
                />
              ),
            },
            {
              key: 'workItems',
              label: `工作单元（${overview.workItems.length}）`,
              children: (
                <WorkItemsTab
                  projectId={r.projectId}
                  requirementId={r.id}
                  workItems={overview.workItems}
                  sessions={overview.sessions}
                  locked={terminal}
                  onChanged={reloadOverview}
                />
              ),
            },
            {
              key: 'timeline',
              label: `时间线（${overview.timeline.length}）`,
              children: <TimelineTab items={overview.timeline} />,
            },
            {
              key: 'records',
              label: '关联记录',
              children: <RelatedRecordsTab overview={overview} />,
            },
          ]}
        />
      </Card>

      <Drawer
        title={`属性 · ${r.code}`}
        width={520}
        open={propsOpen}
        onClose={() => setPropsOpen(false)}
      >
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
            <Descriptions.Item label="Jira 状态">
              <Space size={8}>
                <span>{r.remoteStatus ?? '-'}</span>
                <Tooltip title="按关联 issue 拉回托管字段（JQL 同步未覆盖该 issue 时的兜底通道）">
                  <Button
                    size="small"
                    icon={<ReloadOutlined />}
                    loading={refreshingJira}
                    onClick={refreshFromJira}
                  >
                    从 Jira 刷新
                  </Button>
                </Tooltip>
              </Space>
            </Descriptions.Item>
          )}
          <Descriptions.Item label={
            <Tooltip title="需求下所有 agent 会话时长汇总（活跃会话算到当前）">
              <span>AI 执行耗时</span>
            </Tooltip>
          }>
            {fmtDuration(r.agentSeconds)}
          </Descriptions.Item>
          {isJira && (
            <Descriptions.Item label={managedLabel('预估工时')}>
              {fmtDuration(r.estimatedSeconds)}
            </Descriptions.Item>
          )}
          {isJira && (
            <Descriptions.Item label={managedLabel('已用工时')}>
              {fmtDuration(r.spentSeconds)}
            </Descriptions.Item>
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
      </Drawer>

      {projectId && (
        <RequirementFormDrawer
          projectId={projectId}
          editing={r}
          open={editOpen}
          onClose={() => setEditOpen(false)}
          onSaved={() => reloadOverview()}
        />
      )}

      {/* CAP-47：仅自建需求渲染（推送成功后 source 翻 JIRA，入口自然消失、改由「Jira 操作」接管） */}
      {!isJira && (
        <JiraPushModal
          requirement={r}
          open={pushOpen}
          onClose={() => setPushOpen(false)}
          onPushed={reloadAll}
        />
      )}
    </Space>
  )
}
