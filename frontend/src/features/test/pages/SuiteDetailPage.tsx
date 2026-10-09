// 套件编辑页（/tests/suites/:suiteId）：套件信息 + 用例编辑，内层页面格式（取代原「管理」/「编辑用例」Drawer）。
// 布局遵循 docs/core/前端内容区布局约定.md：单 Card 撑满内容区，操作集中 extra（添加用例/保存全部/沉淀为文档/删除/返回列表），
// body 用 pageCardBodyFlexStyle + FitTable（表头吸顶、只表体滚）。
// 用例整体替换保存：不在列表中的现有用例将被删除。
import {
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
  message,
} from 'antd'
import type { FormInstance } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate, useParams } from 'react-router-dom'
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  ExportOutlined,
  PlusOutlined,
  SaveOutlined,
} from '@ant-design/icons'
import { deleteSuite, getSuite, publishSuite, saveCases } from '../api'
import type { TestCase, TestCaseInput, TestSuite } from '../types'
import { fmtTime, paramsToText, textToParams } from '../../../shared/utils/format'
import { SUITE_KIND_COLOR } from '../constants'
import { pageCardBodyFlexStyle, pageCardStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

interface CaseFormValues {
  name: string
  kind: 'http' | 'health'
  method: string
  path: string
  paramsText: string
  headersText: string
  body: string
  expectedStatus: string
  expectedContains: string
  healthMode: 'http' | 'command'
  healthUrl: string
  healthCommand: string
  enabled: boolean
}

function caseToForm(c: TestCaseInput): CaseFormValues {
  const e = (c.expected ?? {}) as Record<string, unknown>
  const healthMode = e.type === 'command' ? 'command' : 'http'
  return {
    name: c.name ?? '',
    kind: (c.kind === 'health' ? 'health' : 'http'),
    method: c.method || 'GET',
    path: c.path ?? '',
    paramsText: paramsToText(c.params),
    headersText: paramsToText(c.headers),
    body: c.body ?? '',
    expectedStatus: String(e.status ?? ''),
    expectedContains: String(e.contains ?? ''),
    healthMode,
    healthUrl: String(e.url ?? ''),
    healthCommand: String(e.command ?? ''),
    enabled: c.enabled !== false,
  }
}

function formToCase(id: number | undefined, v: CaseFormValues): TestCaseInput {
  const expected: Record<string, unknown> = {}
  if (v.kind === 'health') {
    if (v.healthMode === 'command') {
      expected.type = 'command'
      expected.command = v.healthCommand.trim()
    } else {
      expected.type = 'http'
      if (v.healthUrl.trim()) expected.url = v.healthUrl.trim()
      const st = statusValue(v.expectedStatus)
      if (st !== undefined) expected.status = st
    }
  } else {
    const st = statusValue(v.expectedStatus)
    if (st !== undefined) expected.status = st
    if (v.expectedContains.trim()) expected.contains = v.expectedContains.trim()
  }
  return {
    id,
    name: v.name.trim(),
    kind: v.kind,
    method: v.method || 'GET',
    path: v.path.trim(),
    params: textToParams(v.paramsText),
    headers: textToParams(v.headersText),
    body: v.body || null,
    expected,
    enabled: v.enabled,
  }
}

/** status 支持整数或 "2XX" 前缀通配 */
function statusValue(s: string): number | string | undefined {
  const t = s.trim()
  if (!t) return undefined
  if (/^[1-5][0-9][0-9]$/.test(t)) return Number(t)
  return t.toUpperCase()
}

function fromView(c: TestCase): TestCaseInput {
  return {
    id: c.id,
    name: c.name,
    kind: c.kind,
    method: c.method,
    path: c.path,
    params: c.params,
    headers: c.headers,
    body: c.body,
    expected: c.expected,
    enabled: c.enabled,
  }
}

export default function SuiteDetailPage() {
  const { suiteId } = useParams<{ suiteId: string }>()
  const navigate = useNavigate()
  const [suite, setSuite] = useState<TestSuite | null>(null)
  const [loading, setLoading] = useState(true)
  const [cases, setCases] = useState<TestCaseInput[]>([])
  const [saving, setSaving] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const [editing, setEditing] = useState<TestCaseInput | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [form] = Form.useForm<CaseFormValues>()

  const load = useCallback(async () => {
    if (!suiteId) return
    setLoading(true)
    try {
      const s = await getSuite(Number(suiteId))
      setSuite(s)
      setCases(s.cases.map((c) => fromView(c)))
    } catch (e) {
      showError(e, '加载套件失败')
    } finally {
      setLoading(false)
    }
  }, [suiteId])

  useEffect(() => {
    load()
  }, [load])

  const openEdit = (c: TestCaseInput | null) => {
    setIsNew(!c)
    setEditing(c)
    form.setFieldsValue(c ? caseToForm(c) : { kind: 'http', method: 'GET', enabled: true, healthMode: 'command' })
  }

  const saveCase = async (v: CaseFormValues) => {
    const next = formToCase(editing?.id, v)
    if (editing) {
      setCases(cases.map((c) => (c === editing ? next : c)))
    } else {
      setCases([...cases, next])
    }
    setEditing(null)
  }

  const onSaveAll = async () => {
    if (!suite) return
    setSaving(true)
    try {
      const updated = await saveCases(suite.id, cases)
      setSuite(updated)
      setCases(updated.cases.map((c) => fromView(c)))
      message.success(`已保存 ${cases.length} 个用例`)
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const onPublish = async () => {
    if (!suite) return
    setPublishing(true)
    try {
      const updated = await publishSuite(suite.id)
      setSuite({ ...suite, ...updated, cases: suite.cases })
      message.success(`已沉淀为 api-suite 文档${updated.docId ? `（#${updated.docId}）` : ''}`)
    } catch (e) {
      showError(e, '沉淀失败')
    } finally {
      setPublishing(false)
    }
  }

  const onDelete = () => {
    if (!suite) return
    const s = suite
    Modal.confirm({
      centered: true,
      title: `删除套件「${s.name}」？`,
      content: `将删除 ${s.caseCount} 个用例及对应结果记录，不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteSuite(s.id)
        message.success('已删除')
        navigate('/tests')
      },
    })
  }

  const columns: ColumnsType<TestCaseInput> = [
    { title: '#', dataIndex: 'sort', width: 48, render: (_, __, i) => i + 1 },
    { title: '名称', dataIndex: 'name', ellipsis: true, render: (n: string) => n || '-' },
    { title: '类型', dataIndex: 'kind', width: 80, render: (k: string) => <Tag color={k === 'health' ? 'purple' : 'blue'}>{k}</Tag> },
    { title: '方法', dataIndex: 'method', width: 80, render: (m: string) => <Tag>{m}</Tag> },
    { title: '路径', dataIndex: 'path', ellipsis: true, render: (p: string) => <code style={{ fontSize: 12 }}>{p}</code> },
    { title: '期望', dataIndex: 'expected', width: 180, ellipsis: true, render: (e: Record<string, unknown>) => <span style={{ fontSize: 12 }}>{JSON.stringify(e ?? {})}</span> },
    { title: '启用', dataIndex: 'enabled', width: 70, render: (v: boolean) => (v ? <Tag color="green">是</Tag> : <Tag>否</Tag>) },
    {
      title: '操作',
      key: 'act',
      width: 110,
      render: (_, c) => (
        <Space size={4}>
          <Button size="small" onClick={() => openEdit(c)}>编辑</Button>
          <Button size="small" danger onClick={() => setCases(cases.filter((x) => x !== c))}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title={suite ? `套件 · ${suite.name}` : '套件'}
      extra={
        <Space>
          <Button icon={<PlusOutlined />} onClick={() => openEdit(null)} disabled={!suite}>添加用例</Button>
          <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={onSaveAll} disabled={!suite}>保存全部</Button>
          <Button icon={<ExportOutlined />} loading={publishing} disabled={!suite || !suite.caseCount} onClick={onPublish}>
            沉淀为文档
          </Button>
          <Button danger icon={<DeleteOutlined />} disabled={!suite} onClick={onDelete}>删除</Button>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tests')}>返回列表</Button>
        </Space>
      }
    >
      {loading || !suite ? (
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Spin />
        </div>
      ) : (
        <>
          <Descriptions size="small" column={3} style={{ marginBottom: 12 }}>
            <Descriptions.Item label="ID">#{suite.id}</Descriptions.Item>
            <Descriptions.Item label="类型">
              <Tag color={SUITE_KIND_COLOR[suite.kind]}>{suite.kind}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="来源">
              {suite.source === 'openapi' ? <Tag color="geekblue">OpenAPI</Tag> : <Tag>手动</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label="用例数">{suite.caseCount}</Descriptions.Item>
            <Descriptions.Item label="沉淀文档">
              {suite.docId ? `#${suite.docId}` : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">{fmtTime(suite.createdAt)}</Descriptions.Item>
          </Descriptions>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            用例编辑为整体替换保存：改动先落在下方列表，点「保存全部」生效；不在列表中的现有用例将被删除。
            http 用例由服务端直请求 baseUrl，health 用例的 command 型经 exec 帧下发执行节点。
          </Typography.Paragraph>
          <FitTable<TestCaseInput> rowKey={(c) => c.id ?? c.name + c.path} columns={columns}
            dataSource={cases} pagination={false} locale={{ emptyText: '暂无用例：点右上角「添加用例」' }} />
        </>
      )}

      <Modal title={isNew ? '添加用例' : '编辑用例'} open={!!editing} onCancel={() => setEditing(null)}
        onOk={() => form.submit()} okText="确定" width={640} destroyOnClose>
        <CaseForm form={form} onFinish={saveCase} />
      </Modal>
    </Card>
  )
}

function CaseForm({ form, onFinish }: { form: FormInstance<CaseFormValues>; onFinish: (v: CaseFormValues) => void }) {
  const kind = Form.useWatch('kind', form)
  const healthMode = Form.useWatch('healthMode', form)
  return (
    <Form form={form} layout="vertical" onFinish={onFinish}>
      <Space size={8} style={{ display: 'flex' }} align="start">
        <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入用例名' }]} style={{ flex: 1 }}>
          <Input placeholder="如 健康检查 / 登录接口" />
        </Form.Item>
        <Form.Item label="类型" name="kind" style={{ width: 110 }}>
          <Select options={[{ value: 'http', label: 'http' }, { value: 'health', label: 'health' }]} />
        </Form.Item>
        <Form.Item label="启用" name="enabled" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Space>
      {kind === 'health' ? (
        <Space size={8} style={{ display: 'flex' }} align="start">
          <Form.Item label="检查方式" name="healthMode" style={{ width: 130 }}>
            <Select options={[{ value: 'command', label: '命令' }, { value: 'http', label: 'HTTP' }]} />
          </Form.Item>
          {healthMode === 'command' ? (
            <Form.Item label="命令（CAP-07 模板）" name="healthCommand" rules={[{ required: true, message: '请输入命令' }]} style={{ flex: 1 }}>
              <Input placeholder="如 curl -sf http://…/health（exec 帧下发执行节点，受 runner 白名单约束）" />
            </Form.Item>
          ) : (
            <Space size={8} style={{ display: 'flex' }}>
              <Form.Item label="URL" name="healthUrl" style={{ width: 260 }}>
                <Input placeholder="留空用运行 baseUrl+path" />
              </Form.Item>
              <Form.Item label="期望状态" name="expectedStatus" style={{ width: 130 }}>
                <Input placeholder="如 200 或 2XX" />
              </Form.Item>
            </Space>
          )}
        </Space>
      ) : (
        <Space size={8} style={{ display: 'flex' }} align="start">
          <Form.Item label="方法" name="method" style={{ width: 110 }}>
            <Select options={METHODS.map((m) => ({ value: m, label: m }))} />
          </Form.Item>
          <Form.Item label="路径" name="path" rules={[{ required: true, message: '请输入路径' }]} style={{ flex: 1 }}>
            <Input placeholder="如 /api/users/{id}" />
          </Form.Item>
        </Space>
      )}
      {kind !== 'health' && (
        <>
          <Space size={8} style={{ display: 'flex' }}>
            <Form.Item label="Query 参数（每行 k=v）" name="paramsText" style={{ flex: 1 }}>
              <Input.TextArea rows={2} placeholder="name=test" />
            </Form.Item>
            <Form.Item label="Header（每行 k=v）" name="headersText" style={{ flex: 1 }}>
              <Input.TextArea rows={2} placeholder="X-Api-Key=xxx" />
            </Form.Item>
          </Space>
          <Form.Item label="请求体（JSON）" name="body">
            <Input.TextArea rows={2} placeholder='{"name":"carol"}' />
          </Form.Item>
          <Space size={8} style={{ display: 'flex' }}>
            <Form.Item label="期望状态" name="expectedStatus" style={{ width: 130 }}>
              <Input placeholder="如 200 或 2XX" />
            </Form.Item>
            <Form.Item label="期望包含（可选）" name="expectedContains" style={{ flex: 1 }}>
              <Input placeholder="响应体包含的子串" />
            </Form.Item>
          </Space>
        </>
      )}
    </Form>
  )
}
