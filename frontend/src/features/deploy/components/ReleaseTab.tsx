// CAP-11 发版执行器：发版配置（Nexus/模板/版本规则/执行方式）→ 新建发版（构建产物+版本）→
// 历史表格 → 详情 Drawer（WS 实时日志 + 执行/回滚）。
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Drawer,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { ReloadOutlined } from '@ant-design/icons'
import {
  createRelease,
  deleteRelease,
  executeRelease,
  getRelease,
  getReleaseConfig,
  getReleaseLogs,
  listReleases,
  rollbackRelease,
  saveReleaseConfig,
} from '../api'
import type { CreateReleaseInput, ReleaseConfig, ReleaseRecord, ReleaseStatus } from '../types'
import { fmtTime } from '../../../shared/utils/format'

const STATUS_COLOR: Record<ReleaseStatus, string> = {
  PLANNED: 'blue',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  ROLLED_BACK: 'orange',
}

interface ConfigValues {
  nexusRepo?: string
  scriptTemplateRef?: string
  versionRule?: string
  executor?: string
  remoteServerId?: number
}

interface CreateValues {
  buildId?: number
  version?: string
  executor?: string
  serverId?: number
  force?: boolean
}

export default function ReleaseTab({ id, readOnly }: {
  id: string
  /** 只读模式（工作台 /settings 对 VIEWER）：只看配置与历史，不可保存/新建/执行/回滚/删除 */
  readOnly?: boolean
}) {
  const [config, setConfig] = useState<ReleaseConfig | null>(null)
  const [rows, setRows] = useState<ReleaseRecord[]>([])
  const [detail, setDetail] = useState<ReleaseRecord | null>(null)
  const [configForm] = Form.useForm<ConfigValues>()
  const [createForm] = Form.useForm<CreateValues>()
  const [configBusy, setConfigBusy] = useState(false)
  const [createBusy, setCreateBusy] = useState(false)

  const load = () => {
    listReleases(id).then(setRows).catch(() => setRows([]))
    getReleaseConfig(id).then(setConfig).catch(() => setConfig(null))
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  useEffect(() => {
    configForm.setFieldsValue({
      nexusRepo: config?.nexusRepo ?? '',
      scriptTemplateRef: config?.scriptTemplateRef ?? '',
      versionRule: config?.versionRule ?? '',
      executor: config?.executor ?? 'LOCAL',
      remoteServerId: config?.remoteServerId,
    })
  }, [config, configForm])

  const onSaveConfig = async (v: ConfigValues) => {
    setConfigBusy(true)
    try {
      const c = await saveReleaseConfig(id, v)
      setConfig(c)
      message.success('发版配置已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setConfigBusy(false)
    }
  }

  const onCreate = async (v: CreateValues) => {
    setCreateBusy(true)
    try {
      const input: CreateReleaseInput = {
        projectId: id,
        buildId: v.buildId,
        version: v.version,
        executor: v.executor,
        serverId: v.serverId,
        force: v.force,
      }
      const r = await createRelease(input)
      const running = await executeRelease(r.id)
      setDetail(running)
      message.success(`发版 v${r.version} 已开始执行`)
      load()
    } catch (e) {
      message.error(`创建失败：${(e as Error).message}`)
    } finally {
      setCreateBusy(false)
    }
  }

  const columns: ColumnsType<ReleaseRecord> = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    {
      title: '版本', dataIndex: 'version', width: 120,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>,
    },
    {
      title: '状态', dataIndex: 'status', width: 110,
      render: (v: ReleaseStatus) => <Tag color={STATUS_COLOR[v]}>{v}</Tag>,
    },
    { title: '执行方式', dataIndex: 'executor', width: 90, render: (v: string) => <Tag color="geekblue">{v}</Tag> },
    { title: 'tag', dataIndex: 'tagName', width: 120, render: (v: string) => v || '-' },
    { title: 'Nexus 引用', dataIndex: 'nexusRef', width: 160, ellipsis: true, render: (v: string) => v || '-' },
    { title: '构建', dataIndex: 'buildId', width: 70, render: (v: number) => (v ? `#${v}` : '-') },
    {
      title: '创建时间', dataIndex: 'createdAt', width: 170,
      render: (v: string) => fmtTime(v),
    },
    {
      title: '操作', key: 'ops', width: 90,
      render: (_: unknown, r) => (
        <Button size="small" onClick={() => setDetail(r)}>管理</Button>
      ),
    },
  ]

  return (
    <Card
      title="发版"
      extra={
        <Button icon={<ReloadOutlined />} onClick={load}>
          刷新
        </Button>
      }
    >
      <Typography.Paragraph type="secondary">
        发版执行器（CAP-11）：维护发版配置（Nexus 仓库/推送模板/版本规则/执行方式），创建发版并在历史中跟踪执行与回滚；点「管理」开 Drawer 看实时日志并操作。
      </Typography.Paragraph>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card size="small" title="发版配置（CAP-11）">
        <Form form={configForm} layout="inline" onFinish={onSaveConfig} style={{ rowGap: 8, columnGap: 8 }}>
          <Form.Item label="Nexus 仓库" name="nexusRepo">
            <Input placeholder="snapshots / releases" style={{ width: 150 }} />
          </Form.Item>
          <Form.Item label="推送模板 code" name="scriptTemplateRef" extra="server-adapter 白名单模板 code">
            <Input placeholder="如 nexus_push" style={{ width: 150 }} />
          </Form.Item>
          <Form.Item label="版本规则" name="versionRule" extra="可递增 semver（如 1.0.0）">
            <Input placeholder="1.0.0" style={{ width: 120 }} />
          </Form.Item>
          <Form.Item label="执行方式" name="executor">
            <Select
              style={{ width: 110 }}
              options={[{ value: 'LOCAL', label: 'LOCAL' }, { value: 'REMOTE', label: 'REMOTE' }]}
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(a, b) => a.executor !== b.executor}>
            {({ getFieldValue }) =>
              getFieldValue('executor') === 'REMOTE' ? (
                <Form.Item label="远程服务器 id" name="remoteServerId">
                  <InputNumber min={1} style={{ width: 110 }} />
                </Form.Item>
              ) : null
            }
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={configBusy} disabled={readOnly}>保存配置</Button>
          </Form.Item>
        </Form>
      </Card>

      {!readOnly && (
        <Card size="small" title="新建发版">
        <Form form={createForm} layout="inline" onFinish={onCreate} style={{ rowGap: 8, columnGap: 8 }}>
          <Form.Item label="构建 id" name="buildId" extra="产物来源（可选，留空则模板自带制品）">
            <InputNumber min={1} style={{ width: 110 }} />
          </Form.Item>
          <Form.Item label="版本" name="version" extra="留空按版本规则自动 +1">
            <Input placeholder="如 1.0.1" style={{ width: 110 }} />
          </Form.Item>
          <Form.Item label="执行方式" name="executor" extra="缺省取配置">
            <Select
              allowClear
              placeholder="取配置"
              style={{ width: 110 }}
              options={[{ value: 'LOCAL', label: 'LOCAL' }, { value: 'REMOTE', label: 'REMOTE' }]}
            />
          </Form.Item>
          <Form.Item label="服务器 id" name="serverId" extra="REMOTE 时必填">
            <InputNumber min={1} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item name="force" valuePropName="checked">
            <Checkbox>force（允许同版本重发）</Checkbox>
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={createBusy}>创建并执行</Button>
          </Form.Item>
        </Form>
        </Card>
      )}

      <Card size="small" title="发版历史">
        <Table rowKey="id" columns={columns} dataSource={rows} pagination={false}
          locale={{
            emptyText: readOnly
              ? '暂无发版记录'
              : '暂无发版记录。先在上方保存发版配置，再在「新建发版」创建并执行第一个发版。',
          }}
          scroll={{ x: 1000 }} />
      </Card>

      <DetailDrawer record={detail} onClose={() => setDetail(null)} onChanged={load} readOnly={readOnly} />
      </Space>
    </Card>
  )
}

// ---------------- 发版详情 Drawer（WS 实时） ----------------

function DetailDrawer({ record, onClose, onChanged, readOnly }: {
  record: ReleaseRecord | null
  onClose: () => void
  onChanged: () => void
  readOnly?: boolean
}) {
  const [d, setD] = useState<ReleaseRecord | null>(record)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setD(record)
    setText('')
    if (!record) return
    getReleaseLogs(record.id).then(setText).catch(() => setText(''))

    const active = record.status === 'PLANNED' || record.status === 'RUNNING'
    if (active) {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      const ws = new WebSocket(`${proto}://${location.host}/ws/releases/${record.id}/stream`)
      ws.onopen = () => undefined
      ws.onmessage = (msg) => {
        try {
          const f = JSON.parse(msg.data)
          if (f.type === 'snapshot') {
            setD((cur) => (cur ? { ...cur, status: f.status, version: f.version, tagName: f.tagName, nexusRef: f.nexusRef } : cur))
          } else if (f.type === 'log') {
            setText((t) => (t ? `${t}\n${f.line}` : f.line))
          } else if (f.type === 'done') {
            setD((cur) => (cur ? { ...cur, status: f.status } : cur))
            ws.close()
          }
        } catch {
          /* 忽略坏帧 */
        }
      }
      ws.onclose = () => undefined
      return () => ws.close()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record])

  // 终态轮询刷新（日志/状态落库后同步）
  useEffect(() => {
    if (!record) return
    const timer = setInterval(() => {
      getRelease(record.id)
        .then((latest) => {
          setD((cur) => {
            if (JSON.stringify(latest) !== JSON.stringify(cur)) {
              onChanged()
              return latest
            }
            return cur
          })
        })
        .catch(() => {})
    }, 4000)
    return () => clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [record])

  const act = async (fn: () => Promise<ReleaseRecord>, ok: string) => {
    if (!d) return
    setBusy(true)
    try {
      const nd = await fn()
      setD(nd)
      onChanged()
      message.success(ok)
    } catch (e) {
      message.error((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer title={d ? `发版 v${d.version} #${d.id}` : '发版详情'} width={680} open={!!d} onClose={onClose}>
      {d && (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space wrap>
            <Tag color={STATUS_COLOR[d.status]}>{d.status}</Tag>
            <Tag color="geekblue">{d.executor}</Tag>
            {d.tagName && <Typography.Text code>{d.tagName}</Typography.Text>}
            {d.nexusRef && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{d.nexusRef}</Typography.Text>}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {fmtTime(d.createdAt)}
            </Typography.Text>
            {d.rollbackOf && <Tag color="orange">回滚自 #{d.rollbackOf}</Tag>}
          </Space>
          {d.errorSummary && <Alert type="error" showIcon message="失败原因" description={d.errorSummary} />}
          <Space>
            {!readOnly && d.status === 'PLANNED' && (
              <Button type="primary" loading={busy} onClick={() => act(() => executeRelease(d.id), '已开始执行')}>执行发版</Button>
            )}
            {!readOnly && d.status !== 'RUNNING' && d.status !== 'ROLLED_BACK' && (
              <Popconfirm title="回滚该发版？将删除 tag 并移除制品引用" onConfirm={() => act(() => rollbackRelease(d.id), '已回滚')}>
                <Button loading={busy}>回滚</Button>
              </Popconfirm>
            )}
            {!readOnly && (
              <Popconfirm
                title="删除该发版记录？"
                onConfirm={async () => {
                  await deleteRelease(d.id)
                  message.success('已删除')
                  onChanged()
                  onClose()
                }}
              >
                <Button danger>删除</Button>
              </Popconfirm>
            )}
          </Space>
          <div style={{ maxHeight: 420, overflow: 'auto', background: '#111', color: '#cfc', padding: 8, borderRadius: 4, fontFamily: 'monospace', fontSize: 12, width: '100%' }}>
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{text || '（暂无日志）'}</pre>
          </div>
        </Space>
      )}
    </Drawer>
  )
}
