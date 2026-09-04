import { Button, Card, Form, Input, Modal, Popconfirm, Space, Table, Tag, Typography, message } from 'antd'
import { ApiOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import {
  createGitCredential,
  deleteGitCredential,
  listGitCredentials,
  testGitCredential,
  updateGitCredential,
} from '../api'
import type { GitCredential } from '../types'
import { fmtTime } from '../../../shared/utils/format'

/**
 * CAP-24 我的 Git 凭证：每 git 平台 host 一条 PAT + 提交署名。
 * 会话内 Agent 提交以该署名落 author/committer（env 注入），WI 分支 push 优先使用个人 PAT。
 */
export default function GitCredentialsPage() {
  const [items, setItems] = useState<GitCredential[]>([])
  const [loading, setLoading] = useState(false)
  const [editTarget, setEditTarget] = useState<GitCredential | null>(null)
  const [editOpen, setEditOpen] = useState(false)
  const [testTarget, setTestTarget] = useState<GitCredential | null>(null)
  const [testing, setTesting] = useState(false)
  const [form] = Form.useForm()
  const [testForm] = Form.useForm()

  const reload = () => {
    setLoading(true)
    listGitCredentials()
      .then(setItems)
      .catch((e) => message.error(`加载凭证失败: ${e.message}`))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  const openCreate = () => {
    setEditTarget(null)
    setEditOpen(true)
  }

  const openEdit = (c: GitCredential) => {
    setEditTarget(c)
    setEditOpen(true)
    // secret 不回显：留空 = 不修改
    form.setFieldsValue({ ...c, secret: '' })
  }

  const onSave = async () => {
    const v = await form.validateFields()
    try {
      if (editTarget) {
        await updateGitCredential(editTarget.id, v)
        message.success(`凭证「${v.label}」已更新`)
      } else {
        await createGitCredential(v)
        message.success(`凭证「${v.label}」已创建`)
      }
      setEditOpen(false)
      form.resetFields()
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    }
  }

  const onTest = async () => {
    const v = await testForm.validateFields()
    if (!testTarget) return
    setTesting(true)
    try {
      const r = await testGitCredential(testTarget.id, v.remoteUrl)
      message.success(r.message || '连接成功')
      setTestTarget(null)
      testForm.resetFields()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '自检失败')
    } finally {
      setTesting(false)
    }
  }

  return (
    <Card
      title="我的 Git 凭证"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建凭证
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        每个 git 平台（按 host）一条：会话内 Agent 提交以这里的署名落 author/committer；
        WI 分支推送优先使用你的个人 PAT，未配置时回退项目绑定的平台集成凭证。
      </Typography.Paragraph>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={items}
        pagination={false}
        locale={{ emptyText: '暂无凭证，点击右上角「新建凭证」添加你的第一个 Git 平台 PAT' }}
        columns={[
          { title: '名称', dataIndex: 'label' },
          { title: '平台地址', dataIndex: 'baseUrl' },
          {
            title: '提交署名',
            render: (_, c) => `${c.gitAuthorName} <${c.gitAuthorEmail}>`,
          },
          {
            title: 'PAT',
            dataIndex: 'hasSecret',
            render: (has: boolean) =>
              has ? <Tag color="green">已配置</Tag> : <Tag color="red">未配置</Tag>,
          },
          { title: '更新时间', dataIndex: 'updatedAt', render: (t: string) => fmtTime(t) },
          {
            title: '操作',
            render: (_, c) => (
              <Space>
                <Button size="small" icon={<ApiOutlined />} onClick={() => setTestTarget(c)}>
                  自检
                </Button>
                <Button size="small" onClick={() => openEdit(c)}>
                  编辑
                </Button>
                <Popconfirm
                  title={`删除凭证「${c.label}」？`}
                  onConfirm={() =>
                    deleteGitCredential(c.id)
                      .then(() => {
                        message.success('已删除')
                        reload()
                      })
                      .catch((e) => message.error(e instanceof Error ? e.message : '删除失败'))
                  }
                >
                  <Button size="small" danger>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={editTarget ? `编辑凭证：${editTarget.label}` : '新增 Git 凭证'}
        open={editOpen}
        onOk={onSave}
        onCancel={() => setEditOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item name="label" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：公司 GitLab" />
          </Form.Item>
          <Form.Item
            name="baseUrl"
            label="平台地址"
            rules={[{ required: true, message: '请输入平台地址' }]}
          >
            <Input placeholder="https://gitlab.example.com（按 host 匹配仓库远端）" />
          </Form.Item>
          <Form.Item
            name="secret"
            label="PAT（个人访问令牌）"
            rules={editTarget ? [] : [{ required: true, message: '请输入 PAT' }]}
            extra={editTarget ? '留空表示不修改' : '仅支持 http/https 平台的 PAT'}
          >
            <Input.Password placeholder={editTarget ? '留空不变' : 'glpat-...'} autoComplete="new-password" />
          </Form.Item>
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
        </Form>
      </Modal>

      <Modal
        title={`连通性自检：${testTarget?.label ?? ''}`}
        open={!!testTarget}
        onOk={onTest}
        onCancel={() => setTestTarget(null)}
        confirmLoading={testing}
        destroyOnHidden
      >
        <Form form={testForm} layout="vertical">
          <Form.Item
            name="remoteUrl"
            label="仓库地址"
            rules={[{ required: true, message: '请输入仓库地址' }]}
            extra="以该凭证执行 git ls-remote 验证，host 必须与凭证平台一致"
          >
            <Input placeholder="https://gitlab.example.com/group/repo.git" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
