// 项目仓库页（/admin/projects/:id/repos，P0-4 多库模型）：列表 + 添加/编辑抽屉 + 设主库/移除。
// CAP-23：克隆状态列 + 克隆/重试/日志操作 + 抽屉按 sourceType 切换（LOCAL 填路径，CLONE 填远端+集成实例）。
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Button,
  Card,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Radio,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useOutletContext, useParams } from 'react-router-dom'
import { addRepo, cloneRepo, deleteRepo, listRepos, setPrimaryRepo, updateRepo } from '../../api'
import type { ProjectRepo, ProjectRepoInput } from '../../types'
import CloneLogDrawer, { CLONE_STATUS_COLOR } from '../../components/CloneLogDrawer'
import { useGitIntegrations } from '../../hooks/useGitIntegrations'

const ROLE_OPTIONS = ['CODE', 'DOCS', 'CONFIG']

export default function ReposPage() {
  const { id = '' } = useParams<{ id: string }>()
  // 主库切换会同步 projects.path 镜像，通知布局刷新头部展示
  const { reloadProject } = useOutletContext<{ reloadProject: () => void }>()
  const [repos, setRepos] = useState<ProjectRepo[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ProjectRepo | null>(null)
  const [logRepo, setLogRepo] = useState<ProjectRepo | null>(null)
  const [form] = Form.useForm()
  const sourceType = Form.useWatch('sourceType', form) ?? 'LOCAL'
  const { options: integrationOptions } = useGitIntegrations()

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      setRepos(await listRepos(id))
    } catch (e) {
      message.error(`加载仓库失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    reload()
  }, [reload])

  // CAP-23：存在 CLONING 行时 5s 轮询（终态由 WS done 帧也会触发 reload，轮询兜底）
  const polling = repos.some((r) => r.cloneStatus === 'CLONING')
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  useEffect(() => {
    if (polling) {
      timerRef.current = setInterval(reload, 5000)
      return () => {
        if (timerRef.current) clearInterval(timerRef.current)
      }
    }
  }, [polling, reload])

  const openEdit = (r: ProjectRepo | null) => {
    setEditing(r)
    form.setFieldsValue(
      r
        ? { ...r, sourceType: r.sourceType ?? 'LOCAL', integrationId: r.integrationId ?? undefined }
        : {
            name: '',
            sourceType: 'LOCAL',
            path: '',
            remoteUrl: '',
            integrationId: undefined,
            defaultBranch: '',
            role: 'CODE',
            primary: repos.length === 0,
            sortOrder: repos.length,
          },
    )
    setOpen(true)
  }

  const onSave = async (v: ProjectRepoInput) => {
    try {
      // CLONE 模式 path 由服务端计算；LOCAL 不带克隆字段
      const payload: ProjectRepoInput =
        v.sourceType === 'CLONE'
          ? { ...v, path: undefined, remoteUrl: v.remoteUrl || undefined }
          : { ...v, remoteUrl: v.remoteUrl || undefined, integrationId: undefined }
      if (editing) {
        await updateRepo(id, editing.id, payload)
      } else {
        await addRepo(id, payload)
        if (payload.sourceType === 'CLONE') {
          message.success('已添加，后台开始克隆（可点「日志」查看进度）')
          setOpen(false)
          await reload()
          return
        }
      }
      setOpen(false)
      await reload()
      message.success('已保存')
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`)
    }
  }

  const triggerClone = async (r: ProjectRepo) => {
    try {
      await cloneRepo(id, r.id)
      message.success(`已触发克隆：${r.name}`)
      await reload()
    } catch (e) {
      message.error(`触发克隆失败：${(e as Error).message}`)
    }
  }

  const makePrimary = (r: ProjectRepo) => {
    Modal.confirm({
      centered: true,
      title: '设为主库？',
      content: `「${r.name}」将成为项目主库（会话/构建/摘要默认使用的仓库）。`,
      okText: '设为主库',
      cancelText: '取消',
      onOk: async () => {
        try {
          await setPrimaryRepo(id, r.id)
          await reload()
          reloadProject()
          message.success('已切换主库')
        } catch (e) {
          message.error((e as Error).message)
        }
      },
    })
  }

  const confirmDelete = (r: ProjectRepo) => {
    Modal.confirm({
      centered: true,
      title: '移除仓库？',
      content: `将从项目移除仓库「${r.name}」（不删除磁盘上的仓库）。`,
      okText: '移除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteRepo(id, r.id)
          await reload()
          message.success('已移除')
        } catch (e) {
          message.error((e as Error).message)
        }
      },
    })
  }

  const columns: ColumnsType<ProjectRepo> = [
    {
      title: '名称',
      dataIndex: 'name',
      width: 160,
      render: (n: string, r) => (
        <Space size={4}>
          {n}
          {r.primary && <Tag color="gold">主库</Tag>}
        </Space>
      ),
    },
    { title: '角色', dataIndex: 'role', width: 90, render: (v: string) => <Tag color={v === 'CODE' ? 'blue' : v === 'DOCS' ? 'green' : 'purple'}>{v}</Tag> },
    {
      title: '克隆状态',
      dataIndex: 'cloneStatus',
      width: 100,
      render: (_: unknown, r: ProjectRepo) => {
        if (r.sourceType !== 'CLONE') return <Tag>本地</Tag>
        const tag = (
          <Tag color={CLONE_STATUS_COLOR[r.cloneStatus ?? 'NONE']}>{r.cloneStatus ?? 'NONE'}</Tag>
        )
        return r.cloneStatus === 'FAILED' && r.cloneError ? (
          <Tooltip title={r.cloneError}>{tag}</Tooltip>
        ) : (
          tag
        )
      },
    },
    { title: '路径', dataIndex: 'path', ellipsis: true, render: (p: string) => <Typography.Text code style={{ fontSize: 12 }}>{p}</Typography.Text> },
    { title: '默认分支', dataIndex: 'defaultBranch', width: 110, render: (v?: string) => v || '-' },
    { title: '远端', dataIndex: 'remoteUrl', width: 160, ellipsis: true, render: (v?: string) => v || '-' },
    { title: '排序', dataIndex: 'sortOrder', width: 60 },
    {
      title: '操作',
      key: 'action',
      width: 260,
      render: (_: unknown, r: ProjectRepo) => (
        <Space size={4}>
          {!r.primary && <Button size="small" onClick={() => makePrimary(r)}>设为主库</Button>}
          {r.sourceType === 'CLONE' && r.cloneStatus !== 'CLONING' && (
            <Button size="small" onClick={() => triggerClone(r)}>
              {r.cloneStatus === 'READY' ? '重新克隆' : r.cloneStatus === 'FAILED' ? '重试' : '克隆'}
            </Button>
          )}
          {r.sourceType === 'CLONE' && (
            <Button size="small" onClick={() => setLogRepo(r)}>日志</Button>
          )}
          <Button size="small" onClick={() => openEdit(r)}>编辑</Button>
          {!r.primary && <Button size="small" danger onClick={() => confirmDelete(r)}>移除</Button>}
        </Space>
      ),
    },
  ]

  return (
    <Card
      title="仓库"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit(null)}>
            添加仓库
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        项目为多 git 库组合：代码/文档/配置各有角色；恰好一个主库（projects.path 为主库镜像）。「从 Git 克隆」由服务端拉取到项目工作区。
      </Typography.Paragraph>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={repos}
        loading={loading}
        pagination={false}
        locale={{ emptyText: '暂无仓库。点击「添加仓库」登记本地路径或从 Git 克隆。' }}
      />
      <Drawer title={editing ? '编辑仓库' : '添加仓库'} open={open} onClose={() => setOpen(false)}
        width={600} destroyOnHidden
        footer={
          <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button onClick={() => setOpen(false)}>取消</Button>
            <Button type="primary" onClick={() => form.submit()}>保存</Button>
          </Space>
        }>
        <Form form={form} layout="vertical" onFinish={onSave}>
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 后端服务 / 前端 / 文档库" />
          </Form.Item>
          <Form.Item
            label="仓库来源"
            name="sourceType"
            extra={editing ? '仓库来源创建后不可变更' : '克隆模式：服务端从 GitLab/GitHub 拉取到项目工作区'}
          >
            <Radio.Group disabled={!!editing} optionType="button" buttonStyle="solid">
              <Radio.Button value="LOCAL">本地路径</Radio.Button>
              <Radio.Button value="CLONE">从 Git 克隆</Radio.Button>
            </Radio.Group>
          </Form.Item>
          {sourceType === 'CLONE' ? (
            <>
              <Form.Item
                label="远端仓库地址"
                name="remoteUrl"
                rules={[{ required: !editing, message: '请输入 http/https 仓库地址' }]}
                extra={editing ? '留空保持不变；修改后需手动点「重新克隆」生效' : '仅支持 http/https（PAT 注入认证），不支持 ssh'}
              >
                <Input placeholder="https://gitlab.example.com/group/my-repo.git" />
              </Form.Item>
              <Form.Item
                label="集成实例（认证）"
                name="integrationId"
                extra="私有仓库选择对应 GitLab/GitHub 实例（PAT 克隆）；公开仓库可不选 = 匿名克隆"
              >
                <Select allowClear placeholder="不选 = 公开仓库匿名克隆" options={integrationOptions} />
              </Form.Item>
            </>
          ) : (
            <Form.Item label="本地仓库路径" name="path" rules={[{ required: true, message: '请输入 git 仓库路径' }]}>
              <Input placeholder="本地 git 仓库绝对路径" />
            </Form.Item>
          )}
          <Form.Item
            label="默认分支"
            name="defaultBranch"
            extra={sourceType === 'CLONE' ? '留空则克隆成功后自动探测远端默认分支' : undefined}
          >
            <Input placeholder="如 master / main" />
          </Form.Item>
          <Form.Item label="角色" name="role">
            <Select options={ROLE_OPTIONS.map((v) => ({ value: v, label: v }))} />
          </Form.Item>
          <Form.Item label="设为主库" name="primary" valuePropName="checked" extra="项目内唯一；会话/构建/摘要默认使用主库">
            <Switch disabled={editing?.primary} />
          </Form.Item>
          <Form.Item label="排序" name="sortOrder">
            <InputNumber min={0} />
          </Form.Item>
        </Form>
      </Drawer>
      <CloneLogDrawer
        projectId={id}
        repo={logRepo}
        onClose={() => setLogRepo(null)}
        onDone={() => {
          reload()
          reloadProject()
        }}
      />
    </Card>
  )
}
