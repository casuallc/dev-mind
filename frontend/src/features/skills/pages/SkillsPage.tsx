// Skill 管理（基础模块）：skill 包列表 + 筛选；行内「管理」跳转详情页（页面模式，交互对齐文档管理），新建走 SkillFormDrawer。
// 布局遵循 docs/core/前端内容区布局约定.md：Card 标题，extra 放操作按钮，表格默认密度。
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Card, Input, Select, Space, Tag, Typography } from 'antd'
import {
  ImportOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { useNavigate } from 'react-router-dom'
import { canWrite } from '../../auth/authStore'
import { listProjects } from '../../projects/api'
import type { Project } from '../../projects/types'
import { listSkills } from '../api'
import type { Skill } from '../types'
import SkillFormDrawer from '../components/SkillFormDrawer'
import SkillImportModal from '../components/SkillImportModal'
import { fmtTime } from '../../../shared/utils/format'
import { pageCardStyle, pageCardBodyFlexStyle } from '../../../shared/utils/pageLayout'
import FitTable from '../../../shared/components/FitTable'
import { showError } from '../../../shared/utils/showError'

const scopeTag = (s: string) =>
  s === 'GLOBAL' ? <Tag color="blue">全局</Tag> : <Tag color="purple">项目</Tag>
const statusTag = (s: string) =>
  s === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>

export default function SkillsPage() {
  const navigate = useNavigate()
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
      showError(e, '加载 skill 失败')
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
    setFormOpen(true)
  }

  const columns: ColumnsType<Skill> = [
    {
      title: '名称',
      dataIndex: 'name',
      ellipsis: true,
      render: (name: string, r) => (
        <Space direction="vertical" size={0}>
          <Typography.Text code>
            <a onClick={() => navigate(`/admin/skills/${r.id}`)}>{name}</a>
          </Typography.Text>
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
      width: 90,
      render: (_, r) => (
        <Button size="small" onClick={() => navigate(`/admin/skills/${r.id}`)}>
          管理
        </Button>
      ),
    },
  ]

  return (
    <Card
      style={pageCardStyle}
      styles={{ body: pageCardBodyFlexStyle }}
      title="Skill 管理"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          {canWrite() && (
            <>
              <Button icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>
                导入
              </Button>
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                新建 Skill
              </Button>
            </>
          )}
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        Skill 是可复用的能力包（含说明与附件文件），分全局/项目两种范围；在此检索、启停与维护。
      </Typography.Paragraph>
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
      </Space>
      <FitTable
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
        locale={{
          emptyText: (
            <Space direction="vertical" size={8} style={{ padding: '24px 0' }}>
              <Typography.Text type="secondary">
                还没有 skill——「新建 Skill」手工创建，或「导入」从 zip 包批量导入。
              </Typography.Text>
              {canWrite() && (
                <div>
                  <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                    新建 Skill
                  </Button>
                </div>
              )}
            </Space>
          ),
        }}
      />

      <SkillFormDrawer
        open={formOpen}
        editing={null}
        projects={projects}
        onClose={() => setFormOpen(false)}
        onSaved={load}
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
