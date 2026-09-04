import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Popconfirm,
  Segmented,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import {
  CodeOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
  StarOutlined,
  WindowsOutlined,
} from '@ant-design/icons'
import {
  createAgentNode,
  deleteAgentNode,
  disableAgentNode,
  enableAgentNode,
  getRunnerPackage,
  listAgentNodes,
  setAgentNodeDefault,
  unsetAgentNodeDefault,
  upgradeAgentNode,
} from '../api'
import type { AgentNode, IssuedNode, RunnerPackage } from '../types'
import RunnerPackagePanel from '../components/RunnerPackagePanel'
import { buildLinuxInstallScript, buildWindowsInstallScript, downloadTextFile } from '../utils/installScript'
import { fmtTime } from '../../../shared/utils/format'

const statusColor: Record<string, string> = {
  ONLINE: 'green',
  OFFLINE: 'default',
  DISABLED: 'red',
}

// 安装脚本生成参数：地址随当前访问入口走（同源部署/开发代理均适用）。
// token 为 null 时生成参数化脚本（运行时传入），否则内嵌 token。
const downloadScripts = (token: string | null) => {
  const wsUrl = location.origin.replace(/^http/, 'ws') + '/ws/agent'
  const downloadUrl = location.origin + '/api/agent-nodes/runner-package/download'
  return {
    windows: () =>
      downloadTextFile('install-runner.ps1', buildWindowsInstallScript({ serverUrl: wsUrl, downloadUrl, token }), true),
    linux: () =>
      downloadTextFile('install-runner.sh', buildLinuxInstallScript({ serverUrl: wsUrl, downloadUrl, token })),
  }
}

/**
 * CAP-21 后台页：Agent 节点管理（仅 ADMIN）。布局遵循 CLAUDE.md 前端内容区约定：
 * Card 标题 + Segmented 切换视图，表头 extra 放操作按钮，表格默认密度，行内「管理」开抽屉做全部操作。
 */
export default function AgentNodesPage() {
  const [view, setView] = useState<string>('nodes') // nodes | package
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [pkg, setPkg] = useState<RunnerPackage | null>(null)
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [issued, setIssued] = useState<IssuedNode | null>(null)
  const [drawerId, setDrawerId] = useState<number | null>(null)
  const [form] = Form.useForm<{ name: string; labels?: string }>()

  const reload = () => {
    setLoading(true)
    listAgentNodes()
      .then(setNodes)
      .catch((e) => message.error(`加载节点失败: ${e.message}`))
      .finally(() => setLoading(false))
    getRunnerPackage().then(setPkg).catch(() => setPkg(null)) // 404 = 未上传
  }

  useEffect(() => {
    reload()
    const timer = window.setInterval(reload, 5000) // 在线状态随心跳变化，轻轮询
    return () => window.clearInterval(timer)
  }, [])

  const onCreate = async () => {
    const v = await form.validateFields()
    try {
      const res = await createAgentNode({ name: v.name, labels: v.labels || undefined })
      setCreateOpen(false)
      form.resetFields()
      // token 仅此一次可见——弹窗展示，关闭后无法再查
      setIssued(res)
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败')
    }
  }

  // 抽屉里的节点随 5s 轮询保持新鲜；节点被删后抽屉自动关闭
  const drawerNode = drawerId != null ? nodes.find((n) => n.id === drawerId) ?? null : null

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    {
      title: '名称',
      dataIndex: 'name',
      width: 200,
      render: (s: string, r: AgentNode) => (
        <Space size={4}>
          {s}
          {r.isDefault && (
            <Tooltip title="平台默认执行节点：会话/项目未指定节点时调度到此">
              <Tag color="blue">默认</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: string) => <Tag color={statusColor[s] ?? 'default'}>{s}</Tag>,
    },
    { title: '系统', dataIndex: 'os', width: 180, render: (s?: string) => s || '-' },
    {
      title: 'runner 版本',
      dataIndex: 'runnerVersion',
      width: 150,
      render: (s: string | undefined) => {
        if (!s) return '-'
        // 与托管包版本不一致 = 可升级，橙色提示
        const outdated = pkg && s !== pkg.version
        return <Tag color={outdated ? 'orange' : 'default'}>{s}</Tag>
      },
    },
    {
      title: '最近心跳',
      dataIndex: 'lastHeartbeatAt',
      width: 170,
      render: (t?: string) => fmtTime(t),
    },
    {
      title: '操作',
      key: 'act',
      width: 90,
      render: (_: unknown, r: AgentNode) => (
        <Button size="small" onClick={() => setDrawerId(r.id)}>
          管理
        </Button>
      ),
    },
  ]

  return (
    <Card
      title={
        <Space size={12}>
          <span>Agent 节点</span>
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'nodes', label: '节点列表' },
              { value: 'package', label: 'Runner 包' },
            ]}
          />
        </Space>
      }
      extra={
        view === 'nodes' ? (
          <Space>
            <Button icon={<ReloadOutlined />} onClick={reload}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              新建节点
            </Button>
          </Space>
        ) : undefined
      }
    >
      {view === 'nodes' ? (
        <>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            节点 = 运行 devmind-agent-runner.jar 的远程机器。服务端本机没有 AI 能力时，把节点「设为默认」，未指定节点的会话即自动调度过去。
          </Typography.Paragraph>
          <Table<AgentNode>
            rowKey="id"
            loading={loading}
            columns={columns}
            dataSource={nodes}
            pagination={false}
            locale={{
              emptyText: (
                <Space direction="vertical" size={8} style={{ padding: '24px 0' }}>
                  <Typography.Text type="secondary">
                    还没有 Agent 节点——先「新建节点」拿到 token，再在目标机执行一键安装脚本即可上线。
                  </Typography.Text>
                  <div>
                    <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
                      新建节点
                    </Button>
                  </div>
                </Space>
              ),
            }}
          />
        </>
      ) : (
        <RunnerPackagePanel />
      )}

      <Modal
        title="新建 Agent 节点"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={onCreate}
        okText="创建"
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 zhangsan-win11 / build-win-01" />
          </Form.Item>
          <Form.Item label="标签（逗号分隔，调度预留）" name="labels">
            <Input placeholder="如 windows,office" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="节点已创建（token 仅此一次展示）"
        open={!!issued}
        footer={<Button type="primary" onClick={() => setIssued(null)}>我已保存</Button>}
        onCancel={() => setIssued(null)}
      >
        {issued && (
          <Space direction="vertical" style={{ width: '100%' }} size={8}>
            <Alert type="warning" showIcon message="请立即复制保存 token——关闭后无法再次查看，只能重建节点。" />
            <div>
              <Typography.Text type="secondary">agent.properties 配置示例</Typography.Text>
              <Typography.Paragraph
                code
                copyable={{
                  text: `serverUrl=${location.origin.replace(/^http/, 'ws')}/ws/agent\ntoken=${issued.token}\n`,
                }}
                style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}
              >
                {`serverUrl=${location.origin.replace(/^http/, 'ws')}/ws/agent\ntoken=${issued.token}`}
              </Typography.Paragraph>
            </div>
            <div>
              <Typography.Text type="secondary">一键安装脚本（已内嵌 token，拷到目标机执行即上线）</Typography.Text>
              <div>
                <Space>
                  <Button icon={<WindowsOutlined />} onClick={downloadScripts(issued.token).windows}>
                    Windows (.ps1)
                  </Button>
                  <Button icon={<CodeOutlined />} onClick={downloadScripts(issued.token).linux}>
                    Linux (.sh)
                  </Button>
                </Space>
              </div>
              <Typography.Paragraph type="secondary" style={{ marginTop: 4, marginBottom: 0 }}>
                Windows：<Typography.Text code>powershell -ExecutionPolicy Bypass -File install-runner.ps1</Typography.Text>
                ；Linux：<Typography.Text code>bash install-runner.sh</Typography.Text>
                。脚本会检查 java(21+)、下载 runner 包、写配置并后台启动。
              </Typography.Paragraph>
              {!pkg && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginTop: 8 }}
                  message="尚未上传 runner 包——脚本中的下载步骤会失败，请先在「Runner 包」页签上传。"
                />
              )}
            </div>
          </Space>
        )}
      </Modal>

      {drawerNode && (
        <NodeDrawer
          node={drawerNode}
          pkg={pkg}
          onClose={() => setDrawerId(null)}
          onChanged={reload}
        />
      )}
    </Card>
  )
}

// ---------------- 节点管理抽屉 ----------------
function NodeDrawer({
  node,
  pkg,
  onClose,
  onChanged,
}: {
  node: AgentNode
  pkg: RunnerPackage | null
  onClose: () => void
  onChanged: () => void
}) {
  const [busy, setBusy] = useState(false)
  const outdated = !!(pkg && node.runnerVersion && node.runnerVersion !== pkg.version)

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    try {
      await fn()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    } finally {
      setBusy(false)
    }
  }

  const onUpgrade = () =>
    run(async () => {
      const res = await upgradeAgentNode(node.id)
      if (res.status === 'ACCEPTED') message.success(res.message)
      else if (res.status === 'BUSY') message.warning(res.message)
      else if (res.status === 'ALREADY_LATEST') message.info(res.message)
      else message.error(res.message)
      onChanged()
    })

  const onSetDefault = (isDefault: boolean) =>
    run(async () => {
      if (isDefault) {
        await setAgentNodeDefault(node.id)
        message.success(`已将 ${node.name} 设为平台默认节点`)
      } else {
        await unsetAgentNodeDefault(node.id)
        message.success(`已取消 ${node.name} 的平台默认`)
      }
      onChanged()
    })

  const onToggleEnable = () =>
    run(async () => {
      if (node.status === 'DISABLED') {
        await enableAgentNode(node.id)
        message.success(`已启用 ${node.name}`)
      } else {
        await disableAgentNode(node.id)
        message.success(`已禁用 ${node.name}`)
      }
      onChanged()
    })

  const onDelete = () =>
    run(async () => {
      await deleteAgentNode(node.id)
      message.success('已删除')
      onClose()
      onChanged()
    })

  return (
    <Drawer title={`节点 · ${node.name}`} open onClose={onClose} width={640}>
      <Spin spinning={busy}>
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          <Descriptions size="small" column={2}>
            <Descriptions.Item label="ID">{node.id}</Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag color={statusColor[node.status] ?? 'default'}>{node.status}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="系统">{node.os || '-'}</Descriptions.Item>
            <Descriptions.Item label="能力">{node.capabilities || '-'}</Descriptions.Item>
            <Descriptions.Item label="runner 版本">
              {node.runnerVersion ? (
                <Tag color={outdated ? 'orange' : 'default'}>{node.runnerVersion}</Tag>
              ) : (
                '-'
              )}
            </Descriptions.Item>
            <Descriptions.Item label="最近心跳">{fmtTime(node.lastHeartbeatAt)}</Descriptions.Item>
            <Descriptions.Item label="标签" span={2}>
              {node.labels || '-'}
            </Descriptions.Item>
          </Descriptions>

          <Card size="small" title="一键安装脚本">
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              <Typography.Text type="secondary">
                参数化脚本不含 token（token 仅创建节点时可见），下载后在目标机执行时传入；token 已丢失请重建节点。
              </Typography.Text>
              <Space>
                <Button icon={<WindowsOutlined />} onClick={downloadScripts(null).windows}>
                  Windows (.ps1)
                </Button>
                <Button icon={<CodeOutlined />} onClick={downloadScripts(null).linux}>
                  Linux (.sh)
                </Button>
              </Space>
              <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                Windows：
                <Typography.Text code>powershell -ExecutionPolicy Bypass -File install-runner.ps1 -Token dmag_xxx</Typography.Text>
                ；Linux：<Typography.Text code>bash install-runner.sh dmag_xxx</Typography.Text>
              </Typography.Paragraph>
            </Space>
          </Card>

          <Card size="small" title="调度">
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              <Typography.Text type="secondary">
                平台默认执行节点：会话/项目未指定节点时调度到此（全平台至多一个）。
              </Typography.Text>
              <div>
                {node.isDefault ? (
                  <Button icon={<StarOutlined />} onClick={() => onSetDefault(false)}>
                    取消平台默认
                  </Button>
                ) : (
                  <Tooltip title={node.status === 'DISABLED' ? '已禁用节点不能设为默认' : undefined}>
                    <Button
                      icon={<StarOutlined />}
                      disabled={node.status === 'DISABLED'}
                      onClick={() => onSetDefault(true)}
                    >
                      设为平台默认
                    </Button>
                  </Tooltip>
                )}
              </div>
            </Space>
          </Card>

          {node.status === 'ONLINE' && (
            <Card size="small" title="升级 runner">
              <Space direction="vertical" style={{ width: '100%' }} size={8}>
                <Typography.Text type="secondary">
                  {pkg
                    ? `${node.runnerVersion ?? '-'} → ${pkg.version}；有活跃会话时将推迟执行。`
                    : '请先在「Runner 包」页签上传 runner 包。'}
                </Typography.Text>
                <div>
                  <Popconfirm title={`升级节点「${node.name}」？`} onConfirm={onUpgrade}>
                    <Button type="primary" icon={<RocketOutlined />} disabled={!pkg}>
                      升级
                    </Button>
                  </Popconfirm>
                </div>
              </Space>
            </Card>
          )}

          <Card size="small" title="状态与删除">
            <Space>
              <Button onClick={onToggleEnable}>
                {node.status === 'DISABLED' ? '启用' : '禁用'}
              </Button>
              <Popconfirm title={`删除节点「${node.name}」？其运行中会话将失联。`} onConfirm={onDelete}>
                <Button danger>删除节点</Button>
              </Popconfirm>
            </Space>
          </Card>
        </Space>
      </Spin>
    </Drawer>
  )
}
