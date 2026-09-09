import {
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popover,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useEffect, useState } from 'react'
import { createRepo, deleteRepo, fetchRepo, listRepos, recloneRepo, updateRepo } from '../api'
import type { GitRepo } from '../types'
import { CLONE_STATUS_COLOR } from '../types'
// 集成实例列表为平台设施（projects feature 同样直接引用 integrations/api）
import { listIntegrations } from '../../integrations/api'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyScrollStyle } from '../../../shared/utils/pageLayout'
import { showError } from '../../../shared/utils/showError'

/**
 * CAP-29 全局代码仓库登记（仅 ADMIN，/admin/repos）：平台级独立资源，项目仓库只关联不复制。
 * CLONE = 服务端克隆（支持定时/手动 fetch）；LOCAL = 服务端本地路径登记。
 */
export default function ReposAdminPage() {
  const [rows, setRows] = useState<GitRepo[]>([])
  const [loading, setLoading] = useState(false)
  const [editTarget, setEditTarget] = useState<GitRepo | null>(null)
  const [editOpen, setEditOpen] = useState(false)
  const [integrationOptions, setIntegrationOptions] = useState<{ value: number; label: string }[]>([])
  const [form] = Form.useForm()
  const sourceType = Form.useWatch('sourceType', form)

  const reload = () => {
    setLoading(true)
    listRepos()
      .then(setRows)
      .catch((e) => showError(e, '加载仓库失败'))
      .finally(() => setLoading(false))
  }

  useEffect(reload, [])

  useEffect(() => {
    listIntegrations()
      .then((list) => {
        const git = list.filter((i) => (i.type === 'GITLAB' || i.type === 'GITHUB') && i.status === 'ENABLED')
        setIntegrationOptions(git.map((i) => ({ value: i.id, label: `${i.name}（${i.type} · ${i.baseUrl}）` })))
      })
      .catch(() => {
        // 集成列表加载失败不阻断表单（匿名克隆仍可用）
      })
  }, [])

  // 有克隆中的行时轮询刷新（同项目仓库页交互）
  const polling = rows.some((r) => r.cloneStatus === 'CLONING')
  useEffect(() => {
    if (!polling) return
    const t = setInterval(reload, 3000)
    return () => clearInterval(t)
  }, [polling])

  const openCreate = () => {
    setEditTarget(null)
    form.resetFields()
    form.setFieldsValue({ sourceType: 'CLONE' })
    setEditOpen(true)
  }

  const openEdit = (r: GitRepo) => {
    setEditTarget(r)
    form.resetFields()
    form.setFieldsValue(r)
    setEditOpen(true)
  }

  const onSave = async () => {
    const v = await form.validateFields()
    try {
      if (editTarget) {
        await updateRepo(editTarget.id, v)
        message.success(`仓库「${v.name}」已更新`)
      } else {
        await createRepo(v)
        message.success(
          v.sourceType === 'CLONE'
            ? `仓库「${v.name}」已登记，服务端开始克隆`
            : `仓库「${v.name}」已登记`,
        )
      }
      setEditOpen(false)
      form.resetFields()
      reload()
    } catch (e) {
      showError(e, '保存失败')
    }
  }

  const onFetch = async (r: GitRepo) => {
    try {
      await fetchRepo(r.id)
      message.success(`已触发「${r.name}」抓取`)
      setTimeout(reload, 1500)
    } catch (e) {
      showError(e, '抓取失败')
    }
  }

  const onReclone = async (r: GitRepo) => {
    try {
      await recloneRepo(r.id)
      message.success(`已触发「${r.name}」重新克隆`)
      setTimeout(reload, 1500)
    } catch (e) {
      showError(e, '操作失败')
    }
  }

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyScrollStyle }}
      title="代码仓库"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            登记仓库
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        平台级全局仓库：项目添加仓库时按远端地址自动关联到此处（不复制）；工时日志的 git 扫描也读取这里的服务端克隆。
        CLONE 行由服务端定时抓取（默认 30 分钟），也可手动「立即抓取」。
      </Typography.Paragraph>
      <Table
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: '暂无登记仓库，点击右上角「登记仓库」添加' }}
        columns={[
          { title: '名称', dataIndex: 'name', width: 160 },
          {
            title: '来源',
            dataIndex: 'sourceType',
            width: 100,
            render: (s: string) =>
              s === 'CLONE' ? <Tag color="blue">服务端克隆</Tag> : <Tag>本地路径</Tag>,
          },
          {
            title: '远端',
            dataIndex: 'remoteUrl',
            ellipsis: true,
            render: (u: string) => u || '-',
          },
          { title: '本地路径', dataIndex: 'localPath', ellipsis: true },
          {
            title: '默认分支',
            dataIndex: 'defaultBranch',
            width: 110,
            render: (b: string) => b || '-',
          },
          {
            title: '分支',
            dataIndex: 'branches',
            width: 80,
            render: (bs: string[]) =>
              bs && bs.length > 0 ? (
                <Popover
                  content={
                    <div style={{ maxHeight: 300, overflow: 'auto' }}>
                      {bs.map((b) => (
                        <div key={b}>{b}</div>
                      ))}
                    </div>
                  }
                  title={`远程分支（${bs.length}）`}
                >
                  <a>{bs.length} 个</a>
                </Popover>
              ) : (
                '-'
              ),
          },
          {
            title: '克隆状态',
            dataIndex: 'cloneStatus',
            width: 100,
            render: (s: string, r) => {
              const tag = <Tag color={CLONE_STATUS_COLOR[s ?? 'NONE']}>{s ?? 'NONE'}</Tag>
              return s === 'FAILED' && r.cloneError ? (
                <Tooltip title={r.cloneError}>{tag}</Tooltip>
              ) : (
                tag
              )
            },
          },
          {
            title: '最近抓取',
            dataIndex: 'lastFetchAt',
            width: 170,
            render: (t: string, r) =>
              t ? (
                r.lastFetchError ? (
                  <Tooltip title={r.lastFetchError}>
                    <span style={{ color: '#cf1322' }}>{fmtTime(t)}</span>
                  </Tooltip>
                ) : (
                  fmtTime(t)
                )
              ) : (
                '-'
              ),
          },
          {
            title: '状态',
            dataIndex: 'status',
            width: 80,
            render: (s: string) => (s === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>),
          },
          {
            title: '操作',
            width: 230,
            render: (_, r) => (
              <Space>
                {r.sourceType === 'CLONE' && r.cloneStatus === 'READY' && (
                  <Button size="small" onClick={() => onFetch(r)}>
                    立即抓取
                  </Button>
                )}
                {r.sourceType === 'CLONE' && r.cloneStatus === 'FAILED' && (
                  <Button size="small" onClick={() => onReclone(r)}>
                    重试克隆
                  </Button>
                )}
                <Button size="small" onClick={() => openEdit(r)}>
                  编辑
                </Button>
                <Button
                  size="small"
                  danger
                  onClick={() =>
                    Modal.confirm({
                      centered: true,
                      title: `删除仓库「${r.name}」？`,
                      content: '被项目引用时将被拒绝。',
                      okText: '删除',
                      okButtonProps: { danger: true },
                      onOk: () =>
                        deleteRepo(r.id)
                          .then(() => {
                            message.success('已删除')
                            reload()
                          })
                          .catch((e) => showError(e, '删除失败')),
                    })
                  }
                >
                  删除
                </Button>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title={editTarget ? `编辑仓库：${editTarget.name}` : '登记代码仓库'}
        open={editOpen}
        onOk={onSave}
        onCancel={() => setEditOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" initialValues={{ sourceType: 'CLONE' }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如：dev-mind" maxLength={128} />
          </Form.Item>
          <Form.Item name="sourceType" label="来源" extra="登记后不可切换来源类型">
            <Radio.Group disabled={!!editTarget}>
              <Radio.Button value="CLONE">服务端克隆</Radio.Button>
              <Radio.Button value="LOCAL">本地路径</Radio.Button>
            </Radio.Group>
          </Form.Item>
          {(sourceType ?? 'CLONE') === 'CLONE' ? (
            <>
              <Form.Item
                name="remoteUrl"
                label="远端地址"
                rules={[{ required: true, message: '请输入远端地址' }]}
                extra="仅支持 http/https；同一远端全平台只登记一份，项目添加仓库时自动关联"
              >
                <Input placeholder="https://github.com/org/repo.git" />
              </Form.Item>
              <Form.Item
                name="integrationId"
                label="克隆凭证（平台集成）"
                extra="私有仓库选择对应 GitLab/GitHub 集成；公开仓库可留空"
              >
                <Select allowClear options={integrationOptions} placeholder="匿名克隆（公开仓库）" />
              </Form.Item>
            </>
          ) : (
            <Form.Item
              name="localPath"
              label="本地路径"
              rules={[{ required: true, message: '请输入本地路径' }]}
              extra="服务器侧路径；保存时用 git rev-parse 校验是 git 仓库"
            >
              <Input placeholder="如：D:/apusic/dev-mind" />
            </Form.Item>
          )}
          <Form.Item name="defaultBranch" label="默认分支" extra="留空则克隆后自动识别远端 HEAD">
            <Input placeholder="可选，如 master" />
          </Form.Item>
          {editTarget && (
            <Form.Item name="status" label="状态">
              <Select
                options={[
                  { value: 'ACTIVE', label: '启用' },
                  { value: 'DISABLED', label: '停用' },
                ]}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </Card>
  )
}
