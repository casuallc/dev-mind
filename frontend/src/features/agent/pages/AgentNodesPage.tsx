import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined, RocketOutlined } from '@ant-design/icons'
import {
  createAgentNode,
  deleteAgentNode,
  disableAgentNode,
  enableAgentNode,
  getRunnerPackage,
  listAgentNodes,
  upgradeAgentNode,
} from '../api'
import type { AgentNode, IssuedNode, RunnerPackage } from '../types'
import RunnerPackagePanel from '../components/RunnerPackagePanel'
import { fmtTime } from '../../../shared/utils/format'

const statusColor: Record<string, string> = {
  ONLINE: 'green',
  OFFLINE: 'default',
  DISABLED: 'red',
}

/**
 * CAP-21 后台页：Agent 节点管理（仅 ADMIN）。
 * 节点 = 跑 runner（devmind-agent-runner.jar）的远程机器（如 Windows 开发机），
 * runner 用注册 token 反向 WS 连服务端后即可被会话调度。
 */
export default function AgentNodesPage() {
  const [nodes, setNodes] = useState<AgentNode[]>([])
  const [pkg, setPkg] = useState<RunnerPackage | null>(null)
  const [loading, setLoading] = useState(false)
  const [upgrading, setUpgrading] = useState<number | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [issued, setIssued] = useState<IssuedNode | null>(null)
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

  const onUpgrade = async (r: AgentNode) => {
    setUpgrading(r.id)
    try {
      const res = await upgradeAgentNode(r.id)
      if (res.status === 'ACCEPTED') message.success(res.message)
      else if (res.status === 'BUSY') message.warning(res.message)
      else if (res.status === 'ALREADY_LATEST') message.info(res.message)
      else message.error(res.message)
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '升级失败')
    } finally {
      setUpgrading(null)
    }
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 70 },
    { title: '名称', dataIndex: 'name', width: 160 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: string) => <Tag color={statusColor[s] ?? 'default'}>{s}</Tag>,
    },
    { title: '系统', dataIndex: 'os', width: 180, render: (s?: string) => s || '-' },
    {
      title: '能力',
      dataIndex: 'capabilities',
      width: 110,
      render: (s?: string) => s || '-',
    },
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
    { title: '标签', dataIndex: 'labels', ellipsis: true, render: (s?: string) => s || '-' },
    {
      title: '最近心跳',
      dataIndex: 'lastHeartbeatAt',
      width: 170,
      render: (t?: string) => fmtTime(t),
    },
    {
      title: '操作',
      key: 'act',
      width: 250,
      render: (_: unknown, r: AgentNode) => (
        <Space size={8}>
          {r.status === 'ONLINE' && (
            <Tooltip title={pkg ? undefined : '请先在「Runner 包」页签上传 runner 包'}>
              <Popconfirm
                title={`升级节点「${r.name}」？`}
                description={`${r.runnerVersion ?? '-'} → ${pkg?.version ?? '-'}；有活跃会话时将推迟`}
                onConfirm={() => onUpgrade(r)}
              >
                <Button
                  size="small"
                  icon={<RocketOutlined />}
                  disabled={!pkg}
                  loading={upgrading === r.id}
                >
                  升级
                </Button>
              </Popconfirm>
            </Tooltip>
          )}
          {r.status === 'DISABLED' ? (
            <Button size="small" onClick={async () => {
              await enableAgentNode(r.id)
              message.success(`已启用 ${r.name}`)
              reload()
            }}>
              启用
            </Button>
          ) : (
            <Button size="small" onClick={async () => {
              await disableAgentNode(r.id)
              message.success(`已禁用 ${r.name}`)
              reload()
            }}>
              禁用
            </Button>
          )}
          <Popconfirm title={`删除节点「${r.name}」？其运行中会话将失联。`} onConfirm={async () => {
            await deleteAgentNode(r.id)
            message.success('已删除')
            reload()
          }}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Space style={{ marginBottom: 16, justifyContent: 'space-between', width: '100%' }}>
        <h2 style={{ margin: 0 }}>Agent 节点</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建节点
          </Button>
        </Space>
      </Space>
      <Tabs
        items={[
          {
            key: 'nodes',
            label: '节点列表',
            children: (
              <>
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 12 }}
                  message="节点 = 运行 devmind-agent-runner.jar 的远程机器（如 Windows 开发机）。在节点机上配置 agent.properties（serverUrl / token / project.<项目id>=本地路径），java -jar 启动后反向连接上线，创建会话时即可选择该节点执行。"
                />
                <Table<AgentNode>
                  rowKey="id"
                  size="small"
                  loading={loading}
                  columns={columns}
                  dataSource={nodes}
                  pagination={false}
                />
              </>
            ),
          },
          { key: 'package', label: 'Runner 包', children: <RunnerPackagePanel /> },
        ]}
      />

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
          </Space>
        )}
      </Modal>
    </div>
  )
}
