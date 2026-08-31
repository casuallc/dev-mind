// 用例编辑 Drawer：整体替换保存（不在列表中的现有用例将被删除）。
import {
  Button,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import type { FormInstance } from 'antd'
import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined } from '@ant-design/icons'
import { saveCases } from '../api'
import type { TestCase, TestCaseInput, TestSuite } from '../types'
import { paramsToText, textToParams } from '../../../shared/utils/format'

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

export default function CaseEditorDrawer({ suite, onClose, onChanged }: {
  suite: TestSuite | null
  onClose: () => void
  onChanged: (s: TestSuite) => void
}) {
  const [cases, setCases] = useState<TestCaseInput[]>([])
  const [saving, setSaving] = useState(false)
  const [editing, setEditing] = useState<TestCaseInput | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [form] = Form.useForm<CaseFormValues>()

  useEffect(() => {
    if (!suite) return
    setCases(suite.cases.map((c) => fromView(c)))
  }, [suite])

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
      onChanged(updated)
      message.success(`已保存 ${cases.length} 个用例`)
      onClose()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  const columns: ColumnsType<TestCaseInput> = [
    { title: '#', dataIndex: 'sort', width: 44, render: (_, __, i) => i + 1 },
    { title: '名称', dataIndex: 'name', ellipsis: true, render: (n: string) => n || '-' },
    { title: '类型', dataIndex: 'kind', width: 70, render: (k: string) => <Tag color={k === 'health' ? 'purple' : 'blue'}>{k}</Tag> },
    { title: '方法', dataIndex: 'method', width: 70, render: (m: string) => <Tag>{m}</Tag> },
    { title: '路径', dataIndex: 'path', ellipsis: true, render: (p: string) => <code style={{ fontSize: 12 }}>{p}</code> },
    { title: '期望', dataIndex: 'expected', width: 160, render: (e: Record<string, unknown>) => <span style={{ fontSize: 12 }}>{JSON.stringify(e ?? {})}</span> },
    { title: '启用', dataIndex: 'enabled', width: 60, render: (v: boolean) => (v ? <Tag color="green">是</Tag> : <Tag>否</Tag>) },
    {
      title: '',
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
    <Drawer
      title={suite ? `编辑用例 · ${suite.name}（${suite.caseCount}）` : '编辑用例'}
      width={900}
      open={!!suite}
      onClose={onClose}
      extra={
        <Space>
          <Button size="small" onClick={onClose}>取消</Button>
          <Button size="small" type="primary" loading={saving} onClick={onSaveAll}>保存全部</Button>
        </Space>
      }
    >
      {suite && (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Space wrap>
            <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => openEdit(null)}>添加用例</Button>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              整体替换保存：不在列表中的现有用例将被删除；http 用例直请求 baseUrl，health 用例走目标服务器健康检查。
            </Typography.Text>
          </Space>
          <Table<TestCaseInput> rowKey={(c) => c.id ?? c.name + c.path} size="small" columns={columns}
            dataSource={cases} pagination={false} locale={{ emptyText: '暂无用例' }} />

          <Modal title={isNew ? '添加用例' : '编辑用例'} open={!!editing} onCancel={() => setEditing(null)}
            onOk={() => form.submit()} okText="保存" width={640} destroyOnClose>
            <CaseForm form={form} onFinish={saveCase} />
          </Modal>
        </Space>
      )}
    </Drawer>
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
              <Input placeholder="如 echo ok 或模板 code（走服务器命令模板白名单）" />
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
