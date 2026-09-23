// CAP-57 分类服务 · 服务实例管控：laya 边车实例的登记、起停（WS proc 帧下发 runner 节点）、健康快照。
// 布局遵循 docs/core/前端内容区布局约定.md：Card + FitTable + 行内「管理」Drawer 承载成套操作。
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  message,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import {
  CaretRightOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
  PlusOutlined,
  PoweroffOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons'
import FitTable from '../../../shared/components/FitTable'
import { fmtBytes, fmtTime } from '../../../shared/utils/format'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import LayaViewSwitch from '../../laya/components/LayaViewSwitch'
import { LIST_PAGINATION } from '../../../shared/utils/table'
import { listAgentNodes } from '../../agent/api'
import type { AgentNode } from '../../agent/types'
import {
  createClassifyInstance,
  deleteClassifyInstance,
  getClassifyInstanceStatus,
  listClassifyInstances,
  listClassifyPackages,
  operateClassifyInstance,
  updateClassifyInstance,
} from '../api'
import type { ClassifyInstance, ClassifyInstanceStatus, ClassifyPackage } from '../types'

const STATUS_META: Record<string, { color: string; label: string }> = {
  STOPPED: { color: 'default', label: '已停止' },
  STARTING: { color: 'processing', label: '启动中' },
  RUNNING: { color: 'success', label: '运行中' },
  UNHEALTHY: { color: 'error', label: '不健康' },
}

function StatusTag({ status }: { status: ClassifyInstanceStatus }) {
  const meta = STATUS_META[status] ?? { color: 'default', label: status }
  return (
    <Tag color={meta.color} icon={status === 'STARTING' ? <LoadingOutlined /> : undefined}>
      {meta.label}
    </Tag>
  )
}

interface InstanceFormValues {
  name: string
  agentNodeId: number
  port: number
  baseUrl: string
  appPackageId?: number | null
  pythonBin?: string
  env?: { key: string; value: string }[]
  commandOverride?: string | null
}

export default function ClassifyInstancesPage() {
  const [rows, setRows] = useState<ClassifyInstance[]>([])
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [appPackages, setAppPackages] = useState<ClassifyPackage[]>([])
  const [loading, setLoading] = useState(false)
  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<ClassifyInstance | null>(null)
  const [saving, setSaving] = useState(false)
  const [managing, setManaging] = useState<ClassifyInstance | null>(null)
  const [operating, setOperating] = useState<string | null>(null)
  const [form] = Form.useForm<InstanceFormValues>()

  const nodeName = useMemo(() => {
    const m = new Map<number, string>()
    nodes.forEach((n) => m.set(n.id, n.name))
    return m
  }, [nodes])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [instances, nodeList, pkgs] = await Promise.all([
        listClassifyInstances(),
        listAgentNodes(),
        listClassifyPackages('SIDECAR_APP'),
      ])
      setRows(instances)
      setNodes(nodeList)
      setAppPackages(pkgs)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ port: 8377, pythonBin: 'venv/bin/python', env: [] })
    setEditorOpen(true)
  }

  const openEdit = (row: ClassifyInstance) => {
    setEditing(row)
    form.setFieldsValue({
      name: row.name,
      agentNodeId: row.agentNodeId,
      port: row.port,
      baseUrl: row.baseUrl,
      appPackageId: row.appPackageId ?? null,
      pythonBin: row.pythonBin,
      env: Object.entries(row.env ?? {}).map(([key, value]) => ({ key, value })),
      commandOverride: row.commandOverride ?? null,
    })
    setEditorOpen(true)
  }

  const save = async () => {
    // 弹层/编辑态取值一律 getFieldsValue()（validateFields 白名单口径会丢字段，已踩坑）
    const values = form.getFieldsValue(true)
    const env: Record<string, string> = {}
    for (const pair of values.env ?? []) {
      if (pair?.key) env[pair.key] = pair.value ?? ''
    }
    const body = {
      name: values.name,
      agentNodeId: values.agentNodeId,
      port: values.port,
      baseUrl: values.baseUrl,
      appPackageId: values.appPackageId ?? null,
      pythonBin: values.pythonBin || 'venv/bin/python',
      env,
      commandOverride: values.commandOverride || null,
    }
    setSaving(true)
    try {
      if (editing) {
        await updateClassifyInstance(editing.id, body)
        message.success(`实例「${body.name}」已更新`)
      } else {
        await createClassifyInstance(body)
        message.success(`实例「${body.name}」已创建`)
      }
      setEditorOpen(false)
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const operate = async (row: ClassifyInstance, action: 'start' | 'stop' | 'restart') => {
    setOperating(action)
    try {
      const updated = await operateClassifyInstance(row.id, action)
      message.success(
        action === 'stop' ? `实例「${row.name}」已停止` : `实例「${row.name}」已下发${action === 'start' ? '启动' : '重启'}指令`,
      )
      setRows((prev) => prev.map((r) => (r.id === updated.id ? updated : r)))
      setManaging((prev) => (prev?.id === updated.id ? updated : prev))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    } finally {
      setOperating(null)
    }
  }

  const refreshStatus = async (row: ClassifyInstance) => {
    setOperating('status')
    try {
      const updated = await getClassifyInstanceStatus(row.id)
      setRows((prev) => prev.map((r) => (r.id === updated.id ? updated : r)))
      setManaging((prev) => (prev?.id === updated.id ? updated : prev))
      message.success('已按节点侧进程实况对账')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '状态刷新失败')
    } finally {
      setOperating(null)
    }
  }

  const remove = async (row: ClassifyInstance) => {
    try {
      await deleteClassifyInstance(row.id)
      message.success(`实例「${row.name}」已删除`)
      setManaging(null)
      void load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败')
    }
  }

  const health = managing?.lastHealth as Record<string, unknown> | null | undefined
  const healthSummary = (health?.summary ?? null) as Record<string, unknown> | null
  const healthSources = (health?.sources ?? null) as Record<string, unknown> | null

  const columns = [
    { title: '名称', dataIndex: 'name', width: 160 },
    {
      title: '节点',
      dataIndex: 'agentNodeId',
      width: 140,
      render: (id: number) => nodeName.get(id) ?? `#${id}`,
    },
    {
      title: '地址',
      dataIndex: 'baseUrl',
      render: (v: string, row: ClassifyInstance) => (
        <Typography.Text copyable={{ text: v }} style={{ fontSize: 12 }}>
          {v || `:${row.port}`}
        </Typography.Text>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (s: ClassifyInstanceStatus, row: ClassifyInstance) => (
        <Space size={4}>
          <StatusTag status={s} />
          {row.lastError ? (
            <Typography.Text type="danger" style={{ fontSize: 12 }} ellipsis={{ tooltip: row.lastError }}>
              {row.lastError}
            </Typography.Text>
          ) : null}
        </Space>
      ),
    },
    {
      title: '最近健康检查',
      dataIndex: 'lastHealthAt',
      width: 150,
      render: (t?: string | null) => fmtTime(t),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 150,
      render: (t: string) => fmtTime(t),
    },
    {
      title: '操作',
      key: 'actions',
      width: 80,
      render: (_: unknown, row: ClassifyInstance) => <a onClick={() => setManaging(row)}>管理</a>,
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={<LayaViewSwitch group="instances" value="instances" />}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建实例
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        管理 laya 分类边车实例：登记目标节点与端口，启动/停止经 WS 指令下发 runner 执行；崩溃不自动拉起（标红后人工重启）。
      </Typography.Paragraph>
      <FitTable<ClassifyInstance>
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={rows}
        pagination={LIST_PAGINATION}
        locale={{
          emptyText: (
            <Empty description="还没有分类服务实例">
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                新建实例
              </Button>
            </Empty>
          ),
        }}
      />

      <Modal
        title={editing ? `编辑实例「${editing.name}」` : '新建实例'}
        open={editorOpen}
        onOk={save}
        onCancel={() => setEditorOpen(false)}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        width={640}
        destroyOnHidden={false}
      >
        <Form form={form} layout="vertical" preserve>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 gpu-8377" maxLength={128} />
          </Form.Item>
          <Form.Item label="运行节点" name="agentNodeId" rules={[{ required: true, message: '请选择节点' }]}>
            <Select
              placeholder="选择 runner 节点（需协议 v15+）"
              options={nodes.map((n) => ({
                value: n.id,
                label: `${n.name}${n.status !== 'ONLINE' ? `（${n.status}）` : ''}${
                  (n.protocolVersion ?? 1) < 15 ? ` · 协议 v${n.protocolVersion ?? 1} 需升级` : ''
                }`,
                disabled: n.status !== 'ONLINE',
              }))}
            />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item label="端口" name="port" rules={[{ required: true, message: '请输入端口' }]} style={{ flex: 1 }}>
              <InputNumber min={1} max={65535} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              label="baseUrl"
              name="baseUrl"
              rules={[{ required: true, message: '请输入 baseUrl' }]}
              style={{ flex: 3 }}
              extra="外部访问该实例的地址（健康检查与试分类走它），如 http://172.20.140.88:8377"
            >
              <Input placeholder="http://<节点IP>:<端口>" maxLength={512} />
            </Form.Item>
          </Space>
          <Form.Item
            label="边车程序包"
            name="appPackageId"
            extra="实例的工作目录 = 该包在节点上的安装目录；未分发到所选节点时启动会报 409，请先到「分类安装包」分发"
          >
            <Select
              allowClear
              placeholder="选择 SIDECAR_APP 包"
              options={appPackages.map((p) => ({
                value: p.id,
                label: `${p.name} ${p.pkgVersion}（${fmtBytes(p.sizeBytes)}）`,
              }))}
            />
          </Form.Item>
          <Form.Item
            label="Python 解释器"
            name="pythonBin"
            extra="相对程序包安装目录，如 venv/bin/python（Windows 节点填 venv/Scripts/python.exe）"
          >
            <Input maxLength={256} />
          </Form.Item>
          <Form.Item label="启动命令覆盖" name="commandOverride" extra="留空 = 默认 uvicorn app:app --host 0.0.0.0 --port <端口>">
            <Input.TextArea rows={2} placeholder="-m uvicorn app:app --host 0.0.0.0 --port {port}" maxLength={1024} />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="环境变量支持 ${PKG_DIR:<packageId>} 占位符（展开为对应包在节点上的安装目录），如 LAYA_SLOT_MODELS={&quot;multilingual&quot;:&quot;${PKG_DIR:12}&quot;}。与 vLLM 共卡的节点务必加 LAYA_DEVICE=cpu。"
          />
          <Form.List name="env">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => (
                  <Space key={field.key} size={8} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                    <Form.Item name={[field.name, 'key']} noStyle rules={[{ required: true, message: '变量名' }]}>
                      <Input placeholder="变量名（如 LAYA_DEVICE）" style={{ width: 220 }} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'value']} noStyle>
                      <Input placeholder="值" style={{ width: 340 }} />
                    </Form.Item>
                    <MinusCircleOutlined onClick={() => remove(field.name)} />
                  </Space>
                ))}
                <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />}>
                  添加环境变量
                </Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>

      <Drawer
        title={managing ? `实例「${managing.name}」` : ''}
        open={!!managing}
        onClose={() => setManaging(null)}
        width={560}
      >
        {managing ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Space wrap>
              {managing.status !== 'RUNNING' && managing.status !== 'STARTING' ? (
                <Button
                  type="primary"
                  icon={<CaretRightOutlined />}
                  loading={operating === 'start'}
                  onClick={() => operate(managing, 'start')}
                >
                  启动
                </Button>
              ) : (
                <>
                  <Popconfirm title="确认停止该实例？" onConfirm={() => operate(managing, 'stop')}>
                    <Button icon={<StopOutlined />} loading={operating === 'stop'}>
                      停止
                    </Button>
                  </Popconfirm>
                  <Popconfirm title="确认重启该实例？" onConfirm={() => operate(managing, 'restart')}>
                    <Button icon={<PoweroffOutlined />} loading={operating === 'restart'}>
                      重启
                    </Button>
                  </Popconfirm>
                </>
              )}
              <Button icon={<ReloadOutlined />} loading={operating === 'status'} onClick={() => refreshStatus(managing)}>
                状态对账
              </Button>
              <Button onClick={() => openEdit(managing)}>编辑</Button>
              <Popconfirm
                title="删除实例？"
                description="仅停止状态可删除；删除不影响节点上已安装的包。"
                onConfirm={() => remove(managing)}
              >
                <Button danger>删除</Button>
              </Popconfirm>
            </Space>
            {managing.lastError ? <Alert type="error" showIcon message={managing.lastError} /> : null}
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="状态">
                <StatusTag status={managing.status} />
              </Descriptions.Item>
              <Descriptions.Item label="节点">{nodeName.get(managing.agentNodeId) ?? `#${managing.agentNodeId}`}</Descriptions.Item>
              <Descriptions.Item label="baseUrl">
                <Typography.Text copyable style={{ fontSize: 12 }}>
                  {managing.baseUrl}
                </Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="最近启动">{fmtTime(managing.lastStartAt)}</Descriptions.Item>
              <Descriptions.Item label="最近健康检查">{fmtTime(managing.lastHealthAt)}</Descriptions.Item>
            </Descriptions>
            {health ? (
              <Card size="small" title="健康快照（/healthz）">
                <Descriptions column={1} size="small">
                  <Descriptions.Item label="边车状态">{String(health.status ?? '-')}</Descriptions.Item>
                  <Descriptions.Item label="laya 版本">{String(health.layaVersion ?? '-')}</Descriptions.Item>
                  <Descriptions.Item label="已加载槽位">
                    {Array.isArray(health.loaded) ? (health.loaded as string[]).join('、') : '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label="设备">
                    {health.devices ? JSON.stringify(health.devices) : '-'}
                  </Descriptions.Item>
                </Descriptions>
                {healthSummary ? (
                  <Table
                    style={{ marginTop: 8 }}
                    size="small"
                    rowKey={(r) => String(r.slot)}
                    pagination={false}
                    dataSource={Object.entries(healthSummary).map(([slot, info]) => ({
                      slot,
                      ...(typeof info === 'object' && info !== null ? (info as Record<string, unknown>) : { info }),
                    }))}
                    columns={[
                      { title: '槽位', dataIndex: 'slot' },
                      {
                        title: '详情',
                        key: 'detail',
                        render: (_: unknown, r: Record<string, unknown>) => {
                          const { slot: _slot, ...rest } = r
                          return (
                            <Typography.Text style={{ fontSize: 12 }} copyable={{ text: JSON.stringify(rest, null, 2) }}>
                              {JSON.stringify(rest)}
                            </Typography.Text>
                          )
                        },
                      },
                    ]}
                  />
                ) : null}
                {healthSources ? (
                  <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
                    sources：{JSON.stringify(healthSources)}
                  </Typography.Paragraph>
                ) : null}
              </Card>
            ) : (
              <Typography.Text type="secondary">尚无健康快照（启动后轮询每 30s 打一次 /healthz）。</Typography.Text>
            )}
          </Space>
        ) : null}
      </Drawer>
    </Card>
  )
}
