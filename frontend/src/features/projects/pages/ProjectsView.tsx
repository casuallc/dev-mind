// CAP-02 项目列表视图（项目管理聚合页的「项目」tab）：表格 + 新建/编辑（ProjectFormDrawer）+ 居中确认删除。
// 外壳 Card / extra 按钮在 AdminProjectsHub（refreshTick/createTick/onboardTick 驱动）。
import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Input, Modal, Select, Space, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { deleteProject, listProjects } from '../api'
import { onboardProject } from '../../open-api/api'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import ProjectFormDrawer from '../components/ProjectFormDrawer'
import { CLONE_STATUS_COLOR } from '../components/CloneLogDrawer'
import type { Project } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'
import { LIST_PAGINATION } from '../../../shared/utils/table'

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: 'ACTIVE', color: 'green' },
  { value: 'ARCHIVED', label: 'ARCHIVED', color: 'default' },
]

export default function ProjectsView({
  refreshTick = 0,
  createTick = 0,
  onboardTick = 0,
}: {
  refreshTick?: number
  createTick?: number
  onboardTick?: number
}) {
  const navigate = useNavigate()
  const [projects, setProjects] = useState<Project[]>([])
  const [agentNodes, setAgentNodes] = useState<AgentNode[]>([])
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState('ALL')
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<Project | null>(null)
  // CAP-20 AI 智能接入
  const [onboardOpen, setOnboardOpen] = useState(false)
  const [onboardDesc, setOnboardDesc] = useState('')
  const [onboarding, setOnboarding] = useState(false)

  const load = useCallback(async (st?: string) => {
    setLoading(true)
    try {
      // kind=ALL：后台管理需看到 WORKLOG 空间行（亲和节点等信息），缺省过滤只面向普通用户视图
      setProjects(await listProjects(st ?? status, 'ALL'))
    } catch (e) {
      showError(e, '加载项目失败')
    } finally {
      setLoading(false)
    }
  }, [status])

  useEffect(() => {
    listAgentNodes()
      .then(setAgentNodes)
      .catch(() => setAgentNodes([]))
  }, [])

  useEffect(() => {
    load()
  }, [load, refreshTick])

  // 外壳 extra「新建项目」经 createTick 触发；挂载首帧不触发
  const firstCreate = useRef(true)
  useEffect(() => {
    if (firstCreate.current) { firstCreate.current = false; return }
    if (createTick > 0) openEdit(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [createTick])

  // 外壳 extra「AI 智能接入」经 onboardTick 触发；挂载首帧不触发
  const firstOnboard = useRef(true)
  useEffect(() => {
    if (firstOnboard.current) { firstOnboard.current = false; return }
    if (onboardTick > 0) setOnboardOpen(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onboardTick])

  const openEdit = (p: Project | null) => {
    setEditing(p)
    setEditOpen(true)
  }

  // CAP-20：提交描述 → 起全自动接入会话 → 跳会话页实时观看
  const submitOnboard = async () => {
    if (!onboardDesc.trim()) {
      message.warning('请先描述项目情况')
      return
    }
    setOnboarding(true)
    try {
      const { sessionId } = await onboardProject(onboardDesc.trim())
      message.success('接入会话已启动')
      setOnboardOpen(false)
      setOnboardDesc('')
      navigate(`/sessions?sid=${sessionId}`)
    } catch (e) {
      showError(e, '发起失败')
    } finally {
      setOnboarding(false)
    }
  }

  const confirmDelete = (p: Project) => {
    Modal.confirm({
      centered: true,
      title: '删除项目？',
      content: `将删除项目「${p.name}」及其环境/构建/发版/锁配置（不影响仓库本身）。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteProject(p.id)
          message.success('已删除')
          load()
        } catch (e) {
          showError(e, '删除失败')
        }
      },
    })
  }

  const columns: ColumnsType<Project> = [
    {
      title: '名称',
      dataIndex: 'name',
      width: 180,
      render: (n: string, r) => (
        <Space size={4}>
          <Button type="link" style={{ padding: 0 }} onClick={() => navigate(`/admin/projects/${r.id}`)}>
            {n}
          </Button>
          {/* CAP-23：克隆项目的主库状态徽标（镜像自 project_repos） */}
          {r.sourceType === 'CLONE' && r.cloneStatus && (
            <Tag color={CLONE_STATUS_COLOR[r.cloneStatus]}>{r.cloneStatus}</Tag>
          )}
          {/* CAP-41：工作日志空间徽标（WORKLOG 特殊项目） */}
          {r.kind === 'WORKLOG' && <Tag color="purple">日志</Tag>}
        </Space>
      ),
    },
    { title: 'ID', dataIndex: 'id', width: 110, render: (id: string) => <Typography.Text code>{id}</Typography.Text> },
    {
      title: '仓库路径',
      dataIndex: 'path',
      ellipsis: true,
      render: (p: string) => <Typography.Text style={{ fontSize: 12 }}>{p}</Typography.Text>,
    },
    { title: '分支', dataIndex: 'defaultBranch', width: 110, render: (b?: string) => b || '-' },
    {
      title: '执行节点',
      dataIndex: 'agentNodeId',
      width: 110,
      render: (v?: string) =>
        v ? (
          <Tag color="purple">{agentNodes.find((n) => String(n.id) === v)?.name ?? `节点${v}`}</Tag>
        ) : (
          '跟随平台默认'
        ),
    },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 220,
      render: (tags: string[]) =>
        tags?.length ? tags.map((t) => <Tag key={t} style={{ marginBottom: 2 }}>{t}</Tag>) : '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: string) => {
        const o = STATUS_OPTIONS.find((x) => x.value === s)
        return <Tag color={o?.color ?? 'default'}>{s}</Tag>
      },
    },
    {
      title: '摘要',
      dataIndex: 'summaryGeneratedAt',
      width: 140,
      render: (t?: string) => fmtTime(t),
    },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => navigate(`/admin/projects/${r.id}`)}>
            设置
          </Button>
          <Button size="small" onClick={() => openEdit(r)}>
            编辑
          </Button>
          {/* CAP-41：WORKLOG 项目禁止删除（防孤儿化 runner 侧日志数据），后端同样 409 */}
          {r.kind !== 'WORKLOG' && (
            <Button size="small" danger onClick={() => confirmDelete(r)}>
              删除
            </Button>
          )}
        </Space>
      ),
    },
  ]

  return (
    <>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, flex: 1, minHeight: 0 }}>
        <div>
          <Select
            value={status}
            onChange={(v) => {
              setStatus(v)
              load(v)
            }}
            options={[{ value: 'ALL', label: '全部' }, ...STATUS_OPTIONS.map((o) => ({ value: o.value, label: o.label }))]}
            style={{ width: 130 }}
          />
        </div>
        <Typography.Paragraph type="secondary" style={{ margin: 0 }}>
          注册本地 git 仓库作为项目，作为会话/构建/发版/测试能力的挂载点。worktree 约定在
          <Typography.Text code>path/.devmind/worktrees/&lt;sessionId&gt;</Typography.Text>。
        </Typography.Paragraph>
        <FitTable
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={projects}
          pagination={LIST_PAGINATION}
          locale={{ emptyText: '暂无项目。点击「新建项目」注册一个本地 git 仓库。' }}
        />
      </div>

      <ProjectFormDrawer
        open={editOpen}
        project={editing}
        onCancel={() => setEditOpen(false)}
        onSaved={() => {
          setEditOpen(false)
          load()
        }}
      />

      <Modal
        title="AI 智能接入"
        open={onboardOpen}
        onCancel={() => setOnboardOpen(false)}
        onOk={submitOnboard}
        okText="开始接入"
        confirmLoading={onboarding}
        width={640}
        destroyOnHidden
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="描述项目的仓库、构建脚本、部署方式等信息，平台将启动一个全自动会话，通过开放 API 把配置（项目/模板/环境/构建/部署计划）写入并触发一次构建验证。"
        />
        <Input.TextArea
          rows={8}
          value={onboardDesc}
          onChange={(e) => setOnboardDesc(e.target.value)}
          placeholder={'例如：\n项目仓库 D:\\apusic\\ctyunmanager，构建脚本 build.ps1\n部署到 runner 节点 local-dev，部署目录 /apusic/ctyun\n发布脚本 push.sh'}
        />
      </Modal>
    </>
  )
}
