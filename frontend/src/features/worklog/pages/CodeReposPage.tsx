import {
  Button,
  Card,
  Checkbox,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import {
  createRepo,
  deleteRepo,
  listRepos,
  setSubscription,
  updateRepo,
} from '../api'
import type { WorklogRepo } from '../types'
import { fmtTime } from '../../../shared/utils/format'
import { isAdmin } from '../../auth/authStore'

/**
 * CAP-28 FR-01/02 全局代码仓库：平台级登记（写操作仅 ADMIN），用户勾选哪些仓库参与自己的 git log 扫描。
 */
export default function CodeReposPage() {
  const [rows, setRows] = useState<WorklogRepo[]>([])
  const [loading, setLoading] = useState(false)
  const [editTarget, setEditTarget] = useState<WorklogRepo | null>(null)
  const [editOpen, setEditOpen] = useState(false)
  const [form] = Form.useForm()

  const reload = () => {
    setLoading(true)
    listRepos()
      .then(setRows)
      .catch((e) => message.error(`加载仓库失败: ${e.message}`))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  const onSave = async () => {
    const v = await form.validateFields()
    try {
      if (editTarget) {
        await updateRepo(editTarget.id, v)
        message.success(`仓库「${v.name}」已更新`)
      } else {
        await createRepo(v)
        message.success(`仓库「${v.name}」已登记`)
      }
      setEditOpen(false)
      form.resetFields()
      reload()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    }
  }

  const toggle = async (r: WorklogRepo, checked: boolean) => {
    try {
      await setSubscription(r.id, checked)
      setRows(rows.map((x) => (x.id === r.id ? { ...x, subscribed: checked } : x)))
      message.success(checked ? `已勾选「${r.name}」，将参与你的 git 扫描` : `已取消勾选「${r.name}」`)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  return (
    <Card
      title="代码仓库"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            刷新
          </Button>
          {isAdmin() && (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                setEditTarget(null)
                setEditOpen(true)
              }}
            >
              登记仓库
            </Button>
          )}
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        平台级全局仓库登记（不挂项目）：勾选你参与的仓库后，git 扫描只按你的署名从这些仓库取当日提交。
      </Typography.Paragraph>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: '暂无登记仓库，请联系管理员在右上角「登记仓库」添加' }}
        columns={[
          {
            title: '参与扫描',
            width: 90,
            render: (_, r) => (
              <Checkbox checked={!!r.subscribed} onChange={(e) => toggle(r, e.target.checked)} />
            ),
          },
          { title: '名称', dataIndex: 'name', width: 160 },
          { title: '本地路径', dataIndex: 'localPath', ellipsis: true },
          {
            title: '远端',
            dataIndex: 'remoteUrl',
            ellipsis: true,
            render: (u: string) => u || '-',
          },
          { title: '默认分支', dataIndex: 'defaultBranch', width: 110, render: (b: string) => b || '-' },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (s: string) =>
              s === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>,
          },
          { title: '登记人', dataIndex: 'createdBy', width: 100 },
          {
            title: '登记时间',
            dataIndex: 'createdAt',
            width: 170,
            render: (t: string) => fmtTime(t),
          },
          ...(isAdmin()
            ? [
                {
                  title: '操作',
                  width: 150,
                  render: (_: unknown, r: WorklogRepo) => (
                    <Space>
                      <Button
                        size="small"
                        onClick={() => {
                          setEditTarget(r)
                          setEditOpen(true)
                          form.setFieldsValue(r)
                        }}
                      >
                        编辑
                      </Button>
                      <Popconfirm
                        title={`删除仓库「${r.name}」？（不影响已导入条目）`}
                        onConfirm={() =>
                          deleteRepo(r.id)
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
              ]
            : []),
        ]}
      />

      <Modal
        title={editTarget ? `编辑仓库：${editTarget.name}` : '登记代码仓库'}
        open={editOpen}
        onOk={onSave}
        onCancel={() => setEditOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：dev-mind" maxLength={128} />
          </Form.Item>
          <Form.Item
            name="localPath"
            label="本地路径"
            rules={[{ required: true, message: '请输入本地路径' }]}
            extra="服务器侧 git 扫描在此路径执行；保存时会用 git rev-parse 校验是 git 仓库"
          >
            <Input placeholder="如：D:/apusic/dev-mind" />
          </Form.Item>
          <Form.Item name="remoteUrl" label="远端地址" extra="可选；用于推断 host 匹配你的 git 署名">
            <Input placeholder="https://github.com/org/repo.git 或 git@github.com:org/repo.git" />
          </Form.Item>
          <Form.Item name="defaultBranch" label="默认分支">
            <Input placeholder="可选，如 master" />
          </Form.Item>
          {editTarget && (
            <Form.Item name="status" label="状态">
              <Input placeholder="ACTIVE / DISABLED" />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </Card>
  )
}
