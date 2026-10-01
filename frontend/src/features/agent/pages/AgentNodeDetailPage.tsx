import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Empty,
  Input,
  Modal,
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
  ArrowLeftOutlined,
  CodeOutlined,
  FolderOpenOutlined,
  RocketOutlined,
  StarOutlined,
  WindowsOutlined,
} from '@ant-design/icons'
import {
  deleteAgentNode,
  disableAgentNode,
  enableAgentNode,
  getRunnerPackage,
  listAgentNodes,
  listNodeActiveSessions,
  setAgentNodeDefault,
  unsetAgentNodeDefault,
  updateAgentNode,
  upgradeAgentNode,
} from '../api'
import type { AgentNode, NodeActiveSession, RunnerPackage } from '../types'
import ActiveSessionsCard, { activeSessionColumns } from '../components/ActiveSessionsCard'
import { downloadInstallScripts } from '../utils/installScript'
import { fmtTime, fmtBytes } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyFlexStyle, pagePaneScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

const statusColor: Record<string, string> = {
  ONLINE: 'green',
  OFFLINE: 'default',
  DISABLED: 'red',
}

/**
 * CAP-21 节点详情页（替代原管理抽屉）：头部 extra 放运维操作（默认/升级/启停/删除），
 * body 内 Segmented 切「概览（观测）/ 配置（低频编辑）」。5s 轮询保持状态新鲜；节点被删自动返回列表。
 */
export default function AgentNodeDetailPage() {
  const navigate = useNavigate()
  const nodeId = Number(useParams<{ id: string }>().id)
  const [nodes, setNodes] = useState<AgentNode[] | null>(null) // null=尚未加载成功过
  const [pkg, setPkg] = useState<RunnerPackage | null>(null)
  const [view, setView] = useState<string>('overview') // overview | config
  const [busy, setBusy] = useState(false)

  // 强制升级弹窗：BUSY 时打开，异步拉活跃会话清单（null=加载中）
  const [forceOpen, setForceOpen] = useState(false)
  const [activeSessions, setActiveSessions] = useState<NodeActiveSession[] | null>(null)

  const reload = () => {
    listAgentNodes()
      .then(setNodes)
      .catch((e) => showError(e, '加载节点失败'))
    getRunnerPackage().then(setPkg).catch(() => setPkg(null)) // 404 = 未上传
  }

  useEffect(() => {
    reload()
    const timer = window.setInterval(reload, 5000) // 与列表页同节奏：在线状态随心跳变化
    return () => window.clearInterval(timer)
  }, [])

  const node = nodes?.find((n) => n.id === nodeId) ?? null
  // 节点被删（或 id 非法）：加载成功过且找不到 → 提示并回列表
  const gone = nodes != null && !node
  const goneNotified = useRef(false)
  useEffect(() => {
    if (gone && !goneNotified.current) {
      goneNotified.current = true
      message.warning('节点不存在或已被删除')
      navigate('/admin/agent/nodes', { replace: true })
    }
  }, [gone, navigate])

  // 标签/代理/文件根目录草稿：只在节点身份切换时重置——5s 轮询刷新不应覆盖用户正在编辑的输入
  const [labelsDraft, setLabelsDraft] = useState('')
  const [proxyUrlDraft, setProxyUrlDraft] = useState('')
  const [proxyScopesDraft, setProxyScopesDraft] = useState<string[]>(['git'])
  const [fileRootsDraft, setFileRootsDraft] = useState('')
  useEffect(() => {
    setLabelsDraft(node?.labels ?? '')
    setProxyUrlDraft(node?.proxyUrl ?? '')
    setProxyScopesDraft(node?.proxyScopes ? node.proxyScopes.split(',').filter(Boolean) : ['git'])
    setFileRootsDraft((node?.fileRoots ?? []).join('\n'))
  }, [node?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  const outdated = !!(pkg && node?.runnerVersion && node.runnerVersion !== pkg.version)

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    try {
      await fn()
    } catch (e) {
      showError(e, '操作失败')
    } finally {
      setBusy(false)
    }
  }

  const showForceModal = () => {
    setForceOpen(true)
    setActiveSessions(null)
    listNodeActiveSessions(nodeId)
      .then(setActiveSessions)
      .catch(() => setActiveSessions([])) // 拉取失败降级为纯计数文案，不挡强制升级
  }

  const doUpgrade = (force = false) =>
    run(async () => {
      const res = await upgradeAgentNode(nodeId, force)
      if (res.status === 'ACCEPTED') message.success(res.message)
      else if (res.status === 'BUSY') {
        if (force) {
          // 仍 busy：runner 版本过旧不认识 force 字段
          message.warning(`${res.message}（节点 runner 过旧不支持强制升级，请先手工部署基线版本）`)
        } else {
          showForceModal()
        }
      }
      else if (res.status === 'ALREADY_LATEST') message.info(res.message)
      else message.error(res.message)
      reload()
    })

  // 确认弹窗统一走平台通用的居中 Modal.confirm，不用贴按钮的 Popconfirm
  const onUpgrade = () =>
    node &&
    Modal.confirm({
      centered: true,
      title: `升级节点「${node.name}」？`,
      okText: '升级',
      cancelText: '取消',
      onOk: () => doUpgrade(false),
    })

  const doSetDefault = (isDefault: boolean) =>
    run(async () => {
      if (isDefault) {
        await setAgentNodeDefault(nodeId)
        message.success(`已将 ${node?.name} 设为平台默认节点`)
      } else {
        await unsetAgentNodeDefault(nodeId)
        message.success(`已取消 ${node?.name} 的平台默认`)
      }
      reload()
    })

  // 默认节点影响全平台会话调度，设/取消都需二次确认
  const onSetDefault = (isDefault: boolean) =>
    node &&
    Modal.confirm({
      centered: true,
      title: isDefault ? `将「${node.name}」设为平台默认节点？` : `取消「${node.name}」的平台默认？`,
      content: isDefault
        ? '会话/项目未指定节点时将调度到该节点（全平台至多一个，原有默认会被替换）。'
        : '取消后未指定节点且项目也无默认节点的会话将创建失败（无可用执行节点）。',
      okText: isDefault ? '设为默认' : '取消默认',
      cancelText: '再想想',
      onOk: () => doSetDefault(isDefault),
    })

  const doToggleEnable = () =>
    run(async () => {
      if (node?.status === 'DISABLED') {
        await enableAgentNode(nodeId)
        message.success(`已启用 ${node?.name}`)
      } else {
        await disableAgentNode(nodeId)
        message.success(`已禁用 ${node?.name}`)
      }
      reload()
    })

  // 启用无害直接执行；禁用会切断调度，需二次确认
  const onToggleEnable = () => {
    if (!node) return
    if (node.status === 'DISABLED') {
      void doToggleEnable()
      return
    }
    Modal.confirm({
      centered: true,
      title: `禁用节点「${node.name}」？`,
      content: '禁用后新会话不再调度到该节点，其运行中会话不受影响。',
      okText: '禁用',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: doToggleEnable,
    })
  }

  const onDelete = () =>
    node &&
    Modal.confirm({
      centered: true,
      title: `删除节点「${node.name}」？`,
      content: '其运行中会话将失联。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () =>
        run(async () => {
          await deleteAgentNode(nodeId)
          message.success('已删除')
          navigate('/admin/agent/nodes', { replace: true })
        }),
    })

  const onSaveLabels = () =>
    run(async () => {
      await updateAgentNode(nodeId, { labels: labelsDraft.trim() || undefined })
      message.success('标签已保存')
      reload()
    })

  // CAP-43：URL 留空 = 清空代理（连 scope 一起清）；只传代理字段，labels 不动
  const onSaveProxy = () =>
    run(async () => {
      const url = proxyUrlDraft.trim()
      await updateAgentNode(nodeId, {
        proxyUrl: url,
        proxyScopes: url ? proxyScopesDraft.join(',') : undefined,
      })
      message.success(url ? '外网代理已保存（随下一指令帧生效）' : '外网代理已清空')
      reload()
    })

  // CAP-65：每行一个绝对路径，空行忽略；全留空 = 清空白名单（文件浏览不可用）
  const onSaveFileRoots = () =>
    run(async () => {
      const roots = fileRootsDraft.split('\n').map((l) => l.trim()).filter(Boolean)
      await updateAgentNode(nodeId, { fileRoots: roots })
      message.success(roots.length ? `文件访问根目录已保存（${roots.length} 个）` : '文件访问根目录已清空，文件浏览不可用')
      reload()
    })

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={
        <Space size={12}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/admin/agent/nodes')}>
            返回
          </Button>
          <span>节点 · {node?.name ?? `#${nodeId}`}</span>
          {node && <Tag color={statusColor[node.status] ?? 'default'}>{node.status}</Tag>}
          {node?.isDefault && (
            <Tooltip title="平台默认执行节点：会话/项目未指定节点时调度到此">
              <Tag color="blue">默认</Tag>
            </Tooltip>
          )}
          {node && (
            <Segmented
              value={view}
              onChange={setView}
              options={[
                { value: 'overview', label: '概览' },
                { value: 'config', label: '配置' },
              ]}
            />
          )}
        </Space>
      }
      extra={
        node && (
          <Space>
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
            {node.status === 'ONLINE' && (
              <Tooltip
                title={
                  pkg
                    ? outdated
                      ? `${node.runnerVersion ?? '-'} → ${pkg.version}；有活跃会话时将推迟执行`
                      : '已是最新版本'
                    : '请先在「Runner 包」页上传 runner 包'
                }
              >
                <Button icon={<RocketOutlined />} disabled={!pkg || !outdated} onClick={onUpgrade}>
                  升级 runner
                </Button>
              </Tooltip>
            )}
            <Button onClick={onToggleEnable}>{node.status === 'DISABLED' ? '启用' : '禁用'}</Button>
            <Button danger onClick={onDelete}>
              删除节点
            </Button>
          </Space>
        )
      }
    >
      {!node ? (
        <Empty style={{ margin: 'auto' }} description={nodes === null ? '加载中…' : '节点不存在'} />
      ) : view === 'overview' ? (
        <div style={pagePaneScrollStyle}>
              <Space direction="vertical" style={{ width: '100%' }} size={16}>
                <Descriptions size="small" column={2}>
                  <Descriptions.Item label="ID">{node.id}</Descriptions.Item>
                  <Descriptions.Item label="状态">
                    <Tag color={statusColor[node.status] ?? 'default'}>{node.status}</Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="系统">{node.os || '-'}</Descriptions.Item>
                  <Descriptions.Item label="来源地址">
                    {node.remoteAddr ? <Typography.Text code>{node.remoteAddr}</Typography.Text> : '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label="能力">{node.capabilities || '-'}</Descriptions.Item>
                  <Descriptions.Item label="runner 版本">
                    {node.runnerVersion ? (
                      outdated ? (
                        <Tooltip title={`可升级 → ${pkg!.version}`}>
                          <Tag color="orange">{node.runnerVersion} · 可升级</Tag>
                        </Tooltip>
                      ) : (
                        <Tag>{node.runnerVersion}</Tag>
                      )
                    ) : (
                      '-'
                    )}
                  </Descriptions.Item>
                  <Descriptions.Item label="工作区占用">{fmtBytes(node.workspaceBytes)}</Descriptions.Item>
                  <Descriptions.Item label="协议版本">
                    {node.protocolVersion != null ? `v${node.protocolVersion}` : 'v1（未上报）'}
                  </Descriptions.Item>
                  <Descriptions.Item label="最近心跳">{fmtTime(node.lastHeartbeatAt)}</Descriptions.Item>
                  <Descriptions.Item label="工具链" span={2}>
                    <ToolchainTags json={node.toolchain} />
                  </Descriptions.Item>
                  <Descriptions.Item label="标签" span={2}>
                    {node.labels || '-'}
                  </Descriptions.Item>
                </Descriptions>

                <ActiveSessionsCard nodeId={node.id} />
              </Space>
            </div>
          ) : (
            <div style={pagePaneScrollStyle}>
              <Space direction="vertical" style={{ width: '100%' }} size={16}>
                <Card size="small" title="标签（调度）">
                  <Space direction="vertical" style={{ width: '100%' }} size={8}>
                    <Typography.Text type="secondary">
                      创建会话时可填「标签要求」，仅标签全覆盖的节点可被调度。runner 的 agent.properties 配置了 labels 时，其 hello 会覆盖此处编辑值。
                    </Typography.Text>
                    <Space.Compact style={{ width: '100%' }}>
                      <Input
                        placeholder="如 windows,office（逗号分隔；留空 = 清除）"
                        value={labelsDraft}
                        onChange={(e) => setLabelsDraft(e.target.value)}
                      />
                      <Button type="primary" loading={busy} onClick={onSaveLabels}>
                        保存
                      </Button>
                    </Space.Compact>
                  </Space>
                </Card>

                <Card size="small" title="外网代理（CAP-43）">
                  <Space direction="vertical" style={{ width: '100%' }} size={8}>
                    <Typography.Text type="secondary">
                      节点上的 git 网络操作 / claude 会话进程 / exec 脚本经此代理访问外网（按下方勾选生效）。
                      保存后随下一指令帧即时生效；runner 协议版本需 ≥ v8，低于 v8 时下发会被服务端拒绝并提示升级。
                      不支持带账号密码的代理地址。
                    </Typography.Text>
                    <Input
                      placeholder="http://127.0.0.1:8443（留空 = 直连）"
                      value={proxyUrlDraft}
                      onChange={(e) => setProxyUrlDraft(e.target.value)}
                    />
                    <Checkbox.Group
                      options={[
                        { label: 'git 代码库操作', value: 'git' },
                        { label: 'claude 会话进程', value: 'claude' },
                        { label: 'exec 脚本', value: 'exec' },
                      ]}
                      value={proxyScopesDraft}
                      onChange={(v) => setProxyScopesDraft(v as string[])}
                    />
                    <div>
                      <Button type="primary" loading={busy} onClick={onSaveProxy}>
                        保存
                      </Button>
                    </div>
                  </Space>
                </Card>

                <Card size="small" title="文件访问根目录（CAP-65）">
                  <Space direction="vertical" style={{ width: '100%' }} size={8}>
                    <Typography.Text type="secondary">
                      每行一个绝对路径（Windows 如 D:/data，Linux 如 /var/log），最多 16 个。
                      文件浏览/读写仅限白名单根目录之内；<Typography.Text strong>留空 = 文件浏览不可用</Typography.Text>。
                      runner 协议版本需 ≥ v18（低于 v18 的操作会被服务端拒绝并提示升级）。
                    </Typography.Text>
                    <Input.TextArea
                      autoSize={{ minRows: 2, maxRows: 8 }}
                      placeholder={'D:/data\n/var/log'}
                      value={fileRootsDraft}
                      onChange={(e) => setFileRootsDraft(e.target.value)}
                    />
                    <Space>
                      <Button type="primary" loading={busy} onClick={onSaveFileRoots}>
                        保存
                      </Button>
                      <Tooltip
                        title={
                          !(node.fileRoots?.length)
                            ? '未配置文件访问根目录，文件浏览不可用'
                            : node.status !== 'ONLINE'
                              ? '节点不在线，无法浏览文件'
                              : undefined
                        }
                      >
                        <span>
                          <Button
                            icon={<FolderOpenOutlined />}
                            disabled={!(node.fileRoots?.length) || node.status !== 'ONLINE'}
                            onClick={() => navigate(`/admin/agent/files?nodeId=${node.id}`)}
                          >
                            文件浏览
                          </Button>
                        </span>
                      </Tooltip>
                    </Space>
                  </Space>
                </Card>

                <Card size="small" title="一键安装脚本">
                  <Space direction="vertical" style={{ width: '100%' }} size={8}>
                    <Typography.Text type="secondary">
                      参数化脚本不含 token（token 仅创建节点时可见），下载后在目标机执行时传入；token 已丢失请重建节点。
                    </Typography.Text>
                    <Space>
                      <Button icon={<WindowsOutlined />} onClick={downloadInstallScripts(null).windows}>
                        Windows (.ps1)
                      </Button>
                      <Button icon={<CodeOutlined />} onClick={downloadInstallScripts(null).linux}>
                        Linux (.sh)
                      </Button>
                    </Space>
                    <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                      Windows：
                      <Typography.Text code>powershell -ExecutionPolicy Bypass -File install-runner.ps1 -Token dmag_xxx</Typography.Text>
                    </Typography.Paragraph>
                    <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                      Linux：
                      <Typography.Text code>bash install-runner.sh dmag_xxx</Typography.Text>
                    </Typography.Paragraph>
                  </Space>
                </Card>
              </Space>
            </div>
          )}

      {/* BUSY 后的强制升级确认：列出将被终止的活跃会话 */}
      <Modal
        centered
        title="节点有活跃会话，已推迟升级"
        open={forceOpen}
        onCancel={() => setForceOpen(false)}
        okText="终止并升级"
        okButtonProps={{ danger: true, loading: busy }}
        cancelText="取消"
        onOk={() => {
          setForceOpen(false)
          void doUpgrade(true)
        }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          {activeSessions === null ? (
            <Spin size="small" />
          ) : activeSessions.length === 0 ? (
            <Typography.Text type="secondary">
              未查到会话清单（服务端会话模块未装配或已全部结束）——确认后 runner 将自行终止其本地会话进程。
            </Typography.Text>
          ) : (
            <Table<NodeActiveSession>
              rowKey="sessionId"
              size="small"
              pagination={false}
              dataSource={activeSessions}
              columns={activeSessionColumns}
            />
          )}
          <Alert
            type="warning"
            showIcon
            message="强制升级将终止以上会话：runner 先正常终止进程（托管工作区会尝试 push 未推送的改动），再下载换包并自动重启。"
          />
        </Space>
      </Modal>
    </Card>
  )
}

// ---------------- 工具链标签（FR-07） ----------------
/** toolchain JSON 对象串 → 「工具 版本」Tag 列表；解析失败原样展示。 */
function ToolchainTags({ json }: { json?: string }) {
  if (!json) return <>-</>
  let entries: [string, string][]
  try {
    entries = Object.entries(JSON.parse(json) as Record<string, string>)
  } catch {
    return <Typography.Text code>{json}</Typography.Text>
  }
  if (entries.length === 0) return <>-</>
  return (
    <Space size={4} wrap>
      {entries.map(([k, v]) => (
        <Tag key={k}>
          {k} {v}
        </Tag>
      ))}
    </Space>
  )
}
