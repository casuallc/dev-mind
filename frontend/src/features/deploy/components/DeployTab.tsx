// CAP-09 部署中心：部署计划配置（步骤/回滚步骤）→ 创建部署单（服务器+构建+环境）→ 历史表格 →
// 详情 Drawer（WS 实时步骤状态 + 日志，执行/确认/回滚）。
import {
  Button,
  Card,
  Input,
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
import {
  createDeployment,
  getDeployConfig,
  listDeployments,
  saveDeployConfig,
} from '../api'
import type { DeployConfig, DeployStatus, DeploymentRecord } from '../types'
import type { BuildRecord } from '../../build/types'
import type { ProjectEnvironment, ProjectServer } from '../../projects/types'
import { listBuilds } from '../../build/api'
import { listEnvironments, listServers } from '../../projects/api'
import { durationMs, fmtTime } from '../../../shared/utils/format'
import { STATUS_COLOR } from '../constants'
import ConfigEditor from './ConfigEditor'
import DeployDetailDrawer from './DeployDetailDrawer'

export default function DeployTab({ id }: { id: string }) {
  const [cfg, setCfg] = useState<DeployConfig | null>(null)
  const [servers, setServers] = useState<ProjectServer[]>([])
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])
  const [builds, setBuilds] = useState<BuildRecord[]>([])
  const [deploys, setDeploys] = useState<DeploymentRecord[]>([])
  const [creating, setCreating] = useState(false)
  const [detail, setDetail] = useState<DeploymentRecord | null>(null)

  // 创建表单
  const [serverId, setServerId] = useState<number | undefined>()
  const [environmentId, setEnvironmentId] = useState<number | undefined>()
  const [buildId, setBuildId] = useState<number | undefined>()
  const [env, setEnv] = useState('test')
  const [confirmRequired, setConfirmRequired] = useState(false)

  const refresh = () => listDeployments(id).then(setDeploys).catch(() => {})

  useEffect(() => {
    getDeployConfig(id).then(setCfg).catch(() => {})
    listServers(id).then(setServers).catch(() => {})
    listEnvironments(id).then(setEnvironments).catch(() => {})
    listBuilds(id).then(setBuilds).catch(() => {})
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const deployCaps = servers.filter((s) => s.enabled && s.capabilities.includes('deploy'))
  const artifactBuilds = builds.filter((b) => b.artifactRef)

  const onConfigChanged = async (cfg: DeployConfig) => {
    try {
      const saved = await saveDeployConfig(id, { steps: cfg.steps, rollbackSteps: cfg.rollbackSteps })
      setCfg(saved)
      message.success('部署计划配置已保存')
    } catch (e) {
      message.error((e as Error).message)
      setCfg(await getDeployConfig(id))
    }
  }

  const onCreate = async () => {
    if (!serverId && !environmentId) {
      message.warning('请选择目标服务器或环境')
      return
    }
    setCreating(true)
    try {
      const d = await createDeployment({
        projectId: id,
        serverId: serverId || undefined,
        environmentId: environmentId || undefined,
        buildId: buildId || undefined,
        env: environmentId ? undefined : env || 'test',
        confirmRequired,
      })
      setDetail(d)
      refresh()
    } catch (e) {
      message.error((e as Error).message)
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
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      <Card size="small" title="部署计划配置">
        {cfg && (
          <ConfigEditor cfg={cfg} onChanged={onConfigChanged} />
        )}
      </Card>

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
          <Select<number>
            style={{ width: 200 }}
            placeholder={
              deployCaps.length
                ? environmentId ? '目标服务器（缺省取环境首台）' : '选择目标服务器'
                : '无可用服务器（需 deploy 能力）'
            }
            value={serverId}
            onChange={setServerId}
            allowClear={!!environmentId}
            options={deployCaps.map((s) => ({ value: s.id, label: `${s.name}（${s.accessType} · ${s.env || '?'}）` }))}
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

      <Card size="small" title="部署历史">
        <Table<DeploymentRecord> rowKey="id" size="small" dataSource={deploys} columns={columns} pagination={{ pageSize: 10 }} />
      </Card>

      <DeployDetailDrawer
        record={detail}
        onClose={() => setDetail(null)}
        onChanged={(d) => {
          setDetail(d)
          refresh()
        }}
      />
    </Space>
  )
}
