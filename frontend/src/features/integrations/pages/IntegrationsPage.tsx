// CAP-18/19 平台集成管理页（仅 ADMIN）：集成实例列表 + 新建/编辑 + 连接测试 + 启停。
// GitLab（push 分支/MR/Release）与 Jira（issue 同步）共用同一 integrations 表。
import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Form,
  Input,
  Modal,
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
  updateIntegration,
} from '../api'
import type { Integration, IntegrationInput } from '../types'

const TYPE_OPTIONS = [
  { value: 'GITLAB', label: 'GitLab（代码平台）' },
  { value: 'JIRA', label: 'Jira（任务/Bug 同步）' },
]

const AUTH_OPTIONS = [
  { value: 'PAT', label: '个人访问令牌 PAT（Jira 8.14+）' },
  { value: 'BASIC', label: '用户名 + 密码（Jira 8.13 及更早）' },
]

const TYPE_COLOR: Record<string, string> = { GITLAB: 'orange', GITHUB: 'default', JIRA: 'blue' }

export default function IntegrationsPage() {
  const [items, setItems] = useState<Integration[]>([])
  const [loading, setLoading] = useState(false)
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<Integration | null>(null)
  const [testingId, setTestingId] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<IntegrationInput>()
  const formType = Form.useWatch('type', form)
  const formAuthType = Form.useWatch('authType', form)
  const isJira = formType === 'JIRA'
  const isBasic = isJira && formAuthType === 'BASIC'

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      setItems(await listIntegrations())
    } catch (e) {
      message.error(`加载集成失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [])

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
    try {
      const r = await testIntegration(row.id)
      if (r.ok) {
        message.success(`${row.name}：${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      } else {
        message.error(`${row.name}：${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      }
    } catch (e) {
      message.error(`测试失败：${(e as Error).message}`)
    } finally {
      setTestingId(null)
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
      size="small"
      title="平台集成"
      extra={
        <Space>
          <Button size="small" icon={<ReloadOutlined />} onClick={reload} />
          <Button
            size="small"
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
      <Table<Integration>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={items}
        pagination={false}
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

      <Modal
        title={editing ? `编辑集成「${editing.name}」` : '新建集成'}
        open={editOpen}
        onCancel={() => setEditOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        okText="保存"
        width={520}
        destroyOnHidden
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
            extra="仅 http/https，如 https://jira.example.com"
          >
            <Input placeholder="https://jira.example.com" />
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
                  : 'Jira Server/DC 8.14+：个人访问令牌；GitLab：Personal Access Token（api scope）'
            }
          >
            <Input.Password
              placeholder={editing ? '（不修改请留空）' : isBasic ? '输入密码' : '粘贴 token'}
              autoComplete="off"
            />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
