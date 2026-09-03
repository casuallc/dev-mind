// Skill 管理（基础模块）：skill 包列表 + 筛选 + 启停/删除；新建/编辑走 SkillFormDrawer，附件走 SkillFilesDrawer。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Card, Input, message, Modal, Select, Space, Table, Tag, Typography } from 'antd'
import {
  DeleteOutlined,
  EditOutlined,
  FileAddOutlined,
  FolderOpenOutlined,
  ImportOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { canWrite } from '../../auth/authStore'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { deleteSkill, getSkill, listSkills, updateSkillStatus } from '../api'
import type { Skill, SkillDetail } from '../types'
import SkillFormDrawer from '../components/SkillFormDrawer'
import SkillFilesDrawer from '../components/SkillFilesDrawer'
import SkillImportModal from '../components/SkillImportModal'
import { fmtTime } from '../../../shared/utils/format'

const scopeTag = (s: string) =>
  s === 'GLOBAL' ? <Tag color="blue">全局</Tag> : <Tag color="purple">项目</Tag>
const statusTag = (s: string) =>
  s === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>

export default function SkillsPage() {
  const [projects, setProjects] = useState<Project[]>([])
  const [items, setItems] = useState<Skill[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [scopeFilter, setScopeFilter] = useState<string>('')
  const [projectFilter, setProjectFilter] = useState<string>('')
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)

  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<SkillDetail | null>(null)
  const [filesSkill, setFilesSkill] = useState<Skill | null>(null)
  const [importOpen, setImportOpen] = useState(false)

  const projectName = useMemo(() => {
    const m = new Map(projects.map((p) => [p.id, p.name]))
    return (id?: string | null) => (id ? (m.get(id) ?? id) : '-')
  }, [projects])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listSkills({
        scope: scopeFilter || undefined,
        projectId: projectFilter || undefined,
        status: statusFilter || undefined,
        keyword: keyword.trim() || undefined,
        page,
        size,
      })
      setItems(r.items)
      setTotal(r.total)
    } catch (e) {
      message.error(`加载 skill 失败：${(e as Error).message}`)
    } finally {
      setLoading(false)
    }
  }, [scopeFilter, projectFilter, statusFilter, keyword, page, size])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => {
    listProjects().then(setProjects).catch(() => undefined)
  }, [])

  const openCreate = () => {
    setEditing(null)
    setFormOpen(true)
  }

  const openEdit = async (s: Skill) => {
    try {
      setEditing(await getSkill(s.id))
      setFormOpen(true)
    } catch (e) {
      message.error(`加载详情失败：${(e as Error).message}`)
    }
  }

  const onToggleStatus = async (s: Skill) => {
    try {
      await updateSkillStatus(s.id, s.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
      message.success(s.status === 'ACTIVE' ? '已停用' : '已启用')
      load()
    } catch (e) {
      message.error(`操作失败：${(e as Error).message}`)
    }
  }

  const onDelete = (s: Skill) => {
    Modal.confirm({
      centered: true,
      title: `删除 skill「${s.name}」？`,
      content: '将同时删除其全部附件文件，删除后无法恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteSkill(s.id)
          message.success('已删除')
          load()
        } catch (e) {
          message.error(`删除失败：${(e as Error).message}`)
        }
      },
    })
  }

  const columns: ColumnsType<Skill> = [
    {
      title: '名称',
      dataIndex: 'name',
      ellipsis: true,
      render: (name: string, r) => (
        <Space direction="vertical" size={0}>
          <Typography.Text code>{name}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }} ellipsis>
            {r.description}
          </Typography.Text>
        </Space>
      ),
    },
    { title: '范围', dataIndex: 'scope', width: 80, render: scopeTag },
    { title: '项目', dataIndex: 'projectId', width: 140, ellipsis: true, render: (v) => projectName(v) },
    {
      title: '标签',
      dataIndex: 'tags',
      width: 160,
      render: (tags: string[]) =>
        tags?.length ? tags.map((t) => <Tag key={t}>{t}</Tag>) : '-',
    },
    { title: '附件', dataIndex: 'fileCount', width: 70 },
    { title: '状态', dataIndex: 'status', width: 80, render: statusTag },
    { title: '更新时间', dataIndex: 'updatedAt', width: 165, render: (v) => fmtTime(v) },
    {
      title: '操作',
      width: 220,
      render: (_, r) => (
        <Space size={4}>
          {canWrite() && (
            <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
              编辑
            </Button>
          )}
          <Button size="small" icon={<FolderOpenOutlined />} onClick={() => setFilesSkill(r)}>
            附件
          </Button>
          {canWrite() && (
            <>
              <Button size="small" onClick={() => onToggleStatus(r)}>
                {r.status === 'ACTIVE' ? '停用' : '启用'}
              </Button>
              <Button size="small" danger icon={<DeleteOutlined />} onClick={() => onDelete(r)} />
            </>
          )}
        </Space>
      ),
    },
  ]

  return (
    <Card
      size="small"
      title="Skill 管理"
      extra={
        canWrite() && (
          <Space>
            <Button icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>
              导入
            </Button>
            <Button type="primary" icon={<FileAddOutlined />} onClick={openCreate}>
              新增 Skill
            </Button>
          </Space>
        )
      }
    >
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索名称/描述"
          style={{ width: 240 }}
          onSearch={(v) => {
            setKeyword(v)
            setPage(0)
          }}
        />
        <Select
          allowClear
          placeholder="范围"
          style={{ width: 110 }}
          value={scopeFilter || undefined}
          onChange={(v) => {
            setScopeFilter(v ?? '')
            if (v !== 'PROJECT') setProjectFilter('')
            setPage(0)
          }}
          options={[
            { value: 'GLOBAL', label: '全局' },
            { value: 'PROJECT', label: '项目' },
          ]}
        />
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          placeholder="项目"
          style={{ width: 220 }}
          disabled={scopeFilter !== 'PROJECT'}
          value={projectFilter || undefined}
          onChange={(v) => {
            setProjectFilter(v ?? '')
            setPage(0)
          }}
          options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.id})` }))}
        />
        <Select
          allowClear
          placeholder="状态"
          style={{ width: 100 }}
          value={statusFilter || undefined}
          onChange={(v) => {
            setStatusFilter(v ?? '')
            setPage(0)
          }}
          options={[
            { value: 'ACTIVE', label: '启用' },
            { value: 'DISABLED', label: '停用' },
          ]}
        />
        <Button icon={<ReloadOutlined />} onClick={load} />
      </Space>
      <Table
        size="small"
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={items}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(s !== size ? 0 : p - 1)
            setSize(s)
          },
        }}
      />

      <SkillFormDrawer
        open={formOpen}
        editing={editing}
        projects={projects}
        onClose={() => setFormOpen(false)}
        onSaved={load}
      />
      <SkillFilesDrawer
        open={filesSkill != null}
        skill={filesSkill}
        onClose={() => setFilesSkill(null)}
      />
      <SkillImportModal
        open={importOpen}
        projects={projects}
        onClose={() => setImportOpen(false)}
        onSaved={load}
      />
    </Card>
  )
}
