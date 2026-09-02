import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  DatePicker,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { CopyOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { deleteApiKey, issueApiKey, listApiKeys, setApiKeyEnabled } from '../api'
import type { ApiKey, IssuedKey } from '../types'
import { fmtTime } from '../../../shared/utils/format'

/**
 * CAP-20 后台页：API 密钥管理（仅 ADMIN）。
 * 密钥对 open-api（/open-api/v1/**，HMAC 签名）独立认证，供外部脚本/客户端/AI 接入助手使用。
 */
export default function ApiKeysPage() {
  const [keys, setKeys] = useState<ApiKey[]>([])
  const [loading, setLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [issued, setIssued] = useState<IssuedKey | null>(null)
  const [form] = Form.useForm<{ name: string; expiresAt?: dayjs.Dayjs }>()

  const reload = () => {
    setLoading(true)
    listApiKeys()
      .then(setKeys)
      .catch((e) => message.error(`加载密钥失败: ${e.message}`))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  const onCreate = async () => {
    const v = await form.validateFields()
    try {
      const res = await issueApiKey({
        name: v.name,
        expiresAt: v.expiresAt ? v.expiresAt.toISOString() : null,
      })
      setCreateOpen(false)
      form.resetFields()
      // secret 仅此一次可见——弹窗展示，关闭后无法再查
      setIssued(res)
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '签发失败')
    }
  }

  const copy = async (text: string, label: string) => {
    await navigator.clipboard.writeText(text)
    message.success(`${label}已复制`)
  }

  const columns = [
    { title: '名称', dataIndex: 'name', width: 180 },
    {
      title: 'Access Key',
      dataIndex: 'accessKey',
      render: (ak: string) => <Typography.Text code>{ak}</Typography.Text>,
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 110,
      render: (enabled: boolean, r: ApiKey) => {
        const expired = r.expiresAt && dayjs(r.expiresAt).isBefore(dayjs())
        return (
          <Space size={4}>
            <Tag color={enabled && !expired ? 'green' : 'default'}>{enabled ? '启用' : '禁用'}</Tag>
            {expired && <Tag color="red">已过期</Tag>}
          </Space>
        )
      },
    },
    {
      title: '过期时间',
      dataIndex: 'expiresAt',
      width: 170,
      render: (t: string | null) => (t ? fmtTime(t) : '永不过期'),
    },
    {
      title: '最近使用',
      dataIndex: 'lastUsedAt',
      width: 170,
      render: (t: string | null) => fmtTime(t),
    },
    { title: '创建人', dataIndex: 'createdBy', width: 100, render: (s: string | null) => s || '-' },
    {
      title: '操作',
      key: 'act',
      width: 190,
      render: (_: unknown, r: ApiKey) => (
        <Space size={8}>
          <Switch
            size="small"
            checked={r.enabled}
            onChange={async (checked) => {
              await setApiKeyEnabled(r.id, checked)
              message.success(`已${checked ? '启用' : '禁用'} ${r.name}`)
              reload()
            }}
          />
          <Popconfirm title={`删除密钥「${r.name}」？`} onConfirm={async () => {
            await deleteApiKey(r.id)
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
        <h2 style={{ margin: 0 }}>API 密钥</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建密钥
        </Button>
      </Space>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="密钥用于调用开放 API（/open-api/v1/**，HMAC-SHA256 签名认证）。调用方式见 scripts/openapi.sh；secret 仅在创建时展示一次。"
      />
      <Table<ApiKey>
        rowKey="id"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={keys}
        pagination={false}
      />

      <Modal
        title="新建 API 密钥"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={onCreate}
        okText="签发"
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 ci-bot / gitlab-runner" />
          </Form.Item>
          <Form.Item label="过期时间（留空 = 永不过期）" name="expiresAt">
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="密钥已签发（仅此一次展示）"
        open={!!issued}
        footer={<Button type="primary" onClick={() => setIssued(null)}>我已保存</Button>}
        onCancel={() => setIssued(null)}
      >
        {issued && (
          <Space direction="vertical" style={{ width: '100%' }} size={8}>
            <Alert type="warning" showIcon message="请立即复制保存 secret——关闭后无法再次查看，只能重新签发。" />
            <div>
              <Typography.Text type="secondary">Access Key</Typography.Text>
              <div>
                <Typography.Text code copyable={{ text: issued.key.accessKey }}>{issued.key.accessKey}</Typography.Text>
              </div>
            </div>
            <div>
              <Typography.Text type="secondary">Secret</Typography.Text>
              <div>
                <Typography.Text code copyable={{ text: issued.secret }}>{issued.secret}</Typography.Text>
              </div>
            </div>
            <Button icon={<CopyOutlined />} onClick={() =>
              copy(`DEVMIND_AK=${issued.key.accessKey}\nDEVMIND_SK=${issued.secret}`, '环境变量')
            }>
              复制为环境变量（DEVMIND_AK / DEVMIND_SK）
            </Button>
          </Space>
        )}
      </Modal>
    </div>
  )
}
