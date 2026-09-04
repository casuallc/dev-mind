// CAP-18/19 平台集成管理页（仅 ADMIN）：集成实例列表 + 新建/编辑 + 连接测试 + 启停。
// GitLab/GitHub（push 分支/MR·PR/Release）与 Jira（issue 同步）共用同一 integrations 表。
import { useCallback, useEffect, useState } from 'react'
import {
  Badge,
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { ApiOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  changeIntegrationStatus,
  createIntegration,
  listIntegrations,
  testIntegration,
  testIntegrationDraft,
  updateIntegration,
} from '../api'
import type { Integration, IntegrationInput, IntegrationTestResult } from '../types'

const TYPE_OPTIONS = [
  { value: 'GITLAB', label: 'GitLab（代码平台）' },
  { value: 'GITHUB', label: 'GitHub（代码平台，含 GHE）' },
  { value: 'JIRA', label: 'Jira（任务/Bug 同步）' },
]

const AUTH_OPTIONS = [
  { value: 'PAT', label: '个人访问令牌 PAT（Jira 8.14+）' },
  { value: 'BASIC', label: '用户名 + 密码（Jira 8.13 及更早）' },
]

const TYPE_COLOR: Record<string, string> = { GITLAB: 'orange', GITHUB: 'default', JIRA: 'blue' }

/** 列表连通性实时探测结果（逐行异步更新） */
type ConnState =
  | { phase: 'probing' }
  | { phase: 'done'; ok: boolean; message: string; detail?: string | null }

export default function IntegrationsPage() {
  const [items, setItems] = useState<Integration[]>([])
  const [loading, setLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<Integration | null>(null)
  const [testingId, setTestingId] = useState<number | null>(null)
  const [testingForm, setTestingForm] = useState(false)
  const [saving, setSaving] = useState(false)
  const [conn, setConn] = useState<Record<number, ConnState>>({})
  const [form] = Form.useForm<IntegrationInput>()
  const formType = Form.useWatch('type', form)
  const formAuthType = Form.useWatch('authType', form)
  const isJira = formType === 'JIRA'
  const isGitHub = formType === 'GITHUB'
  const isBasic = isJira && formAuthType === 'BASIC'

  /** 列表加载后逐行实时探测连通性（结果回填「连通性」列） */
  const probeAll = useCallback((list: Integration[]) => {
    setConn(Object.fromEntries(list.map(i => [i.id, { phase: 'probing' as const }])))
    list.forEach(i => {
      testIntegration(i.id)
        .then(r =>
          setConn(prev => ({
            ...prev,
            [i.id]: { phase: 'done', ok: r.ok, message: r.message, detail: r.detail },
          })),
        )
        .catch(e =>
          setConn(prev => ({
            ...prev,
            [i.id]: { phase: 'done', ok: false, message: (e as Error).message },
          })),
        )
    })
  }, [])

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const list = await listIntegrations()
      setItems(list)
      probeAll(list)
    } catch (e) {
      message.error(`加载集成失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [probeAll])

  useEffect(() => {
    reload()
  }, [reload])

  useEffect(() => {
    if (!editOpen) return
    form.setFieldsValue(
      editing
        ? {
            type: editing.type,
            name: editing.name,
            baseUrl: editing.baseUrl,
            authType: editing.authType ?? 'PAT',
            username: '',
            token: '',
          }
        : { type: 'JIRA', name: '', baseUrl: '', authType: 'PAT', username: '', token: '' },
    )
  }, [editOpen, editing, form])

  const onSave = async (values: IntegrationInput) => {
    setSaving(true)
    try {
      // GitLab 仅 PAT，不下发 authType；BASIC 才带 username
      const payload: IntegrationInput = {
        ...values,
        authType: isJira ? values.authType : undefined,
        username: isBasic && values.username ? values.username : undefined,
      }
      if (editing) {
        // token 留空 = 保持不变
        await updateIntegration(editing.id, { ...payload, token: values.token || undefined })
        message.success('已更新')
      } else {
        await createIntegration(payload)
        message.success('已创建，建议先点「测试」验证连通性')
      }
      setEditOpen(false)
      reload()
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    } finally {
      setSaving(false)
    }
  }

  const onTest = async (row: Integration) => {
    setTestingId(row.id)
    setConn(prev => ({ ...prev, [row.id]: { phase: 'probing' } }))
    try {
      const r = await testIntegration(row.id)
      setConn(prev => ({
        ...prev,
        [row.id]: { phase: 'done', ok: r.ok, message: r.message, detail: r.detail },
      }))
      if (r.ok) {
        message.success(`${row.name}：${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      } else {
        message.error(`${row.name}：${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      }
    } catch (e) {
      setConn(prev => ({
        ...prev,
        [row.id]: { phase: 'done', ok: false, message: (e as Error).message },
      }))
      message.error(`测试失败：${(e as Error).message}`)
    } finally {
      setTestingId(null)
    }
  }

  /** 表单内「测试连接」：新建走未保存试连；编辑且 token 留空（凭据未改）时测已保存实例 */
  const onTestForm = async () => {
    let values: IntegrationInput
    try {
      values = await form.validateFields(['type', 'baseUrl', 'authType', 'username', 'token'])
    } catch {
      return // 校验未过，错误已标红
    }
    setTestingForm(true)
    try {
      const r: IntegrationTestResult =
        editing && !values.token
          ? await testIntegration(editing.id)
          : await testIntegrationDraft({
              ...values,
              name: values.name ?? '',
              authType: isJira ? values.authType : undefined,
              username: isBasic && values.username ? values.username : undefined,
            })
      if (editing) {
        setConn(prev => ({
          ...prev,
          [editing.id]: { phase: 'done', ok: r.ok, message: r.message, detail: r.detail },
        }))
      }
      if (r.ok) {
        message.success(`${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      } else {
        message.error(`${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      }
    } catch (e) {
      message.error(`测试失败：${(e as Error).message}`)
    } finally {
      setTestingForm(false)
    }
  }

  const onToggle = async (row: Integration) => {
    try {
      await changeIntegrationStatus(row.id, row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED')
      message.success(row.status === 'ENABLED' ? '已停用' : '已启用')
      reload()
    } catch (e) {
      message.error(`操作失败：${(e as Error).message}`)
    }
  }

  return (
    <Card
      title="平台集成"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>刷新</Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setEditing(null)
              setEditOpen(true)
            }}
          >
            新建集成
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        管理 GitLab/GitHub/Jira 实例的接入凭据，供代码事件接入与 Jira 需求同步使用。
      </Typography.Paragraph>
      <Table<Integration>
        rowKey="id"
        loading={loading}
        dataSource={items}
        pagination={false}
        locale={{ emptyText: '还没有集成实例，点右上角「新建集成」接入 GitLab / GitHub / Jira' }}
        columns={[
          {
            title: '类型',
            dataIndex: 'type',
            width: 100,
            render: (t: string) => <Tag color={TYPE_COLOR[t] ?? 'default'}>{t}</Tag>,
          },
          { title: '名称', dataIndex: 'name' },
          {
            title: '地址',
            dataIndex: 'baseUrl',
            render: (u: string) => (
              <Typography.Text copyable style={{ fontSize: 12 }}>{u}</Typography.Text>
            ),
          },
          {
            title: '凭据',
            dataIndex: 'hasToken',
            width: 120,
            render: (has: boolean, row) =>
              has ? (
                <Tag color="green">{row.authType === 'BASIC' ? '账号密码' : 'PAT'}</Tag>
              ) : (
                <Tag>未配置</Tag>
              ),
          },
          {
            title: '连通性',
            key: 'conn',
            width: 100,
            render: (_, row) => {
              const c = conn[row.id]
              if (!c || c.phase === 'probing') {
                return <Badge status="processing" text="探测中" />
              }
              return (
                <Tooltip title={`${c.message}${c.detail ? `（${c.detail}）` : ''}`}>
                  {c.ok ? (
                    <Badge status="success" text="正常" />
                  ) : (
                    <Badge status="error" text="异常" />
                  )}
                </Tooltip>
              )
            },
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (s: string) => (
              <Tag color={s === 'ENABLED' ? 'green' : 'default'}>{s === 'ENABLED' ? '启用' : '停用'}</Tag>
            ),
          },
          {
            title: '操作',
            width: 220,
            render: (_, row) => (
              <Space size={4}>
                <Tooltip title="验证地址可达 + 凭据有效">
                  <Button
                    size="small"
                    icon={<ApiOutlined />}
                    loading={testingId === row.id}
                    onClick={() => onTest(row)}
                  >
                    测试
                  </Button>
                </Tooltip>
                <Button
                  size="small"
                  icon={<EditOutlined />}
                  onClick={() => {
                    setEditing(row)
                    setEditOpen(true)
                  }}
                >
                  编辑
                </Button>
                <Button size="small" onClick={() => onToggle(row)}>
                  {row.status === 'ENABLED' ? '停用' : '启用'}
                </Button>
              </Space>
            ),
          },
        ]}
      />

      <Drawer
        title={editing ? `编辑集成「${editing.name}」` : '新建集成'}
        open={editOpen}
        onClose={() => setEditOpen(false)}
        width={560}
        destroyOnHidden
        footer={
          <Space style={{ display: 'flex', justifyContent: 'space-between', width: '100%' }}>
            <Tooltip
              title={
                editing && !form.getFieldValue('token')
                  ? '凭据未修改，将测试已保存的配置'
                  : '按当前表单内容试连，凭据不会保存'
              }
            >
              <Button icon={<ApiOutlined />} loading={testingForm} onClick={onTestForm}>
                测试连接
              </Button>
            </Tooltip>
            <Space>
              <Button onClick={() => setEditOpen(false)}>取消</Button>
              <Button type="primary" loading={saving} onClick={() => form.submit()}>
                保存
              </Button>
            </Space>
          </Space>
        }
      >
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="平台类型" name="type" rules={[{ required: true, message: '请选择平台类型' }]}>
            <Select options={TYPE_OPTIONS} disabled={!!editing} />
          </Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 公司 Jira / 研发 GitLab" />
          </Form.Item>
          <Form.Item
            label="实例地址"
            name="baseUrl"
            rules={[{ required: true, message: '请输入实例地址' }]}
            extra={
              isGitHub
                ? 'github.com 填 https://github.com（API 自动走 api.github.com）；GHE 填实例地址（自动拼 /api/v3）'
                : '仅 http/https，如 https://jira.example.com'
            }
          >
            <Input placeholder={isGitHub ? 'https://github.com' : 'https://jira.example.com'} />
          </Form.Item>
          {isJira && (
            <Form.Item
              label="认证方式"
              name="authType"
              rules={[{ required: true, message: '请选择认证方式' }]}
              extra={
                editing
                  ? '认证方式创建后不可切换，换方式请新建集成'
                  : 'Jira Server/DC 8.13 及更早没有 PAT，选「用户名 + 密码」'
              }
            >
              <Select options={AUTH_OPTIONS} disabled={!!editing} />
            </Form.Item>
          )}
          {isBasic && (
            <Form.Item
              label="用户名"
              name="username"
              rules={
                editing
                  ? []
                  : [{ required: true, message: 'Basic Auth 需要填写 Jira 登录用户名' }]
              }
              extra={editing ? '留空表示沿用原用户名' : undefined}
            >
              <Input placeholder="Jira 登录用户名" autoComplete="off" />
            </Form.Item>
          )}
          <Form.Item
            label={isBasic ? '密码' : '访问令牌（PAT）'}
            name="token"
            rules={
              editing
                ? []
                : [{ required: true, message: isBasic ? '请输入密码' : '请输入 PAT' }]
            }
            extra={
              editing
                ? '留空表示保持现有凭据不变'
                : isBasic
                  ? 'Jira 登录密码（加密存储，仅用于调用 Jira API）'
                  : isGitHub
                    ? 'GitHub Personal Access Token（classic 需 repo scope；fine-grained 按仓库授权 Contents/Pull requests 读写）'
                    : 'Jira Server/DC 8.14+：个人访问令牌；GitLab：Personal Access Token（api scope）'
            }
          >
            <Input.Password
              placeholder={editing ? '（不修改请留空）' : isBasic ? '输入密码' : '粘贴 token'}
              autoComplete="off"
            />
          </Form.Item>
        </Form>
      </Drawer>
    </Card>
  )
}
