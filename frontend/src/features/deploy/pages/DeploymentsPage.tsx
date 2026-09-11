// 部署记录页（/deployments）：当前项目的部署历史与部署计划配置（Segmented 两视图，配置不常改故独立成视图）。
// CAP-09 部署中心：部署计划配置（步骤/回滚步骤）→ 创建部署单（节点+构建+环境）→ 历史表格（服务端分页）→
// 详情 Drawer（WS 实时步骤状态 + 日志，执行/确认/回滚）。
// CAP-36：部署步骤经 exec 帧下发 runner 节点执行（渲染后的脚本串），SSH/HTTP 直连已下线。
import {
  Button,
  Card,
  Input,
  Segmented,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { ReloadOutlined } from '@ant-design/icons'
import {
  createDeployment,
  getDeployConfig,
  listDeployments,
  saveDeployConfig,
} from '../api'
import type { DeployConfig, DeployStatus, DeploymentRecord } from '../types'
import type { BuildRecord } from '../../build/types'
import type { ProjectEnvironment } from '../../projects/types'
import type { AgentNode } from '../../agent/types'
import { listBuilds } from '../../build/api'
import { listEnvironments } from '../../projects/api'
import { listAgentNodes } from '../../agent/api'
import { useCurrentProjectId } from '../../../app/useCurrentProject'
import { durationMs, fmtTime } from '../../../shared/utils/format'
import { STATUS_COLOR } from '../constants'
import ConfigEditor from '../components/ConfigEditor'
import DeployDetailDrawer from '../components/DeployDetailDrawer'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

export default function DeploymentsPage() {
  const projectId = useCurrentProjectId()
  if (!projectId) return null // ProjectContextGate 已保证非空，这里只为过 TS
  return <DeployCenter id={projectId} />
}

function DeployCenter({ id }: { id: string }) {
  const [view, setView] = useState<'history' | 'config'>('history')
  const [cfg, setCfg] = useState<DeployConfig | null>(null)
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])
  const [builds, setBuilds] = useState<BuildRecord[]>([])
  const [deploys, setDeploys] = useState<DeploymentRecord[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [loading, setLoading] = useState(false)
  const [creating, setCreating] = useState(false)
  const [detail, setDetail] = useState<DeploymentRecord | null>(null)

  // 创建表单
  const [agentNodeId, setAgentNodeId] = useState<string | undefined>()
  const [environmentId, setEnvironmentId] = useState<number | undefined>()
  const [buildId, setBuildId] = useState<number | undefined>()
  const [env, setEnv] = useState('test')
  const [confirmRequired, setConfirmRequired] = useState(false)

  const loadHistory = (p = page, s = size) => {
    setLoading(true)
    listDeployments(id, p, s)
      .then((r) => {
        setDeploys(r.items)
        setTotal(r.total)
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }

  const load = () => {
    getDeployConfig(id).then(setCfg).catch(() => {})
    listAgentNodes().then(setNodes).catch(() => {})
    listEnvironments(id).then(setEnvironments).catch(() => {})
    listBuilds(id).then(setBuilds).catch(() => {})
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  useEffect(() => {
    loadHistory()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, page, size])

  const artifactBuilds = builds.filter((b) => b.artifactRef)

  const onConfigChanged = async (cfg: DeployConfig) => {
    try {
      const saved = await saveDeployConfig(id, { steps: cfg.steps, rollbackSteps: cfg.rollbackSteps })
      setCfg(saved)
      message.success('部署计划配置已保存')
    } catch (e) {
      showError(e)
      setCfg(await getDeployConfig(id))
    }
  }

  const onCreate = async () => {
    setCreating(true)
    try {
      const d = await createDeployment({
        projectId: id,
        agentNodeId: agentNodeId || undefined,
        environmentId: environmentId || undefined,
        buildId: buildId || undefined,
        env: environmentId ? undefined : env || 'test',
        confirmRequired,
      })
      setDetail(d)
      setPage(0) // 新记录在最前，回第一页看
      if (page === 0) loadHistory(0)
    } catch (e) {
      showError(e)
    } finally {
      setCreating(false)
    }
  }

  const columns: ColumnsType<DeploymentRecord> = [
    {
      title: 'ID', dataIndex: 'id', width: 70,
      render: (v: number) => `#${v}`,
    },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (v: DeployStatus) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
    },
    {
      title: '构建', dataIndex: 'buildId', width: 80,
      render: (v: number | null) => (v ? `#${v}` : <span>-</span>),
    },
    {
      title: '环境', dataIndex: 'env', width: 90,
      render: (v: string) => <Tag color={v === 'prod' ? 'red' : v === 'staging' ? 'orange' : 'blue'}>{v || '-'}</Tag>,
    },
    {
      title: '执行节点', dataIndex: 'agentNodeId', width: 110,
      render: (v: string) => {
        const n = nodes.find((x) => String(x.id) === v)
        return n ? n.name : v || '-'
      },
    },
    {
      title: '备份', dataIndex: 'backupRef', ellipsis: true,
      render: (v: string | null) => (v ? <Typography.Text copyable code style={{ fontSize: 12 }}>{v}</Typography.Text> : <span>-</span>),
    },
    {
      title: '回滚自', dataIndex: 'rollbackOf', width: 90,
      render: (v: number | null) => (v ? `#${v}` : <span>-</span>),
    },
    {
      title: '创建时间', dataIndex: 'createdAt', width: 160,
      render: (v: string) => fmtTime(v),
    },
    {
      title: '耗时', key: 'dur', width: 100,
      render: (_, r) => durationMs(r.startedAt, r.finishedAt),
    },
    {
      title: '', key: 'act', width: 80,
      render: (_, r) => <Button size="small" onClick={() => setDetail(r)}>详情</Button>,
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title={
        <Space size={12}>
          <span>部署记录</span>
          <Segmented
            value={view}
            onChange={(v) => setView(v as 'history' | 'config')}
            options={[
              { value: 'history', label: '部署历史' },
              { value: 'config', label: '部署配置' },
            ]}
          />
        </Space>
      }
      extra={
        <Button icon={<ReloadOutlined />} onClick={() => { load(); loadHistory() }}>
          刷新
        </Button>
      }
    >
      <Typography.Paragraph type="secondary">
        部署中心（CAP-09）：创建部署单并在历史中跟踪执行（点「详情」开 Drawer 实时看步骤与日志）；部署计划（步骤/回滚步骤）在「部署配置」视图维护，一般不常改。
      </Typography.Paragraph>

      {view === 'history' ? (
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          <Card size="small" title="创建部署">
            <Space wrap>
              <Select<number>
                style={{ width: 180 }}
                placeholder="选择环境（可选）"
                value={environmentId}
                onChange={setEnvironmentId}
                allowClear
                options={environments.map((e) => ({ value: e.id, label: `${e.name}${e.description ? ` · ${e.description}` : ''}` }))}
              />
              <Select<string>
                style={{ width: 200 }}
                placeholder={
                  nodes.length
                    ? environmentId ? '执行节点（缺省取环境首节点）' : '执行节点（缺省走默认路由）'
                    : '无可用节点（先到后台「Agent 节点」登记）'
                }
                value={agentNodeId}
                onChange={setAgentNodeId}
                allowClear
                options={nodes.map((n) => ({
                  value: String(n.id),
                  label: `${n.name}${n.isDefault ? ' · 平台默认' : ''}${n.status !== 'ONLINE' ? '（离线）' : ''}`,
                }))}
              />
              <Select<number>
                style={{ width: 220 }}
                placeholder={artifactBuilds.length ? '选择构建（产物）' : '无已登记产物的构建'}
                value={buildId}
                onChange={setBuildId}
                allowClear
                options={artifactBuilds.map((b) => ({ value: b.id, label: `#${b.id} · ${b.artifactRef}` }))}
              />
              {environmentId == null && (
                <Input placeholder="环境" value={env} onChange={(e) => setEnv(e.target.value)} style={{ width: 120 }} />
              )}
              <Space size={4}>
                <span style={{ fontSize: 12 }}>需确认</span>
                <Switch checked={confirmRequired} onChange={setConfirmRequired} size="small" />
              </Space>
              <Button type="primary" loading={creating} onClick={onCreate}>
                创建部署
              </Button>
            </Space>
            <div style={{ marginTop: 8, fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>
              创建后进入待执行（PLANNED），计划在执行前可见；同构建重复部署会被识别（需 force 才可重建）。
            </div>
          </Card>

          <Table<DeploymentRecord>
            rowKey="id"
            loading={loading}
            dataSource={deploys}
            columns={columns}
            pagination={{
              current: page + 1,
              pageSize: size,
              total,
              showSizeChanger: true,
              showTotal: (t) => `共 ${t} 条`,
              onChange: (p, s) => {
                setPage(s !== size ? 0 : p - 1)
                setSize(s)
              },
            }}
            locale={{ emptyText: '暂无部署记录。在上方「创建部署」选择环境/节点与构建，发起第一个部署。' }}
          />
        </Space>
      ) : (
        cfg && <ConfigEditor cfg={cfg} onChanged={onConfigChanged} />
      )}

      <DeployDetailDrawer
        record={detail}
        onClose={() => setDetail(null)}
        onChanged={(d) => {
          setDetail(d)
          loadHistory()
        }}
      />
    </Card>
  )
}
