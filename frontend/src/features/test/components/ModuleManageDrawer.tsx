// CAP-104 功能模块管理：项目 → 模块 → 套件 → 用例的中间层。
// 删除模块只把套件置为未分类，不级联删除套件/用例。
import { Button, Drawer, Flex, Form, Input, InputNumber, Modal, Space, Table, Tag, message } from 'antd'
import { useEffect, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { createModule, deleteModule, listModules, updateModule } from '../api'
import type { TestModule, TestModuleInput } from '../types'
import { DRAWER_DEFAULT } from '../../../shared/utils/overlay'
import { fmtTime } from '../../../shared/utils/format'
import { showError } from '../../../shared/utils/showError'

interface FormValues {
  name: string
  owner?: string
  tags?: string
  description?: string
  sortNo?: number
}

export default function ModuleManageDrawer({ open, projectId, onClose, onChanged }: {
  open: boolean
  projectId: string
  onClose: () => void
  onChanged: () => void
}) {
  const [rows, setRows] = useState<TestModule[]>([])
  const [loading, setLoading] = useState(false)
  const [editing, setEditing] = useState<TestModule | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<FormValues>()

  const load = () => {
    setLoading(true)
    listModules(projectId)
      .then(setRows)
      .catch((e) => showError(e, '加载模块失败'))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    if (!open) return
    load()
    setEditing(null)
    form.resetFields()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, projectId])

  const startEdit = (m: TestModule | null) => {
    setEditing(m)
    form.setFieldsValue(m
      ? { name: m.name, owner: m.owner ?? '', tags: m.tags ?? '', description: m.description ?? '', sortNo: m.sortNo }
      : { name: '', owner: '', tags: '', description: '', sortNo: 100 })
  }

  const save = async () => {
    const v = await form.validateFields()
    const input: TestModuleInput = {
      name: v.name,
      owner: v.owner?.trim() || undefined,
      tags: v.tags?.trim() || undefined,
      description: v.description?.trim() || undefined,
      sortNo: v.sortNo ?? undefined,
    }
    setSaving(true)
    try {
      if (editing) await updateModule(editing.id, input)
      else await createModule(projectId, input)
      message.success(editing ? '模块已更新' : '模块已创建')
      setEditing(null)
      form.resetFields()
      load()
      onChanged()
    } catch (e) {
      showError(e, '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const remove = (m: TestModule) => {
    Modal.confirm({
      centered: true,
      title: `删除模块「${m.name}」？`,
      content: `模块下 ${m.suiteCount} 个套件将变为「未分类」，套件与用例本身不会被删除。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        await deleteModule(m.id)
        message.success('已删除')
        load()
        onChanged()
      },
    })
  }

  const columns: ColumnsType<TestModule> = [
    { title: 'ID', dataIndex: 'id', width: 64, render: (v: number) => `#${v}` },
    { title: '名称', dataIndex: 'name', width: 180, ellipsis: true },
    { title: '描述', dataIndex: 'description', ellipsis: true, render: (v: string | null) => v || '-' },
    { title: '负责人', dataIndex: 'owner', width: 110, render: (v: string | null) => v || '-' },
    { title: '标签', dataIndex: 'tags', width: 160, ellipsis: true, render: (v: string | null) => (v ? <Tag>{v}</Tag> : '-') },
    { title: '套件数', dataIndex: 'suiteCount', width: 80 },
    { title: '排序', dataIndex: 'sortNo', width: 70 },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: (v: string) => fmtTime(v) },
    {
      title: '操作',
      key: 'act',
      width: 120,
      render: (_, m) => (
        <Space size={4}>
          <Button size="small" onClick={() => startEdit(m)}>编辑</Button>
          <Button size="small" danger onClick={() => remove(m)}>删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <Drawer title="功能模块" width={DRAWER_DEFAULT} open={open} onClose={onClose}>
      <Form form={form} layout="vertical" style={{ marginBottom: 16 }}>
        <Flex gap={8} align="start">
          <Form.Item label="模块名称" name="name" rules={[{ required: true, message: '请输入模块名称' }]}
            style={{ width: 200 }}>
            <Input placeholder="如 用户中心" />
          </Form.Item>
          <Form.Item label="负责人" name="owner" style={{ width: 140 }}>
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item label="标签" name="tags" style={{ width: 160 }}>
            <Input placeholder="逗号分隔，可选" />
          </Form.Item>
          <Form.Item label="排序" name="sortNo" style={{ width: 100 }}>
            <InputNumber min={0} max={9999} style={{ width: '100%' }} />
          </Form.Item>
        </Flex>
        <Flex gap={8} align="start">
          <Form.Item label="描述" name="description" style={{ flex: 1 }}>
            <Input placeholder="模块职责/边界，可选" />
          </Form.Item>
          <Form.Item label=" ">
            <Space>
              {editing && <Button onClick={() => { setEditing(null); form.resetFields() }}>取消编辑</Button>}
              <Button type="primary" icon={editing ? undefined : <PlusOutlined />} loading={saving} onClick={save}>
                {editing ? '保存修改' : '新增模块'}
              </Button>
            </Space>
          </Form.Item>
        </Flex>
      </Form>
      <Flex justify="end" style={{ marginBottom: 8 }}>
        <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
      </Flex>
      <Table<TestModule> rowKey="id" size="small" loading={loading} columns={columns} dataSource={rows}
        pagination={false} locale={{ emptyText: '暂无模块：未分类的套件也可正常使用' }} />
    </Drawer>
  )
}
