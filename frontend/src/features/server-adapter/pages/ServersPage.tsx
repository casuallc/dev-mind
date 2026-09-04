// CAP-07 服务器运维：连通测试 / 健康检查 / 模板执行 / 上传下载 / 日志 + 模板白名单 + 审计
// 布局遵循 docs/core/前端内容区布局约定.md：Card 标题 + Segmented 切换视图，extra 随视图放操作按钮，表格默认密度。
import { useCallback, useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  message,
  Radio,
  Segmented,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  Upload,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { UploadFile } from 'antd/es/upload/interface'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { Project } from '../../projects/types'
import { listProjects } from '../../projects/api'
import {
  downloadFromServer,
  execTemplate,
  healthServer,
  listServers,
  listTemplates,
  serverLogs,
  storedConfig,
  testServer,
  uploadToServer,
} from '../api'
import type { ExecResult, ServerListItem, StoredConfig, TemplateView } from '../types'
import TemplatesTab from './TemplatesTab'
import AuditTab from './AuditTab'

const CAPABILITY_OPTIONS = ['build', 'deploy', 'release', 'test', 'logs', 'exec']

const VIEW_DESC: Record<string, string> = {
  ops: '项目服务器的远程运维入口：连通测试、健康检查、模板执行、上传下载与日志拉取。远程仅允许执行项目白名单命令模板（FR-05），全部操作留痕审计（FR-06）。',
  templates: '命令模板白名单（FR-05）：服务器上只允许执行项目内登记的模板，可按能力限定可用范围、声明参数 schema。',
  audit: '服务器远程操作审计（FR-06）：连通测试、模板执行、上传下载、健康检查全量留痕，可按项目 / 服务器 / 动作过滤。',
}

export default function ServersPage() {
  const [view, setView] = useState<string>('ops') // ops | templates | audit
  // extra 按钮通过 tick 触发当前视图内组件的动作（刷新 / 新建模板）
  const [refreshTick, setRefreshTick] = useState(0)
  const [createTick, setCreateTick] = useState(0)

  return (
    <Card
      title={
        <Space size={12}>
          <span>服务器运维</span>
          <Segmented
            value={view}
            onChange={setView}
            options={[
              { value: 'ops', label: '服务器运维' },
              { value: 'templates', label: '命令模板' },
              { value: 'audit', label: '审计日志' },
            ]}
          />
        </Space>
      }
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => setRefreshTick((t) => t + 1)}>
            刷新
          </Button>
          {view === 'templates' && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateTick((t) => t + 1)}>
              新建模板
            </Button>
          )}
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">{VIEW_DESC[view]}</Typography.Paragraph>
      {view === 'ops' && <OpsTab refreshTick={refreshTick} />}
      {view === 'templates' && <TemplatesTab refreshTick={refreshTick} createTick={createTick} />}
      {view === 'audit' && <AuditTab refreshTick={refreshTick} />}
    </Card>
  )
}

// ---------------- 服务器运维 ----------------
function OpsTab({ refreshTick }: { refreshTick: number }) {
  const [servers, setServers] = useState<ServerListItem[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(false)
  const [current, setCurrent] = useState<ServerListItem | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [s, p] = await Promise.all([listServers(), listProjects()])
      setServers(s)
      setProjects(p)
    } catch (e) {
      message.error(`加载失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load, refreshTick]) // refreshTick：外壳 extra「刷新」

  const columns: ColumnsType<ServerListItem> = [
    { title: '名称', dataIndex: 'name', width: 150 },
    {
      title: '项目',
      dataIndex: 'projectId',
      width: 150,
      render: (pid) => `${projects.find((p) => p.id === pid)?.name ?? pid} (${pid})`,
    },
    { title: '环境', dataIndex: 'env', width: 90, render: (e) => e || '-' },
    { title: '类型', dataIndex: 'accessType', width: 80, render: (t) => <Tag color={t === 'ssh' ? 'geekblue' : 'purple'}>{t}</Tag> },
    {
      title: '能力',
      dataIndex: 'capabilities',
      render: (c: string[]) => (c && c.length > 0 ? c.map((x) => <Tag key={x}>{x}</Tag>) : <Typography.Text type="secondary">-</Typography.Text>),
    },
    {
      title: '操作',
      width: 90,
      render: (_, r) => <Button size="small" onClick={() => setCurrent(r)}>管理</Button>,
    },
  ]

  return (
    <>
      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={servers}
        pagination={false}
        locale={{ emptyText: '暂无服务器。请到项目后台的「服务器」页登记接入后，再回到此处运维。' }}
      />
      {current && <OpsDrawer server={current} onClose={() => setCurrent(null)} />}
    </>
  )
}

// ---------------- 运维抽屉 ----------------
function OpsDrawer({ server, onClose }: { server: ServerListItem; onClose: () => void }) {
  const [templates, setTemplates] = useState<TemplateView[]>([])
  const [busy, setBusy] = useState(false)
  const [testResult, setTestResult] = useState<string>()
  const [testOk, setTestOk] = useState<boolean>()
  const [execResult, setExecResult] = useState<ExecResult | null>(null)
  const [healthResult, setHealthResult] = useState<{ ok: boolean; message: string }>()
  const [stored, setStored] = useState<StoredConfig | null>(null)
  const [execForm] = Form.useForm<{ templateCode: string; capability?: string }>()
  const [healthForm] = Form.useForm<{ type: 'http' | 'command'; url?: string; expectedStatus?: number; command?: string }>()
  const [uploadPath, setUploadPath] = useState('')
  const [downloadPath, setDownloadPath] = useState('')
  const [downloadText, setDownloadText] = useState<string>()
  const [logsTemplate, setLogsTemplate] = useState('logs')
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [logText, setLogText] = useState<string>()
  const [paramValues, setParamValues] = useState<Record<string, string>>({})

  const selectedTemplate = templates.find((t) => t.code === execForm.getFieldValue('templateCode'))

  const loadTemplates = useCallback(async () => {
    try {
      setTemplates(await listTemplates(server.projectId))
    } catch {
      setTemplates([])
    }
  }, [server.projectId])

  const loadStored = useCallback(async () => {
    try {
      setStored(await storedConfig(server.id))
    } catch {
      setStored(null)
    }
  }, [server.id])

  useEffect(() => {
    loadTemplates()
    loadStored()
  }, [loadTemplates, loadStored])

  const onTest = async () => {
    setBusy(true)
    try {
      const r = await testServer(server.id)
      setTestOk(r.ok)
      setTestResult(r.message)
    } catch (e) {
      setTestOk(false)
      setTestResult((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const onExec = async () => {
    const v = await execForm.validateFields()
    setBusy(true)
    setExecResult(null)
    try {
      setExecResult(await execTemplate(server.id, {
        templateCode: v.templateCode,
        params: paramValues,
        capability: v.capability,
      }))
    } catch (e) {
      setExecResult({ exitCode: -1, success: false, stdout: '', stderr: (e as Error).message, durationMs: 0 })
    } finally {
      setBusy(false)
    }
  }

  const onHealth = async () => {
    const v = await healthForm.validateFields()
    setBusy(true)
    try {
      const r = await healthServer(server.id, v as { type: string; url?: string; expectedStatus?: number; command?: string })
      setHealthResult(r)
    } catch (e) {
      setHealthResult({ ok: false, message: (e as Error).message })
    } finally {
      setBusy(false)
    }
  }

  const onUpload = async () => {
    const f = fileList[0]?.originFileObj as File | undefined
    if (!f) { message.warning('请选择文件'); return }
    if (!uploadPath) { message.warning('请填写远端路径'); return }
    setBusy(true)
    try {
      const r = await uploadToServer(server.id, f, uploadPath)
      message.success(r.message)
      setFileList([])
    } catch (e) {
      message.error(`上传失败：${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  const onDownload = async () => {
    if (!downloadPath) { message.warning('请填写远端路径'); return }
    setBusy(true)
    try {
      setDownloadText(await downloadFromServer(server.id, downloadPath))
    } catch (e) {
      message.error(`下载失败：${(e as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  const onLogs = async () => {
    setBusy(true)
    try {
      const r = await serverLogs(server.id, logsTemplate)
      setLogText(`${r.success ? '' : '失败'}\n${r.stdout}${r.stderr ? '\n[stderr]\n' + r.stderr : ''}`)
    } catch (e) {
      setLogText((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer title={`运维 · ${server.name}`} open onClose={onClose} width={720}>
      <Spin spinning={busy}>
        <Space direction="vertical" style={{ width: '100%' }} size={16}>
          <Descriptions size="small" column={2}>
            <Descriptions.Item label="项目">{server.projectId}</Descriptions.Item>
            <Descriptions.Item label="环境">{server.env || '-'}</Descriptions.Item>
            <Descriptions.Item label="类型">{server.accessType}</Descriptions.Item>
            <Descriptions.Item label="能力">{server.capabilities.join(', ') || '-'}</Descriptions.Item>
            {stored && (
              <Descriptions.Item label="凭证存储" span={2}>
                {stored.fields.length > 0
                  ? stored.fields.map((f) => (
                    <Tag key={f.field} color={f.encrypted ? 'green' : 'red'}>{f.field}: {f.encrypted ? '已加密' : '明文'}</Tag>
                  ))
                  : '无敏感字段'}
              </Descriptions.Item>
            )}
          </Descriptions>

          <Card size="small" title="连通性测试" extra={<Button size="small" type="primary" loading={busy} onClick={onTest}>测试连接</Button>}>
            {testResult && <Alert type={testOk ? 'success' : 'error'} message={testResult} showIcon />}
          </Card>

          <Card size="small" title="执行模板（白名单）">
            <Form form={execForm} layout="inline" initialValues={{ capability: 'deploy' }}>
              <Form.Item name="templateCode" label="模板" rules={[{ required: true, message: '选择模板' }]}>
                <Select style={{ width: 160 }} options={templates.map((t) => ({ label: t.code, value: t.code }))} placeholder="选择" />
              </Form.Item>
              <Form.Item name="capability" label="能力">
                <Select style={{ width: 110 }} options={CAPABILITY_OPTIONS.map((c) => ({ label: c, value: c }))} />
              </Form.Item>
            </Form>
            {selectedTemplate && selectedTemplate.params.length > 0 && (
              <Space wrap style={{ margin: '8px 0' }}>
                {selectedTemplate.params.map((p) => (
                  <Input
                    key={p.name}
                    placeholder={`${p.label ?? p.name}${p.required ? '*' : ''}`}
                    style={{ width: 180 }}
                    defaultValue={p.defaultValue ?? ''}
                    onChange={(e) => setParamValues((prev) => ({ ...prev, [p.name]: e.target.value }))}
                  />
                ))}
              </Space>
            )}
            <Button type="primary" loading={busy} onClick={onExec}>执行</Button>
            {execResult && (
              <pre style={{ background: execResult.success ? '#f6ffed' : '#fff1f0', padding: 10, borderRadius: 4, fontSize: 12, marginTop: 8, whiteSpace: 'pre-wrap' }}>
                exit={execResult.exitCode} ({execResult.durationMs}ms)
                {'\n'}{execResult.stdout || ''}{execResult.stderr ? '\n[stderr]\n' + execResult.stderr : ''}
              </pre>
            )}
          </Card>

          <Card size="small" title="健康检查">
            <Form form={healthForm} layout="inline" initialValues={{ type: 'http', expectedStatus: 200 }}>
              <Form.Item name="type" label="类型">
                <Radio.Group options={[{ label: 'HTTP', value: 'http' }, { label: '命令', value: 'command' }]} />
              </Form.Item>
              <Form.Item noStyle shouldUpdate>
                {({ getFieldValue }) =>
                  getFieldValue('type') === 'http' ? (
                    <>
                      <Form.Item name="url" rules={[{ required: true, message: 'URL 必填' }]} style={{ marginBottom: 0 }}>
                        <Input placeholder="http://host:port/health" style={{ width: 220 }} />
                      </Form.Item>
                      <Form.Item name="expectedStatus" style={{ marginLeft: 8, marginBottom: 0 }}>
                        <Input type="number" placeholder="期望码" style={{ width: 90 }} />
                      </Form.Item>
                    </>
                  ) : (
                    <Form.Item name="command" rules={[{ required: true, message: '命令必填' }]} style={{ marginBottom: 0 }}>
                      <Input placeholder="curl -sf http://…/health" style={{ width: 260 }} />
                    </Form.Item>
                  )
                }
              </Form.Item>
              <Button type="primary" loading={busy} onClick={onHealth} style={{ marginLeft: 8 }}>检查</Button>
            </Form>
            {healthResult && <Alert style={{ marginTop: 8 }} type={healthResult.ok ? 'success' : 'error'} message={healthResult.message} showIcon />}
          </Card>

          <Card size="small" title="文件传输">
            <Space wrap>
              <Upload beforeUpload={() => false} fileList={fileList} maxCount={1} onChange={({ fileList }) => setFileList(fileList)}>
                <Button>选择本地文件</Button>
              </Upload>
              <Input placeholder="远端路径，如 /tmp/app.jar" value={uploadPath} onChange={(e) => setUploadPath(e.target.value)} style={{ width: 220 }} />
              <Button type="primary" loading={busy} onClick={onUpload}>上传</Button>
            </Space>
            <Space wrap style={{ marginTop: 8 }}>
              <Input placeholder="下载远端路径" value={downloadPath} onChange={(e) => setDownloadPath(e.target.value)} style={{ width: 220 }} />
              <Button loading={busy} onClick={onDownload}>下载（文本）</Button>
            </Space>
            {downloadText !== undefined && (
              <pre style={{ background: '#f6f6f6', padding: 10, borderRadius: 4, fontSize: 12, marginTop: 8, whiteSpace: 'pre-wrap' }}>
                {downloadText}
              </pre>
            )}
          </Card>

          <Card size="small" title="拉取日志（logs 模板）">
            <Space>
              <Select
                style={{ width: 160 }}
                value={logsTemplate}
                onChange={setLogsTemplate}
                options={templates.map((t) => ({ label: t.code, value: t.code }))}
              />
              <Button loading={busy} onClick={onLogs}>拉取</Button>
            </Space>
            {logText !== undefined && (
              <pre style={{ background: '#f6f6f6', padding: 10, borderRadius: 4, fontSize: 12, marginTop: 8, whiteSpace: 'pre-wrap' }}>{logText}</pre>
            )}
          </Card>
        </Space>
      </Spin>
    </Drawer>
  )
}
