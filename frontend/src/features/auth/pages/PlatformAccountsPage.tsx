import { Badge, Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, Typography, message } from 'antd'
import { ApiOutlined, ReloadOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import {
  listPlatformAccounts,
  testPlatformAccount,
  unbindPlatformAccount,
  upsertPlatformAccount,
} from '../api'
import type { PlatformAccountUpsertRequest } from '../api'
import type { PlatformAccount } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'

const TYPE_COLOR: Record<string, string> = { GITLAB: 'orange', GITHUB: 'default', JIRA: 'blue' }
const TYPE_LABEL: Record<string, string> = { GITLAB: 'GitLab', GITHUB: 'GitHub', JIRA: 'Jira' }

/**
 * CAP-35 我的第三方账号：一行 = 一个已启用的平台实例 + 我的绑定状态。
 * 绑定个人账号后，WI push / 建 MR·PR / Jira 状态转换 / 登记工时以本人身份执行；
 * git 平台账号同时决定会话内 Agent 提交的 author/committer 署名。
 */
export default function PlatformAccountsPage() {
  const [items, setItems] = useState<PlatformAccount[]>([])
  const [loading, setLoading] = useState(false)
  const [editTarget, setEditTarget] = useState<PlatformAccount | null>(null)
  const [saving, setSaving] = useState(false)
  const [testingId, setTestingId] = useState<number | null>(null)
  const [form] = Form.useForm<PlatformAccountUpsertRequest>()
  const formAuthType = Form.useWatch('authType', form)
  const isGit = editTarget?.integrationType === 'GITLAB' || editTarget?.integrationType === 'GITHUB'
  const isJira = editTarget?.integrationType === 'JIRA'

  const reload = () => {
    setLoading(true)
    listPlatformAccounts()
      .then(setItems)
      .catch((e) => message.error(`加载账号失败: ${e.message}`))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  const openEdit = (row: PlatformAccount) => {
    setEditTarget(row)
    // secret 不回显：留空 = 不修改；BASIC 用户名可回显（非敏感）
    form.setFieldsValue({
      authType: row.authType ?? 'PAT',
      username: row.username ?? undefined,
      secret: '',
      gitAuthorName: row.gitAuthorName ?? undefined,
      gitAuthorEmail: row.gitAuthorEmail ?? undefined,
    })
  }

  const onSave = async () => {
    if (!editTarget) return
    const v = await form.validateFields()
    setSaving(true)
    try {
      const payload: PlatformAccountUpsertRequest = {
        ...v,
        // git 平台固定 PAT，不下发 authType/username
        authType: isJira ? v.authType : undefined,
        username: isJira && v.authType === 'BASIC' ? v.username : undefined,
        secret: v.secret || undefined,
      }
      await upsertPlatformAccount(editTarget.integrationId, payload)
      message.success(editTarget.bound ? `「${editTarget.integrationName}」账号已更新` : `「${editTarget.integrationName}」绑定成功`)
      setEditTarget(null)
      form.resetFields()
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const onTest = async (row: PlatformAccount) => {
    setTestingId(row.integrationId)
    try {
      const r = await testPlatformAccount(row.integrationId)
      if (r.ok) {
        message.success(`${row.integrationName}：${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      } else {
        message.error(`${row.integrationName}：${r.message}${r.detail ? `（${r.detail}）` : ''}`)
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '自检失败')
    } finally {
      setTestingId(null)
    }
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title="第三方账号"
      extra={
        <Button icon={<ReloadOutlined />} onClick={reload}>
          刷新
        </Button>
      }
    >
      <Typography.Paragraph type="secondary">
        绑定你在各平台实例上的个人账号：会话内 Agent 提交以 git 平台账号的署名落 author/committer；
        WI 分支推送、创建 MR/PR、Jira 状态转换与工时登记优先以你的个人身份执行，
        未绑定时回退实例的平台（机器人）凭证。
      </Typography.Paragraph>
      <Table<PlatformAccount>
        rowKey="integrationId"
        loading={loading}
        dataSource={items}
        pagination={false}
        locale={{ emptyText: '暂无已启用的平台实例，请联系管理员在后台「平台集成」登记 GitLab / GitHub / Jira' }}
        columns={[
          {
            title: '类型',
            dataIndex: 'integrationType',
            width: 100,
            render: (t: string) => (
              <Tag color={TYPE_COLOR[t] ?? 'default'}>{TYPE_LABEL[t] ?? t}</Tag>
            ),
          },
          { title: '实例', dataIndex: 'integrationName' },
          {
            title: '地址',
            dataIndex: 'baseUrl',
            render: (u: string) => (
              <Typography.Text copyable style={{ fontSize: 12 }}>{u}</Typography.Text>
            ),
          },
          {
            title: '绑定状态',
            dataIndex: 'bound',
            width: 110,
            render: (bound: boolean, row) =>
              bound ? (
                <Space size={4}>
                  <Badge status="success" text="已绑定" />
                  <Tooltip title={row.authType === 'BASIC' ? `账号密码（${row.username}）` : 'PAT'}>
                    <Tag>{row.authType === 'BASIC' ? '账号密码' : 'PAT'}</Tag>
                  </Tooltip>
                </Space>
              ) : (
                <Badge status="default" text="未绑定" />
              ),
          },
          {
            title: '提交署名',
            width: 220,
            render: (_, row) =>
              row.gitAuthorName
                ? `${row.gitAuthorName} <${row.gitAuthorEmail}>`
                : '—',
          },
          {
            title: '绑定时间',
            dataIndex: 'updatedAt',
            width: 160,
            render: (t: string | null) => (t ? fmtTime(t) : '—'),
          },
          {
            title: '操作',
            width: 200,
            render: (_, row) => (
              <Space size={4}>
                <Button size="small" type={row.bound ? 'default' : 'primary'} onClick={() => openEdit(row)}>
                  {row.bound ? '编辑' : '绑定'}
                </Button>
                {row.bound && (
                  <>
                    <Button
                      size="small"
                      icon={<ApiOutlined />}
                      loading={testingId === row.integrationId}
                      onClick={() => onTest(row)}
                    >
                      自检
                    </Button>
                    <Popconfirm
                      title={`解绑「${row.integrationName}」的我的账号？`}
                      description="解绑后写操作将回退平台（机器人）凭证"
                      onConfirm={() =>
                        unbindPlatformAccount(row.integrationId)
                          .then(() => {
                            message.success('已解绑')
                            reload()
                          })
                          .catch((e) => message.error(e instanceof Error ? e.message : '解绑失败'))
                      }
                    >
                      <Button size="small" danger>
                        解绑
                      </Button>
                    </Popconfirm>
                  </>
                )}
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={editTarget ? `${editTarget.bound ? '编辑' : '绑定'}账号：${editTarget.integrationName}（${editTarget.baseUrl}）` : ''}
        open={!!editTarget}
        onOk={onSave}
        onCancel={() => setEditTarget(null)}
        confirmLoading={saving}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          {isJira && (
            <Form.Item
              name="authType"
              label="认证方式"
              rules={[{ required: true, message: '请选择认证方式' }]}
              extra="Jira Server/DC 8.13 及更早没有 PAT，选「用户名 + 密码」"
            >
              <Select
                options={[
                  { value: 'PAT', label: '个人访问令牌 PAT（Jira 8.14+）' },
                  { value: 'BASIC', label: '用户名 + 密码（Jira 8.13 及更早）' },
                ]}
              />
            </Form.Item>
          )}
          {isJira && formAuthType === 'BASIC' && (
            <Form.Item
              name="username"
              label="用户名"
              rules={editTarget?.bound && editTarget.authType === 'BASIC'
                ? []
                : [{ required: true, message: 'Basic Auth 需要填写 Jira 登录用户名' }]}
              extra={editTarget?.bound ? '留空表示沿用原用户名' : undefined}
            >
              <Input placeholder="Jira 登录用户名" autoComplete="off" />
            </Form.Item>
          )}
          <Form.Item
            name="secret"
            label={isJira && formAuthType === 'BASIC' ? '密码' : '访问令牌（PAT）'}
            rules={editTarget?.bound ? [] : [{ required: true, message: '请输入凭据' }]}
            extra={
              editTarget?.bound
                ? '留空表示保持不变（认证方式或用户名变更时需重填）'
                : '加密存储，仅用于以你的身份调用平台 API'
            }
          >
            <Input.Password
              placeholder={editTarget?.bound ? '（不修改请留空）' : '粘贴 token / 输入密码'}
              autoComplete="new-password"
            />
          </Form.Item>
          {isGit && (
            <>
              <Form.Item
                name="gitAuthorName"
                label="提交署名 Name"
                rules={[{ required: true, message: '请输入署名' }]}
              >
                <Input placeholder="git commit author 名" />
              </Form.Item>
              <Form.Item
                name="gitAuthorEmail"
                label="提交署名 Email"
                rules={[
                  { required: true, message: '请输入邮箱' },
                  { type: 'email', message: '邮箱格式不正确' },
                ]}
              >
                <Input placeholder="you@example.com" />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>
    </Card>
  )
}
